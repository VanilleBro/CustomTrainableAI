package com.customtrainableai.engine

import androidx.compose.ui.graphics.ImageBitmap
import com.customtrainableai.model.PracticeKind
import com.customtrainableai.model.Profile
import com.customtrainableai.model.ScreenRect

data class PermissionItem(
    val title: String,
    val description: String,
    val granted: Boolean,
    val actionLabel: String,
    val request: () -> Unit,
)

data class WindowInfo(val title: String, val client: ScreenRect)

/** Alles, was Windows und Android unterschiedlich machen. */
interface Platform {
    val name: String
    /** Android: nur Berührungen, Tasten werden zu Bildschirmpunkten. */
    val touchOnly: Boolean
    val supportsBackgroundInput: Boolean
    val supportsWindowList: Boolean
    val supportsLaunch: Boolean
    /** Desktop zeigt den Editor in einem eigenen Vollbild-Fenster. */
    val editorInSeparateWindow: Boolean
    val stopHint: String
    /** Countdown vor Trainingsbeginn (Zeit, um zum Spiel zu wechseln). */
    val startDelaySec: Double

    val storage: Storage
    val capturer: ScreenCapturer
    val input: InputController
    /** Liest Spielwerte direkt aus (Chrome-Tabs für BROWSER_JS-Regeln); null = nicht unterstützt. */
    val probe: ValueProbe? get() = null
    /** Browser-Spiele (Chrome): Fenster öffnen und Bereiche übernehmen; null = nicht unterstützt. */
    val browser: BrowserControl? get() = null

    /** "dd.MM.yyyy_HH.mm" in Ortszeit. */
    fun timestamp(): String

    fun exportText(fileName: String, text: String, onResult: (String?) -> Unit)
    fun importText(onResult: (String?) -> Unit)
    fun saveImage(fileName: String, frame: Frame, onResult: (String?) -> Unit)

    /** Versteckt die App, wartet, macht einen Screenshot des ganzen Bildschirms. */
    suspend fun captureForEditor(delaySec: Int): Frame?
    fun onEditorClosed() {}
    fun frameToImage(frame: Frame): ImageBitmap

    fun permissions(): List<PermissionItem>
    /** Prüft/konfiguriert vor dem Start. Rückgabe = Fehlermeldung oder null. */
    fun beforeRun(profile: Profile): String?
    fun onRunStarted()
    /** Start eines Laufs, der nur über Chrome steuert (keine echte Maus/Tastatur): App bleibt offen und sichtbar. */
    fun onBrowserRunStarted() = onRunStarted()
    fun onRunStopped()
    var onStopRequest: (() -> Unit)?
    /** Status-Text für Blase/Statusfenster. */
    fun showRunStatus(text: String) {}

    fun openPracticeGame(kind: PracticeKind)
    fun practiceAreas(): List<ScreenRect>
    fun launchInstances(command: String, count: Int): String?
    fun listWindows(): List<WindowInfo>
}

/** Steuert Chrome für Browser-Spiele (nur Windows). */
interface BrowserControl {
    /** Öffnet Chrome mit [cfg].windows Tabs bzw. Fenstern. Rückgabe: Fehlermeldung oder null. */
    suspend fun open(cfg: com.customtrainableai.model.BrowserConfig): String?
    /** Ermittelt die Spielfelder (CSS-Selektor) in allen offenen Tabs, in Tab-Reihenfolge. */
    suspend fun areas(selector: String): List<com.customtrainableai.model.ScreenRect>
    /** Anzahl verbundener Tabs. */
    fun connected(): Int
    /** Führt JavaScript in allen Tabs aus (z.B. Einstellungen setzen). Rückgabe: Ergebnisse als Text. */
    suspend fun runAll(script: String): List<String>
    /** Spielfelder als Bildschirm-Bereiche (für den Editor). */
    suspend fun screenAreas(selector: String): List<com.customtrainableai.model.ScreenRect> = emptyList()
    /** Vor einem Lauf: Spielfelder vermessen, Zeitsteuerung bzw. Bild-Stream starten. */
    suspend fun prepare(cfg: com.customtrainableai.model.BrowserConfig?) {}
    /** Nach einem Lauf: Seiten laufen wieder normal. */
    suspend fun release() {}
    /** Chrome schließen. */
    suspend fun closeAll() {}
    /** Uhr, mit der die KI die Spielzeit selbst weiterschaltet (Zeitsteuerung); null = Echtzeit. */
    fun stepClock(): Clock? = null
    /** Aktuelle Bilder aller Tabs für die Live-Ansicht. */
    fun previews(): List<Frame?> = emptyList()
}
