package com.customtrainableai.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Bedienungshilfe-Dienst: führt Gesten aus (Tippen, Halten, Ziehen), zeigt die schwebende Status-Blase
 * und stoppt das Training mit der Lautstärke-leiser-Taste.
 */
class AutoService : AccessibilityService() {
    companion object {
        @Volatile var instance: AutoService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private var bubble: LinearLayout? = null
    private var bubbleText: TextView? = null
    private var bubbleButtons: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        hideBubble()
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        hideBubble()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val app = application as? CtaiApp ?: return false
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && app.state.isRunning) {
            if (event.action == KeyEvent.ACTION_DOWN) app.platform.onStopRequest?.invoke()
            return true
        }
        return false
    }

    /** Geste ausführen und auf das Ende warten. */
    suspend fun dispatchAndWait(g: GestureDescription, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val ok = dispatchGesture(g, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }, main)
                if (!ok && cont.isActive) cont.resume(false)
            }
        } ?: false

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)

    // ------------------------- Blase -------------------------

    fun showBubble(text: String, running: Boolean) = main.post {
        if (bubble == null) createBubble()
        bubbleText?.text = text
        setButtons(running)
    }

    fun hideBubble() = main.post {
        bubble?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        bubble = null
        bubbleText = null
        bubbleButtons = null
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(10), dp(4), dp(10), dp(4))
        setOnClickListener { onClick() }
    }

    private fun setButtons(running: Boolean) {
        val box = bubbleButtons ?: return
        box.removeAllViews()
        val app = application as CtaiApp
        if (running) {
            box.addView(button("■ Stopp") { app.platform.onStopRequest?.invoke() })
        } else {
            box.addView(button("App") {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            })
            box.addView(button("✕") { hideBubble() })
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createBubble() {
        val wm = getSystemService(WindowManager::class.java)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(230, 17, 26, 46))
                setStroke(dp(1), Color.argb(255, 96, 165, 250))
            }
        }
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            text = "🤖 CustomTrainableAI"
            setPadding(0, 0, dp(8), 0)
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(tv)
        root.addView(buttons)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(80)
        }
        // Blase an der Beschriftung verschieben
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; true }
                MotionEvent.ACTION_MOVE -> {
                    p.x = startX + (e.rawX - downX).toInt()
                    p.y = startY + (e.rawY - downY).toInt()
                    runCatching { wm.updateViewLayout(root, p) }
                    true
                }
                else -> true
            }
        }
        runCatching { wm.addView(root, p) }
        bubble = root
        bubbleText = tv
        bubbleButtons = buttons
        params = p
    }

}
