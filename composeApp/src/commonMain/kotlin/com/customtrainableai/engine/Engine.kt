package com.customtrainableai.engine

import com.customtrainableai.ai.ActionSpace
import com.customtrainableai.ai.Evolution
import com.customtrainableai.ai.Individual
import com.customtrainableai.ai.NeuralNet
import com.customtrainableai.ai.Normalizer
import com.customtrainableai.ai.Observer
import com.customtrainableai.ai.RewardTracker
import com.customtrainableai.model.Brain
import com.customtrainableai.model.GenStats
import com.customtrainableai.model.Genome
import com.customtrainableai.model.Profile
import com.customtrainableai.model.ScreenRect
import com.customtrainableai.model.StepType
import com.customtrainableai.model.decodeWeights
import com.customtrainableai.model.encodeWeights
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

enum class RunMode { IDLE, TRAINING, PLAYBACK }

data class InstanceStatus(val index: Int, val phase: String, val agent: Int, val reward: Float, val seconds: Float)

/** Zustand eines Agenten der aktuellen Generation (für die Agenten-Übersicht). */
data class AgentStatus(
    val index: Int,
    /** 0 = wartet, 1 = spielt gerade, 2 = fertig bewertet */
    val state: Int,
    /** Durchschnittliche Belohnung dieser Generation (fertig) bzw. laufende Belohnung (spielt). */
    val score: Float,
    /** Instanz, auf der er gerade spielt (-1 = keine). */
    val instance: Int = -1,
)

data class EngineStatus(
    val mode: RunMode = RunMode.IDLE,
    val generation: Int = 0,
    val runGenerationsDone: Int = 0,
    val runGenerationsTarget: Int = 0,
    val evaluated: Int = 0,
    val total: Int = 0,
    val instances: List<InstanceStatus> = emptyList(),
    val history: List<GenStats> = emptyList(),
    val playback: List<Float> = emptyList(),
    val bestFitness: Float = 0f,
    /** Takte pro echter Sekunde. */
    val ticksPerSecond: Float = 0f,
    /** Spielzeit pro echter Sekunde (1 = Echtzeit; mit Zeitsteuerung/Simulator auch mehr). */
    val timeFactor: Float = 1f,
    val countdown: Int = 0,
    val message: String = "",
    /** Alle Agenten der laufenden Generation. */
    val agents: List<AgentStatus> = emptyList(),
    /** Ergebnisse der zuletzt fertigen Generation (pro Agent). */
    val lastGenScores: List<Float> = emptyList(),
) {
    val running get() = mode != RunMode.IDLE
}

/**
 * Trainings-/Abspiel-Schleife. Alle Instanzen laufen im Gleichschritt:
 * pro Takt Bild aufnehmen -> Netz entscheidet -> Eingaben ausführen -> Belohnung auswerten.
 * Beim Training spielt jede Instanz einen anderen Agenten der aktuellen Generation.
 */
class Engine(
    private val capturer: ScreenCapturer,
    private val input: InputController,
    private val clock: Clock = RealClock,
    private val touchOnly: Boolean = false,
    private val random: Random = Random.Default,
    /** Wie oft der Status nach außen gemeldet wird (in Engine-Sekunden). */
    private val statusInterval: Double = 0.2,
    /** Liest Spielwerte für Browser-Regeln (BROWSER_JS); null = nicht verfügbar. */
    private val probe: ValueProbe? = null,
) {
    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status

    fun setMessage(msg: String) {
        _status.value = _status.value.copy(message = msg)
    }

    private enum class Phase(val label: String) { IDLE("wartet"), RESETTING("Neustart"), STARTING("Start"), RUNNING("spielt") }

    private class Slot(val index: Int, val area: ScreenRect, val tracker: RewardTracker, heads: Int, gridSize: Int, inputSize: Int) {
        var phase = Phase.IDLE
        var needsReset = false
        var agent = -1
        var net: NeuralNet? = null
        var until = 0.0
        var stepIdx = 0
        var startAttempts = 0
        var episodeStart = 0.0
        var lastT = 0.0
        var reward = 0.0
        var grid = FloatArray(gridSize)
        var prevGrid = FloatArray(gridSize)
        var hasPrev = false
        val input = FloatArray(inputSize)
        val decision = IntArray(heads)
        var lastCmd = InstanceCommand.NONE
        var pendingEvents = 0
    }

    companion object {
        fun layoutFor(profile: Profile, touchOnly: Boolean): IntArray {
            val space = ActionSpace(profile.actions, touchOnly)
            val obs = Observer(profile.network, space.heads)
            val hidden = profile.network.hidden.filter { it > 0 }.map { it.coerceAtMost(1024) }
            return (listOf(obs.inputSize) + hidden + listOf(space.outputSize)).toIntArray()
        }
    }

    /**
     * Läuft bis zum Abbruch (Coroutine canceln) oder bis die eingestellten Generationen fertig sind.
     * [onBrain] wird nach jeder Generation mit dem neuen Trainingsstand aufgerufen.
     */
    suspend fun run(
        profile: Profile,
        mode: RunMode,
        startDelaySec: Double = 0.0,
        onBrain: suspend (Brain) -> Unit = {},
    ) {
        try {
            runInternal(profile, mode, startDelaySec, onBrain)
        } finally {
            withContext(NonCancellable) {
                runCatching { input.releaseAll() }
                _status.value = _status.value.copy(mode = RunMode.IDLE, countdown = 0, instances = emptyList())
            }
        }
    }

    private suspend fun runInternal(profile: Profile, mode: RunMode, startDelaySec: Double, onBrain: suspend (Brain) -> Unit) {
        val space = ActionSpace(profile.actions, touchOnly)
        val observer = Observer(profile.network, space.heads)
        val layout = layoutFor(profile, touchOnly)
        val evo = profile.evolution
        val ep = profile.rewards.episode
        val rules = profile.rewards.rules.filter { it.enabled }
        val tickInterval = 1.0 / profile.tickRate.coerceIn(1, 60)
        val brain = profile.brain?.takeIf { it.layout == layout.toList() }
        var message = if (profile.brain != null && brain == null) "Netz-Aufbau wurde geändert – Training startet neu." else ""

        val bestWeights = brain?.best?.let { decodeWeights(it.w) }?.takeIf { it.size == NeuralNet.paramCount(layout) }
        if (mode == RunMode.PLAYBACK && bestWeights == null) {
            _status.value = EngineStatus(message = "Noch kein trainiertes Netz – bitte zuerst trainieren.")
            return
        }

        val popSize = evo.population.coerceIn(2, 1000)
        val population = buildPopulation(brain, layout, popSize, evo.mutationStrength)
        val history = brain?.history?.toMutableList() ?: mutableListOf()
        var generation = brain?.generation ?: 0
        var totalEpisodes = brain?.totalEpisodes ?: 0L
        var bestFitness = brain?.bestFitness ?: 0f
        val playbackNet = bestWeights?.let { NeuralNet(layout, it) }
        val normalizer = Normalizer.from(brain?.norm, observer.normSize)
        val eps = if (mode == RunMode.TRAINING) evo.randomActionChance else 0f

        val slots = profile.instances.ifEmpty { listOf(ScreenRect()) }.mapIndexed { i, r ->
            Slot(i, r.normalized(), RewardTracker(rules), space.heads.size, observer.gridSize, observer.inputSize).also {
                // Vor der ersten Runde einmal die Neustart-Schritte ausführen (Spiel sicher starten)
                it.needsReset = ep.resetAtStart && ep.resetSteps.isNotEmpty()
            }
        }

        // Countdown, damit man noch zum Spiel wechseln kann
        var remaining = startDelaySec
        while (remaining > 0) {
            _status.value = EngineStatus(mode = mode, generation = generation, history = history.toList(), countdown = ceil(remaining).toInt(), message = "Start in ${ceil(remaining).toInt()} s …")
            val s = min(1.0, remaining)
            clock.sleep(s)
            remaining -= s
        }

        val episodesPerAgent = evo.episodesPerAgent.coerceIn(1, 20)
        val queue = ArrayDeque<Int>()
        val genScores = DoubleArray(popSize)
        val genCounts = IntArray(popSize)
        var completed = 0
        var totalThisGen = 0
        fun prepareGeneration() {
            queue.clear()
            repeat(episodesPerAgent) { for (a in 0 until popSize) queue.addLast(a) }
            genScores.fill(0.0); genCounts.fill(0)
            completed = 0
            totalThisGen = queue.size
        }
        if (mode == RunMode.TRAINING) prepareGeneration()

        val playbackScores = mutableListOf<Float>()
        var runGens = 0
        val target = if (mode == RunMode.TRAINING) evo.generationsPerRun.coerceAtLeast(0) else 0
        var lastStatus = -1e9
        var stuckMsg: String? = null
        var lastGenScores: List<Float> = emptyList()
        var tickCount = 0
        // Takte und Tempo immer in echter Zeit messen (die Engine-Uhr kann virtuell sein)
        val wall = kotlin.time.TimeSource.Monotonic
        var tpsMark = wall.markNow()
        var tpsClock = clock.now()
        var tps = 0f
        var factor = 1f

        while (true) {
            currentCoroutineContext().ensureActive()
            val now = clock.now()

            // 1) Freien Instanzen Arbeit geben
            for (s in slots) {
                if (s.phase != Phase.IDLE) continue
                if (mode == RunMode.TRAINING) {
                    val a = queue.removeFirstOrNull() ?: continue
                    s.agent = a
                    s.net = NeuralNet(layout, population[a].weights)
                } else {
                    s.agent = -1
                    s.net = playbackNet
                }
                s.startAttempts = 0
                if (s.needsReset && ep.resetSteps.isNotEmpty()) {
                    s.phase = Phase.RESETTING; s.stepIdx = 0; s.until = now
                } else {
                    s.phase = Phase.STARTING; s.until = now + ep.startDelaySec
                }
            }

            // 2) Bilder aufnehmen
            val needFrames = slots.filter { it.phase == Phase.RUNNING || (it.phase == Phase.STARTING && now >= it.until) }
            val frames = if (needFrames.isEmpty()) emptyList() else capturer.capture(needFrames.map { it.area })
            val frameOf = HashMap<Int, Frame?>()
            needFrames.forEachIndexed { k, s -> frameOf[s.index] = frames.getOrNull(k) }
            // Browser-Werte (Punktestand, Game Over …) direkt aus dem Spiel lesen
            if (probe != null && slots.first().tracker.usesJs && needFrames.isNotEmpty()) {
                val scripts = slots.first().tracker.jsScripts
                val p = probe!!
                val results = kotlinx.coroutines.coroutineScope {
                    needFrames.map { s -> async { runCatching { p.values(s.area, scripts) }.getOrNull() } }.awaitAll()
                }
                needFrames.forEachIndexed { k, s ->
                    val v = results[k]
                    if (v == null) s.tracker.jsValues.fill(Double.NaN) else v.copyInto(s.tracker.jsValues, 0, 0, minOf(v.size, s.tracker.jsValues.size))
                }
            }
            var pauseMsg: String? = null
            if (needFrames.isNotEmpty() && frames.all { it == null }) pauseMsg = "Kein Bild – Spiel nicht sichtbar/verdeckt oder Bildschirmaufnahme nicht aktiv. Pausiert."

            // 3) Entscheiden
            val commands = HashMap<Int, InstanceCommand>()
            for (s in slots) {
                var cmd = InstanceCommand.NONE
                when (s.phase) {
                    Phase.IDLE -> {}
                    Phase.RESETTING -> if (now >= s.until) {
                        val step = ep.resetSteps.getOrNull(s.stepIdx)
                        if (step == null) {
                            s.phase = Phase.STARTING
                            s.until = now + ep.startDelaySec
                        } else {
                            s.stepIdx++
                            when (step.type) {
                                StepType.CLICK -> { cmd = InstanceCommand(taps = listOf(s.area.point(step.point))); s.until = now + 0.15 }
                                StepType.KEY -> { cmd = InstanceCommand(keyTaps = listOf(step.key)); s.until = now + 0.15 }
                                StepType.BACK -> { cmd = InstanceCommand(back = true); s.until = now + 0.5 }
                                StepType.WAIT -> s.until = now + step.seconds.coerceIn(0f, 120f)
                            }
                        }
                    }
                    Phase.STARTING -> if (now >= s.until) {
                        val f = frameOf[s.index]
                        if (f != null) {
                            if (ep.resetSteps.isNotEmpty() && s.tracker.endConditionVisible(f)) {
                                // Game Over ist noch zu sehen: Neustart wiederholen. Klappt es dauerhaft nicht, NICHT werten
                                // (sonst bekäme jeder Agent sinnlos dieselbe Strafe), sondern warten und Hinweis zeigen.
                                s.startAttempts++
                                s.phase = Phase.RESETTING; s.stepIdx = 0
                                s.until = if (s.startAttempts <= 3) now else now + 2.0
                                if (s.startAttempts > 3) stuckMsg = "Instanz ${s.index + 1}: Spiel startet nicht neu (Game Over bleibt sichtbar). " +
                                    "Kommen die Klicks an? Neustart-Schritte und Eingabe-Methode prüfen."
                            } else {
                                s.tracker.reset()
                                s.episodeStart = now
                                s.lastT = now
                                s.reward = 0.0
                                s.hasPrev = false
                                s.decision.fill(0)
                                s.lastCmd = InstanceCommand.NONE
                                s.pendingEvents = 0
                                s.phase = Phase.RUNNING
                                if (s.startAttempts > 3) stuckMsg = null
                            }
                        }
                    }
                    Phase.RUNNING -> {
                        val f = frameOf[s.index]
                        if (f == null) {
                            // Spielbereich nicht sichtbar/verdeckt: Runde anhalten (Uhr läuft nicht weiter), nichts drücken
                            s.episodeStart += now - s.lastT
                            s.lastT = now
                            s.lastCmd = InstanceCommand.NONE
                            if (pauseMsg == null) pauseMsg = "Spielbereich ${s.index + 1} nicht sichtbar oder verdeckt – pausiert, bis das Spiel wieder sichtbar ist."
                        } else {
                            val t = now - s.episodeStart
                            val dt = (now - s.lastT).coerceIn(0.0, 1.0)
                            s.lastT = now
                            s.reward += s.tracker.step(f, t, dt, s.pendingEvents)
                            s.pendingEvents = 0
                            val timeUp = t >= ep.maxSeconds
                            if (s.tracker.ended || timeUp) {
                                val score = s.reward
                                totalEpisodes++
                                if (mode == RunMode.TRAINING) {
                                    population[s.agent].addResult(score)
                                    genScores[s.agent] += score
                                    genCounts[s.agent]++
                                    completed++
                                } else {
                                    playbackScores += score.toFloat()
                                    if (playbackScores.size > 2000) playbackScores.removeAt(0)
                                }
                                s.needsReset = !timeUp || ep.resetOnTimeLimit
                                s.phase = Phase.IDLE
                                s.lastCmd = InstanceCommand.NONE
                            } else {
                                observer.grid(f, s.grid)
                                observer.buildInput(s.grid, if (s.hasPrev) s.prevGrid else null, s.decision, s.input)
                                val tmp = s.prevGrid; s.prevGrid = s.grid; s.grid = tmp
                                s.hasPrev = true
                                if (mode == RunMode.TRAINING) normalizer.update(s.input)
                                normalizer.normalize(s.input)
                                val out = s.net!!.forward(s.input)
                                var off = 0
                                for (h in space.heads.indices) {
                                    val n = space.heads[h]
                                    var best = 0
                                    for (o in 1 until n) if (out[off + o] > out[off + best]) best = o
                                    if (eps > 0f && random.nextFloat() < eps) best = random.nextInt(n)
                                    s.decision[h] = best
                                    off += n
                                }
                                cmd = space.command(s.decision, s.area)
                                s.pendingEvents = countEvents(s.lastCmd, cmd)
                                s.lastCmd = cmd
                            }
                        }
                    }
                }
                cmd = cmd.copy(area = s.area)
                if (cmd.keys.isNotEmpty() || cmd.keyTaps.isNotEmpty()) {
                    cmd = cmd.copy(keyTarget = s.area.point(com.customtrainableai.model.RelPoint(0.5f, 0.5f)))
                }
                commands[s.index] = cmd
            }

            // 4) Eingaben ausführen
            input.apply(commands)

            // 5) Generation fertig?
            if (mode == RunMode.TRAINING && queue.isEmpty() && slots.all { it.phase == Phase.IDLE }) {
                val scores = (0 until popSize).filter { genCounts[it] > 0 }.map { genScores[it] / genCounts[it] }
                lastGenScores = (0 until popSize).map { if (genCounts[it] > 0) (genScores[it] / genCounts[it]).toFloat() else Float.NaN }
                if (scores.isNotEmpty()) {
                    generation++
                    runGens++
                    history += GenStats(generation, scores.max().toFloat(), scores.average().toFloat(), scores.min().toFloat())
                    if (history.size > 5000) history.removeAt(0)
                    val ranked = population.sortedByDescending { it.fitness }
                    val champion = ranked.first()
                    bestFitness = champion.fitness.toFloat()
                    val next = Evolution.nextGeneration(ranked, layout, popSize, evo.elites, evo.mutationRate, evo.mutationStrength, random)
                    population.clear()
                    population.addAll(next)
                    val newBrain = Brain(
                        layout = layout.toList(),
                        generation = generation,
                        best = Genome(encodeWeights(champion.weights), champion.fitnessSum.toFloat(), champion.evals),
                        bestFitness = bestFitness,
                        population = population.map { Genome(encodeWeights(it.weights), it.fitnessSum.toFloat(), it.evals) },
                        history = history.toList(),
                        totalEpisodes = totalEpisodes,
                        norm = normalizer.toStats(),
                    )
                    onBrain(newBrain)
                    message = "Generation $generation fertig – beste Belohnung ${com.customtrainableai.model.fmt(scores.max())}"
                }
                if (target > 0 && runGens >= target) {
                    _status.value = _status.value.copy(
                        generation = generation, runGenerationsDone = runGens, history = history.toList(), bestFitness = bestFitness,
                        message = "Fertig: $runGens Generationen trainiert.",
                    )
                    return
                }
                prepareGeneration()
            }

            // 6) Status melden
            tickCount++
            val after = clock.now()
            val real = tpsMark.elapsedNow().inWholeMicroseconds / 1e6
            if (real >= 1.0) {
                tps = (tickCount / real).toFloat()
                factor = ((after - tpsClock) / real).toFloat()
                tickCount = 0
                tpsMark = wall.markNow()
                tpsClock = after
            }
            if (after - lastStatus >= statusInterval) {
                lastStatus = after
                _status.value = EngineStatus(
                    mode = mode,
                    generation = generation,
                    runGenerationsDone = runGens,
                    runGenerationsTarget = target,
                    evaluated = completed,
                    total = totalThisGen,
                    instances = slots.map {
                        InstanceStatus(
                            it.index, it.phase.label, it.agent, it.reward.toFloat(),
                            if (it.phase == Phase.RUNNING) (after - it.episodeStart).toFloat() else 0f,
                        )
                    },
                    history = history.toList(),
                    playback = playbackScores.toList(),
                    bestFitness = bestFitness,
                    ticksPerSecond = tps,
                    timeFactor = factor,
                    message = pauseMsg ?: stuckMsg ?: message,
                    agents = if (mode != RunMode.TRAINING) emptyList() else (0 until popSize).map { a ->
                        val slot = slots.firstOrNull { it.agent == a && it.phase != Phase.IDLE }
                        when {
                            slot != null -> AgentStatus(a, 1, slot.reward.toFloat(), slot.index)
                            genCounts[a] >= episodesPerAgent -> AgentStatus(a, 2, (genScores[a] / genCounts[a]).toFloat())
                            else -> AgentStatus(a, 0, 0f)
                        }
                    },
                    lastGenScores = lastGenScores,
                )
            }

            // 7) Takt halten
            clock.sleep(max(0.0, tickInterval - (clock.now() - now)))
        }
    }

    private fun countEvents(prev: InstanceCommand, cmd: InstanceCommand): Int {
        var n = cmd.taps.size
        n += cmd.keys.count { it !in prev.keys }
        n += cmd.holds.count { it !in prev.holds }
        if (cmd.drag != null && prev.drag == null) n++
        return n
    }

    private fun buildPopulation(brain: Brain?, layout: IntArray, size: Int, strength: Float): MutableList<Individual> {
        val n = NeuralNet.paramCount(layout)
        val list = mutableListOf<Individual>()
        brain?.population?.forEach { g ->
            decodeWeights(g.w)?.takeIf { it.size == n }?.let { list += Individual(it, g.fitnessSum.toDouble(), g.evals) }
        }
        if (list.isEmpty()) {
            brain?.best?.let { g -> decodeWeights(g.w)?.takeIf { it.size == n }?.let { list += Individual(it) } }
        }
        if (list.size > size) {
            list.sortByDescending { it.fitness }
            while (list.size > size) list.removeAt(list.lastIndex)
        }
        val seeds = list.size
        while (list.size < size) {
            list += if (seeds > 0) {
                val w = list[random.nextInt(seeds)].weights.copyOf()
                Evolution.mutate(w, 0.2f, strength, random)
                Individual(w)
            } else {
                Individual(NeuralNet.randomWeights(layout, random))
            }
        }
        return list
    }
}
