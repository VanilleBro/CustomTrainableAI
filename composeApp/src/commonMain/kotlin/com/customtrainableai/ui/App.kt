package com.customtrainableai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.customtrainableai.engine.BackupInfo
import com.customtrainableai.model.PracticeKind
import com.customtrainableai.model.Templates
import kotlinx.coroutines.delay

@Composable
fun App(state: AppState) {
    MaterialTheme(colorScheme = AppColors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val ed = state.editor
            if (ed != null && !state.platform.editorInSeparateWindow) {
                EditorScreen(ed, onDone = { state.closeEditor(it) }, onSaveShot = { saveAreaShot(state, ed) })
            } else {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    when (state.screen) {
                        Screen.LIST -> ProfileListScreen(state)
                        Screen.PROFILE -> ProfileScreen(state)
                    }
                }
            }
            Toast(state)
            if (state.confirmClose) {
                AlertDialog(
                    onDismissRequest = { state.confirmClose = false },
                    title = { Text("Ungespeicherte Änderungen") },
                    text = { Text("Soll „${state.profile.name}“ vor dem Schließen gespeichert werden?") },
                    confirmButton = { Button(onClick = { if (state.save()) state.close() }) { Text("Speichern") } },
                    dismissButton = {
                        Row {
                            TextButton(onClick = { state.close() }) { Text("Verwerfen") }
                            TextButton(onClick = { state.confirmClose = false }) { Text("Abbrechen") }
                        }
                    },
                )
            }
        }
    }
}

fun saveAreaShot(state: AppState, ed: EditorSession) {
    val name = (state.fileName.ifBlank { state.profile.name }) + "_bereich.png"
    state.platform.saveImage(name, ed.areaCrop()) { msg -> msg?.let { ed.info = it } }
}

@Composable
private fun Toast(state: AppState) {
    val msg = state.toast ?: return
    LaunchedEffect(msg) {
        delay(4500)
        if (state.toast == msg) state.toast = null
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.widthIn(max = 640.dp)
                .background(Color(0xF0263248), RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(msg, color = Color.White, fontSize = 14.sp)
        }
    }
}

@Composable
fun ProfileListScreen(state: AppState) {
    val profiles = remember(state.listVersion) { runCatching { state.store.listProfiles() }.getOrDefault(emptyList()) }
    val backups = remember(state.listVersion) { runCatching { state.store.listBackups() }.getOrDefault(emptyList()) }
    var deleteName by remember { mutableStateOf<String?>(null) }
    var deleteBackup by remember { mutableStateOf<BackupInfo?>(null) }
    var permTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            permTick++
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("CustomTrainableAI", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Hint("Selbstlernende KI für Spiele · ${state.platform.name} · lernt per Belohnung, Generation für Generation")
        VSpace(6)

        val perms = remember(permTick) { state.platform.permissions() }
        if (perms.isNotEmpty()) {
            Section("Berechtigungen") {
                perms.forEach { p ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text((if (p.granted) "✅ " else "⚠️ ") + p.title, fontWeight = FontWeight.Medium)
                            Hint(p.description)
                        }
                        if (!p.granted) FilledTonalButton(onClick = p.request) { Text(p.actionLabel) }
                    }
                }
            }
        }

        Section("Profile") {
            ButtonRow {
                Button(onClick = { state.newProfile(Templates.blank()) }) { Text("+ Neues Profil") }
                OutlinedButton(onClick = { state.importProfile() }) { Text("Profil importieren…") }
            }
            if (profiles.isEmpty()) Hint("Noch keine gespeicherten Profile. Lege ein neues an oder starte mit einer Übungs-Vorlage.")
            profiles.forEach { name ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f), fontSize = 15.sp)
                    TextButton(onClick = { state.open(name) }) { Text("Öffnen") }
                    TextButton(onClick = { deleteName = name }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        Section("Ausprobieren: Übungsspiel") {
            Hint("Das eingebaute Übungsspiel zeigt, wie die KI lernt. 1) Übungsspiel öffnen (auf Windows auch mehrmals = mehrere Instanzen), 2) passende Vorlage wählen, 3) Training starten. Im Simulator geht es ganz ohne Bildschirm-Steuerung.")
            ButtonRow {
                FilledTonalButton(onClick = { state.platform.openPracticeGame(PracticeKind.CATCH) }) { Text("Übungsspiel „Fangen“ öffnen") }
                FilledTonalButton(onClick = { state.platform.openPracticeGame(PracticeKind.JUMP) }) { Text("Übungsspiel „Springen“ öffnen") }
            }
            ButtonRow {
                OutlinedButton(onClick = { state.newFromTemplate(PracticeKind.CATCH) }) { Text("Vorlage: Fangen (2 Punkte)") }
                OutlinedButton(onClick = { state.newFromTemplate(PracticeKind.JUMP) }) { Text("Vorlage: Springen (1 Punkt)") }
            }
        }

        Section("Backups") {
            Hint("Während ein Profil offen ist, wird alle 2 Minuten automatisch ein Backup geschrieben: „{Dateiname}backup{TT.MM.JJJJ}_{HH.MM}.json“. Pro Profil gibt es genau ein Backup, das jedes Mal ersetzt wird.")
            if (backups.isEmpty()) Hint("Keine Backups vorhanden.")
            backups.forEach { b ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b.baseName, fontSize = 15.sp)
                        Hint(b.fileName)
                    }
                    TextButton(onClick = { state.openBackup(b) }) { Text("Wiederherstellen") }
                    TextButton(onClick = { deleteBackup = b }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        Section("So funktioniert's") {
            Hint("1. Profil anlegen → Tab „Aktionen“: Steuerungsart wählen (1 Punkt, 2 Punkte, WASD, WASD + Klicks, Joystick, 1 Punkt halten …).")
            Hint("2. Tab „Bereiche“: Editor öffnen → Rechteck über das Spiel ziehen, Klickpunkte/Joystick platzieren. Für mehrere Spiel-Instanzen weitere Rechtecke hinzufügen.")
            Hint("3. Tab „Belohnungen“: Spielziel im Prompt-Assistenten beschreiben → Prompt in ChatGPT/Claude einfügen → Antwort zurück einfügen. Positionen/Farben im Editor mit der Pipette prüfen.")
            Hint("4. Tab „Training“: Training starten. Der Graph zeigt den Fortschritt pro Generation. Mit „Abspielen“ spielt die beste KI, ohne weiter zu lernen.")
            Hint("Stoppen: ${state.platform.stopHint}")
        }
    }

    deleteName?.let { n ->
        AlertDialog(
            onDismissRequest = { deleteName = null },
            title = { Text("Profil löschen?") },
            text = { Text("„$n“ wird endgültig gelöscht (das Backup bleibt erhalten).") },
            confirmButton = { Button(onClick = { state.deleteProfile(n); deleteName = null }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { deleteName = null }) { Text("Abbrechen") } },
        )
    }
    deleteBackup?.let { b ->
        AlertDialog(
            onDismissRequest = { deleteBackup = null },
            title = { Text("Backup löschen?") },
            text = { Text("${b.fileName} wird endgültig gelöscht.") },
            confirmButton = { Button(onClick = { state.deleteBackup(b); deleteBackup = null }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { deleteBackup = null }) { Text("Abbrechen") } },
        )
    }
}
