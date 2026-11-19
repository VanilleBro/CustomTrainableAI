package com.customtrainableai.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.customtrainableai.engine.Frame
import com.customtrainableai.engine.PermissionItem
import com.customtrainableai.engine.Platform
import com.customtrainableai.engine.Storage
import com.customtrainableai.engine.WindowInfo
import com.customtrainableai.model.PracticeKind
import com.customtrainableai.model.Profile
import com.customtrainableai.model.ScreenRect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Profile/Backups im App-Ordner (über USB unter Android/data/com.customtrainableai/files sichtbar). */
class AndroidStorage(base: File) : Storage {
    private val profiles = File(base, "profiles").apply { mkdirs() }
    private val backups = File(base, "backups").apply { mkdirs() }
    override val profilesDir: String = profiles.absolutePath
    override val backupsDir: String = backups.absolutePath
    override fun list(dir: String): List<String> = File(dir).list()?.toList() ?: emptyList()
    override fun read(path: String): String? = File(path).takeIf { it.isFile }?.readText(Charsets.UTF_8)
    override fun write(path: String, text: String) {
        val target = File(path)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
    override fun delete(path: String): Boolean = File(path).delete()
    override fun join(dir: String, name: String): String = File(dir, name).absolutePath
}

class AndroidPlatform(private val ctx: Context) : Platform {
    override val name = "Android"
    override val touchOnly = true
    override val supportsBackgroundInput = false
    override val supportsWindowList = false
    override val supportsLaunch = false
    override val editorInSeparateWindow = false
    override val stopHint = "Lautstärke-leiser-Taste drücken oder „■ Stopp“ in der schwebenden Blase"
    override val startDelaySec = 5.0

    override val storage = AndroidStorage(ctx.getExternalFilesDir(null) ?: ctx.filesDir)
    override val capturer = AndroidCapturer()
    override val input = AndroidInput()
    override var onStopRequest: (() -> Unit)? = null

    @Volatile var activity: MainActivity? = null
    private var projectionWait: CompletableDeferred<Boolean>? = null
    private var pendingExport: Pair<String, (String?) -> Unit>? = null
    private var pendingImage: Pair<Frame, (String?) -> Unit>? = null
    private var pendingImport: ((String?) -> Unit)? = null

    override fun timestamp(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy_HH.mm"))

    // ---------------- Dateien (Speicherzugriffs-Framework) ----------------

    override fun exportText(fileName: String, text: String, onResult: (String?) -> Unit) {
        val a = activity ?: return onResult("App nicht im Vordergrund.")
        pendingExport = text to onResult
        a.exportLauncher.launch(fileName)
    }

    fun onExportUri(uri: Uri?) {
        val (text, cb) = pendingExport ?: return
        pendingExport = null
        if (uri == null) return cb(null)
        cb(runCatching {
            ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            "Exportiert."
        }.getOrElse { "Export fehlgeschlagen: ${it.message}" })
    }

    override fun importText(onResult: (String?) -> Unit) {
        val a = activity ?: return onResult(null)
        pendingImport = onResult
        a.importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
    }

    fun onImportUri(uri: Uri?) {
        val cb = pendingImport ?: return
        pendingImport = null
        if (uri == null) return cb(null)
        cb(runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull())
    }

    override fun saveImage(fileName: String, frame: Frame, onResult: (String?) -> Unit) {
        val a = activity ?: return onResult(null)
        pendingImage = frame to onResult
        a.imageLauncher.launch(fileName)
    }

    fun onImageUri(uri: Uri?) {
        val (frame, cb) = pendingImage ?: return
        pendingImage = null
        if (uri == null) return cb(null)
        cb(runCatching {
            val bmp = Bitmap.createBitmap(frame.argb, frame.width, frame.height, Bitmap.Config.ARGB_8888)
            ctx.contentResolver.openOutputStream(uri, "wt")!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            "Gespeichert – dieses Bild an ChatGPT/Claude anhängen."
        }.getOrElse { "Speichern fehlgeschlagen: ${it.message}" })
    }

    // ---------------- Bildschirmaufnahme ----------------

    fun requestProjection(): CompletableDeferred<Boolean> {
        val wait = CompletableDeferred<Boolean>()
        projectionWait?.complete(false)
        projectionWait = wait
        val a = activity
        if (a == null) {
            wait.complete(false)
        } else {
            val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
            a.projectionLauncher.launch(mpm.createScreenCaptureIntent())
        }
        return wait
    }

    fun onProjectionResult(code: Int, data: Intent?) {
        val ok = code == android.app.Activity.RESULT_OK && data != null
        if (ok) CaptureService.start(ctx, code, data!!)
        projectionWait?.complete(ok)
        projectionWait = null
    }

    private suspend fun ensureProjection(): Boolean {
        if (CaptureService.running) return true
        val granted = withTimeoutOrNull(120_000) { requestProjection().await() } ?: false
        if (!granted) return false
        // Warten, bis der Dienst läuft und das erste Bild da ist
        repeat(40) {
            if (CaptureService.running && CaptureService.instance?.grabFull() != null) return true
            delay(100)
        }
        return CaptureService.running
    }

    override suspend fun captureForEditor(delaySec: Int): Frame? {
        if (!ensureProjection()) return null
        activity?.moveTaskToBack(true)
        val secs = delaySec.coerceAtLeast(3)
        for (s in secs downTo 1) {
            AutoService.instance?.showBubble("📷 Screenshot in $s s …", running = true)
            delay(1000)
        }
        AutoService.instance?.hideBubble()
        delay(250)
        val frame = capturer.fullScreenshot()
        bringToFront()
        return frame
    }

    fun bringToFront() {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val svc = AutoService.instance
        runCatching { if (svc != null) svc.startActivity(i) else ctx.startActivity(i) }
    }

    override fun frameToImage(frame: Frame): ImageBitmap =
        Bitmap.createBitmap(frame.argb, frame.width, frame.height, Bitmap.Config.ARGB_8888).asImageBitmap()

    // ---------------- Berechtigungen ----------------

    override fun permissions(): List<PermissionItem> {
        val list = mutableListOf(
            PermissionItem(
                "Bedienungshilfe „CustomTrainableAI Steuerung“",
                "Nötig, damit die KI tippen, halten und wischen kann. Einstellungen → Bedienungshilfen → Installierte Apps → CustomTrainableAI Steuerung → An. " +
                    "Android 13+: Ist der Schalter gesperrt, zuerst App-Info → ⋮ → „Eingeschränkte Einstellungen zulassen“.",
                AutoService.instance != null,
                "Öffnen",
            ) {
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            PermissionItem(
                "Bildschirmaufnahme",
                "Nötig, damit die KI das Spiel sieht. Wird bei jedem App-Start einmal abgefragt – „Gesamter Bildschirm“ wählen.",
                CaptureService.running,
                "Erlauben",
            ) { requestProjection() },
        )
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            list += PermissionItem("Benachrichtigungen (optional)", "Zeigt an, dass die Bildschirmaufnahme läuft.", granted, "Erlauben") {
                activity?.notificationLauncher?.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        return list
    }

    override fun beforeRun(profile: Profile): String? {
        if (AutoService.instance == null) return "Bitte zuerst die Bedienungshilfe „CustomTrainableAI Steuerung“ aktivieren (Startseite → Berechtigungen)."
        if (!CaptureService.running) {
            requestProjection()
            return "Bitte die Bildschirmaufnahme erlauben und dann erneut starten."
        }
        input.tickMs = (1000L / profile.tickRate.coerceIn(1, 60))
        input.tapMs = profile.actions.tapMs.toLong()
        return null
    }

    override fun onRunStarted() {
        AutoService.instance?.showBubble("Start …", running = true)
        activity?.moveTaskToBack(true)
    }

    override fun onRunStopped() {
        AutoService.instance?.showBubble("Gestoppt", running = false)
    }

    override fun showRunStatus(text: String) {
        AutoService.instance?.showBubble(text, running = true)
    }

    // ---------------- Übungsspiel ----------------

    override fun openPracticeGame(kind: PracticeKind) {
        ctx.startActivity(
            Intent(ctx, PracticeActivity::class.java)
                .putExtra(PracticeActivity.EXTRA_KIND, kind.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun practiceAreas(): List<ScreenRect> = listOfNotNull(PracticeActivity.lastArea)

    override fun launchInstances(command: String, count: Int): String? = "Auf Android nicht verfügbar."
    override fun listWindows(): List<WindowInfo> = emptyList()
}
