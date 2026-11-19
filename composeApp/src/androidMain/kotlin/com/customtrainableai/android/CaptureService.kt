package com.customtrainableai.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import com.customtrainableai.engine.Frame
import com.customtrainableai.model.ScreenRect

/**
 * Vordergrund-Dienst mit MediaProjection: spiegelt den Bildschirm verkleinert in einen ImageReader.
 * Das neueste Bild wird laufend in einen Puffer kopiert, aus dem die Spielbereiche ausgeschnitten werden.
 */
class CaptureService : Service() {
    companion object {
        @Volatile var instance: CaptureService? = null
            private set
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "ctai_capture"
        /** Aufnahme-Auflösung relativ zum Bildschirm (0.5 = halbe Breite/Höhe, spart Rechenzeit). */
        const val SCALE = 0.5f

        val running get() = instance?.display != null

        fun start(ctx: Context, code: Int, data: Intent) {
            val i = Intent(ctx, CaptureService::class.java).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile var screenW = 0; private set
    @Volatile var screenH = 0; private set
    private var capW = 0
    private var capH = 0
    private var dpi = 320

    private val lock = Any()
    private var buf = ByteArray(0)
    private var bufW = 0
    private var bufH = 0
    private var rowStride = 0
    private var hasFrame = false
    private var lastCopy = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        stopCapture()
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = runCatching { mpm.getMediaProjection(code, data) }.getOrNull()
        if (proj == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = proj
        val t = HandlerThread("ctai-capture").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCapture()
                stopSelf()
            }
        }, h)
        setupDisplay()
        instance = this
        return START_NOT_STICKY
    }

    private fun realSize(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    private fun setupDisplay() {
        val proj = projection ?: return
        val (w, h) = realSize()
        screenW = w
        screenH = h
        dpi = resources.displayMetrics.densityDpi
        capW = ((w * SCALE).toInt() / 2 * 2).coerceAtLeast(2)
        capH = ((h * SCALE).toInt() / 2 * 2).coerceAtLeast(2)
        val old = reader
        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd -> onImage(rd) }, handler)
        reader = r
        val d = display
        if (d == null) {
            display = proj.createVirtualDisplay("ctai", capW, capH, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler)
        } else {
            d.resize(capW, capH, dpi)
            d.surface = r.surface
        }
        old?.close()
        synchronized(lock) { hasFrame = false }
    }

    private fun onImage(rd: ImageReader) {
        val img = runCatching { rd.acquireLatestImage() }.getOrNull() ?: return
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastCopy < 25) return
            lastCopy = now
            val plane = img.planes[0]
            val bb = plane.buffer
            synchronized(lock) {
                if (buf.size != bb.remaining()) buf = ByteArray(bb.remaining())
                bb.get(buf)
                rowStride = plane.rowStride
                bufW = img.width
                bufH = img.height
                hasFrame = true
            }
        } catch (_: Exception) {
        } finally {
            img.close()
        }
    }

    /** Hat sich die Bildschirm-Ausrichtung gedreht? Dann Aufnahme anpassen. */
    private fun checkRotation() {
        val (w, h) = realSize()
        if (w != screenW || h != screenH) handler?.post { setupDisplay() }
    }

    private fun cropLocked(r: ScreenRect): Frame? {
        if (!hasFrame) return null
        val sx = bufW.toFloat() / screenW
        val sy = bufH.toFloat() / screenH
        val x0 = (r.x * sx).toInt().coerceIn(0, bufW - 1)
        val y0 = (r.y * sy).toInt().coerceIn(0, bufH - 1)
        val w = (r.w * sx).toInt().coerceIn(1, bufW - x0)
        val h = (r.h * sy).toInt().coerceIn(1, bufH - y0)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            var src = (y0 + y) * rowStride + x0 * 4
            val row = y * w
            for (x in 0 until w) {
                val rr = buf[src].toInt() and 0xFF
                val gg = buf[src + 1].toInt() and 0xFF
                val bb = buf[src + 2].toInt() and 0xFF
                out[row + x] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                src += 4
            }
        }
        return Frame(w, h, out, r.w.toFloat() / w)
    }

    fun grab(areas: List<ScreenRect>): List<Frame?> {
        checkRotation()
        return synchronized(lock) { areas.map { cropLocked(it) } }
    }

    fun grabFull(): Frame? {
        checkRotation()
        return synchronized(lock) { cropLocked(ScreenRect(0, 0, screenW, screenH)) }
    }

    private fun stopCapture() {
        runCatching { display?.release() }
        display = null
        runCatching { reader?.close() }
        reader = null
        runCatching { projection?.stop() }
        projection = null
        thread?.quitSafely()
        thread = null
        synchronized(lock) { hasFrame = false }
    }

    override fun onDestroy() {
        stopCapture()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bildschirmaufnahme", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("CustomTrainableAI")
            .setContentText("Bildschirmaufnahme für das KI-Training aktiv")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }
}
