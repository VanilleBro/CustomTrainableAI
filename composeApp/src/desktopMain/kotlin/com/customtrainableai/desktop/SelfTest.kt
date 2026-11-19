package com.customtrainableai.desktop

import com.customtrainableai.engine.Engine
import com.customtrainableai.engine.RealClock
import com.customtrainableai.engine.RunMode
import com.customtrainableai.model.InputMethod
import com.customtrainableai.model.PracticeKind
import com.customtrainableai.model.Templates
import com.customtrainableai.model.Templates.withAreas
import com.customtrainableai.model.fmt
import com.customtrainableai.model.toJson
import com.customtrainableai.ui.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import javax.imageio.ImageIO

/**
 * Selbsttest auf dem echten Bildschirm:
 *   CustomTrainableAI --selftest [catch|jump] [Instanzen] [background|real] [Generationen] [Logdatei]
 * Öffnet Übungsspiel-Fenster, nimmt sie per Robot auf, steuert sie und trainiert die KI. Ergebnis -> Logdatei.
 */
suspend fun runSelfTest(platform: DesktopPlatform, args: List<String>) {
    val kind = if (args.getOrNull(0) == "jump") PracticeKind.JUMP else PracticeKind.CATCH
    val count = args.getOrNull(1)?.toIntOrNull() ?: 2
    val method = if (args.getOrNull(2) == "real") InputMethod.REAL else InputMethod.BACKGROUND
    val gens = args.getOrNull(3)?.toIntOrNull() ?: 5
    val log = File(args.getOrNull(4) ?: "selftest-log.txt")
    log.writeText("")
    fun out(s: String) {
        println(s)
        log.appendText(s + "\n")
    }

    repeat(count) { platform.openPracticeGame(kind) }
    delay(3000)
    val areas = platform.practiceAreas()
    out("Selbsttest: $kind, $count Instanzen, $method, $gens Generationen")
    out("Bereiche: $areas  (Skalierung ${screenScale()})")
    if (areas.size != count) {
        out("FEHLER: nicht alle Übungsfenster gefunden")
        return
    }
    // Kontrollbild: so sieht die KI Instanz 1
    platform.capturer.capture(areas).firstOrNull()?.let {
        ImageIO.write(it.toBufferedImage(), "png", File(log.parentFile ?: File("."), "selftest-instanz1.png"))
    }

    val base = if (kind == PracticeKind.CATCH) Templates.practiceCatch() else Templates.practiceJump()
    val profile = base.withAreas(areas).copy(
        inputMethod = method,
        evolution = base.evolution.copy(generationsPerRun = gens),
    )
    platform.beforeRun(profile)
    val engine = Engine(platform.capturer, platform.input, RealClock, touchOnly = false)
    val start = System.currentTimeMillis()
    coroutineScope {
        val monitor = launch {
            while (true) {
                delay(10_000)
                val s = engine.status.value
                out("  … ${(System.currentTimeMillis() - start) / 1000}s: Gen ${s.generation + 1}, ${s.evaluated}/${s.total} bewertet, ${fmt(s.ticksPerSecond, 1)} Takte/s, Instanzen: " +
                    s.instances.joinToString { "${it.phase}(${fmt(it.reward, 1)})" })
            }
        }
        withContext(Dispatchers.Default) {
            engine.run(profile, RunMode.TRAINING, startDelaySec = 0.5) { b ->
                val h = b.history.last()
                out("Generation ${h.gen}: bester=${fmt(h.best)} Ø=${fmt(h.avg)} min=${fmt(h.min)}  (${(System.currentTimeMillis() - start) / 1000}s)")
            }
        }
        monitor.cancel()
    }
    out("Fertig nach ${(System.currentTimeMillis() - start) / 1000}s. ${engine.status.value.message}")
}

/** Macht Screenshots aller Bildschirme der App (zur Kontrolle der Oberfläche):  --tour <Ordner> */
suspend fun runTour(state: AppState, platform: DesktopPlatform, dir: File) {
    dir.mkdirs()
    val robot = Robot()
    suspend fun shot(name: String, full: Boolean = false) {
        delay(900)
        val r = if (full) primaryScreenBounds() else platform.mainWindow?.bounds ?: return
        ImageIO.write(robot.createScreenCapture(Rectangle(r)), "png", File(dir, "$name.png"))
    }
    delay(2500)
    platform.mainWindow?.toFront()
    shot("01-start")
    platform.openPracticeGame(PracticeKind.CATCH)
    delay(1500)
    state.newFromTemplate(PracticeKind.CATCH)
    platform.mainWindow?.toFront()
    for (t in 0..4) {
        state.tab = t
        shot("02-tab$t")
    }
    state.tab = 0
    state.simulate = true
    state.simRealtime = false
    state.update { it.copy(evolution = it.evolution.copy(generationsPerRun = 25)) }
    state.start(RunMode.TRAINING)
    delay(1500)
    shot("03-sim-running")
    var waited = 0
    while (state.isRunning && waited < 120) { delay(1000); waited++ }
    shot("04-sim-done")
    state.simulate = false
    state.openEditor(0)
    delay(3500)
    shot("05-editor", full = true)
    state.closeEditor(false)
    delay(800)
    shot("06-after-editor", full = true)
}

/** Diagnose der Bildschirmaufnahme:  --capdiag <Ordner> */
suspend fun runCapDiag(platform: DesktopPlatform, dir: File) {
    dir.mkdirs()
    val log = File(dir, "capdiag.txt").apply { writeText("") }
    fun out(s: String) { println(s); log.appendText(s + "\n") }
    platform.openPracticeGame(PracticeKind.CATCH)
    delay(2500)
    val area = platform.practiceAreas().firstOrNull() ?: return out("kein Fenster")
    out("Bereich $area")
    val score = com.customtrainableai.engine.PracticeLayout.SCORE
    var prev: FloatArray? = null
    var glitches = 0
    val t0 = System.nanoTime()
    for (i in 0 until 150) {
        val tStart = System.nanoTime()
        val f = platform.capturer.capture(listOf(area)).first() ?: continue
        val ms = (System.nanoTime() - tStart) / 1e6
        val cur = ArrayList<Float>()
        com.customtrainableai.ai.RewardTracker.forSamples(f, score, 1600) { _, _, c -> cur += com.customtrainableai.ai.RewardTracker.gray(c) }
        val arr = cur.toFloatArray()
        val p = prev
        var changed = 0
        if (p != null && p.size == arr.size) for (k in arr.indices) if (kotlin.math.abs(arr[k] - p[k]) > 25f) changed++
        val frac = if (p == null) 0.0 else changed.toDouble() / arr.size
        var sum = 0L
        for (c in f.argb) sum += (c shr 8) and 0xFF
        if (frac >= 0.01) {
            glitches++
            ImageIO.write(f.toBufferedImage(), "png", File(dir, "glitch-$i.png"))
            p?.let { }
        }
        out("#$i ${fmt(ms, 1)}ms ${f.width}x${f.height} scoreChange=${fmt(frac, 3)} meanG=${sum / f.argb.size}")
        prev = arr
        delay(100)
    }
    out("Fehlbilder: $glitches von 150 in ${(System.nanoTime() - t0) / 1_000_000} ms")
}

/**
 * Unsichtbarer Schnelltest der fertigen EXE (öffnet keine Fenster, klickt nichts):
 *   CustomTrainableAI.exe --smoketest <Ergebnisdatei>
 * Prüft Win32-Anbindung, Bildschirmaufnahme, Profil-JSON und ob die KI im Simulator lernt.
 */
fun runSmokeTest(out: File): Boolean {
    val sb = StringBuilder()
    var ok = true
    fun check(name: String, block: () -> String) {
        val r = runCatching(block)
        sb.appendLine((if (r.isSuccess) "OK   " else "FEHLER ") + name + ": " + (r.getOrNull() ?: r.exceptionOrNull().toString()))
        if (r.isFailure) ok = false
    }
    check("Java") { System.getProperty("java.runtime.version") + " " + System.getProperty("java.home") }
    check("Win32/JNA") {
        if (U32.INSTANCE == null) error("user32 nicht geladen")
        "geladen, Fenster unter (10,10) gefunden: ${WindowGuard().rootAt(com.customtrainableai.model.ScreenPoint(10, 10)) != null}"
    }
    check("Bildschirmaufnahme") { Robot().createScreenCapture(Rectangle(0, 0, 4, 4)).let { "${it.width}x${it.height}, Skalierung ${screenScale()}" } }
    check("Profil-JSON") {
        val p = Templates.practiceCatch()
        require(com.customtrainableai.model.profileFromJson(p.toJson()) == p) { "Profil nach Laden verändert" }
        "ok"
    }
    check("KI lernt (Simulator, Fangen, 40 Generationen)") {
        val areas = listOf(com.customtrainableai.model.ScreenRect(0, 0, 400, 640), com.customtrainableai.model.ScreenRect(410, 0, 400, 640))
        val p = Templates.practiceCatch().copy(instances = areas).let { it.copy(evolution = it.evolution.copy(generationsPerRun = 40)) }
        val clock = com.customtrainableai.engine.VirtualClock()
        val sim = com.customtrainableai.engine.PracticeSim(PracticeKind.CATCH, areas, clock, seed = 7)
        val engine = Engine(sim, sim, clock, touchOnly = false, random = kotlin.random.Random(7), statusInterval = 1e9)
        var brain: com.customtrainableai.model.Brain? = null
        kotlinx.coroutines.runBlocking { engine.run(p, RunMode.TRAINING) { brain = it } }
        val h = brain!!.history
        val first = h.take(3).map { it.avg.toDouble() }.average()
        val last = h.takeLast(5).map { it.avg.toDouble() }.average()
        require(last > first + 3) { "kein Lernfortschritt: ${fmt(first)} -> ${fmt(last)}" }
        "Ø Belohnung ${fmt(first)} -> ${fmt(last)}, beste Runde ${fmt(h.maxOf { it.best })}"
    }
    sb.appendLine(if (ok) "ALLES OK" else "FEHLGESCHLAGEN")
    out.writeText(sb.toString())
    return ok
}

/** Diagnose der Hintergrund-Eingabe:  --inputdiag <Datei>. Öffnet ein Übungsfenster und prüft, ob der Schläger reagiert. */
fun runInputDiagMain(out: File) {
    androidx.compose.ui.window.application {
        val kind = PracticeKind.CATCH
        var coords by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
        androidx.compose.ui.window.Window(
            onCloseRequest = ::exitApplication,
            title = "Übungsspiel Diagnose",
            alwaysOnTop = true,
            state = androidx.compose.ui.window.rememberWindowState(
                size = androidx.compose.ui.unit.DpSize(400.dp, 660.dp),
                position = androidx.compose.ui.window.WindowPosition(60.dp, 60.dp),
            ),
        ) {
            val density = androidx.compose.ui.platform.LocalDensity.current.density
            val w = window
            com.customtrainableai.ui.PracticeGameView(kind, androidx.compose.ui.Modifier.fillMaxSize(), onPlaced = { c -> coords = c })
            androidx.compose.runtime.LaunchedEffect(Unit) {
                delay(2500)
                val c = coords!!
                val p = c.positionInWindow()
                val loc = w.contentPane.locationOnScreen
                val area = com.customtrainableai.model.ScreenRect(
                    loc.x + (p.x / density).toInt(), loc.y + (p.y / density).toInt(),
                    (c.size.width / density).toInt(), (c.size.height / density).toInt(),
                )
                withContext(Dispatchers.IO) {
                    runCatching { inputDiag(area, out) }.onFailure { out.appendText("FEHLER $it\n") }
                }
                exitApplication()
            }
        }
    }
}

private suspend fun inputDiag(area: com.customtrainableai.model.ScreenRect, out: File) {
    out.writeText("Bereich $area\n")
    val u = U32.INSTANCE!!
    val cap = DesktopCapturer()
    suspend fun paddleX(): Float {
        val f = cap.capture(listOf(area)).first()!!
        var sum = 0.0; var n = 0
        for (y in (f.height * 0.73f).toInt()..(f.height * 0.78f).toInt()) for (x in 0 until f.width) {
            val c = f.argb[y * f.width + x]
            if ((c shr 16 and 0xFF) > 235 && (c shr 8 and 0xFF) > 235 && (c and 0xFF) > 235) { sum += x; n++ }
        }
        return if (n == 0) -1f else (sum / n / f.width).toFloat()
    }
    val pt = area.point(com.customtrainableai.engine.PracticeLayout.RIGHT_BTN.let { com.customtrainableai.model.RelPoint(it.x + it.w / 2, it.y + it.h / 2) })
    val ptL = area.point(com.customtrainableai.engine.PracticeLayout.LEFT_BTN.let { com.customtrainableai.model.RelPoint(it.x + it.w / 2, it.y + it.h / 2) })
    val child = u.WindowFromPoint(com.sun.jna.platform.win32.WinDef.POINT.ByValue(pt.x, pt.y))!!
    val root = u.GetAncestor(child, U32.GA_ROOT)!!
    fun cls(h: com.sun.jna.platform.win32.WinDef.HWND): String { val b = CharArray(128); val n = u.GetClassName(h, b, 128); return String(b, 0, n) }
    out.appendText("child=${cls(child)} root=${cls(root)} same=${child == root}\n")
    fun lp(h: com.sun.jna.platform.win32.WinDef.HWND, p: com.customtrainableai.model.ScreenPoint): com.sun.jna.platform.win32.WinDef.LPARAM {
        val q = com.sun.jna.platform.win32.WinDef.POINT(p.x, p.y); u.ScreenToClient(h, q)
        return com.sun.jna.platform.win32.WinDef.LPARAM((((q.y and 0xFFFF) shl 16) or (q.x and 0xFFFF)).toLong())
    }
    val wp0 = com.sun.jna.platform.win32.WinDef.WPARAM(0)
    val wp1 = com.sun.jna.platform.win32.WinDef.WPARAM(1)
    suspend fun trial(name: String, target: com.sun.jna.platform.win32.WinDef.HWND, send: Boolean, p: com.customtrainableai.model.ScreenPoint) {
        val before = paddleX()
        fun m(msg: Int, wp: com.sun.jna.platform.win32.WinDef.WPARAM) { if (send) u.SendMessage(target, msg, wp, lp(target, p)) else u.PostMessage(target, msg, wp, lp(target, p)) }
        m(U32.WM_MOUSEMOVE, wp0); m(U32.WM_LBUTTONDOWN, wp1)
        repeat(10) { delay(100); m(U32.WM_MOUSEMOVE, wp1) }
        m(U32.WM_LBUTTONUP, wp0)
        delay(300)
        val after = paddleX()
        out.appendText("$name: Schläger ${fmt(before, 3)} -> ${fmt(after, 3)}  ${if (kotlin.math.abs(after - before) > 0.03f) "BEWEGT" else "keine Bewegung"}\n")
    }
    trial("Post an Kind (rechts)", child, false, pt)
    trial("Post an Kind (links)", child, false, ptL)
    trial("Post an Hauptfenster (rechts)", root, false, pt)
    trial("Post an Hauptfenster (links)", root, false, ptL)
    trial("Send an Kind (rechts)", child, true, pt)
    trial("Send an Kind (links)", child, true, ptL)
    trial("Send an Hauptfenster (rechts)", root, true, pt)
    trial("Send an Hauptfenster (links)", root, true, ptL)
}
