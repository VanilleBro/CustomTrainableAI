package com.customtrainableai.engine

import com.customtrainableai.model.ScreenPoint
import com.customtrainableai.model.ScreenRect
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlin.time.TimeSource

/** Ein Bildausschnitt (ARGB-Pixel). [unitsPerPixel] = Bildschirm-Einheiten pro Bildpixel (Android nimmt verkleinert auf). */
class Frame(val width: Int, val height: Int, val argb: IntArray, val unitsPerPixel: Float = 1f) {
    fun pixel(x: Int, y: Int): Int = argb[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]

    fun crop(x0: Int, y0: Int, w: Int, h: Int): Frame {
        val cx = x0.coerceIn(0, width - 1)
        val cy = y0.coerceIn(0, height - 1)
        val cw = w.coerceIn(1, width - cx)
        val ch = h.coerceIn(1, height - cy)
        val out = IntArray(cw * ch)
        for (y in 0 until ch) argb.copyInto(out, y * cw, (cy + y) * width + cx, (cy + y) * width + cx + cw)
        return Frame(cw, ch, out, unitsPerPixel)
    }
}

data class Drag(val from: ScreenPoint, val to: ScreenPoint)

/**
 * Gewünschter Eingabe-Zustand einer Instanz für einen Schritt.
 * taps/keyTaps/back = einmalig, holds/keys/drag = solange gedrückt, wie sie im nächsten Befehl noch enthalten sind.
 */
data class InstanceCommand(
    val taps: List<ScreenPoint> = emptyList(),
    val holds: List<ScreenPoint> = emptyList(),
    val keys: Set<String> = emptySet(),
    val drag: Drag? = null,
    val keyTaps: List<String> = emptyList(),
    val back: Boolean = false,
    /** Mitte des Spielbereichs – dorthin gehen Tasten bei Hintergrund-Eingabe. */
    val keyTarget: ScreenPoint? = null,
    /** Spielbereich dieser Instanz (für Sicherheitsprüfungen: nur ins eigene Spielfenster klicken). */
    val area: ScreenRect? = null,
) {
    companion object { val NONE = InstanceCommand() }
}

interface ScreenCapturer {
    /** Nimmt alle Bereiche auf (Reihenfolge wie übergeben). null = gerade kein Bild verfügbar. */
    suspend fun capture(areas: List<ScreenRect>): List<Frame?>
    suspend fun fullScreenshot(): Frame?
}

interface InputController {
    /** Wendet die Befehle aller Instanzen für diesen Schritt an (Schlüssel = Instanz-Index). */
    suspend fun apply(commands: Map<Int, InstanceCommand>)
    suspend fun releaseAll()
}

interface Clock {
    fun now(): Double
    suspend fun sleep(seconds: Double)
}

object RealClock : Clock {
    private val mark = TimeSource.Monotonic.markNow()
    override fun now(): Double = mark.elapsedNow().inWholeMicroseconds / 1e6
    override suspend fun sleep(seconds: Double) {
        if (seconds > 0.0005) delay((seconds * 1000).toLong().coerceAtLeast(1)) else yield()
    }
}

/** Virtuelle Zeit für Simulation/Tests: sleep() springt sofort vorwärts. */
class VirtualClock : Clock {
    private var t = 0.0
    private var calls = 0
    override fun now() = t
    override suspend fun sleep(seconds: Double) {
        t += seconds.coerceAtLeast(0.0)
        if (++calls % 50 == 0) yield()
    }
}

/** Liest Werte direkt aus dem Spiel (z.B. per JavaScript aus einem Browser-Tab). Nur für Regeln vom Typ BROWSER_JS. */
interface ValueProbe {
    /** Wertet die Ausdrücke im Spiel des Bereichs aus. Leere Ausdrücke -> NaN. null = Spiel nicht erreichbar. */
    suspend fun values(area: com.customtrainableai.model.ScreenRect, scripts: List<String>): DoubleArray?
}
