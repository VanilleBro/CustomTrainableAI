package com.customtrainableai.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.customtrainableai.engine.Engine
import com.customtrainableai.engine.RunMode
import com.customtrainableai.model.fmt

val TABS = listOf("Training", "Aktionen", "Bereiche", "Belohnungen", "KI-Einstellungen")

@Composable
fun ProfileScreen(state: AppState) {
    val p = state.profile
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { state.requestClose() }) { Text("← Profile") }
            OutlinedTextField(
                value = p.name,
                onValueChange = { n -> state.update { it.copy(name = n) } },
                label = { Text("Profilname") },
                singleLine = true,
                modifier = Modifier.weight(1f).widthIn(max = 420.dp),
            )
        }
        ButtonRow {
            Button(onClick = { state.save() }) { Text(if (state.dirty) "● Speichern" else "Speichern") }
            OutlinedButton(onClick = { state.exportProfile() }) { Text("Exportieren…") }
            Column {
                Hint(if (state.fileName.isBlank()) "Noch nicht gespeichert" else "Datei: ${state.fileName}.json")
                state.lastBackup?.let { Hint("Letztes Backup: $it") }
            }
        }
        VSpace(6)
        TabBar(TABS, state.tab) { state.tab = it }
        VSpace(6)
        if (state.isRunning && state.tab != 0) {
            Hint("Training läuft – Änderungen gelten ab dem nächsten Start.", MaterialTheme.colorScheme.tertiary)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            when (state.tab) {
                0 -> TrainingTab(state)
                1 -> ActionsTab(state)
                2 -> AreasTab(state)
                3 -> RewardsTab(state)
                else -> AiTab(state)
            }
            VSpace(40)
        }
    }
}

@Composable
fun TrainingTab(state: AppState) {
    val p = state.profile
    val status by state.activeStatus.collectAsState()
    val running = state.isRunning

    Section("Steuerung") {
        if (running) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { state.stop() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("■ Stopp") }
                Text(AppState.statusLine(status), fontWeight = FontWeight.Medium)
            }
            if (!state.simulate || p.practiceKind == null) Hint("Stoppen auch mit: ${state.platform.stopHint}")
        } else {
            Fields {
                IntField("Generationen (0 = endlos)", p.evolution.generationsPerRun, { v -> state.update { it.copy(evolution = it.evolution.copy(generationsPerRun = v)) } }, Modifier.width(210.dp), 0, 100000)
            }
            ButtonRow {
                Button(onClick = { state.start(RunMode.TRAINING) }) { Text("▶ Training starten") }
                OutlinedButton(onClick = { state.start(RunMode.PLAYBACK) }, enabled = p.brain?.best != null) { Text("▶ Abspielen (ohne Lernen)") }
            }
            if (p.practiceKind != null) {
                LabeledSwitch("Im Simulator trainieren (ohne Bildschirm, zum Testen)", state.simulate, { state.simulate = it })
                if (state.simulate) {
                    LabeledSwitch("Echtzeit (zum Zuschauen) – aus = so schnell wie möglich", state.simRealtime, { state.simRealtime = it })
                    Fields {
                        IntField("Agenten gleichzeitig (0 = alle)", p.simParallel, { v -> state.update { it.copy(simParallel = v) } }, Modifier.width(230.dp), 0, 64)
                        IntField("Agenten pro Generation", p.evolution.population, { v -> state.update { it.copy(evolution = it.evolution.copy(population = v)) } }, Modifier.width(200.dp), 2, 1000)
                    }
                    Hint("Im Simulator spielen ${state.simParallelCount(p)} Agenten gleichzeitig – alle sind unten live zu sehen.")
                }
            }
            if (!state.simulate || p.practiceKind == null) {
                Fields {
                    IntField("Agenten pro Generation", p.evolution.population, { v -> state.update { it.copy(evolution = it.evolution.copy(population = v)) } }, Modifier.width(200.dp), 2, 1000)
                }
                Hint(
                    "Gleichzeitig spielen ${p.instances.size} Agent(en) – einer pro Spielbereich" +
                        (if (p.browser != null) " bzw. Chrome-Fenster (Anzahl im Tab „Bereiche“)." else " (mehr Bereiche im Tab „Bereiche“).") +
                        " ${p.tickRate} Entscheidungen/s. Nach dem Start bleiben ${fmt(state.platform.startDelaySec, 0)} s. Stoppen: ${state.platform.stopHint}",
                )
            }
        }
        if (status.message.isNotBlank()) Hint(status.message, MaterialTheme.colorScheme.tertiary)
    }

    if (running) {
        Section("Live") {
            if (status.mode == RunMode.TRAINING && status.total > 0) {
                Text("Generation ${status.generation + 1} – Agent ${status.evaluated} von ${status.total} bewertet")
                LinearProgressIndicator(progress = { status.evaluated.toFloat() / status.total }, modifier = Modifier.fillMaxWidth())
            }
            StatLine("Takte pro Sekunde", fmt(status.ticksPerSecond, 1))
            if (kotlin.math.abs(status.timeFactor - 1f) > 0.05f && status.instances.isNotEmpty()) {
                StatLine("Spieltempo je Instanz (× Echtzeit)", fmt(status.timeFactor, 2) + "×  ·  gesamt " + fmt(status.timeFactor * status.instances.size, 1) + "×")
            }
            StatLine("Beste Fitness (Durchschnitt des Champions)", fmt(status.bestFitness, 2))
            status.instances.forEach { s ->
                StatLine(
                    "Instanz ${s.index + 1}" + (if (s.agent >= 0) " · Agent ${s.agent + 1}" else ""),
                    "${s.phase} · ${fmt(s.seconds, 1)} s · Belohnung ${fmt(s.reward, 2)}",
                )
            }
        }
        if (status.mode == RunMode.TRAINING && status.agents.isNotEmpty()) {
            Section("Agenten dieser Generation") { AgentOverview(status) }
        }
        if (state.simulate && p.practiceKind != null && state.simFrames.isNotEmpty()) {
            Section("Simulator – alle gleichzeitig spielenden Agenten") { SimulatorGrid(state, state.simFrames, status) }
        }
        if (state.browserLive && state.isRunning && state.simFrames.isNotEmpty()) {
            Section("Live – alle Chrome-Tabs") { SimulatorGrid(state, state.simFrames, status) }
        }
    }

    Section("Lernfortschritt") {
        val history = if (running && status.mode == RunMode.TRAINING) status.history else p.brain?.history ?: emptyList()
        ProgressChart(history)
        val b = p.brain
        if (b != null) {
            StatLine("Trainierte Generationen", "${b.generation}")
            StatLine("Gespielte Runden insgesamt", "${b.totalEpisodes}")
            StatLine("Fitness des besten Netzes", fmt(b.bestFitness, 2))
            history.lastOrNull()?.let { StatLine("Letzte Generation: bester / Ø", "${fmt(it.best, 2)} / ${fmt(it.avg, 2)}") }
        } else {
            Hint("Noch nicht trainiert.")
        }
    }

    if (status.playback.isNotEmpty() || status.mode == RunMode.PLAYBACK) {
        Section("Abspielen – Belohnung pro Runde") {
            PlaybackChart(status.playback)
            if (status.playback.isNotEmpty()) StatLine("Runden / Ø Belohnung", "${status.playback.size} / ${fmt(status.playback.average(), 2)}")
        }
    }

    val layout = remember(p.actions, p.network) { Engine.layoutFor(p, state.platform.touchOnly) }
    if (p.brain != null && p.brain.layout != layout.toList()) {
        Section("Achtung") {
            Hint("Steuerung oder Netz-Einstellungen wurden geändert. Das gespeicherte Netz passt nicht mehr – beim nächsten Training beginnt es von vorne.", MaterialTheme.colorScheme.error)
        }
    }
}
