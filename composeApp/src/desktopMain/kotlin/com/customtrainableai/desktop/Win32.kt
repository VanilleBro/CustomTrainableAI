package com.customtrainableai.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.POINT
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser.WNDENUMPROC
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/** Die wenigen Win32-Funktionen, die wir brauchen (über JNA). */
@Suppress("FunctionName")
interface U32 : StdCallLibrary {
    fun WindowFromPoint(pt: POINT.ByValue): HWND?
    fun ScreenToClient(hWnd: HWND, pt: POINT): Boolean
    fun ClientToScreen(hWnd: HWND, pt: POINT): Boolean
    fun PostMessage(hWnd: HWND, msg: Int, wParam: WPARAM, lParam: LPARAM): Boolean
    fun SendMessage(hWnd: HWND, msg: Int, wParam: WPARAM, lParam: LPARAM): com.sun.jna.platform.win32.WinDef.LRESULT
    fun GetAsyncKeyState(vKey: Int): Short
    fun EnumWindows(cb: WNDENUMPROC, data: Pointer?): Boolean
    fun IsWindowVisible(hWnd: HWND): Boolean
    fun IsIconic(hWnd: HWND): Boolean
    fun GetWindowText(hWnd: HWND, buf: CharArray, max: Int): Int
    fun GetClientRect(hWnd: HWND, rect: RECT): Boolean
    fun MapVirtualKey(code: Int, mapType: Int): Int
    fun GetAncestor(hWnd: HWND, flags: Int): HWND?
    fun GetClassName(hWnd: HWND, buf: CharArray, max: Int): Int
    fun GetWindowThreadProcessId(hWnd: HWND, pid: IntByReference?): Int
    fun IsWindow(hWnd: HWND): Boolean
    fun GetForegroundWindow(): HWND?

    companion object {
        val INSTANCE: U32? by lazy {
            if (!System.getProperty("os.name").lowercase().contains("win")) null
            else runCatching { Native.load("user32", U32::class.java, W32APIOptions.DEFAULT_OPTIONS) }.getOrNull()
        }

        const val WM_MOUSEMOVE = 0x0200
        const val WM_LBUTTONDOWN = 0x0201
        const val WM_LBUTTONUP = 0x0202
        const val WM_KEYDOWN = 0x0100
        const val WM_KEYUP = 0x0101
        const val MK_LBUTTON = 0x0001
        const val VK_F8 = 0x77
        const val GA_ROOT = 2
    }
}

/** Tastennamen -> Windows Virtual-Key-Codes (= java.awt.event.KeyEvent.VK_* für diese Tasten). */
object Keys {
    fun vk(name: String): Int? {
        val n = name.trim().uppercase()
        if (n.length == 1) {
            val ch = n[0]
            if (ch in 'A'..'Z' || ch in '0'..'9') return ch.code
        }
        if (n.startsWith("F") && n.length in 2..3) n.drop(1).toIntOrNull()?.let { if (it in 1..12) return 0x6F + it }
        return when (n) {
            "SPACE", "LEERTASTE" -> 0x20
            "ENTER", "RETURN" -> 0x0D
            "ESC", "ESCAPE" -> 0x1B
            "TAB" -> 0x09
            "SHIFT" -> 0x10
            "CTRL", "STRG", "CONTROL" -> 0x11
            "ALT" -> 0x12
            "BACKSPACE" -> 0x08
            "UP" -> 0x26
            "DOWN" -> 0x28
            "LEFT" -> 0x25
            "RIGHT" -> 0x27
            else -> null
        }
    }

    /** Umkehrung von [vk] für die Tasten, die das Übungsspiel kennt. */
    fun name(vk: Int): String? = when (vk) {
        0x26 -> "UP"; 0x28 -> "DOWN"; 0x25 -> "LEFT"; 0x27 -> "RIGHT"; 0x20 -> "SPACE"; 0x0D -> "ENTER"
        in 0x41..0x5A -> vk.toChar().toString()
        else -> null
    }

    /** Pfeiltasten sind "erweiterte" Tasten (wichtig für das lParam bei PostMessage). */
    fun isExtended(vk: Int) = vk in 0x21..0x28
}

/**
 * Sicherung: merkt sich pro Spielbereich das Fenster, das beim Start dort lag.
 * Ist es geschlossen, minimiert oder verdeckt, wird der Bereich nicht bewertet (Training pausiert)
 * und es wird NICHT hineingeklickt – so landet kein Klick auf dem Desktop oder in anderen Programmen.
 */
class WindowGuard {
    @Volatile var enabled = true
    private val expected = HashMap<com.customtrainableai.model.ScreenRect, HWND>()
    private val ownPid = ProcessHandle.current().pid().toInt()

    fun reset() = synchronized(this) { expected.clear() }

    private fun physical(p: com.customtrainableai.model.ScreenPoint): POINT {
        val s = screenScale()
        return POINT((p.x * s).toInt(), (p.y * s).toInt())
    }

    /** Oberstes Fenster unter dem Punkt (Java-Koordinaten). */
    fun rootAt(p: com.customtrainableai.model.ScreenPoint): HWND? {
        val u = U32.INSTANCE ?: return null
        val pt = physical(p)
        val h = u.WindowFromPoint(POINT.ByValue(pt.x, pt.y)) ?: return null
        return u.GetAncestor(h, U32.GA_ROOT) ?: h
    }

    private fun className(h: HWND): String {
        val buf = CharArray(128)
        val n = U32.INSTANCE?.GetClassName(h, buf, buf.size) ?: 0
        return String(buf, 0, n.coerceIn(0, buf.size))
    }

    /** Desktop-Hintergrund oder Taskleiste – dort darf nie geklickt werden. */
    private fun isShell(h: HWND) = className(h) in SHELL_CLASSES

    private fun isOwn(h: HWND): Boolean {
        val ref = IntByReference()
        U32.INSTANCE?.GetWindowThreadProcessId(h, ref)
        return ref.value == ownPid
    }

    private fun center(r: com.customtrainableai.model.ScreenRect) =
        com.customtrainableai.model.ScreenPoint(r.x + r.w / 2, r.y + r.h / 2)

    private fun title(h: HWND): String {
        val buf = CharArray(256)
        val n = U32.INSTANCE?.GetWindowText(h, buf, buf.size) ?: 0
        return String(buf, 0, n.coerceIn(0, buf.size))
    }

    /** Als Spielfenster taugt alles außer Desktop/Taskleiste und den eigenen Fenstern (bis auf die Übungsspiele). */
    private fun isGameCandidate(h: HWND) = !isShell(h) && (!isOwn(h) || title(h).startsWith("Übungsspiel"))

    /** Das beim ersten Aufruf gemerkte Fenster des Bereichs (null = keins / Desktop). */
    private fun expectedFor(r: com.customtrainableai.model.ScreenRect): HWND? = synchronized(this) {
        expected[r] ?: rootAt(center(r))?.takeIf { isGameCandidate(it) }?.also { expected[r] = it }
    }

    /** Zeigt der Bereich (noch) das Spielfenster? Eigene Fenster (z.B. Status-Anzeige) zählen nicht als Verdeckung. */
    fun areaOk(r: com.customtrainableai.model.ScreenRect): Boolean {
        val u = U32.INSTANCE ?: return true
        if (!enabled) return true
        val exp = expectedFor(r) ?: return false
        if (!u.IsWindow(exp) || u.IsIconic(exp)) return false
        val root = rootAt(center(r)) ?: return false
        return root == exp || (isOwn(root) && !isShell(root))
    }

    /** Darf an diesen Punkt geklickt werden? Nur wenn dort genau das Spielfenster des Bereichs liegt. */
    fun pointOk(r: com.customtrainableai.model.ScreenRect?, p: com.customtrainableai.model.ScreenPoint): Boolean {
        val u = U32.INSTANCE ?: return true
        if (!enabled || r == null) return true
        val exp = expectedFor(r) ?: return false
        if (!u.IsWindow(exp) || u.IsIconic(exp)) return false
        return rootAt(p) == exp
    }

    /** Hat das Spielfenster des Bereichs den Tastatur-Fokus? (für echte Tastendrücke) */
    fun focusOk(r: com.customtrainableai.model.ScreenRect?): Boolean {
        val u = U32.INSTANCE ?: return true
        if (!enabled || r == null) return true
        val exp = expectedFor(r) ?: return false
        val fg = u.GetForegroundWindow() ?: return false
        return (u.GetAncestor(fg, U32.GA_ROOT) ?: fg) == exp
    }

    companion object {
        val SHELL_CLASSES = setOf("Progman", "WorkerW", "Shell_TrayWnd", "Shell_SecondaryTrayWnd")
    }
}
