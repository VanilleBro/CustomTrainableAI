package com.customtrainableai.model

import com.customtrainableai.engine.PracticeLayout

/** Fertige Profile für das eingebaute Übungsspiel (zum Ausprobieren und als Beispiel). */
object Templates {
    fun blank(name: String = "Neues Profil") = Profile(name = name)

    private val gameOverRule = RewardRule(
        type = RuleType.PIXEL_COLOR,
        name = "Game Over (Statusfeld rot)",
        reward = -5f,
        point = PracticeLayout.STATUS_POINT,
        color = "#EF4444",
        tolerance = 60,
        timing = Timing.ON_ENTER,
        endsEpisode = true,
    )

    private val restartSteps = listOf(ResetStep(StepType.CLICK, PracticeLayout.RESTART_POINT))

    fun practiceCatch(area: ScreenRect = ScreenRect(100, 100, 400, 640)) = Profile(
        name = "Übung Fangen",
        practiceKind = PracticeKind.CATCH,
        instances = listOf(area),
        actions = ActionConfig(
            mode = ActionMode.CLICK_2,
            clickPoints = listOf(
                PracticeLayout.center(PracticeLayout.LEFT_BTN),
                PracticeLayout.center(PracticeLayout.RIGHT_BTN),
                RelPoint(0.5f, 0.5f),
                RelPoint(0.5f, 0.3f),
            ),
            extraClicks = 1,
            joystickCenter = PracticeLayout.JOY_CENTER,
            joystickRadius = PracticeLayout.JOY_RANGE,
            keyPoints = listOf(
                RelPoint(0.5f, 0.3f),
                PracticeLayout.center(PracticeLayout.LEFT_BTN),
                RelPoint(0.5f, 0.35f),
                PracticeLayout.center(PracticeLayout.RIGHT_BTN),
            ),
        ),
        tickRate = 10,
        network = NetworkConfig(hidden = listOf(16), gridW = 7, gridH = 11, motion = true),
        evolution = EvolutionConfig(population = 20, generationsPerRun = 40, experiment = 0.35f),
        rewards = RewardSetup(
            rules = listOf(
                RewardRule(
                    type = RuleType.REGION_CHANGE,
                    name = "Ball gefangen (Punktestand ändert sich)",
                    reward = 5f,
                    region = PracticeLayout.SCORE,
                    threshold = 0.01f,
                    cooldownSec = 0.3f,
                ),
                RewardRule(
                    type = RuleType.REGION_CHANGE,
                    name = "Leben verloren",
                    reward = -3f,
                    region = PracticeLayout.LIVES,
                    threshold = 0.05f,
                    cooldownSec = 0.3f,
                ),
                gameOverRule,
                RewardRule(RuleType.SURVIVAL, "Überleben", reward = 0.2f),
            ),
            episode = EpisodeConfig(maxSeconds = 45f, startDelaySec = 0.15f, resetSteps = restartSteps),
        ),
    )

    fun practiceJump(area: ScreenRect = ScreenRect(100, 100, 400, 640)) = Profile(
        name = "Übung Springen",
        practiceKind = PracticeKind.JUMP,
        instances = listOf(area),
        actions = ActionConfig(
            mode = ActionMode.CLICK_1,
            clickPoints = listOf(PracticeLayout.center(PracticeLayout.JUMP_BTN)) + DEFAULT_CLICK_POINTS.drop(1),
        ),
        tickRate = 10,
        network = NetworkConfig(hidden = listOf(16), gridW = 7, gridH = 11, motion = true),
        evolution = EvolutionConfig(population = 20, generationsPerRun = 40, experiment = 0.35f),
        rewards = RewardSetup(
            rules = listOf(
                RewardRule(RuleType.SURVIVAL, "Überleben", reward = 1f),
                RewardRule(
                    type = RuleType.OBJECT_TRACK,
                    name = "Vogel zur Mitte (orange Figur verfolgen)",
                    reward = 1f,
                    color = "#F97316",
                    tolerance = 50,
                    region = RelRect(0.15f, PracticeLayout.FIELD_TOP, 0.2f, PracticeLayout.FIELD_BOTTOM - PracticeLayout.FIELD_TOP),
                    goal = TrackGoal.TARGET,
                    target = RelPoint(PracticeLayout.BIRD_X, 0.45f),
                    onlyNewBest = false,
                ),
                RewardRule(
                    type = RuleType.REGION_CHANGE,
                    name = "Röhre geschafft (Punktestand ändert sich)",
                    reward = 3f,
                    region = PracticeLayout.SCORE,
                    threshold = 0.01f,
                    cooldownSec = 0.3f,
                ),
                gameOverRule,
            ),
            episode = EpisodeConfig(maxSeconds = 60f, startDelaySec = 0.15f, resetSteps = restartSteps),
        ),
    )

    /** Die Vorlage an mehrere Bereiche anpassen. */
    fun Profile.withAreas(areas: List<ScreenRect>) = if (areas.isEmpty()) this else copy(instances = areas)
}
