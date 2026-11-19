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

    val engine = Engine(platform.capturer, platform.input, RealClock, platform.touchOnly)
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
            val practice = PracticeSim(p.practiceKind!!, p.instances.map { it.normalized() }, clock, seed = (0..100000).random())
            eng = Engine(practice, practice, clock, touchOnly = false, statusInterval = if (simRealtime) 0.2 else 2.0)
            scope.launch {
                while (isRunning) {
                    simPreview = practice.lastFrame
                    delay(100)
                }
            }
        } else {
            platform.beforeRun(p)?.let { toast = it; return }
            eng = engine
        }
        activeStatus = eng.status
        runMode = mode
        runJob = scope.launch(Dispatchers.Default) {
            if (!sim) withContext(Dispatchers.Main) { platform.onRunStarted() }
            try {
                eng.run(p, mode, if (sim) 0.0 else platform.startDelaySec) { brain ->
                    withContext(Dispatchers.Main) { onBrain(brain) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { toast = "Fehler im Training: ${e.message}" }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    runMode = RunMode.IDLE
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
        }
    }

    private fun onBrain(b: Brain) {
        profile = profile.copy(brain = b)
        dirty = true
        if (profile.evolution.autosave) saveQuietly()
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
            editor = EditorSession(frame, platform.frameToImage(frame), profile, platform.touchOnly)
        }
    }

    fun closeEditor(apply: Boolean) {
        val e = editor ?: return
        if (apply) {
            update {
                it.copy(
                    instances = e.instances,
                    actions = e.actions,
                    rewards = e.rewards,
                )
            }
            toast = "Positionen übernommen."
        }
        editor = null
        platform.onEditorClosed()
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
