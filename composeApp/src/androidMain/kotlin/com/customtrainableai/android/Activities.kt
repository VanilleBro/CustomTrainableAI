package com.customtrainableai.android

import android.app.Application
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInWindow
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.customtrainableai.model.PracticeKind
import com.customtrainableai.model.ScreenRect
import com.customtrainableai.ui.App
import com.customtrainableai.ui.AppColors
import com.customtrainableai.ui.AppState
import com.customtrainableai.ui.PracticeGameView
import kotlinx.coroutines.MainScope
import kotlin.math.roundToInt

/** Hält Plattform und App-Zustand für die ganze Laufzeit (überlebt Activity-Neustarts). */
class CtaiApp : Application() {
    val scope = MainScope()
    val platform by lazy { AndroidPlatform(this) }
    val state by lazy { AppState(platform, scope) }
}

class MainActivity : ComponentActivity() {
    private val app get() = application as CtaiApp

    val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        app.platform.onProjectionResult(r.resultCode, r.data)
    }
    val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { app.platform.onExportUri(it) }
    val imageLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { app.platform.onImageUri(it) }
    val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { app.platform.onImportUri(it) }
    val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        app.platform.activity = this
        val state = app.state
        setContent {
            val editor = state.editor
            // Im Editor: Vollbild und Ausrichtung passend zum Screenshot
            LaunchedEffect(editor) {
                val ctl = WindowCompat.getInsetsController(window, window.decorView)
                if (editor != null) {
                    ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    ctl.hide(WindowInsetsCompat.Type.systemBars())
                    requestedOrientation = if (editor.frame.width > editor.frame.height) {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    }
                } else {
                    ctl.show(WindowInsetsCompat.Type.systemBars())
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
            App(state)
        }
    }

    override fun onResume() {
        super.onResume()
        app.platform.activity = this
        app.state.listVersion++
    }

    override fun onDestroy() {
        if (app.platform.activity === this) app.platform.activity = null
        super.onDestroy()
    }
}

/** Das eingebaute Übungsspiel als eigene Activity (eigene Aufgabe, damit es beim Training vorne bleibt). */
class PracticeActivity : ComponentActivity() {
    companion object {
        const val EXTRA_KIND = "kind"
        @Volatile var lastArea: ScreenRect? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.hide(WindowInsetsCompat.Type.systemBars())
        val kind = runCatching { PracticeKind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: "") }.getOrDefault(PracticeKind.CATCH)
        setContent {
            MaterialTheme(colorScheme = AppColors) {
                PracticeGameView(kind, Modifier.fillMaxSize(), onPlaced = { coords ->
                    val pos = coords.positionInWindow()
                    val loc = IntArray(2)
                    window.decorView.getLocationOnScreen(loc)
                    lastArea = ScreenRect(
                        loc[0] + pos.x.roundToInt(),
                        loc[1] + pos.y.roundToInt(),
                        coords.size.width,
                        coords.size.height,
                    )
                })
            }
        }
    }
}

