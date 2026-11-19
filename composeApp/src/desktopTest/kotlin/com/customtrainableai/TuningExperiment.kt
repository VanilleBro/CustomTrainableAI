package com.customtrainableai

import com.customtrainableai.engine.Engine
import com.customtrainableai.engine.PracticeSim
import com.customtrainableai.engine.RunMode
import com.customtrainableai.engine.VirtualClock
import com.customtrainableai.model.Brain
import com.customtrainableai.model.Profile
import com.customtrainableai.model.ScreenRect
import com.customtrainableai.model.Templates
import com.customtrainableai.model.fmt
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test

/** Nur zum Abstimmen der Standardwerte: CTAI_TUNE=1 ./gradlew desktopTest --tests '*TuningExperiment*' */
class TuningExperiment {
    private val areas = listOf(ScreenRect(0, 0, 400, 640), ScreenRect(410, 0, 400, 640), ScreenRect(820, 0, 400, 640))

    private fun run(p: Profile, seed: Int): Brain {
        val clock = VirtualClock()
        val sim = PracticeSim(p.practiceKind!!, p.instances, clock, seed = seed)
        val engine = Engine(sim, sim, clock, random = Random(seed), statusInterval = 1e9)
        var brain: Brain? = null
        runBlocking { engine.run(p, RunMode.TRAINING) { brain = it } }
        return brain!!
    }

    @Test
    fun sweep() {
        if (System.getenv("CTAI_TUNE") != "1") return
        val configs = (System.getenv("CTAI_CONFIGS") ?: "0.118:0.31,1.0:0.05,1.0:0.1,0.3:0.15,0.05:0.5")
            .split(",").map { it.split(":").let { (a, b) -> a.toFloat() to b.toFloat() } }
        val pops = (System.getenv("CTAI_POPS") ?: "20,40").split(",").map { it.toInt() }
        val budget = (System.getenv("CTAI_BUDGET") ?: "500").toInt()
        val hidden = (System.getenv("CTAI_HIDDEN") ?: "16").split(",").map { it.toInt() }
        com.customtrainableai.ai.Evolution.parentFraction = (System.getenv("CTAI_PARENTS") ?: "0").toFloat()
        com.customtrainableai.ai.Evolution.mixedStrength = System.getenv("CTAI_MIXED") == "1"
        com.customtrainableai.ai.NeuralNet.initDensity = (System.getenv("CTAI_DENSITY") ?: "1").toFloat()
        com.customtrainableai.ai.Normalizer.enabled = System.getenv("CTAI_NORM") != "0"
        val elites = (System.getenv("CTAI_ELITES") ?: "2").toInt()
        val epa = (System.getenv("CTAI_EPA") ?: "1").toInt()
        val games = (System.getenv("CTAI_GAMES") ?: "catch,jump").split(",")
        for (game in games) {
            for ((rate, strength) in configs) for (pop in pops) {
                val results = (1..2).map { seed ->
                    val base = if (game == "catch") Templates.practiceCatch() else Templates.practiceJump()
                    val p = base.copy(
                        instances = areas,
                        actions = System.getenv("CTAI_MODE")?.let { base.actions.copy(mode = com.customtrainableai.model.ActionMode.valueOf(it)) } ?: base.actions,
                        tickRate = (System.getenv("CTAI_TICK") ?: "${base.tickRate}").toInt(),
                        network = base.network.copy(
                            hidden = hidden,
                            gridW = (System.getenv("CTAI_GRID") ?: "10x16").split("x")[0].toInt(),
                            gridH = (System.getenv("CTAI_GRID") ?: "10x16").split("x")[1].toInt(),
                            motion = System.getenv("CTAI_MOTION") != "0",
                            color = System.getenv("CTAI_COLOR") == "1",
                        ),
                        evolution = base.evolution.copy(population = pop, generationsPerRun = budget / pop / epa, elites = elites, episodesPerAgent = epa, customRate = rate, customStrength = strength),
                    )
                    val b = run(p, seed * 31)
                    if (System.getenv("CTAI_VERBOSE") == "1") b.history.filter { it.gen % 5 == 0 }.forEach { println("   gen ${it.gen} best=${fmt(it.best)} avg=${fmt(it.avg)}") }
                    b.history.take(2).map { it.avg }.average() to b.history.takeLast(3).map { it.avg }.average()
                }
                val first = results.map { it.first }.average()
                val last = results.map { it.second }.average()
                println("TUNE $game mode=${System.getenv("CTAI_MODE")} tick=${System.getenv("CTAI_TICK")} norm=${System.getenv("CTAI_NORM")} density=${System.getenv("CTAI_DENSITY")} mixed=${System.getenv("CTAI_MIXED")} elites=$elites epa=$epa grid=${System.getenv("CTAI_GRID")} motion=${System.getenv("CTAI_MOTION")} hidden=$hidden rate=$rate strength=$strength pop=$pop: start=${fmt(first)} end=${fmt(last)}")
            }
        }
    }
}

class JumpSolvability {
    @Test
    fun handPolicy() {
        if (System.getenv("CTAI_TUNE") != "1") return
        for (offset in listOf(-0.04f, 0f, 0.03f, 0.06f)) {
            val times = (1..10).map { seed ->
                val g = com.customtrainableai.engine.PracticeGame(com.customtrainableai.model.PracticeKind.JUMP, seed)
                var t = 0.0
                while (!g.over && t < 60) {
                    val next = g.pipes.firstOrNull { it.x + com.customtrainableai.engine.PracticeLayout.PIPE_W > 0.2f }
                    val target = (next?.gapY ?: 0.45f) + offset
                    if (g.birdY > target) g.tap(0.5f, 0.9f)
                    repeat(6) { g.update(1.0 / 60) }
                    t += 0.1
                }
                t
            }
            println("HAND offset=$offset: Ø ${fmt(times.average())} s, min ${fmt(times.min())}")
        }
    }
}
