package com.customtrainableai.desktop

import com.customtrainableai.engine.Drag
import com.customtrainableai.engine.Frame
import com.customtrainableai.engine.InputController
import com.customtrainableai.engine.InstanceCommand
import com.customtrainableai.engine.ScreenCapturer
import com.customtrainableai.engine.Storage
import com.customtrainableai.model.InputMethod
import com.customtrainableai.model.ScreenPoint
import com.customtrainableai.model.ScreenRect
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.POINT
import com.sun.jna.platform.win32.WinDef.WPARAM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Profile/Backups liegen unter %USERPROFILE%\CustomTrainableAI. */
class FileStorage(base: File) : Storage {
    private val profiles = File(base, "profiles").apply { mkdirs() }
    private val backups = File(base, "backups").apply { mkdirs() }
    override val profilesDir: String = profiles.absolutePath
    override val backupsDir: String = backups.absolutePath
    override fun list(dir: String): List<String> = File(dir).list()?.toList() ?: emptyList()
    override fun read(path: String): String? = File(path).takeIf { it.isFile }?.readText(Charsets.UTF_8)
    override fun write(path: String, text: String) {
        val target = File(path)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
    override fun delete(path: String): Boolean = File(path).delete()
    override fun join(dir: String, name: String): String = File(dir, name).absolutePath
}

fun BufferedImage.toFrame(unitsPerPixel: Float = 1f): Frame {
    val w = width
    val h = height
    return Frame(w, h, getRGB(0, 0, w, h, null, 0, w), unitsPerPixel)
}

fun Frame.toBufferedImage(): BufferedImage {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val px = IntArray(argb.size) { argb[it] or (0xFF shl 24) }
    img.setRGB(0, 0, width, height, px, 0, width)
    return img
}

/** Bildschirm-Skalierung (z.B. 1.25 bei 125 %): Java-Koordinaten × scale = echte Pixel. */
fun screenScale(): Double = runCatching {
    GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX
}.getOrDefault(1.0)

fun primaryScreenBounds(): Rectangle =
    GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds

/** Bildschirmaufnahme über java.awt.Robot. */
class DesktopCapturer(private val guard: WindowGuard? = null) : ScreenCapturer {
    private val robot by lazy { Robot() }
    private val lock = Any()

    override suspend fun capture(areas: List<ScreenRect>): List<Frame?> = withContext(Dispatchers.IO) {
        val raw = grabAll(areas)
        // Fenster weg/verdeckt -> kein Bild (die Engine pausiert die Runde)
        if (guard == null) raw else raw.mapIndexed { i, f -> f?.takeIf { guard.areaOk(areas[i]) } }
    }

    private fun grabAll(areas: List<ScreenRect>): List<Frame?> {
        return synchronized(lock) {
            if (areas.isEmpty()) return@synchronized emptyList()
            val minX = areas.minOf { it.x }
            val minY = areas.minOf { it.y }
            val maxX = areas.maxOf { it.right }
            val maxY = areas.maxOf { it.bottom }
            val union = (maxX - minX).toLong() * (maxY - minY)
            val sum = areas.sumOf { it.w.toLong() * it.h }
            if (areas.size == 1 || union > sum * 2) {
                areas.map { r -> runCatching { grab(r) }.getOrNull() }
            } else {
                val all = runCatching { robot.createScreenCapture(Rectangle(minX, minY, maxX - minX, maxY - minY)).toFrame() }.getOrNull()
                    ?: return@synchronized areas.map { null }
                val sx = all.width.toFloat() / (maxX - minX)
                val sy = all.height.toFloat() / (maxY - minY)
                areas.map { r ->
                    all.crop(((r.x - minX) * sx).toInt(), ((r.y - minY) * sy).toInt(), (r.w * sx).toInt(), (r.h * sy).toInt())
                        .let { Frame(it.width, it.height, it.argb, r.w.toFloat() / it.width) }
                }
            }
        }
    }

    private fun grab(r: ScreenRect): Frame {
        val img = robot.createScreenCapture(Rectangle(r.x, r.y, r.w, r.h))
        return img.toFrame(r.w.toFloat() / img.width)
    }

    override suspend fun fullScreenshot(): Frame? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val b = primaryScreenBounds()
            runCatching { robot.createScreenCapture(b).let { it.toFrame(b.width.toFloat() / it.width) } }.getOrNull()
        }
    }
}

/**
 * Eingabe auf Windows.
 * REAL: java.awt.Robot (echter Mauszeiger/Tastatur, eine Maus für alle Instanzen).
 * BACKGROUND: PostMessage an das Fenster unter dem Punkt (Mauszeiger bleibt frei, mehrere Instanzen parallel).
 */
class DesktopInput(private val guard: WindowGuard? = null) : InputController {
    @Volatile var method = InputMethod.REAL
    @Volatile var tapMs = 30
    /** Hinweis für die Status-Anzeige (z.B. Spiel hat keinen Fokus). */
    @Volatile var warning: String? = null
    /** Letzte Position, auf die wir die echte Maus gesetzt haben (für den Not-Aus "Maus in die Ecke"). */
    @Volatile var lastRobotPos: java.awt.Point? = null

    private val robot by lazy { Robot().apply { autoDelay = 0 } }
    private val lock = Any()

    // ---- REAL ----
    private var realDownAt: ScreenPoint? = null
    private var realPos: ScreenPoint? = null
    private var realKeys = setOf<Int>()

    // ---- BACKGROUND ----
    private class Bg {
        var hwnd: HWND? = null
        var downAt: ScreenPoint? = null
        var pos: ScreenPoint? = null
        var keys = setOf<Int>()
        var keyHwnd: HWND? = null
    }
    private val bg = HashMap<Int, Bg>()

    override suspend fun apply(commands: Map<Int, InstanceCommand>) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val background = method == InputMethod.BACKGROUND && U32.INSTANCE != null
            val safe = commands.mapValues { (_, c) -> sanitize(c, background) }
            if (background) applyBackground(safe) else applyReal(safe)
        }
    }

    override suspend fun releaseAll() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            runCatching {
                for (k in realKeys) robot.keyRelease(k)
                realKeys = emptySet()
                if (realDownAt != null) robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                realDownAt = null
            }
            for ((_, st) in bg) {
                if (st.downAt != null) st.hwnd?.let { h -> st.pos?.let { post(h, U32.WM_LBUTTONUP, 0, it) } }
                st.downAt = null
                st.keyHwnd?.let { h -> for (k in st.keys) postKey(h, k, false) }
                st.keys = emptySet()
            }
            Unit
        }
    }

    /** Entfernt alles, was nicht im Spielfenster der Instanz landen würde. */
    private fun sanitize(c: InstanceCommand, background: Boolean): InstanceCommand {
        val g = guard ?: return c
        if (c == InstanceCommand.NONE || c.area == null) return c
        val area = c.area
        val keysOk = if (c.keys.isEmpty() && c.keyTaps.isEmpty()) true
        else if (background) c.keyTarget?.let { g.pointOk(area, it) } ?: false
        else g.focusOk(area)
        warning = if (!keysOk) "Tasten pausiert: Spielfenster ${if (background) "nicht sichtbar" else "hat keinen Fokus – einmal ins Spiel klicken"}." else null
        return c.copy(
            taps = c.taps.filter { g.pointOk(area, it) },
            holds = c.holds.filter { g.pointOk(area, it) },
            drag = c.drag?.takeIf { g.pointOk(area, it.from) },
            keys = if (keysOk) c.keys else emptySet(),
            keyTaps = if (keysOk) c.keyTaps else emptyList(),
        )
    }

    // ================= REAL =================

    private fun move(p: ScreenPoint) {
        robot.mouseMove(p.x, p.y)
        realPos = p
        lastRobotPos = java.awt.Point(p.x, p.y)
    }

    private fun applyReal(commands: Map<Int, InstanceCommand>) {
        val sorted = commands.toSortedMap()
        // Tastatur ist global: Vereinigung aller gewünschten Tasten
        val wantKeys = sorted.values.flatMap { it.keys }.mapNotNull { Keys.vk(it) }.toSet()
        for (k in realKeys - wantKeys) robot.keyRelease(k)
        for (k in wantKeys - realKeys) robot.keyPress(k)
        realKeys = wantKeys
        for (c in sorted.values) for (name in c.keyTaps) {
            val vk = Keys.vk(name) ?: continue
            robot.keyPress(vk); Thread.sleep(25); robot.keyRelease(vk)
        }
        // Eine Maus: die erste Instanz mit Halten/Ziehen bekommt sie
        val want: Drag? = sorted.values.firstNotNullOfOrNull { c -> c.drag ?: c.holds.firstOrNull()?.let { Drag(it, it) } }
        syncRealMouse(want)
        for (c in sorted.values) for (t in c.taps) {
            val held = realDownAt
            if (held != null) robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            move(t)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            Thread.sleep(tapMs.toLong().coerceIn(1, 1000))
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            if (held != null && want != null) {
                move(want.from)
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                if (want.to != want.from) move(want.to)
            }
        }
    }

    private fun syncRealMouse(want: Drag?) {
        val down = realDownAt
        if (want == null) {
            if (down != null) {
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                realDownAt = null
            }
            return
        }
        if (down == null || down != want.from) {
            if (down != null) robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            move(want.from)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            realDownAt = want.from
            if (want.to != want.from) {
                Thread.sleep(8)
                move(ScreenPoint((want.from.x + want.to.x) / 2, (want.from.y + want.to.y) / 2))
                Thread.sleep(8)
                move(want.to)
            }
        } else if (realPos != want.to) {
            move(want.to)
        }
    }

    // ================= BACKGROUND =================

    private fun physical(p: ScreenPoint): POINT {
        val s = screenScale()
        return POINT((p.x * s).toInt(), (p.y * s).toInt())
    }

    private fun windowAt(p: ScreenPoint): HWND? {
        val u = U32.INSTANCE ?: return null
        val pt = physical(p)
        return u.WindowFromPoint(POINT.ByValue(pt.x, pt.y))
    }

    private fun post(h: HWND, msg: Int, wParam: Int, p: ScreenPoint) {
        val u = U32.INSTANCE ?: return
        val pt = physical(p)
        u.ScreenToClient(h, pt)
        val lp = ((pt.y and 0xFFFF) shl 16) or (pt.x and 0xFFFF)
        u.PostMessage(h, msg, WPARAM(wParam.toLong()), LPARAM(lp.toLong()))
    }

    private fun postKey(h: HWND, vk: Int, down: Boolean) {
        val u = U32.INSTANCE ?: return
        val scan = u.MapVirtualKey(vk, 0)
        var lp = 1L or (scan.toLong() shl 16)
        if (Keys.isExtended(vk)) lp = lp or (1L shl 24)
        if (!down) lp = lp or (1L shl 30) or (1L shl 31)
        u.PostMessage(h, if (down) U32.WM_KEYDOWN else U32.WM_KEYUP, WPARAM(vk.toLong()), LPARAM(lp))
    }

    private fun applyBackground(commands: Map<Int, InstanceCommand>) {
        for ((i, c) in commands) {
            val st = bg.getOrPut(i) { Bg() }
            val want = c.drag ?: c.holds.firstOrNull()?.let { Drag(it, it) }
            val down = st.downAt
            if (want == null) {
                if (down != null) st.hwnd?.let { h -> post(h, U32.WM_LBUTTONUP, 0, st.pos ?: down) }
                st.downAt = null
            } else {
                if (down == null || down != want.from) {
                    if (down != null) st.hwnd?.let { h -> post(h, U32.WM_LBUTTONUP, 0, st.pos ?: down) }
                    val h = windowAt(want.from)
                    st.hwnd = h
                    if (h != null) {
                        post(h, U32.WM_MOUSEMOVE, 0, want.from)
                        post(h, U32.WM_LBUTTONDOWN, U32.MK_LBUTTON, want.from)
                    }
                    st.downAt = want.from
                    st.pos = want.from
                }
                if (st.pos != want.to) {
                    st.hwnd?.let { h -> post(h, U32.WM_MOUSEMOVE, U32.MK_LBUTTON, want.to) }
                    st.pos = want.to
                }
            }
            for (t in c.taps) {
                val h = windowAt(t) ?: continue
                val held = st.downAt
                if (held != null) st.hwnd?.let { post(it, U32.WM_LBUTTONUP, 0, st.pos ?: held) }
                post(h, U32.WM_MOUSEMOVE, 0, t)
                post(h, U32.WM_LBUTTONDOWN, U32.MK_LBUTTON, t)
                Thread.sleep(tapMs.toLong().coerceIn(1, 1000))
                post(h, U32.WM_LBUTTONUP, 0, t)
                if (held != null) st.hwnd?.let { hh ->
                    post(hh, U32.WM_LBUTTONDOWN, U32.MK_LBUTTON, held)
                    st.pos?.let { post(hh, U32.WM_MOUSEMOVE, U32.MK_LBUTTON, it) }
                }
            }
            // Tasten an das Fenster in der Mitte des Spielbereichs
            val wantKeys = c.keys.mapNotNull { Keys.vk(it) }.toSet()
            if (wantKeys.isNotEmpty() || c.keyTaps.isNotEmpty() || st.keys.isNotEmpty()) {
                val target = c.keyTarget?.let { windowAt(it) } ?: st.keyHwnd
                if (target != null) {
                    if (st.keyHwnd != null && st.keyHwnd != target) for (k in st.keys) postKey(st.keyHwnd!!, k, false)
                    st.keyHwnd = target
                    for (k in st.keys - wantKeys) postKey(target, k, false)
                    for (k in wantKeys - st.keys) postKey(target, k, true)
                    st.keys = wantKeys
                    for (name in c.keyTaps) {
                        val vk = Keys.vk(name) ?: continue
                        postKey(target, vk, true); Thread.sleep(25); postKey(target, vk, false)
                    }
                }
            }
        }
    }
}
