package com.customtrainableai.android

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.customtrainableai.engine.Frame
import com.customtrainableai.engine.InputController
import com.customtrainableai.engine.InstanceCommand
import com.customtrainableai.engine.ScreenCapturer
import com.customtrainableai.model.ScreenPoint
import com.customtrainableai.model.ScreenRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bildschirmaufnahme über den [CaptureService]. */
class AndroidCapturer : ScreenCapturer {
    override suspend fun capture(areas: List<ScreenRect>): List<Frame?> = withContext(Dispatchers.Default) {
        CaptureService.instance?.grab(areas) ?: areas.map { null }
    }

    override suspend fun fullScreenshot(): Frame? = withContext(Dispatchers.Default) { CaptureService.instance?.grabFull() }
}

/**
 * Eingabe über Bedienungshilfe-Gesten. Pro Takt wird EINE Geste mit allen Strichen gebaut
 * (Halten/Ziehen werden mit continueStroke fortgesetzt, Tipps als kurze Striche), damit mehrere
 * Finger/Instanzen gleichzeitig möglich sind.
 */
class AndroidInput : InputController {
    @Volatile var tickMs = 100L
    @Volatile var tapMs = 30L

    private class Active(val stroke: GestureDescription.StrokeDescription, val x: Float, val y: Float)
    private var active = HashMap<String, Active>()

    private fun path(fx: Float, fy: Float, tx: Float, ty: Float) = Path().apply {
        moveTo(fx.coerceAtLeast(0f), fy.coerceAtLeast(0f))
        if (fx != tx || fy != ty) lineTo(tx.coerceAtLeast(0f), ty.coerceAtLeast(0f))
    }

    override suspend fun apply(commands: Map<Int, InstanceCommand>) {
        val svc = AutoService.instance ?: return
        val desired = LinkedHashMap<String, Pair<ScreenPoint, ScreenPoint>>()
        for ((i, c) in commands) {
            for (h in c.holds) desired["$i:h:${h.x},${h.y}"] = h to h
            c.drag?.let { desired["$i:d:${it.from.x},${it.from.y}"] = it.from to it.to }
        }
        if (commands.values.any { it.back }) svc.back()
        val taps = commands.values.flatMap { it.taps }
        if (desired.isEmpty() && active.isEmpty() && taps.isEmpty()) return

        val maxStrokes = GestureDescription.getMaxStrokeCount()
        val dur = (tickMs * 0.6).toLong().coerceIn(20, 1000)
        val builder = GestureDescription.Builder()
        var count = 0
        val next = HashMap<String, Active>()
        // Laufende Striche fortsetzen oder beenden
        for ((key, a) in active) {
            if (count >= maxStrokes) break
            val want = desired[key]
            val tx = want?.second?.x?.toFloat() ?: a.x
            val ty = want?.second?.y?.toFloat() ?: a.y
            val s = runCatching { a.stroke.continueStroke(path(a.x, a.y, tx, ty), 0, dur, want != null) }.getOrNull() ?: continue
            builder.addStroke(s)
            count++
            if (want != null) next[key] = Active(s, tx, ty)
        }
        // Neue Halte-/Zieh-Striche
        for ((key, ft) in desired) {
            if (key in active || count >= maxStrokes) continue
            val (from, to) = ft
            val s = GestureDescription.StrokeDescription(path(from.x.toFloat(), from.y.toFloat(), to.x.toFloat(), to.y.toFloat()), 0, dur, true)
            builder.addStroke(s)
            count++
            next[key] = Active(s, to.x.toFloat(), to.y.toFloat())
        }
        // Kurze Tipps
        for (t in taps) {
            if (count >= maxStrokes) break
            builder.addStroke(GestureDescription.StrokeDescription(path(t.x.toFloat(), t.y.toFloat(), t.x.toFloat(), t.y.toFloat()), 0, tapMs.coerceIn(1, 1000)))
            count++
        }
        active = next
        if (count == 0) return
        val gesture = runCatching { builder.build() }.getOrNull() ?: run { active.clear(); return }
        val ok = svc.dispatchAndWait(gesture, maxOf(dur, tapMs) + 400)
        if (!ok) active.clear()
    }

    override suspend fun releaseAll() {
        if (active.isEmpty()) return
        // Leere Befehle beenden alle laufenden Striche
        apply(emptyMap())
        active.clear()
    }
}
