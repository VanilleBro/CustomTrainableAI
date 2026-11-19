package com.customtrainableai.ai

import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Einfaches vollvernetztes Netz (MLP). Versteckte Schichten nutzen tanh, die Ausgabe ist linear.
 * Gewichte liegen neuronenweise hintereinander: für jedes Neuron erst alle Eingangsgewichte, dann der Bias.
 */
class NeuralNet(val layout: IntArray, val weights: FloatArray) {
    private val buffers = Array(layout.size) { FloatArray(layout[it]) }

    init {
        require(weights.size == paramCount(layout)) { "Gewichtsanzahl ${weights.size} passt nicht zu ${layout.toList()}" }
    }

    fun forward(input: FloatArray): FloatArray {
        val first = buffers[0]
        val n = minOf(first.size, input.size)
        input.copyInto(first, 0, 0, n)
        var off = 0
        for (l in 0 until layout.size - 1) {
            val inN = layout[l]
            val outN = layout[l + 1]
            val src = buffers[l]
            val dst = buffers[l + 1]
            val last = l == layout.size - 2
            for (o in 0 until outN) {
                var s = weights[off + inN]
                for (i in 0 until inN) s += weights[off + i] * src[i]
                off += inN + 1
                dst[o] = if (last) s else tanh(s)
            }
        }
        return buffers[layout.size - 1]
    }

    companion object {
        /** Anteil der Eingangsgewichte, die am Anfang nicht 0 sind (spärlicher Start wie bei NEAT). */
        var initDensity = 0.05f

        fun paramCount(layout: IntArray): Int {
            var c = 0
            for (l in 0 until layout.size - 1) c += (layout[l] + 1) * layout[l + 1]
            return c
        }

        fun randomWeights(layout: IntArray, rnd: Random): FloatArray {
            val w = FloatArray(paramCount(layout))
            var off = 0
            for (l in 0 until layout.size - 1) {
                val inN = layout[l]
                val density = if (l == 0) initDensity.coerceIn(0.001f, 1f) else 1f
                val scale = 1f / sqrt((inN * density).coerceAtLeast(1f))
                for (o in 0 until layout[l + 1]) {
                    for (i in 0 until inN) w[off + i] = if (density >= 1f || rnd.nextFloat() < density) gaussian(rnd) * scale else 0f
                    w[off + inN] = 0f
                    off += inN + 1
                }
            }
            return w
        }
    }
}

fun gaussian(rnd: Random): Float {
    var u = rnd.nextDouble()
    if (u < 1e-12) u = 1e-12
    val v = rnd.nextDouble()
    return (sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2.0 * kotlin.math.PI * v)).toFloat()
}
