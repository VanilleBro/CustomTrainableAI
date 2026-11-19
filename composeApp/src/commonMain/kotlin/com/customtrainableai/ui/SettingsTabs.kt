package com.customtrainableai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.customtrainableai.ai.NeuralNet
import com.customtrainableai.engine.Engine
import com.customtrainableai.engine.WindowInfo
import com.customtrainableai.model.ActionMode
import com.customtrainableai.model.InputMethod
import com.customtrainableai.model.KeyLayout
import com.customtrainableai.model.PromptInput
import com.customtrainableai.model.RelPoint
import com.customtrainableai.model.RewardImport
import com.customtrainableai.model.RewardPrompt
import com.customtrainableai.model.RewardRule
import com.customtrainableai.model.RuleType
import com.customtrainableai.model.ScreenRect
import com.customtrainableai.model.fmt
import com.customtrainableai.model.parseRewardImport
import com.customtrainableai.model.toPrettyJson

// ======================= Aktionen =======================

@Composable
fun ActionsTab(state: AppState) {
    val p = state.profile
    val a = p.actions
    val touch = state.platform.touchOnly
    Section("Steuerungsart") {
        Dropdown("Modus", ActionMode.entries, a.mode, { it.label }, { m -> state.update { it.copy(actions = it.actions.copy(mode = m)) } })
        Hint(a.mode.description)
        Fields {
            if (a.mode == ActionMode.WASD_CLICKS || a.mode == ActionMode.JOYSTICK_CLICKS) {
                IntField("Anzahl Klickpunkte", a.extraClicks, { v -> state.update { it.copy(actions = it.actions.copy(extraClicks = v)) } }, min = 1, max = 4)
            }
            if (a.usesJoystick) {
                Dropdown("Richtungen", listOf(4, 8, 16), a.joystickDirections, { "$it" }, { v -> state.update { it.copy(actions = it.actions.copy(joystickDirections = v)) } })
            }
            if (a.usesKeys && !touch) {
                Dropdown("Tasten", KeyLayout.entries, a.keyLayout, { it.label }, { v -> state.update { it.copy(actions = it.actions.copy(keyLayout = v)) } })
            }
            IntField("Klickdauer (ms)", a.tapMs, { v -> state.update { it.copy(actions = it.actions.copy(tapMs = v)) } }, min = 5, max = 1000)
            IntField("Entscheidungen pro Sekunde", p.tickRate, { v -> state.update { it.copy(tickRate = v) } }, Modifier.width(220.dp), 1, 60)
        }
        if (a.usesKeys && touch) Hint("Auf Android gibt es keine echte Tastatur: W/A/S/D werden als gedrückte Bildschirmpunkte ausgeführt (gelbe Punkte im Editor).")
        Hint("Mehr Entscheidungen pro Sekunde = feinere Steuerung, aber jede Runde liefert mehr (verrauschte) Schritte. 5–15 ist meist gut.")
    }

    Section("Positionen (relativ zum Spielbereich, 0…1)") {
        Button(onClick = { state.openEditor(3) }) { Text("Im Editor festlegen (Screenshot in 3 s)") }
        for (i in 0 until a.usedClickPoints) {
            val cp = a.clickPoint(i)
            PointRow(if (a.mode == ActionMode.HOLD_1) "Haltepunkt" else "Klickpunkt ${'A' + i}", cp) { np -> state.update { it.copy(actions = it.actions.withClickPoint(i, np)) } }
        }
        if (a.usesJoystick) {
            PointRow("Joystick-Mitte", a.joystickCenter) { np -> state.update { it.copy(actions = it.actions.copy(joystickCenter = np)) } }
            Fields {
                FloatField("Joystick-Radius (Anteil Breite)", a.joystickRadius, { v -> state.update { it.copy(actions = it.actions.copy(joystickRadius = v)) } }, Modifier.width(240.dp), 0.005f, 0.5f)
            }
        }
        if (a.usesKeys && touch) {
            listOf("W", "A", "S", "D").forEachIndexed { i, k ->
                PointRow("Taste $k", a.keyPoint(i)) { np -> state.update { it.copy(actions = it.actions.withKeyPoint(i, np)) } }
            }
        }
    }

    if (state.platform.supportsBackgroundInput) {
        Section("Eingabe-Methode (Windows)") {
            Dropdown("Methode", InputMethod.entries, p.inputMethod, { it.label }, { m -> state.update { it.copy(inputMethod = m) } })
            if (p.inputMethod == InputMethod.REAL) {
                Hint("Echte Maus/Tastatur: funktioniert mit fast allen Spielen. Es gibt nur einen Mauszeiger und die Tasten gehen an das aktive Fenster – Halten, Joystick und WASD klappen daher nur mit einer Instanz gleichzeitig zuverlässig. Klick-Modi gehen auch mit mehreren Instanzen.")
                if (p.instances.size > 1 && (a.usesKeys || a.usesJoystick || a.mode == ActionMode.HOLD_1)) {
                    Hint("⚠️ ${p.instances.size} Instanzen mit „${a.mode.label}“: bitte „Hintergrund“ verwenden.", MaterialTheme.colorScheme.error)
                }
            } else {
                Hint("Hintergrund: Klicks und Tasten werden direkt an das Fenster unter dem Punkt geschickt – der Mauszeiger bleibt frei, mehrere Instanzen können gleichzeitig halten/ziehen/Tasten drücken. Manche Spiele (DirectInput/Vollbild) ignorieren das – dann „Echt“ verwenden.")
            }
        }
    }
}

@Composable
fun PointRow(label: String, p: RelPoint, onChange: (RelPoint) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(130.dp), fontSize = 14.sp)
        FloatField("x", p.x, { onChange(p.copy(x = it).clamped()) }, Modifier.width(100.dp), 0f, 1f)
        FloatField("y", p.y, { onChange(p.copy(y = it).clamped()) }, Modifier.width(100.dp), 0f, 1f)
    }
}

// ======================= Bereiche =======================

@Composable
fun AreasTab(state: AppState) {
    val p = state.profile
    var windows by remember { mutableStateOf<List<WindowInfo>?>(null) }
    Section("Spielbereiche (eine Instanz pro Rechteck)") {
        Hint("Jedes Rechteck ist eine eigene Instanz des Spiels. Alle Instanzen trainieren gleichzeitig verschiedene Agenten – mit 4 Instanzen ist eine Generation ~4× schneller fertig. Klickpunkte und Belohnungen gelten relativ zum Rechteck, also für alle Instanzen gleich.")
        ButtonRow {
            Button(onClick = { state.openEditor(3) }) { Text("Editor öffnen (Screenshot in 3 s)") }
            OutlinedButton(onClick = { state.openEditor(0) }) { Text("Editor sofort") }
            FilledTonalButton(onClick = {
                state.update { pr ->
                    val last = pr.instances.lastOrNull() ?: ScreenRect()
                    pr.copy(instances = pr.instances + ScreenRect(last.x + last.w + 10, last.y, last.w, last.h))
                }
            }) { Text("+ Instanz") }
            if (p.practiceKind != null) OutlinedButton(onClick = { state.usePracticeAreas() }) { Text("An Übungsspiel anpassen") }
            if (state.platform.supportsWindowList) OutlinedButton(onClick = { windows = state.platform.listWindows() }) { Text("An Fenster anpassen…") }
        }
        p.instances.forEachIndexed { i, r ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Instanz ${i + 1}", Modifier.width(80.dp), fontWeight = FontWeight.Medium)
                fun set(nr: ScreenRect) = state.update { pr -> pr.copy(instances = pr.instances.toMutableList().also { it[i] = nr.normalized() }) }
                IntField("x", r.x, { set(r.copy(x = it)) }, Modifier.width(90.dp), -10000, 100000)
                IntField("y", r.y, { set(r.copy(y = it)) }, Modifier.width(90.dp), -10000, 100000)
                IntField("Breite", r.w, { set(r.copy(w = it)) }, Modifier.width(90.dp), 8, 100000)
                IntField("Höhe", r.h, { set(r.copy(h = it)) }, Modifier.width(90.dp), 8, 100000)
                if (p.instances.size > 1) TextButton(onClick = { state.update { pr -> pr.copy(instances = pr.instances.filterIndexed { k, _ -> k != i }) } }) { Text("✕") }
            }
        }
    }

    if (state.platform.supportsLaunch) {
        Section("Spiel-Instanzen starten (Windows)") {
            Hint("Befehl, der das Spiel startet, z.B. „start \"\" \"C:\\Spiele\\MeinSpiel.exe\"“ oder „start chrome --new-window https://beispiel.de/spiel“. Danach die Fenster nebeneinander legen und „An Fenster anpassen…“ benutzen.")
            Fields {
                TextField("Startbefehl", p.launchCommand, { v -> state.update { it.copy(launchCommand = v) } }, Modifier.fillMaxWidth())
                IntField("Anzahl", p.launchCount, { v -> state.update { it.copy(launchCount = v) } }, min = 1, max = 16)
            }
            Button(onClick = {
                val err = state.platform.launchInstances(p.launchCommand, p.launchCount)
                state.toast = err ?: "${p.launchCount} Instanz(en) gestartet."
            }, enabled = p.launchCommand.isNotBlank()) { Text("Instanzen starten") }
        }
    }

    windows?.let { list -> WindowPicker(state, list) { windows = null } }
}

@Composable
private fun WindowPicker(state: AppState, list: List<WindowInfo>, onClose: () -> Unit) {
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var keepOffset by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Fenster als Instanzen übernehmen") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                LabeledCheck("Lage von Instanz 1 im Fenster beibehalten (z.B. nur das Spielfeld im Browser)", keepOffset) { keepOffset = it }
                list.forEachIndexed { i, w ->
                    LabeledCheck("${w.title.take(60)}  (${w.client.w}×${w.client.h})", i in selected) { on -> selected = if (on) selected + i else selected - i }
                }
                if (list.isEmpty()) Hint("Keine Fenster gefunden.")
            }
        },
        confirmButton = {
            Button(enabled = selected.isNotEmpty(), onClick = {
                val chosen = selected.sorted().map { list[it].client }
                state.update { pr ->
                    val first = pr.instances.firstOrNull()
                    val base = chosen.first()
                    val inside = first != null && first.x >= base.x && first.y >= base.y && first.right <= base.right && first.bottom <= base.bottom
                    val areas = if (keepOffset && inside && first != null) {
                        val fx = (first.x - base.x).toFloat() / base.w
                        val fy = (first.y - base.y).toFloat() / base.h
                        val fw = first.w.toFloat() / base.w
                        val fh = first.h.toFloat() / base.h
                        chosen.map { c -> ScreenRect(c.x + (fx * c.w).toInt(), c.y + (fy * c.h).toInt(), (fw * c.w).toInt(), (fh * c.h).toInt()) }
                    } else chosen
                    pr.copy(instances = areas)
                }
                state.toast = "${chosen.size} Instanz(en) übernommen."
                onClose()
            }) { Text("Übernehmen") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Abbrechen") } },
    )
}

// ======================= Belohnungen =======================

@Composable
fun RewardsTab(state: AppState) {
    val p = state.profile
    val clipboard = LocalClipboardManager.current
    var input by remember { mutableStateOf(PromptInput(game = p.name)) }
    var pasteOpen by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<RewardImport?>(null) }

    Section("Prompt-Assistent (Belohnungen von ChatGPT/Claude erstellen lassen)") {
        Hint("Beschreibe dein Spiel. Dann „Prompt kopieren“, in ChatGPT/Claude einfügen (am besten mit „Bereich-Screenshot“ aus dem Editor als Bild) und die Antwort hier einfügen.")
        Fields {
            TextField("Spiel", input.game, { input = input.copy(game = it) }, Modifier.fillMaxWidth())
            TextField("Spielziel", input.goal, { input = input.copy(goal = it) }, Modifier.fillMaxWidth())
            TextField("Belohnung wann/wo (z.B. ab welchem Punkt)", input.rewardWhen, { input = input.copy(rewardWhen = it) }, Modifier.fillMaxWidth())
            TextField("Schlecht / Game Over wenn", input.badWhen, { input = input.copy(badWhen = it) }, Modifier.fillMaxWidth())
            TextField("Neustart durch", input.restart, { input = input.copy(restart = it) }, Modifier.fillMaxWidth())
            TextField("Steuerung", input.controls, { input = input.copy(controls = it) }, Modifier.fillMaxWidth())
        }
        LabeledCheck("Ich hänge einen Screenshot des Spielbereichs an", input.withScreenshot) { input = input.copy(withScreenshot = it) }
        LabeledCheck("Aktuelle Regeln zum Verbessern mitschicken", input.includeCurrent) { input = input.copy(includeCurrent = it) }
        ButtonRow {
            Button(onClick = {
                clipboard.setText(AnnotatedString(RewardPrompt.build(input, p)))
                state.toast = "Prompt kopiert – jetzt in ChatGPT/Claude einfügen."
            }) { Text("Prompt kopieren") }
            FilledTonalButton(onClick = {
                val text = clipboard.getText()?.text.orEmpty()
                runImport(state, text) { pending = it }
            }) { Text("Antwort aus Zwischenablage einfügen") }
            OutlinedButton(onClick = { pasteOpen = true }) { Text("Antwort einfügen…") }
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(p.rewards.toPrettyJson()))
                state.toast = "Aktuelle Belohnungen als JSON kopiert."
            }) { Text("Als JSON kopieren") }
        }
    }

    Section("Runde (Episode)") {
        val ep = p.rewards.episode
        fun setEp(f: (com.customtrainableai.model.EpisodeConfig) -> com.customtrainableai.model.EpisodeConfig) =
            state.update { it.copy(rewards = it.rewards.copy(episode = f(it.rewards.episode))) }
        Fields {
            FloatField("Max. Sekunden pro Runde", ep.maxSeconds, { v -> setEp { it.copy(maxSeconds = v) } }, Modifier.width(200.dp), 1f, 36000f)
            FloatField("Startverzögerung (s)", ep.startDelaySec, { v -> setEp { it.copy(startDelaySec = v) } }, Modifier.width(180.dp), 0f, 60f)
        }
        LabeledSwitch("Neustart-Schritte auch nach Zeitlimit ausführen", ep.resetOnTimeLimit, { v -> setEp { it.copy(resetOnTimeLimit = v) } })
        Text("Neustart-Schritte (nach Game Over)", fontWeight = FontWeight.Medium)
        ep.resetSteps.forEachIndexed { i, st ->
            fun setStep(ns: com.customtrainableai.model.ResetStep) = setEp { e -> e.copy(resetSteps = e.resetSteps.toMutableList().also { it[i] = ns }) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${i + 1}.", Modifier.width(24.dp))
                Dropdown("Typ", com.customtrainableai.model.StepType.entries, st.type, { it.label }, { setStep(st.copy(type = it)) })
                when (st.type) {
                    com.customtrainableai.model.StepType.CLICK -> {
                        FloatField("x", st.point.x, { setStep(st.copy(point = st.point.copy(x = it).clamped())) }, Modifier.width(90.dp), 0f, 1f)
                        FloatField("y", st.point.y, { setStep(st.copy(point = st.point.copy(y = it).clamped())) }, Modifier.width(90.dp), 0f, 1f)
                    }
                    com.customtrainableai.model.StepType.KEY -> TextField("Taste", st.key, { setStep(st.copy(key = it.uppercase())) }, Modifier.width(130.dp))
                    com.customtrainableai.model.StepType.WAIT -> FloatField("Sekunden", st.seconds, { setStep(st.copy(seconds = it)) }, Modifier.width(110.dp), 0f, 120f)
                    com.customtrainableai.model.StepType.BACK -> {}
                }
                TextButton(onClick = { setEp { e -> e.copy(resetSteps = e.resetSteps.filterIndexed { k, _ -> k != i }) } }) { Text("✕") }
            }
        }
        ButtonRow {
            OutlinedButton(onClick = { setEp { it.copy(resetSteps = it.resetSteps + com.customtrainableai.model.ResetStep()) } }) { Text("+ Schritt") }
        }
        Hint("Klick-Positionen der Neustart-Schritte kannst du im Editor als weiße Punkte (N1, N2 …) verschieben.")
    }

    Section("Belohnungsregeln") {
        Hint("Fitness eines Agenten = Summe aller Belohnungen einer Runde. Positive Werte = gut, negative = Strafe. Positionen/Farben im Editor prüfen (Pipette).")
        p.rewards.rules.forEachIndexed { i, r -> RuleCard(state, i, r) }
        ButtonRow {
            Button(onClick = { state.update { it.copy(rewards = it.rewards.copy(rules = it.rewards.rules + RewardRule(RuleType.PIXEL_COLOR, "Neue Regel"))) } }) { Text("+ Regel") }
            OutlinedButton(onClick = { state.openEditor(3) }) { Text("Im Editor prüfen (Screenshot in 3 s)") }
        }
    }

    if (pasteOpen) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pasteOpen = false },
            title = { Text("Antwort der Sprach-KI einfügen") },
            text = {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 400.dp), label = { Text("JSON (auch mit Text drumherum)") })
            },
            confirmButton = { Button(onClick = { runImport(state, text) { pending = it }; pasteOpen = false }) { Text("Prüfen") } },
            dismissButton = { TextButton(onClick = { pasteOpen = false }) { Text("Abbrechen") } },
        )
    }
    pending?.let { imp ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Import: ${imp.summary}") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    imp.rules?.forEach { Text("• ${it.displayName} (${it.type.name}, ${fmt(it.reward, 2)})", fontSize = 13.sp) }
                    imp.episode?.let { Hint("Runde: max ${fmt(it.maxSeconds, 1)} s, ${it.resetSteps.size} Neustart-Schritt(e)") }
                    imp.actions?.let { Hint("Steuerung: ${it.mode.label}") }
                }
            },
            confirmButton = {
                Row {
                    if (imp.rules != null) TextButton(onClick = { applyImport(state, imp, append = true); pending = null }) { Text("Regeln anhängen") }
                    Button(onClick = { applyImport(state, imp, append = false); pending = null }) { Text("Übernehmen (ersetzen)") }
                }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Abbrechen") } },
        )
    }
}

private fun runImport(state: AppState, text: String, onOk: (RewardImport) -> Unit) {
    try {
        onOk(parseRewardImport(text, state.profile))
    } catch (e: Exception) {
        state.toast = "Konnte JSON nicht lesen: ${e.message?.take(160)}"
    }
}

private fun applyImport(state: AppState, imp: RewardImport, append: Boolean) {
    state.update { pr ->
        var r = pr.rewards
        imp.rules?.let { r = r.copy(rules = if (append) r.rules + it else it) }
        imp.episode?.let { r = r.copy(episode = it) }
        pr.copy(rewards = r, actions = imp.actions ?: pr.actions)
    }
    state.toast = "Import übernommen: ${imp.summary}. Positionen im Editor prüfen!"
}

@Composable
private fun RuleCard(state: AppState, i: Int, r: RewardRule) {
    fun set(nr: RewardRule) = state.update { pr -> pr.copy(rewards = pr.rewards.copy(rules = pr.rewards.rules.toMutableList().also { it[i] = nr })) }
    Section("R${i + 1}: ${r.displayName}") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dropdown("Typ", RuleType.entries, r.type, { it.label }, { set(r.copy(type = it)) })
            LabeledSwitch("aktiv", r.enabled, { set(r.copy(enabled = it)) })
        }
        Fields {
            TextField("Name", r.name, { set(r.copy(name = it)) }, Modifier.width(280.dp))
            FloatField(if (r.type == RuleType.SURVIVAL) "Belohnung pro s" else "Belohnung", r.reward, { set(r.copy(reward = it)) })
            if (r.type != RuleType.SURVIVAL && r.type != RuleType.ACTION_COST) FloatField("aktiv ab s", r.delaySec, { set(r.copy(delaySec = it)) }, min = 0f, max = 3600f)
        }
        when (r.type) {
            RuleType.SURVIVAL -> Hint("Gibt die Belohnung für jede Sekunde, die die Runde läuft.")
            RuleType.ACTION_COST -> Hint("Wird bei jedem Klick/Tastendruck verrechnet (negativ = Kosten).")
            RuleType.PIXEL_COLOR -> {
                PointRow("Punkt", r.point) { set(r.copy(point = it)) }
                ColorRow(r) { set(it) }
                Fields {
                    Dropdown("Bedingung", com.customtrainableai.model.Condition.entries, r.condition, { it.label }, { set(r.copy(condition = it)) })
                    Dropdown("Wann", com.customtrainableai.model.Timing.entries, r.timing, { it.label }, { set(r.copy(timing = it)) })
                }
                LabeledSwitch("Beendet die Runde (Game Over / Ziel)", r.endsEpisode, { set(r.copy(endsEpisode = it)) })
            }
            RuleType.REGION_CHANGE, RuleType.REGION_STILL -> {
                RectRow(r.region) { set(r.copy(region = it)) }
                Fields {
                    FloatField("Schwelle (Anteil)", r.threshold, { set(r.copy(threshold = it)) }, min = 0f, max = 1f)
                    if (r.type == RuleType.REGION_CHANGE) FloatField("Pause (s)", r.cooldownSec, { set(r.copy(cooldownSec = it)) }, min = 0f, max = 60f)
                    else FloatField("Sekunden still", r.seconds, { set(r.copy(seconds = it)) }, min = 0.1f, max = 600f)
                }
                LabeledSwitch("Beendet die Runde", r.endsEpisode, { set(r.copy(endsEpisode = it)) })
            }
            RuleType.COLOR_AMOUNT -> {
                RectRow(r.region) { set(r.copy(region = it)) }
                ColorRow(r) { set(it) }
                Fields {
                    Dropdown("Messung", com.customtrainableai.model.Measure.entries, r.measure, { it.label }, { set(r.copy(measure = it)) })
                    FloatField("Ende bei Anteil ≤", r.threshold, { set(r.copy(threshold = it)) }, min = 0f, max = 1f)
                }
                LabeledSwitch("Beendet die Runde, wenn Anteil ≤ Schwelle", r.endsEpisode, { set(r.copy(endsEpisode = it)) })
            }
            RuleType.OBJECT_TRACK -> {
                Text("Suchbereich", fontSize = 13.sp)
                RectRow(r.region) { set(r.copy(region = it)) }
                ColorRow(r) { set(it) }
                Fields {
                    Dropdown("Ziel", com.customtrainableai.model.TrackGoal.entries, r.goal, { it.label }, { set(r.copy(goal = it)) })
                    IntField("Min. Pixel", r.minPixels, { set(r.copy(minPixels = it)) }, min = 1, max = 10000)
                }
                when (r.goal) {
                    com.customtrainableai.model.TrackGoal.TARGET -> {
                        PointRow("Zielpunkt", r.target) { set(r.copy(target = it)) }
                        FloatField("Ende bei Abstand ≤", r.threshold, { set(r.copy(threshold = it)) }, min = 0f, max = 2f)
                        LabeledSwitch("Ziel erreicht beendet die Runde", r.endsEpisode, { set(r.copy(endsEpisode = it)) })
                    }
                    com.customtrainableai.model.TrackGoal.ZONE -> {
                        Text("Zone", fontSize = 13.sp)
                        RectRow(r.zone) { set(r.copy(zone = it)) }
                        Dropdown("Wann", com.customtrainableai.model.Timing.entries, r.timing, { it.label }, { set(r.copy(timing = it)) })
                        LabeledSwitch("In der Zone beendet die Runde", r.endsEpisode, { set(r.copy(endsEpisode = it)) })
                    }
                    else -> {}
                }
                if (r.goal != com.customtrainableai.model.TrackGoal.ZONE) {
                    LabeledSwitch("Nur neue Rekorde belohnen (kein Hin-und-Her-Farmen)", r.onlyNewBest, { set(r.copy(onlyNewBest = it)) })
                }
            }
        }
        ButtonRow {
            TextButton(onClick = {
                if (i > 0) state.update { pr -> pr.copy(rewards = pr.rewards.copy(rules = pr.rewards.rules.toMutableList().also { val t = it[i]; it[i] = it[i - 1]; it[i - 1] = t })) }
            }, enabled = i > 0) { Text("▲ nach oben") }
            TextButton(onClick = { state.update { pr -> pr.copy(rewards = pr.rewards.copy(rules = pr.rewards.rules.toMutableList().also { it.add(i + 1, r.copy(name = r.displayName + " (Kopie)")) })) } }) { Text("Duplizieren") }
            TextButton(onClick = { state.update { pr -> pr.copy(rewards = pr.rewards.copy(rules = pr.rewards.rules.filterIndexed { k, _ -> k != i })) } }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ColorRow(r: RewardRule, onChange: (RewardRule) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ColorSwatch(r.color)
        TextField("Farbe #RRGGBB", r.color, { onChange(r.copy(color = it.trim())) }, Modifier.width(160.dp))
        IntField("Toleranz", r.tolerance, { onChange(r.copy(tolerance = it)) }, Modifier.width(110.dp), 0, 255)
    }
}

@Composable
private fun RectRow(rr: com.customtrainableai.model.RelRect, onChange: (com.customtrainableai.model.RelRect) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FloatField("x", rr.x, { onChange(rr.copy(x = it).normalized()) }, Modifier.width(85.dp), 0f, 1f)
        FloatField("y", rr.y, { onChange(rr.copy(y = it).normalized()) }, Modifier.width(85.dp), 0f, 1f)
        FloatField("Breite", rr.w, { onChange(rr.copy(w = it).normalized()) }, Modifier.width(85.dp), 0f, 1f)
        FloatField("Höhe", rr.h, { onChange(rr.copy(h = it).normalized()) }, Modifier.width(85.dp), 0f, 1f)
    }
}

// ======================= KI-Einstellungen =======================

@Composable
fun AiTab(state: AppState) {
    val p = state.profile
    val n = p.network
    val e = p.evolution
    var confirmReset by remember { mutableStateOf(false) }
    val layout = remember(p.actions, n) { Engine.layoutFor(p, state.platform.touchOnly) }
    val params = remember(layout) { NeuralNet.paramCount(layout) }

    Section("Komplexität des Netzes") {
        var hiddenText by remember(n.hidden) { mutableStateOf(n.hidden.joinToString(", ")) }
        Fields {
            OutlinedTextField(
                value = hiddenText,
                onValueChange = { t ->
                    hiddenText = t
                    val list = t.split(',', ' ', ';').mapNotNull { it.trim().toIntOrNull() }.filter { it in 0..1024 }
                    if (list.isNotEmpty() || t.isBlank()) state.update { it.copy(network = it.network.copy(hidden = list)) }
                },
                label = { Text("Neuronen pro versteckter Schicht (z.B. 16 oder 32, 16)") },
                singleLine = true,
                modifier = Modifier.width(380.dp),
            )
        }
        ButtonRow {
            listOf("Linear" to emptyList(), "Klein 8" to listOf(8), "Mittel 16" to listOf(16), "Groß 32" to listOf(32), "Sehr groß 64, 32" to listOf(64, 32)).forEach { (label, h) ->
                OutlinedButton(onClick = { state.update { it.copy(network = it.network.copy(hidden = h)) } }) { Text(label) }
            }
        }
        Fields {
            IntField("Raster Breite", n.gridW, { v -> state.update { it.copy(network = it.network.copy(gridW = v)) } }, min = 2, max = 64)
            IntField("Raster Höhe", n.gridH, { v -> state.update { it.copy(network = it.network.copy(gridH = v)) } }, min = 2, max = 64)
        }
        LabeledSwitch("Farbe sehen (RGB statt Graustufen)", n.color, { v -> state.update { it.copy(network = it.network.copy(color = v)) } })
        LabeledSwitch("Bewegung sehen (Unterschied zum letzten Bild)", n.motion, { v -> state.update { it.copy(network = it.network.copy(motion = v)) } })
        Hint("Aufbau: ${layout.joinToString(" → ")} Neuronen · ${params} Gewichte. Die KI sieht den Bereich als ${n.gridW}×${n.gridH}-Raster. Größere Netze/Raster können mehr, lernen aber langsamer. Für einfache Spiele reichen 7×11 bis 12×12 und 8–16 Neuronen.")
        if (p.brain != null && p.brain.layout != layout.toList()) Hint("⚠️ Passt nicht zum trainierten Netz – nächstes Training beginnt neu.", MaterialTheme.colorScheme.error)
    }

    Section("Evolution") {
        Fields {
            IntField("Agenten pro Generation", e.population, { v -> state.update { it.copy(evolution = it.evolution.copy(population = v)) } }, Modifier.width(200.dp), 2, 1000)
            IntField("Generationen pro Training (0 = endlos)", e.generationsPerRun, { v -> state.update { it.copy(evolution = it.evolution.copy(generationsPerRun = v)) } }, Modifier.width(280.dp), 0, 100000)
            IntField("Eliten (unverändert behalten)", e.elites, { v -> state.update { it.copy(evolution = it.evolution.copy(elites = v)) } }, Modifier.width(220.dp), 0, 100)
            IntField("Runden pro Agent", e.episodesPerAgent, { v -> state.update { it.copy(evolution = it.evolution.copy(episodesPerAgent = v)) } }, Modifier.width(160.dp), 1, 20)
        }
        Text("Experimentierfreude: ${(e.experiment * 100).toInt()} %", fontWeight = FontWeight.Medium)
        Slider(value = e.experiment, onValueChange = { v -> state.update { it.copy(evolution = it.evolution.copy(experiment = v)) } }, valueRange = 0f..1f)
        Hint("Niedrig = verfeinert vorsichtig, was schon klappt. Hoch = probiert mutiger Neues aus (gut am Anfang oder wenn es feststeckt). Aktuell: ${fmt(e.mutationRate * 100, 1)} % der Gewichte pro Kind, Stärke ${fmt(e.mutationStrength, 2)}, Zufallsaktionen ${fmt(e.randomActionChance * 100, 1)} %.")
        LabeledSwitch("Zufallsaktionen beim Training (Erkunden)", e.randomActions, { v -> state.update { it.copy(evolution = it.evolution.copy(randomActions = v)) } })
        LabeledSwitch("Nach jeder Generation automatisch speichern", e.autosave, { v -> state.update { it.copy(evolution = it.evolution.copy(autosave = v)) } })
        LabeledSwitch("Mutation von Hand festlegen", e.customRate != null, { on ->
            state.update { it.copy(evolution = if (on) it.evolution.copy(customRate = it.evolution.mutationRate, customStrength = it.evolution.mutationStrength) else it.evolution.copy(customRate = null, customStrength = null)) }
        })
        if (e.customRate != null) {
            Fields {
                FloatField("Anteil Gewichte (0…1)", e.customRate, { v -> state.update { it.copy(evolution = it.evolution.copy(customRate = v)) } }, Modifier.width(180.dp), 0.0001f, 1f)
                FloatField("Stärke", e.customStrength ?: e.mutationStrength, { v -> state.update { it.copy(evolution = it.evolution.copy(customStrength = v)) } }, Modifier.width(120.dp), 0.0001f, 10f)
            }
        }
        val inst = p.instances.size.coerceAtLeast(1)
        Hint("Pro Generation werden ${e.population * e.episodesPerAgent} Runden gespielt, verteilt auf $inst Instanz(en) ≈ ${(e.population * e.episodesPerAgent + inst - 1) / inst} Runden nacheinander.")
    }

    Section("Trainingsstand") {
        val b = p.brain
        if (b == null) Hint("Noch nicht trainiert.") else {
            StatLine("Generation", "${b.generation}")
            StatLine("Runden gesamt", "${b.totalEpisodes}")
            StatLine("Bestes Netz – Fitness", fmt(b.bestFitness, 2))
            StatLine("Netz-Aufbau", b.layout.joinToString(" → "))
        }
        OutlinedButton(onClick = { confirmReset = true }, enabled = b != null && !state.isRunning) { Text("Training zurücksetzen…") }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Training zurücksetzen?") },
            text = { Text("Alle trainierten Gewichte und der Verlauf dieses Profils werden verworfen (erst beim Speichern endgültig).") },
            confirmButton = { Button(onClick = { state.resetTraining(); confirmReset = false }) { Text("Zurücksetzen") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Abbrechen") } },
        )
    }
}
