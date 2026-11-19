package com.customtrainableai

import androidx.compose.foundation.background
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.customtrainableai.desktop.DesktopPlatform
import com.customtrainableai.desktop.PracticeWindow
import com.customtrainableai.desktop.primaryScreenBounds
import com.customtrainableai.desktop.runSelfTest
import com.customtrainableai.desktop.runTour
import com.customtrainableai.model.ScreenRect
import com.customtrainableai.ui.App
import com.customtrainableai.ui.AppColors
import com.customtrainableai.ui.AppState
import com.customtrainableai.ui.EditorScreen
import com.customtrainableai.ui.PracticeGameView
import com.customtrainableai.ui.Screen
import com.customtrainableai.ui.saveAreaShot
import kotlinx.coroutines.runBlocking
import kotlin.math.roundToInt

fun main(args: Array<String>) {
    args.indexOf("--smoketest").takeIf { it >= 0 }?.let { i ->
        val ok = com.customtrainableai.desktop.runSmokeTest(java.io.File(args.getOrNull(i + 1) ?: "smoketest.txt"))
        kotlin.system.exitProcess(if (ok) 0 else 1)
    }
    val selftest = args.indexOf("--selftest").takeIf { it >= 0 }?.let { args.drop(it + 1) }
    val tourDir = args.indexOf("--tour").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val diagDir = args.indexOf("--capdiag").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    application {
        val scope = rememberCoroutineScope()
        val platform = remember { DesktopPlatform() }
        val state = remember { AppState(platform, scope) }

        if (selftest == null && diagDir == null) {
            Window(
                onCloseRequest = {
                    // Beim Schließen sicherheitshalber noch ein Backup schreiben
                    if (state.screen == Screen.PROFILE && state.dirty) runCatching { runBlocking { state.backupNow() } }
                    exitApplication()
                },
                title = "CustomTrainableAI",
                state = rememberWindowState(size = DpSize(1120.dp, 860.dp), position = WindowPosition(Alignment.Center)),
            ) {
                LaunchedEffect(Unit) { platform.mainWindow = window }
                App(state)
            }
        }

        PracticeWindows(platform, alwaysOnTop = selftest != null || diagDir != null)
        EditorWindow(state, platform)
        StatusWindow(state, platform)

        if (selftest != null) LaunchedEffect(Unit) { runSelfTest(platform, selftest); exitApplication() }
        if (diagDir != null) LaunchedEffect(Unit) { com.customtrainableai.desktop.runCapDiag(platform, java.io.File(diagDir)); exitApplication() }
        if (tourDir != null) LaunchedEffect(Unit) { runTour(state, platform, java.io.File(tourDir)); exitApplication() }
    }
}

@Composable
private fun ApplicationScope.PracticeWindows(platform: DesktopPlatform, alwaysOnTop: Boolean) {
    for (pw in platform.practiceWindows) key(pw.id) {
        PracticeGameWindow(platform, pw, alwaysOnTop)
    }
}

@Composable
private fun PracticeGameWindow(platform: DesktopPlatform, pw: PracticeWindow, alwaysOnTop: Boolean) {
    val idx = pw.id - 1
    Window(
        onCloseRequest = { platform.practiceWindows.remove(pw) },
        title = "Übungsspiel ${pw.kind.label} #${pw.id}",
        alwaysOnTop = alwaysOnTop,
        state = rememberWindowState(size = DpSize(400.dp, 660.dp), position = WindowPosition((30 + (idx % 4) * 410).dp, (40 + (idx / 4) * 40).dp)),
    ) {
        val win = window
        val density = LocalDensity.current.density
        MaterialTheme(colorScheme = AppColors) {
            PracticeGameView(pw.kind, Modifier.fillMaxSize(), onPlaced = { coords ->
                pw.areaProvider = {
                    if (!coords.isAttached || !win.isShowing) {
                        null
                    } else {
                        val pos = coords.positionInWindow()
                        val loc = win.contentPane.locationOnScreen
                        ScreenRect(
                            loc.x + (pos.x / density).roundToInt(),
                            loc.y + (pos.y / density).roundToInt(),
                            (coords.size.width / density).roundToInt(),
                            (coords.size.height / density).roundToInt(),
                        )
                    }
                }
            })
        }
    }
}

@Composable
private fun EditorWindow(state: AppState, platform: DesktopPlatform) {
    val ed = state.editor ?: return
    val b = remember { primaryScreenBounds() }
    Window(
        onCloseRequest = { state.closeEditor(false) },
        title = "CustomTrainableAI Editor",
        undecorated = true,
        alwaysOnTop = true,
        state = rememberWindowState(position = WindowPosition(b.x.dp, b.y.dp), size = DpSize(b.width.dp, b.height.dp)),
    ) {
        LaunchedEffect(Unit) { platform.dialogOwner = window }
        MaterialTheme(colorScheme = AppColors) {
            EditorScreen(ed, onDone = {
                platform.dialogOwner = null
                state.closeEditor(it)
            }, onSaveShot = { saveAreaShot(state, ed) })
        }
    }
}

@Composable
private fun StatusWindow(state: AppState, platform: DesktopPlatform) {
    if (!platform.statusVisible) return
    Window(
        onCloseRequest = { state.stop() },
        title = "CustomTrainableAI Status",
        undecorated = true,
        alwaysOnTop = true,
        focusable = false,
        resizable = false,
        state = rememberWindowState(size = DpSize(520.dp, 52.dp), position = WindowPosition(Alignment.TopCenter)),
    ) {
        MaterialTheme(colorScheme = AppColors) {
            WindowDraggableArea {
                Row(
                    Modifier.fillMaxSize().background(Color(0xF0111A2E)).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("🤖 " + platform.statusText, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Button(
                        onClick = { state.stop() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text("■ Stopp (F8)", fontSize = 12.sp) }
                }
            }
        }
    }
}
