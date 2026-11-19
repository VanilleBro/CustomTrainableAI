package com.customtrainableai.ai

import com.customtrainableai.engine.Frame
import com.customtrainableai.model.NetworkConfig

/**
 * Macht aus einem Bildausschnitt den Eingabevektor des Netzes:
 * verkleinertes Raster (Graustufen oder RGB, -1..1) + optional Bewegung (Differenz zum letzten Bild)
 * + die zuletzt gewählte Aktion (one-hot pro Kopf).
 */
class Observer(cfg: NetworkConfig, private val heads: IntArray) {
    val gw = cfg.gridW.coerceIn(2, 64)
    val gh = cfg.gridH.coerceIn(2, 64)
    val color = cfg.color
    val motion = cfg.motion
    private val channels = if (color) 3 else 1
    val gridSize = gw * gh * channels
    /** Anzahl Bild-Eingaben (werden normalisiert), danach folgen die Aktions-Eingaben. */
    val normSize = gridSize * (if (motion) 2 else 1)
    val inputSize = normSize + heads.sum()

    fun grid(frame: Frame, out: FloatArray) {
        val w = frame.width
        val h = frame.height
        val px = frame.argb
        for (gy in 0 until gh) {
            val y0 = gy * h / gh
            val y1 = ((gy + 1) * h / gh).coerceAtLeast(y0 + 1).coerceAtMost(h)
            for (gx in 0 until gw) {
                val x0 = gx * w / gw
                val x1 = ((gx + 1) * w / gw).coerceAtLeast(x0 + 1).coerceAtMost(w)
                val cw = x1 - x0
                val ch = y1 - y0
                // Höchstens ~20x20 Stichproben pro Zelle
                val sx = (cw / 20).coerceAtLeast(1)
                val sy = (ch / 20).coerceAtLeast(1)
                var r = 0L
                var g = 0L
                var b = 0L
                var n = 0
                var y = y0
                while (y < y1) {
                    var x = x0
                    val row = y * w
                    while (x < x1) {
                        val c = px[row + x]
                        r += (c shr 16) and 0xFF
                        g += (c shr 8) and 0xFF
                        b += c and 0xFF
                        n++
                        x += sx
                    }
                    y += sy
                }
                if (n == 0) n = 1
                val idx = (gy * gw + gx) * channels
                if (color) {
                    out[idx] = r.toFloat() / n / 127.5f - 1f
                    out[idx + 1] = g.toFloat() / n / 127.5f - 1f
                    out[idx + 2] = b.toFloat() / n / 127.5f - 1f
                } else {
                    val gray = (0.299f * r + 0.587f * g + 0.114f * b) / n
                    out[idx] = gray / 127.5f - 1f
                }
            }
        }
    }

    fun buildInput(grid: FloatArray, prev: FloatArray?, lastDecision: IntArray, out: FloatArray) {
        grid.copyInto(out, 0, 0, gridSize)
        var off = gridSize
        if (motion) {
            for (i in 0 until gridSize) {
                out[off + i] = if (prev == null) 0f else ((grid[i] - prev[i]) * 3f).coerceIn(-1f, 1f)
            }
            off += gridSize
        }
        for (hIdx in heads.indices) {
            for (o in 0 until heads[hIdx]) out[off + o] = if (lastDecision[hIdx] == o) 1f else 0f
            off += heads[hIdx]
        }
    }
}
