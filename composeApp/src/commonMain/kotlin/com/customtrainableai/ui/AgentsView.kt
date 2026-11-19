package com.customtrainableai.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.customtrainableai.engine.AgentStatus
import com.customtrainableai.engine.EngineStatus
import com.customtrainableai.engine.Frame
import com.customtrainableai.model.fmt
import kotlin.math.abs

private val Good = Color(0xFF22C55E)
private val Bad = Color(0xFFEF4444)
private val Neutral = Color(0xFF475569)
private val Playing = Color(0xFF3B82F6)

/** Farbe für ein Ergebnis: grün = gut (Belohnung > 0), rot = schlecht; kräftiger, je weiter vom Nullpunkt. */
private fun scoreColor(score: Float, scale: Float): Color {
    if (score.isNaN()) return Neutral
    val k = (abs(score) / scale.coerceAtLeast(0.001f)).coerceIn(0.25f, 1f)
    return lerp(Neutral, if (score > 0f) Good else Bad, k)
}

private fun countLine(scores: List<Float>): String {
    val valid = scores.filter { !it.isNaN() }
    if (valid.isEmpty()) return "–"
    val good = valid.count { it > 0f }
    return "$good gut · ${valid.size - good} schlecht · Ø ${fmt(valid.average(), 1)} · bester ${fmt(valid.max(), 1)}"
}

/**
 * Alle Agenten der aktuellen Generation als Kacheln: grau = wartet, blau = spielt gerade,
 * grün/rot = fertig mit guter/schlechter Belohnung.
 */
@Composable
fun AgentOverview(status: EngineStatus) {
    val agents = status.agents
    if (agents.isEmpty()) return
    val done = agents.filter { it.state == 2 }
    val playing = agents.count { it.state == 1 }
    val waiting = agents.count { it.state == 0 }
    val good = done.count { it.score > 0f }
    Text(
        "Diese Generation: $good gut · ${done.size - good} schlecht · $playing spielen gerade · $waiting warten",
        fontWeight = FontWeight.Medium,
    )
    if (status.lastGenScores.isNotEmpty()) Hint("Letzte fertige Generation: ${countLine(status.lastGenScores)}")
    val scale = (agents.filter { it.state != 0 }.maxOfOrNull { abs(it.score) } ?: 1f).coerceAtLeast(1f)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        agents.forEach { a -> AgentTile(a, scale) }
    }
    Hint("Grün = Belohnung über 0 (gut), Rot = unter 0 (schlecht), Blau = spielt gerade, Grau = wartet. Die Zahl ist die Belohnung der Runde.")
}

@Composable
private fun AgentTile(a: AgentStatus, scale: Float) {
    val bg = when (a.state) {
        0 -> MaterialTheme.colorScheme.surfaceVariant
        1 -> Playing.copy(alpha = 0.35f)
        else -> scoreColor(a.score, scale)
    }
    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)).background(bg)
            .then(if (a.state == 1) Modifier.border(2.dp, Playing, RoundedCornerShape(6.dp)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("#${a.index + 1}", fontSize = 10.sp, color = Color.White.copy(alpha = 0.8f))
            Text(
                if (a.state == 0) "…" else fmt(a.score, 1),
                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center,
            )
        }
    }
}

/** Simulator: alle gleichzeitig spielenden Agenten mit Live-Bild. */
@Composable
fun SimulatorGrid(state: AppState, frames: List<Frame?>, status: EngineStatus) {
    if (frames.isEmpty()) return
    val scale = (status.instances.maxOfOrNull { abs(it.reward) } ?: 1f).coerceAtLeast(1f)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        frames.forEachIndexed { i, f ->
            val inst = status.instances.getOrNull(i)
            val reward = inst?.reward ?: Float.NaN
            val col = if (inst == null || inst.phase != "spielt") Neutral else scoreColor(reward, scale)
            Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.border(3.dp, col, RoundedCornerShape(4.dp)).padding(3.dp)) {
                    if (f != null) {
                        val img = remember(f) { state.platform.frameToImage(f) }
                        Image(img, "Agent", Modifier.size(70.dp, 112.dp), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None)
                    } else {
                        Box(Modifier.size(70.dp, 112.dp).background(MaterialTheme.colorScheme.surfaceVariant))
                    }
                }
                Row {
                    Text(
                        (inst?.agent?.takeIf { it >= 0 }?.let { "A${it + 1} " } ?: "") + (if (reward.isNaN()) "" else fmt(reward, 1)),
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}
