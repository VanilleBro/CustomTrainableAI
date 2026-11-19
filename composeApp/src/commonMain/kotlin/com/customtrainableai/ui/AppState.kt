package com.customtrainableai.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.customtrainableai.engine.BackupInfo
import com.customtrainableai.engine.Engine
import com.customtrainableai.engine.EngineStatus
import com.customtrainableai.engine.Frame
import com.customtrainableai.engine.Platform
import com.customtrainableai.engine.PracticeSim
import com.customtrainableai.engine.ProfileStore
import com.customtrainableai.engine.RealClock
import com.customtrainableai.engine.RunMode
import com.customtrainableai.engine.VirtualClock
import com.customtrainableai.model.Brain
import com.customtrainableai.model.PracticeKind
import com.customtrainableai.model.Profile
import com.customtrainableai.model.ScreenRect
import com.customtrainableai.model.VIRTUAL_AREA_X
import com.customtrainableai.model.Templates
import com.customtrainableai.model.Templates.withAreas
import com.customtrainableai.model.fmt
import com.customtrainableai.model.profileFromJson
import com.customtrainableai.model.toJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { LIST, PROFILE }

/** Zentraler Zustand der App (gemeinsam für Windows und Android). */
class AppState(val platform: Platform, private val scope: CoroutineScope) {
    val store = ProfileStore(platform.storage, platform::timestamp)

    var screen by mutableStateOf(Screen.LIST)
    var profile by mutableStateOf(Templates.blank())
        private set
    /** Dateiname (ohne .json), unter dem gespeichert wird und nach dem die Backups heißen. */
    var fileName by mutableStateOf("")
    var dirty by mutableStateOf(false)
    var tab by mutableStateOf(0)
    var editor by mutableStateOf<EditorSession?>(null)
    var toast by mutableStateOf<String?>(null)
    var listVersion by mutableStateOf(0)
    var lastBackup by mutableStateOf<String?>(null)
    var confirmClose by mutableStateOf(false)
    var runMode by mutableStateOf(RunMode.IDLE)
        private set

    /** Simulation statt echtem Bildschirm (nur für Übungsspiel-Profile). */
    var simulate by mutableStateOf(false)
    var simRealtime by mutableStateOf(true)
    var simPreview by mutableStateOf<Frame?>(null)
    /** Bilder aller gleichzeitig spielenden Simulator-Agenten. */
    var simFrames by mutableStateOf<List<Frame?>>(emptyList())
    /** Live-Bilder aller Chrome-Tabs werden gezeigt (Training mit Browser-Spiel). */
    var browserLive by mutableStateOf(false)

    val engine = Engine(platform.capturer, platform.input, RealClock, platform.touchOnly, probe = platform.probe)
    var activeStatus: StateFlow<EngineStatus> by mutableStateOf(engine.status)
        private set
    private var runJob: Job? = null

    val isRunning get() = runMode != RunMode.IDLE

    init {
        platform.onStopRequest = { scope.launch(Dispatchers.Main) { stop() } }
        scope.launch { backupLoop() }
    }

    // ---------------- Profile ----------------

    fun update(transform: (Profile) -> Profile) {
        val next = transform(profile)
        if (next != profile) {
            profile = next
            dirty = true
        }
    }

    fun newProfile(p: Profile) {
        profile = p
        fileName = ""
        dirty = true
        tab = 0
        screen = Screen.PROFILE
    }

    fun newFromTemplate(kind: PracticeKind) {
        val areas = platform.practiceAreas()
        val t = if (kind == PracticeKind.CATCH) Templates.practiceCatch() else Templates.practiceJump()
        newProfile(t.withAreas(areas))
        toast = if (areas.isEmpty()) "Tipp: Übungsspiel öffnen und unter „Bereiche“ → „An Übungsspiel anpassen“ tippen." else "Bereiche an ${areas.size} Übungsspiel-Fenster angepasst."
    }

    /** Vorlage für das Browser-Spiel Neon Flap (Chrome). */
    fun newNeonFlap() {
        newProfile(com.customtrainableai.model.Templates.neonFlap())
        tab = 2
        toast = "Vorlage angelegt – jetzt „Chrome-Tabs öffnen“."
    }

    // ---------------- Browser (Chrome) ----------------

    var browserBusy by mutableStateOf(false)
    var browserInfo by mutableStateOf("")

    private fun browserTask(label: String, block: suspend (com.customtrainableai.engine.BrowserControl) -> String) {
        val b = platform.browser ?: return
        if (browserBusy) return
        browserBusy = true
        browserInfo = "$label …"
        scope.launch {
            val msg = try { block(b) } catch (e: Throwable) { "Fehler: ${e.message}" }
            browserInfo = msg
            browserBusy = false
        }
    }

    private suspend fun runSetup(b: com.customtrainableai.engine.BrowserControl, script: String): String {
        var res = b.runAll(script)
        if (res.any { it.contains("reload") }) {
            kotlinx.coroutines.delay(3500)
            res = b.runAll(script)
        }
        val texts = res.map { it.trim('"') }
        // Gleiche Ergebnisse zusammenfassen (sonst eine Zeile pro Tab)
        return texts.withIndex().groupBy({ it.value }, { it.index + 1 }).entries.joinToString("\n") { (text, tabs) ->
            (if (tabs.size == texts.size && tabs.size > 1) "Alle ${tabs.size} Tabs" else "Tab ${tabs.joinToString(", ")}") + ": $text"
        }
    }

    private suspend fun adopt(b: com.customtrainableai.engine.BrowserControl, selector: String): String {
        val areas = b.areas(selector)
        if (areas.isEmpty()) return "Kein Spielfeld „$selector“ gefunden – ist die Seite geladen?"
        update { it.copy(instances = areas, inputMethod = com.customtrainableai.model.InputMethod.CHROME) }
        return "${areas.size} Spielbereich(e) aus Chrome übernommen."
    }

    /** Öffnet die Chrome-Fenster, setzt die Spiel-Einstellungen und übernimmt die Spielbereiche. */
    fun browserOpen() {
        val cfg = profile.browser ?: return
        browserTask("Chrome wird geöffnet") { b ->
            b.open(cfg)?.let { return@browserTask it }
            kotlinx.coroutines.delay(if (cfg.timeControl) 5000 else 4000)
            val setup = if (cfg.setupScript.isNotBlank()) runSetup(b, cfg.setupScript) + "\n" else ""
            kotlinx.coroutines.delay(500)
            setup + adopt(b, cfg.selector)
        }
    }

    fun browserClose() {
        browserTask("Chrome wird geschlossen") { b -> b.closeAll(); "Chrome geschlossen." }
    }

    fun browserSetup() {
        val cfg = profile.browser ?: return
        browserTask("Einstellungen werden gesetzt") { b -> runSetup(b, cfg.setupScript) }
    }

    fun browserAdopt() {
        val cfg = profile.browser ?: return
        browserTask("Spielfelder werden gesucht") { b -> adopt(b, cfg.selector) }
    }

    fun open(name: String) = guard("Laden") {
        profile = store.load(name)
        fileName = name
        dirty = false
        tab = 0
        screen = Screen.PROFILE
    }

    fun openBackup(b: BackupInfo) = guard("Backup laden") {
        profile = store.loadBackup(b.fileName)
        fileName = b.baseName
        dirty = true
        tab = 0
        screen = Screen.PROFILE
        toast = "Backup vom ${b.stamp.replace('_', ' ')} geladen – speichern nicht vergessen."
    }

    fun deleteProfile(name: String) = guard("Löschen") {
        store.delete(name)
        listVersion++
    }

    fun deleteBackup(b: BackupInfo) = guard("Löschen") {
        store.deleteBackup(b.fileName)
        listVersion++
    }

    fun save(asName: String? = null): Boolean {
        val target = ProfileStore.sanitize(asName ?: fileName.ifBlank { profile.name })
        return try {
            store.save(target, profile)
            fileName = target
            dirty = false
            listVersion++
            toast = "Gespeichert als $target.json"
            true
        } catch (e: Exception) {
            toast = "Speichern fehlgeschlagen: ${e.message}"
            false
        }
    }

    private fun saveQuietly() {
        val target = ProfileStore.sanitize(fileName.ifBlank { profile.name })
        val p = profile
        scope.launch(Dispatchers.Default) {
            runCatching { store.save(target, p) }.onSuccess {
                withContext(Dispatchers.Main) {
                    fileName = target
                    if (profile == p) dirty = false
                    listVersion++
                }
            }
        }
    }

    fun exportProfile() {
        val name = ProfileStore.sanitize(fileName.ifBlank { profile.name })
        platform.exportText("$name.json", profile.toJson()) { msg -> msg?.let { toast = it } }
    }

    fun importProfile() {
        platform.importText { text ->
            if (text == null) return@importText
            try {
                val p = profileFromJson(text)
                newProfile(p)
                fileName = ProfileStore.sanitize(p.name)
                toast = "Profil „${p.name}“ importiert – zum Behalten speichern."
            } catch (e: Exception) {
                toast = "Keine gültige Profil-Datei: ${e.message?.take(120)}"
            }
        }
    }

    fun requestClose() {
        if (dirty) confirmClose = true else close()
    }

    fun close() {
        if (isRunning) stop()
        confirmClose = false
        screen = Screen.LIST
        listVersion++
    }

    fun resetTraining() = update { it.copy(brain = null) }

    // ---------------- Backups ----------------

    private suspend fun backupLoop() {
        while (true) {
            delay(BACKUP_INTERVAL_MS)
            backupNow()
        }
    }

    suspend fun backupNow() {
        if (screen != Screen.PROFILE) return
        val p = profile
        val base = fileName.ifBlank { p.name }
        val result = withContext(Dispatchers.Default) { runCatching { store.writeBackup(base, p) } }
        result.onSuccess { lastBackup = it }.onFailure { toast = "Backup fehlgeschlagen: ${it.message}" }
    }

    // ---------------- Training ----------------

    fun start(mode: RunMode) {
        if (isRunning) return
        val p = profile
        if (mode == RunMode.PLAYBACK && p.brain?.best == null) {
            toast = "Noch kein trainiertes Netz – erst trainieren."
            return
        }
        val sim = simulate && p.practiceKind != null
        val eng: Engine
        if (sim) {
            val clock = if (simRealtime) RealClock else VirtualClock()
            // Im Simulator spielen so viele Agenten gleichzeitig wie eingestellt (0 = alle der Generation)
            val parallel = simParallelCount(p)
            val simAreas = List(parallel) { i -> ScreenRect(i * 1000, 0, 400, 640) }
            val practice = PracticeSim(p.practiceKind!!, simAreas, clock, seed = (0..100000).random())
            eng = Engine(practice, practice, clock, touchOnly = false, statusInterval = if (simRealtime) 0.2 else 1.0)
            val simProfile = p.copy(instances = simAreas)
            scope.launch {
                while (isRunning) {
                    simPreview = practice.lastFrame
                    simFrames = practice.frames.toList()
                    delay(if (simRealtime) 100 else 250)
                }
            }
            activeStatus = eng.status
            runMode = mode
            runJob = scope.launch(Dispatchers.Default) {
                try {
                    eng.run(simProfile, mode, 0.0) { brain -> withContext(Dispatchers.Main) { onBrain(brain) } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    withContext(Dispatchers.Main) { toast = "Fehler im Training: ${e.message}" }
                } finally {
                    withContext(NonCancellable + Dispatchers.Main) {
                        runMode = RunMode.IDLE
                        eng.status.value.message.takeIf { it.isNotBlank() }?.let { toast = it }
                    }
                }
            }
            return
        } else {
            platform.beforeRun(p)?.let { toast = it; return }
            eng = if (usesStepping(p)) {
                // Zeitsteuerung: die KI schaltet die Spielzeit in allen Chrome-Tabs selbst weiter
                val clock = platform.browser?.stepClock() ?: RealClock
                Engine(platform.capturer, platform.input, clock, platform.touchOnly, statusInterval = 0.5, probe = platform.probe)
            } else engine
        }
        val stepped = usesStepping(p)
        val usesBrowser = usesBrowser(p)
        activeStatus = eng.status
        runMode = mode
        runJob = scope.launch(Dispatchers.Default) {
            if (!sim) {
                if (usesBrowser) runCatching { platform.browser?.prepare(p.browser) }
                withContext(Dispatchers.Main) {
                    if (p.inputMethod == com.customtrainableai.model.InputMethod.CHROME) platform.onBrowserRunStarted() else platform.onRunStarted()
                }
            }
            try {
                // Bei Zeitsteuerung muss man nicht erst zum Spiel wechseln -> kein Countdown
                eng.run(p, mode, if (sim || stepped) 0.0 else platform.startDelaySec) { brain ->
                    withContext(Dispatchers.Main) { onBrain(brain) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { toast = "Fehler im Training: ${e.message}" }
            } finally {
                if (usesBrowser) withContext(NonCancellable) { runCatching { platform.browser?.release() } }
                withContext(NonCancellable + Dispatchers.Main) {
                    runMode = RunMode.IDLE
                    browserLive = false
                    if (!sim) platform.onRunStopped()
                    eng.status.value.message.takeIf { it.isNotBlank() }?.let { toast = it }
                }
            }
        }
        if (!sim) {
            scope.launch {
                while (isRunning) {
                    val s = eng.status.value
                    platform.showRunStatus(statusLine(s))
                    delay(500)
                }
            }
            if (usesBrowser && p.instances.any { it.x >= com.customtrainableai.model.VIRTUAL_AREA_X }) {
                // Live-Ansicht aller Chrome-Tabs in der App (die Tabs selbst müssen nicht sichtbar sein)
                browserLive = true
                scope.launch {
                    while (isRunning) {
                        val frames = withContext(Dispatchers.Default) { runCatching { platform.browser?.previews() }.getOrNull().orEmpty() }
                        simFrames = frames.take(p.instances.size)
                        delay(300)
                    }
                }
            }
        }
    }

    private fun usesBrowser(p: Profile) = p.inputMethod == com.customtrainableai.model.InputMethod.CHROME ||
        p.rewards.rules.any { it.enabled && it.type == com.customtrainableai.model.RuleType.BROWSER_JS }

    /** Training mit Zeitsteuerung in Chrome (KI gibt den Takt vor). */
    private fun usesStepping(p: Profile) = platform.browser != null && usesBrowser(p) && (p.browser?.timeControl ?: true) &&
        p.instances.isNotEmpty() && p.instances.all { it.x >= com.customtrainableai.model.VIRTUAL_AREA_X }

    private fun onBrain(b: Brain) {
        profile = profile.copy(brain = b)
        dirty = true
        if (profile.evolution.autosave) saveQuietly()
    }

    /** Anzahl gleichzeitig spielender Agenten im Simulator. */
    fun simParallelCount(p: Profile = profile): Int {
        val pop = p.evolution.population.coerceIn(2, 1000)
        return (if (p.simParallel <= 0) pop else p.simParallel).coerceIn(1, minOf(pop, 64))
    }

    fun stop() {
        runJob?.cancel()
    }

    // ---------------- Editor ----------------

    fun openEditor(delaySec: Int) {
        if (isRunning || editor != null) return
        scope.launch {
            if (delaySec > 0) toast = "Screenshot in $delaySec s – wechsle jetzt zum Spiel!"
            val frame = try {
                platform.captureForEditor(delaySec)
            } catch (e: Exception) {
                null
            }
            if (frame == null) {
                toast = "Kein Screenshot möglich – Bildschirmaufnahme erlaubt?"
                platform.onEditorClosed()
                return@launch
            }
            editor = EditorSession(frame, platform.frameToImage(frame), editorProfile(), platform.touchOnly)
        }
    }

    fun closeEditor(apply: Boolean) {
        val e = editor ?: return
        if (apply) {
            update {
                it.copy(
                    // Chrome-Instanzen (Bild direkt aus Chrome) behalten ihre Bereiche – übernommen werden nur die Positionen
                    instances = if (it.instances.any { r -> r.x >= VIRTUAL_AREA_X }) it.instances else e.instances,
                    actions = e.actions,
                    rewards = e.rewards,
                )
            }
            toast = "Positionen übernommen."
        }
        editor = null
        platform.onEditorClosed()
    }

    /** Editor mit Chrome-Instanzen: Spielfeld des 1. Fensters auf dem Bildschirm zeigen. */
    private suspend fun editorProfile(): Profile {
        val p = profile
        if (p.instances.none { it.x >= VIRTUAL_AREA_X }) return p
        val sel = p.browser?.selector ?: "canvas"
        val screen = runCatching { platform.browser?.screenAreas(sel) }.getOrNull().orEmpty()
        return p.copy(instances = screen.take(1).ifEmpty { listOf(ScreenRect(100, 100, 400, 600)) })
    }

    fun usePracticeAreas() {
        val areas = platform.practiceAreas()
        if (areas.isEmpty()) {
            toast = "Kein Übungsspiel offen."
            return
        }
        update { it.copy(instances = areas) }
        toast = "${areas.size} Bereich(e) an das Übungsspiel angepasst."
    }

    private inline fun guard(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            toast = "$what fehlgeschlagen: ${e.message?.take(150)}"
        }
    }

    companion object {
        const val BACKUP_INTERVAL_MS = 120_000L

        fun statusLine(s: EngineStatus): String = when {
            s.countdown > 0 -> "Start in ${s.countdown} s"
            s.mode == RunMode.TRAINING -> "Gen ${s.generation + 1} · ${s.evaluated}/${s.total} · Bester ${fmt(s.bestFitness, 1)}"
            s.mode == RunMode.PLAYBACK -> "Abspielen · Runden ${s.playback.size} · Letzte ${s.playback.lastOrNull()?.let { fmt(it, 1) } ?: "–"}"
            else -> "Gestoppt"
        }
    }
}
