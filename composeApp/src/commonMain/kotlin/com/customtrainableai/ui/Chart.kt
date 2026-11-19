package com.customtrainableai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.customtrainableai.model.GenStats
import com.customtrainableai.model.fmt
import kotlin.math.max
import kotlin.math.min

data class Series(val name: String, val color: Color, val values: List<Float>, val dashed: Boolean = false)

/** Linien-Diagramm (Lernfortschritt pro Generation). */
@Composable
fun LineChart(series: List<Series>, xStart: Int, modifier: Modifier = Modifier, xLabel: String = "Generation") {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outline
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            series.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 14.dp, height = 3.dp).background(s.color))
                    Spacer(Modifier.width(5.dp))
                    Text(s.name, fontSize = 12.sp, color = labelColor)
                }
            }
        }
        Canvas(Modifier.fillMaxWidth().height(220.dp)) {
            val n = series.maxOfOrNull { it.values.size } ?: 0
            val left = 52f
            val bottom = size.height - 22f
            val top = 8f
            val right = size.width - 8f
            val style = TextStyle(color = labelColor, fontSize = 11.sp)
            if (n == 0) {
                drawText(measurer, "Noch keine Daten – starte das Training.", Offset(left, size.height / 2 - 8), style)
                return@Canvas
            }
            var lo = series.flatMap { it.values }.minOrNull() ?: 0f
            var hi = series.flatMap { it.values }.maxOrNull() ?: 1f
            if (hi - lo < 1e-3f) { hi += 1f; lo -= 1f }
            val pad = (hi - lo) * 0.08f
            lo -= pad; hi += pad
            fun x(i: Int) = left + (right - left) * (if (n <= 1) 0.5f else i.toFloat() / (n - 1))
            fun y(v: Float) = bottom - (bottom - top) * ((v - lo) / (hi - lo))

            // Gitter + Achsen
            for (k in 0..4) {
                val v = lo + (hi - lo) * k / 4
                val yy = y(v)
                drawLine(gridColor, Offset(left, yy), Offset(right, yy), 1f)
                drawText(measurer, fmt(v.toDouble(), 1), Offset(2f, yy - 8), style)
            }
            if (lo < 0 && hi > 0) drawLine(labelColor.copy(alpha = 0.6f), Offset(left, y(0f)), Offset(right, y(0f)), 1.5f)
            val ticks = min(n, 6)
            for (k in 0 until ticks) {
                val i = if (ticks == 1) 0 else (n - 1) * k / (ticks - 1)
                drawText(measurer, "${xStart + i}", Offset(x(i) - 6, bottom + 4), style)
            }
            for (s in series) drawSeries(s, n, ::x, ::y)
        }
        Text(xLabel, fontSize = 11.sp, color = labelColor, modifier = Modifier.fillMaxWidth())
    }
}

private fun DrawScope.drawSeries(s: Series, n: Int, x: (Int) -> Float, y: (Float) -> Float) {
    if (s.values.isEmpty()) return
    if (s.values.size == 1) {
        drawCircle(s.color, 4f, Offset(x(0), y(s.values[0])))
        return
    }
    val path = Path()
    // Bei sehr vielen Punkten ausdünnen
    val step = max(1, s.values.size / 600)
    var first = true
    var i = 0
    while (i < s.values.size) {
        val px = x(i)
        val py = y(s.values[i])
        if (first) path.moveTo(px, py) else path.lineTo(px, py)
        first = false
        i += step
    }
    path.lineTo(x(s.values.lastIndex), y(s.values.last()))
    drawPath(path, s.color, style = Stroke(width = 2.5f, pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) else null))
}

@Composable
fun ProgressChart(history: List<GenStats>, modifier: Modifier = Modifier) {
    val shown = history.takeLast(1000)
    val start = shown.firstOrNull()?.gen ?: 1
    LineChart(
        listOf(
            Series("Bester", ColorBest, shown.map { it.best }),
            Series("Durchschnitt", ColorAvg, shown.map { it.avg }),
            Series("Schlechtester", ColorMin, shown.map { it.min }, dashed = true),
        ),
        start,
        modifier,
    )
}

@Composable
fun PlaybackChart(scores: List<Float>, modifier: Modifier = Modifier) {
    val avg = scores.runningFold(0f to 0) { (sum, c), v -> (sum + v) to (c + 1) }.drop(1).map { (s, c) -> s / c }
    LineChart(
        listOf(Series("Belohnung pro Runde", ColorAvg, scores), Series("Schnitt", ColorBest, avg)),
        1,
        modifier,
        xLabel = "Runde",
    )
}
