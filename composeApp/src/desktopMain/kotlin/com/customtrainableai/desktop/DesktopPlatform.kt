package com.customtrainableai.desktop

import com.customtrainableai.model.InputMethod
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.customtrainableai.engine.Frame
import com.customtrainableai.engine.PermissionItem
import com.customtrainableai.engine.Platform
import com.customtrainableai.engine.WindowInfo
import com.customtrainableai.model.PracticeKind
import com.customtrainableai.model.Profile
import com.customtrainableai.model.ScreenRect
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.POINT
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinUser.WNDENUMPROC
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame as AwtFrame
import java.awt.MouseInfo
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

class PracticeWindow(val id: Int, val kind: PracticeKind) {
    /** Liefert die aktuelle Lage der Spielfläche in Bildschirm-Koordinaten (wird von der Oberfläche gesetzt). */
    @Volatile var areaProvider: (() -> ScreenRect?)? = null
    /** Das laufende Spiel und sein Fenster – Eingaben der KI gehen direkt hinein (Java-Fenster ignorieren Fenster-Nachrichten). */
    @Volatile var game: com.customtrainableai.engine.PracticeGame? = null
    @Volatile var hwnd: com.sun.jna.platform.win32.WinDef.HWND? = null
}

class DesktopPlatform(baseDir: File = File(System.getProperty("user.home"), "CustomTrainableAI")) : Platform {
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    override val name = if (isWindows) "Windows" else System.getProperty("os.name")
    override val touchOnly = false
    override val supportsBackgroundInput = isWindows && U32.INSTANCE != null
    override val supportsWindowList = isWindows && U32.INSTANCE != null
    override val supportsLaunch = isWindows
    override val editorInSeparateWindow = true
    override val stopHint = "F8 drücken oder die Maus ganz in die linke obere Bildschirmecke schieben"
    override val startDelaySec = 3.0

    override val storage = FileStorage(baseDir)
    /** Klickt/bewertet nur, solange das Spielfenster im Bereich liegt. */
    val guard = WindowGuard()
    /** Chrome-Anbindung für Browser-Spiele. */
    val chrome = ChromeBridge(baseDir)
    override val capturer = DesktopCapturer(guard) { area -> chrome.frameFor(area) }
    override val input = DesktopInput(guard, { root -> practiceWindows.toList().firstOrNull { it.hwnd == root } }, chrome)
    override val probe: com.customtrainableai.engine.ValueProbe = chrome
    override val browser: com.customtrainableai.engine.BrowserControl = chrome
    override var onStopRequest: (() -> Unit)? = null

    var mainWindow: ComposeWindow? = null
    /** Fenster, über dem Datei-Dialoge erscheinen (z.B. der Editor). */
    var dialogOwner: ComposeWindow? = null
    val practiceWindows = mutableStateListOf<PracticeWindow>()
    private var nextPracticeId = 1
    var statusVisible by mutableStateOf(false)
    var statusText by mutableStateOf("")
    var minimizeOnRun by mutableStateOf(true)

    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watcher: Job? = null

    override fun timestamp(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy_HH.mm"))

    private fun dialog(title: String, mode: Int, file: String? = null): File? {
        val d = FileDialog((dialogOwner ?: mainWindow) as AwtFrame?, title, mode)
        if (file != null) d.file = file
        d.isVisible = true
        val f = d.file ?: return null
        return File(d.directory, f)
    }

    override fun exportText(fileName: String, text: String, onResult: (String?) -> Unit) {
        val f = dialog("Profil exportieren", FileDialog.SAVE, fileName) ?: return onResult(null)
        val target = if (f.name.contains('.')) f else File(f.parentFile, f.name + ".json")
        runCatching { target.writeText(text, Charsets.UTF_8) }
            .onSuccess { onResult("Exportiert: ${target.absolutePath}") }
            .onFailure { onResult("Export fehlgeschlagen: ${it.message}") }
    }

    override fun importText(onResult: (String?) -> Unit) {
        val f = dialog("Profil importieren", FileDialog.LOAD) ?: return onResult(null)
        onResult(runCatching { f.readText(Charsets.UTF_8) }.getOrNull())
    }

    override fun saveImage(fileName: String, frame: Frame, onResult: (String?) -> Unit) {
        val f = dialog("Screenshot speichern", FileDialog.SAVE, fileName) ?: return onResult(null)
        val target = if (f.name.lowercase().endsWith(".png")) f else File(f.parentFile, f.name + ".png")
        runCatching { ImageIO.write(frame.toBufferedImage(), "png", target) }
            .onSuccess { onResult("Gespeichert: ${target.absolutePath} – dieses Bild an ChatGPT/Claude anhängen.") }
            .onFailure { onResult("Speichern fehlgeschlagen: ${it.message}") }
    }

    private fun onEdt(block: () -> Unit) = if (EventQueue.isDispatchThread()) block() else EventQueue.invokeLater(block)

    fun minimizeMain() = onEdt { mainWindow?.let { it.extendedState = it.extendedState or AwtFrame.ICONIFIED } }
    fun restoreMain() = onEdt {
        mainWindow?.let {
            it.extendedState = it.extendedState and AwtFrame.ICONIFIED.inv()
            it.toFront()
        }
    }

    override suspend fun captureForEditor(delaySec: Int): Frame? {
        minimizeMain()
        delay((delaySec * 1000L).coerceAtLeast(700))
        return capturer.fullScreenshot()
    }

    override fun onEditorClosed() = restoreMain()

    override fun frameToImage(frame: Frame): ImageBitmap = frame.toBufferedImage().toComposeImageBitmap()

    override fun permissions(): List<PermissionItem> = emptyList()

    override fun beforeRun(profile: Profile): String? {
        input.method = profile.inputMethod
        input.tapMs = profile.actions.tapMs
        input.warning = null
        guard.reset()
        val usesJs = profile.rewards.rules.any { it.enabled && it.type == com.customtrainableai.model.RuleType.BROWSER_JS }
        val virtual = profile.instances.any { it.x >= ChromeBridge.VIRTUAL_X }
        if ((profile.inputMethod == InputMethod.CHROME || usesJs || virtual) && chrome.connected() == 0) {
            return "Chrome ist nicht verbunden. Im Tab „Bereiche“ zuerst „Chrome-Tabs öffnen“."
        }
        if (virtual && profile.instances.any { chrome.tabFor(it) == null }) {
            return "Es sind weniger Chrome-Tabs offen als Instanzen. „Chrome-Tabs öffnen“ bzw. „Bereiche aus Chrome übernehmen“."
        }
        profile.browser?.let { chrome.useSelector(it.selector) }
        val b = primaryScreenBounds()
        profile.instances.filter { it.x < ChromeBridge.VIRTUAL_X }.forEachIndexed { i, r ->
            if (r.right <= b.x || r.bottom <= b.y || r.x >= b.x + b.width || r.y >= b.y + b.height) {
                return "Instanz ${i + 1} liegt außerhalb des Bildschirms – bitte im Editor festlegen."
            }
        }
        return null
    }

    override fun onBrowserRunStarted() {
        // Eingaben gehen nur an Chrome: App nicht minimieren, kein Not-Stopp über Maus-Ecke (man arbeitet nebenbei)
        statusVisible = false
        watcher?.cancel()
        watcher = null
    }

    override fun onRunStarted() {
        statusText = "Start …"
        statusVisible = true
        if (minimizeOnRun) minimizeMain()
        watcher?.cancel()
        watcher = bgScope.launch {
            delay(500)
            while (isActive) {
                val f8 = U32.INSTANCE?.let { (it.GetAsyncKeyState(U32.VK_F8).toInt() and 0x8000) != 0 } ?: false
                val p = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
                val corner = p != null && p.x <= 2 && p.y <= 2
                if (f8 || corner) {
                    withContext(Dispatchers.Main) { onStopRequest?.invoke() }
                    delay(1000)
                }
                delay(80)
            }
        }
    }

    override fun onRunStopped() {
        watcher?.cancel()
        watcher = null
        statusVisible = false
        restoreMain()
    }

    override fun showRunStatus(text: String) {
        statusText = input.warning?.let { "$text – $it" } ?: text
    }

    override fun openPracticeGame(kind: PracticeKind) {
        practiceWindows += PracticeWindow(nextPracticeId++, kind)
    }

    override fun practiceAreas(): List<ScreenRect> = practiceWindows.mapNotNull { pw -> runCatching { pw.areaProvider?.invoke() }.getOrNull() }

    override fun launchInstances(command: String, count: Int): String? = runCatching {
        repeat(count.coerceIn(1, 16)) {
            ProcessBuilder("cmd.exe", "/c", command).start()
            Thread.sleep(400)
        }
        null
    }.getOrElse { "Start fehlgeschlagen: ${it.message}" }

    override fun listWindows(): List<WindowInfo> {
        val u = U32.INSTANCE ?: return emptyList()
        val scale = screenScale()
        val out = mutableListOf<WindowInfo>()
        u.EnumWindows(WNDENUMPROC { hwnd: HWND, _ ->
            if (u.IsWindowVisible(hwnd) && !u.IsIconic(hwnd)) {
                val buf = CharArray(256)
                val len = u.GetWindowText(hwnd, buf, buf.size)
                val title = String(buf, 0, len.coerceAtLeast(0)).trim()
                val rc = RECT()
                if (title.isNotEmpty() && u.GetClientRect(hwnd, rc)) {
                    val w = rc.right - rc.left
                    val h = rc.bottom - rc.top
                    val pt = POINT(0, 0)
                    if (w >= 80 && h >= 80 && u.ClientToScreen(hwnd, pt) && !title.startsWith("CustomTrainableAI")) {
                        out += WindowInfo(
                            title,
                            ScreenRect((pt.x / scale).toInt(), (pt.y / scale).toInt(), (w / scale).toInt(), (h / scale).toInt()),
                        )
                    }
                }
            }
            true
        }, null)
        return out
    }
}
