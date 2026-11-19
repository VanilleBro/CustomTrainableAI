package com.customtrainableai

import com.customtrainableai.ai.NeuralNet
import com.customtrainableai.ai.RewardTracker
import com.customtrainableai.engine.Engine
import com.customtrainableai.engine.Frame
import com.customtrainableai.engine.PracticeLayout
import com.customtrainableai.engine.PracticeSim
import com.customtrainableai.engine.RunMode
import com.customtrainableai.engine.VirtualClock
import com.customtrainableai.model.ActionMode
import com.customtrainableai.model.Brain
import com.customtrainableai.model.Profile
import com.customtrainableai.model.ScreenRect
import com.customtrainableai.model.Templates
import com.customtrainableai.model.decodeWeights
import com.customtrainableai.model.encodeWeights
import com.customtrainableai.model.fmt
import com.customtrainableai.model.parseRewardImport
import com.customtrainableai.model.profileFromJson
import com.customtrainableai.model.toJson
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LearningTest {
    private val areas = listOf(ScreenRect(0, 0, 400, 640), ScreenRect(410, 0, 400, 640), ScreenRect(820, 0, 400, 640))

    private fun train(profile: Profile, seed: Int): Brain {
        val clock = VirtualClock()
        val sim = PracticeSim(profile.practiceKind!!, profile.instances, clock, seed = seed)
        val engine = Engine(sim, sim, clock, touchOnly = false, random = Random(seed), statusInterval = 1e9)
        var brain: Brain? = null
        runBlocking { engine.run(profile, RunMode.TRAINING) { brain = it } }
        return assertNotNull(brain, "Training hat kein Ergebnis geliefert")
    }

    private fun report(name: String, b: Brain) {
        println("== $name: ${b.history.size} Generationen, ${b.totalEpisodes} Runden")
        b.history.forEach { println("  Gen ${it.gen}: best=${fmt(it.best)} avg=${fmt(it.avg)} min=${fmt(it.min)}") }
    }

    /** Durchschnitt der ersten 3 Generationen vs. bester 5er-Schnitt der letzten 10 Generationen. */
    private fun improvement(b: Brain): Pair<Double, Double> {
        val first = b.history.take(3).map { it.avg.toDouble() }.average()
        val tail = b.history.takeLast(10).map { it.avg.toDouble() }
        val last = tail.windowed(5).maxOf { it.average() }
        return first to last
    }

    @Test
    fun catchWithTwoClickPointsLearns() {
        val p = Templates.practiceCatch().copy(instances = areas)
            .let { it.copy(evolution = it.evolution.copy(generationsPerRun = 60)) }
        val b = train(p, 7)
        report("Fangen / 2 Punkte", b)
        val (first, last) = improvement(b)
        assertEquals(60, b.history.size)
        // Anfangs fängt die KI fast nie etwas (negativ), am Ende mehrere Bälle pro Runde
        assertTrue(last > first + 8, "Kein Lernfortschritt: Anfang ${fmt(first)}, Ende ${fmt(last)}")
    }

    @Test
    fun jumpWithOneClickPointLearns() {
        val p = Templates.practiceJump().copy(instances = areas)
            .let { it.copy(evolution = it.evolution.copy(generationsPerRun = 60)) }
        val b = train(p, 11)
        report("Springen / 1 Punkt", b)
        val (first, last) = improvement(b)
        assertTrue(last > first + 2.5, "Kein Lernfortschritt: Anfang ${fmt(first)}, Ende ${fmt(last)}")
        assertTrue(b.history.maxOf { it.best } > 10f, "Kein Agent hat eine Röhre geschafft")
    }

    @Test
    fun otherModesRunAndLearn() {
        for (mode in listOf(ActionMode.JOYSTICK, ActionMode.WASD, ActionMode.HOLD_1, ActionMode.JOYSTICK_CLICKS, ActionMode.WASD_CLICKS)) {
            val base = if (mode == ActionMode.HOLD_1) Templates.practiceJump() else Templates.practiceCatch()
            val p = base.copy(instances = areas, actions = base.actions.copy(mode = mode))
                .let { it.copy(evolution = it.evolution.copy(generationsPerRun = 30)) }
            val b = train(p, 3)
            report("Modus $mode", b)
            val (first, last) = improvement(b)
            assertTrue(last > first, "Modus $mode: kein Fortschritt (Anfang ${fmt(first)}, Ende ${fmt(last)})")
        }
    }

    @Test
    fun trainingContinuesAndPlaybackPlays() {
        val p0 = Templates.practiceCatch().copy(instances = areas)
            .let { it.copy(evolution = it.evolution.copy(generationsPerRun = 8)) }
        val b1 = train(p0, 5)
        // Weitertrainieren vom gespeicherten Stand (über JSON, wie beim Laden einer Datei)
        val reloaded = profileFromJson(p0.copy(brain = b1).toJson())
        val b2 = train(reloaded.copy(evolution = reloaded.evolution.copy(generationsPerRun = 4)), 6)
        assertEquals(12, b2.generation)
        assertEquals(12, b2.history.size)

        // Abspielen ohne Training
        val clock = VirtualClock()
        val play = reloaded.copy(brain = b2)
        val sim = PracticeSim(play.practiceKind!!, play.instances, clock, seed = 99)
        val engine = Engine(sim, sim, clock, random = Random(1), statusInterval = 1.0)
        val scores = runBlocking {
            val job = launch { engine.run(play, RunMode.PLAYBACK) }
            while (engine.status.value.playback.size < 6) delay(1)
            job.cancel()
            engine.status.value.playback
        }
        println("Abspielen: ${scores.joinToString { fmt(it) }}")
        assertTrue(scores.size >= 6)
    }

    @Test
    fun weightsAndProfileRoundTrip() {
        val layout = intArrayOf(5, 4, 3)
        val w = NeuralNet.randomWeights(layout, Random(1))
        assertEquals(NeuralNet.paramCount(layout), w.size)
        assertTrue(w.contentEquals(decodeWeights(encodeWeights(w))!!))
        val out = NeuralNet(layout, w).forward(floatArrayOf(1f, 0f, -1f, 0.5f, 0.2f))
        assertEquals(3, out.size)
        val p = Templates.practiceJump()
        assertEquals(p, profileFromJson(p.toJson()))
    }

    @Test
    fun importsRewardJsonFromChatAnswer() {
        val answer = """
            Hier ist deine Konfiguration:
            ```json
            {
              // Spiel: Jump & Run
              "rules": [
                {"type": "pixel_color", "name": "Tot", "point": {"x": 0.5, "y": 0.1}, "color": "#ff0000", "reward": -10, "endsEpisode": true,},
                {"type": "OBJECT_TRACK", "name": "Nach rechts", "color": "#00AAFF", "goal": "right", "reward": 2},
                {"type": "SURVIVAL", "reward": 0.1}
              ],
              "episode": {"maxSeconds": 90},
              "actions": {"mode": "WASD_CLICKS", "extraClicks": 2}
            }
            ```
            Viel Erfolg!
        """.trimIndent()
        val current = Templates.practiceCatch()
        val imp = parseRewardImport(answer, current)
        assertEquals(3, imp.rules!!.size)
        assertEquals(90f, imp.episode!!.maxSeconds)
        // nicht angegebene Felder bleiben erhalten
        assertEquals(current.rewards.episode.resetSteps, imp.episode!!.resetSteps)
        assertEquals(ActionMode.WASD_CLICKS, imp.actions!!.mode)
        assertEquals(current.actions.clickPoints, imp.actions!!.clickPoints)
    }

    @Test
    fun gameOverPixelEndsEpisode() {
        val rules = Templates.practiceCatch().rewards.rules
        val tracker = RewardTracker(rules)
        val w = 100
        val h = 160
        val px = IntArray(w * h) { PracticeLayout.C_FIELD }
        val ok = Frame(w, h, px.copyOf())
        tracker.step(ok, 0.0, 0.1, 0)
        assertTrue(!tracker.ended)
        val over = px.copyOf()
        for (y in 2..9) for (x in 2..12) over[y * w + x] = PracticeLayout.C_OVER
        val r = tracker.step(Frame(w, h, over), 0.1, 0.1, 0)
        assertTrue(tracker.ended)
        assertTrue(r < 0, "Game Over sollte bestraft werden")
    }
}
