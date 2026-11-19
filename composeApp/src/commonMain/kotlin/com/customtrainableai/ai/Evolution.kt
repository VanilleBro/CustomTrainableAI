package com.customtrainableai.ai

import kotlin.random.Random

/** Ein Agent der Population: Gewichte + bisher gemessene Belohnungen. */
class Individual(val weights: FloatArray, var fitnessSum: Double = 0.0, var evals: Int = 0) {
    val fitness: Double get() = if (evals == 0) Double.NEGATIVE_INFINITY else fitnessSum / evals

    fun addResult(score: Double) {
        fitnessSum += score
        evals++
        // Ältere Ergebnisse langsam vergessen, damit Glückstreffer nicht ewig oben bleiben.
        if (evals > MAX_MEMORY) {
            fitnessSum = fitnessSum * MAX_MEMORY / evals
            evals = MAX_MEMORY
        }
    }

    companion object { const val MAX_MEMORY = 4 }
}

/**
 * Neuroevolution: Elitismus + Turnierauswahl + Kreuzung (pro Neuron) + Gauß-Mutation mit gemischter Stärke.
 * Die Netze starten spärlich (siehe NeuralNet.initDensity), damit zuerst einfache Regeln gefunden werden.
 */
object Evolution {
    /** >0: Eltern gleichverteilt aus den besten X % (Abschneide-Auswahl), sonst Turnier. */
    var parentFraction = 0f
    /** Jedes Kind bekommt zufällig 1/4, 1x oder 2x Mutationsstärke (mischt Feintuning und Ausprobieren). */
    var mixedStrength = true

    fun nextGeneration(
        population: List<Individual>,
        layout: IntArray,
        size: Int,
        elites: Int,
        mutationRate: Float,
        mutationStrength: Float,
        rnd: Random,
        crossoverRate: Float = 0.5f,
    ): List<Individual> {
        val ranked = population.sortedByDescending { it.fitness }
        val next = ArrayList<Individual>(size)
        val eliteCount = elites.coerceIn(0, minOf(size - 1, ranked.size))
        for (i in 0 until eliteCount) {
            val e = ranked[i]
            next += Individual(e.weights.copyOf(), e.fitnessSum, e.evals)
        }
        val parents = if (parentFraction > 0f) ranked.take((ranked.size * parentFraction).toInt().coerceAtLeast(2)) else null
        fun pick() = parents?.let { it[rnd.nextInt(it.size)] } ?: tournament(ranked, rnd)
        while (next.size < size) {
            val a = pick()
            val child = if (rnd.nextFloat() < crossoverRate && ranked.size > 1) {
                crossover(a.weights, pick().weights, layout, rnd)
            } else {
                a.weights.copyOf()
            }
            val st = if (mixedStrength) mutationStrength * STRENGTH_MIX[rnd.nextInt(STRENGTH_MIX.size)] else mutationStrength
            mutate(child, mutationRate, st, rnd)
            next += Individual(child)
        }
        return next
    }

    private val STRENGTH_MIX = floatArrayOf(0.25f, 1f, 2f)

    private fun tournament(ranked: List<Individual>, rnd: Random, k: Int = 3): Individual {
        var best: Individual? = null
        repeat(k) {
            val c = ranked[rnd.nextInt(ranked.size)]
            if (best == null || c.fitness > best!!.fitness) best = c
        }
        return best!!
    }

    /** Kreuzung pro Neuron: alle Eingangsgewichte eines Neurons kommen vom selben Elternteil. */
    fun crossover(a: FloatArray, b: FloatArray, layout: IntArray, rnd: Random): FloatArray {
        val child = FloatArray(a.size)
        var off = 0
        for (l in 0 until layout.size - 1) {
            val block = layout[l] + 1
            for (o in 0 until layout[l + 1]) {
                val src = if (rnd.nextBoolean()) a else b
                src.copyInto(child, off, off, off + block)
                off += block
            }
        }
        return child
    }

    fun mutate(w: FloatArray, rate: Float, strength: Float, rnd: Random) {
        var changed = false
        for (i in w.indices) {
            if (rnd.nextFloat() < rate) {
                w[i] += gaussian(rnd) * strength
                changed = true
            }
        }
        if (!changed && w.isNotEmpty()) w[rnd.nextInt(w.size)] += gaussian(rnd) * strength
    }
}
