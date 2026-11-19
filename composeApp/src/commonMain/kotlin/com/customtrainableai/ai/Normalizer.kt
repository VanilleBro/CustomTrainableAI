package com.customtrainableai.ai

import com.customtrainableai.model.NormStats
import com.customtrainableai.model.decodeWeights
import com.customtrainableai.model.encodeWeights
import kotlin.math.sqrt

/**
 * Laufende Normalisierung der Bild-Eingaben: (x - Mittelwert) / Streuung.
 * Statischer Hintergrund wird so zu ~0, bewegte Objekte (Ball, Figur, Gegner) werden deutlich sichtbar.
 * Die Werte werden mit dem Netz gespeichert, damit das Abspielen genauso "sieht" wie im Training.
 */
class Normalizer(val size: Int) {
    val mean = FloatArray(size)
    val variance = FloatArray(size) { 1f }
    var count = 0L
        private set

    fun update(x: FloatArray) {
        count++
        val a = (1.0 / count).coerceAtLeast(1.0 / 20000).toFloat()
        for (i in 0 until size) {
            val d = x[i] - mean[i]
            mean[i] += d * a
            val target = if (count == 1L) 0f else d * d
            variance[i] += (target - variance[i]) * a
        }
    }

    fun normalize(x: FloatArray) {
        if (count == 0L || !enabled) return
        for (i in 0 until size) {
            val v = (x[i] - mean[i]) / (sqrt(variance[i]) + EPS)
            x[i] = v.coerceIn(-5f, 5f)
        }
    }

    fun toStats() = NormStats(encodeWeights(mean), encodeWeights(variance), count)

    companion object {
        const val EPS = 0.05f
        var enabled = true

        fun from(stats: NormStats?, size: Int): Normalizer {
            val n = Normalizer(size)
            if (stats == null) return n
            val m = decodeWeights(stats.mean)
            val v = decodeWeights(stats.variance)
            if (m != null && v != null && m.size == size && v.size == size) {
                m.copyInto(n.mean)
                v.copyInto(n.variance)
                n.count = stats.count
            }
            return n
        }
    }
}
