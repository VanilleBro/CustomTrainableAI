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
    fun onRunStopped()
    var onStopRequest: (() -> Unit)?
    /** Status-Text für Blase/Statusfenster. */
    fun showRunStatus(text: String) {}

    fun openPracticeGame(kind: PracticeKind)
    fun practiceAreas(): List<ScreenRect>
    fun launchInstances(command: String, count: Int): String?
    fun listWindows(): List<WindowInfo>
}
