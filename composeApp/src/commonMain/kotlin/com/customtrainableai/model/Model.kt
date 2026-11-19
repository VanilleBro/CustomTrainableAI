package com.customtrainableai.model

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** Punkt relativ zum Spielbereich: (0,0) = oben links, (1,1) = unten rechts. */
@Serializable
data class RelPoint(val x: Float = 0.5f, val y: Float = 0.5f) {
    fun clamped() = RelPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
}

/** Rechteck relativ zum Spielbereich (x, y = obere linke Ecke, w/h = Breite/Hoehe, alles 0..1). */
@Serializable
data class RelRect(val x: Float = 0.4f, val y: Float = 0.4f, val w: Float = 0.2f, val h: Float = 0.2f) {
    fun contains(px: Float, py: Float) = px >= x && px <= x + w && py >= y && py <= y + h
    fun normalized(): RelRect {
        val nw = w.coerceIn(0.005f, 1f)
        val nh = h.coerceIn(0.005f, 1f)
        return RelRect(x.coerceIn(0f, 1f - nw), y.coerceIn(0f, 1f - nh), nw, nh)
    }
}

/** Rechteck in Bildschirm-Einheiten (Windows: Pixel nach Skalierung, Android: echte Pixel). */
@Serializable
data class ScreenRect(val x: Int = 100, val y: Int = 100, val w: Int = 400, val h: Int = 600) {
    fun point(p: RelPoint) = ScreenPoint(x + (p.x * w).roundToInt(), y + (p.y * h).roundToInt())
    fun rel(sx: Float, sy: Float) = RelPoint((sx - x) / w.coerceAtLeast(1), (sy - y) / h.coerceAtLeast(1))
    val right get() = x + w
    val bottom get() = y + h
    fun normalized() = ScreenRect(x, y, w.coerceAtLeast(8), h.coerceAtLeast(8))
}

data class ScreenPoint(val x: Int, val y: Int)

@Serializable
enum class ActionMode(val label: String, val description: String) {
    CLICK_1("1 Punkt klicken", "Die KI entscheidet in jedem Schritt, ob sie auf Punkt A klickt."),
    CLICK_2("2 Punkte klicken", "Die KI klickt Punkt A, Punkt B oder gar nichts."),
    WASD("WASD", "Die KI hält W/A/S/D gedrückt (8 Richtungen oder nichts)."),
    WASD_CLICKS("WASD + Klicks", "Richtungstasten und zusätzlich bis zu 4 Klickpunkte."),
    JOYSTICK("Joystick", "Die KI zieht einen virtuellen Joystick in eine Richtung oder lässt los."),
    JOYSTICK_CLICKS("Joystick + Klicks", "Joystick und zusätzlich bis zu 4 Klickpunkte."),
    HOLD_1("1 Punkt halten", "Die KI hält Punkt A gedrückt oder lässt los."),
}

@Serializable
enum class KeyLayout(val label: String) { WASD("W A S D"), ARROWS("Pfeiltasten") }

@Serializable
enum class InputMethod(val label: String) {
    REAL("Echte Maus/Tastatur"),
    BACKGROUND("Hintergrund (Fenster-Nachrichten)"),
}

val DEFAULT_CLICK_POINTS = listOf(
    RelPoint(0.5f, 0.85f), RelPoint(0.8f, 0.85f), RelPoint(0.2f, 0.85f), RelPoint(0.5f, 0.65f),
)
val DEFAULT_KEY_POINTS = listOf( // W, A, S, D als Bildschirm-Tasten (Android)
    RelPoint(0.2f, 0.72f), RelPoint(0.1f, 0.82f), RelPoint(0.2f, 0.92f), RelPoint(0.3f, 0.82f),
)

@Serializable
data class ActionConfig(
    val mode: ActionMode = ActionMode.CLICK_1,
    val clickPoints: List<RelPoint> = DEFAULT_CLICK_POINTS,
    val extraClicks: Int = 1,
    val joystickCenter: RelPoint = RelPoint(0.2f, 0.8f),
    val joystickRadius: Float = 0.08f,
    val joystickDirections: Int = 8,
    val keyLayout: KeyLayout = KeyLayout.WASD,
    val keyPoints: List<RelPoint> = DEFAULT_KEY_POINTS,
    val tapMs: Int = 30,
) {
    fun clickPoint(i: Int) = clickPoints.getOrNull(i) ?: DEFAULT_CLICK_POINTS[i.coerceIn(0, 3)]
    fun keyPoint(i: Int) = keyPoints.getOrNull(i) ?: DEFAULT_KEY_POINTS[i.coerceIn(0, 3)]

    /** Anzahl Klickpunkte, die der aktuelle Modus benutzt. */
    val usedClickPoints: Int
        get() = when (mode) {
            ActionMode.CLICK_1, ActionMode.HOLD_1 -> 1
            ActionMode.CLICK_2 -> 2
            ActionMode.WASD_CLICKS, ActionMode.JOYSTICK_CLICKS -> extraClicks.coerceIn(1, 4)
            else -> 0
        }
    val usesJoystick get() = mode == ActionMode.JOYSTICK || mode == ActionMode.JOYSTICK_CLICKS
    val usesKeys get() = mode == ActionMode.WASD || mode == ActionMode.WASD_CLICKS

    fun withClickPoint(i: Int, p: RelPoint): ActionConfig {
        val list = (0 until 4).map { clickPoint(it) }.toMutableList()
        list[i] = p.clamped()
        return copy(clickPoints = list)
    }

    fun withKeyPoint(i: Int, p: RelPoint): ActionConfig {
        val list = (0 until 4).map { keyPoint(it) }.toMutableList()
        list[i] = p.clamped()
        return copy(keyPoints = list)
    }
}

@Serializable
data class NetworkConfig(
    val hidden: List<Int> = listOf(16),
    val gridW: Int = 10,
    val gridH: Int = 10,
    val color: Boolean = false,
    val motion: Boolean = true,
)

@Serializable
data class EvolutionConfig(
    val population: Int = 20,
    val generationsPerRun: Int = 30,
    val experiment: Float = 0.35f,
    val elites: Int = 4,
    val episodesPerAgent: Int = 1,
    val randomActions: Boolean = true,
    val autosave: Boolean = true,
    /** Optional: Mutation von Hand statt über den Experimentier-Regler. */
    val customRate: Float? = null,
    val customStrength: Float? = null,
) {
    // Abgestimmt im Simulator (TuningExperiment): Standard 0.35 -> ~5 % der Gewichte, Stärke ~0.3
    val mutationRate get() = customRate ?: (0.01f + 0.12f * experiment.coerceIn(0f, 1f))
    val mutationStrength get() = customStrength ?: (0.1f + 0.6f * experiment.coerceIn(0f, 1f))
    val randomActionChance get() = if (randomActions) 0.10f * experiment.coerceIn(0f, 1f) else 0f
}

@Serializable
enum class RuleType(val label: String) {
    SURVIVAL("Überleben (pro Sekunde)"),
    PIXEL_COLOR("Farbe an Punkt"),
    REGION_CHANGE("Bereich ändert sich (z.B. Punktestand)"),
    REGION_STILL("Bereich steht still (hängt fest)"),
    COLOR_AMOUNT("Farbanteil im Bereich (z.B. Lebensbalken)"),
    OBJECT_TRACK("Objekt verfolgen (Farbe)"),
    ACTION_COST("Kosten pro Aktion"),
}

@Serializable
enum class Condition(val label: String) { MATCH("Farbe ist da"), NO_MATCH("Farbe ist weg") }

@Serializable
enum class Timing(val label: String) {
    ON_ENTER("jedes Mal, wenn es eintritt"),
    ONCE("einmal pro Runde"),
    PER_SECOND("pro Sekunde, solange es gilt"),
}

@Serializable
enum class Measure(val label: String) { CHANGE("Änderung (pro 10 %)"), LEVEL("Menge pro Sekunde") }

@Serializable
enum class TrackGoal(val label: String) {
    RIGHT("nach rechts"), LEFT("nach links"), UP("nach oben"), DOWN("nach unten"),
    TARGET("zum Zielpunkt"), ZONE("in die Zone"),
}

@Serializable
data class RewardRule(
    val type: RuleType = RuleType.SURVIVAL,
    val name: String = "",
    val enabled: Boolean = true,
    val reward: Float = 1f,
    val point: RelPoint = RelPoint(0.5f, 0.5f),
    val region: RelRect = RelRect(0.35f, 0.35f, 0.3f, 0.3f),
    val color: String = "#FF0000",
    val tolerance: Int = 40,
    val condition: Condition = Condition.MATCH,
    val timing: Timing = Timing.ON_ENTER,
    val endsEpisode: Boolean = false,
    val threshold: Float = 0.05f,
    val cooldownSec: Float = 0.5f,
    val seconds: Float = 3f,
    val measure: Measure = Measure.CHANGE,
    val goal: TrackGoal = TrackGoal.RIGHT,
    val target: RelPoint = RelPoint(0.9f, 0.5f),
    val zone: RelRect = RelRect(0.8f, 0.4f, 0.2f, 0.2f),
    val onlyNewBest: Boolean = true,
    val minPixels: Int = 3,
    val delaySec: Float = 0f,
) {
    val displayName get() = name.ifBlank { type.label }
    val usesColor get() = type == RuleType.PIXEL_COLOR || type == RuleType.COLOR_AMOUNT || type == RuleType.OBJECT_TRACK
}

@Serializable
enum class StepType(val label: String) { CLICK("Klick"), KEY("Taste"), WAIT("Warten"), BACK("Zurück (Android)") }

@Serializable
data class ResetStep(
    val type: StepType = StepType.CLICK,
    val point: RelPoint = RelPoint(0.5f, 0.5f),
    val key: String = "SPACE",
    val seconds: Float = 1f,
)

@Serializable
data class EpisodeConfig(
    val maxSeconds: Float = 30f,
    val startDelaySec: Float = 0.5f,
    val resetSteps: List<ResetStep> = emptyList(),
    val resetOnTimeLimit: Boolean = false,
)

@Serializable
data class RewardSetup(
    val rules: List<RewardRule> = listOf(RewardRule(RuleType.SURVIVAL, "Überleben", reward = 1f)),
    val episode: EpisodeConfig = EpisodeConfig(),
)

@Serializable
data class Genome(val w: String, val fitnessSum: Float = 0f, val evals: Int = 0)

@Serializable
data class GenStats(val gen: Int, val best: Float, val avg: Float, val min: Float)

/** Gespeicherte Eingabe-Normalisierung (Mittelwert/Varianz pro Eingabe, Base64). */
@Serializable
data class NormStats(val mean: String, val variance: String, val count: Long)

/** Der trainierte Zustand: Netz-Aufbau, Population, bestes Netz, Verlauf. */
@Serializable
data class Brain(
    val layout: List<Int>,
    val generation: Int = 0,
    val best: Genome? = null,
    val bestFitness: Float = 0f,
    val population: List<Genome> = emptyList(),
    val history: List<GenStats> = emptyList(),
    val totalEpisodes: Long = 0,
    val norm: NormStats? = null,
)

@Serializable
enum class PracticeKind(val label: String) { CATCH("Fangen"), JUMP("Springen") }

@Serializable
data class Profile(
    val format: Int = 1,
    val name: String = "Neues Profil",
    val actions: ActionConfig = ActionConfig(),
    val instances: List<ScreenRect> = listOf(ScreenRect()),
    val inputMethod: InputMethod = InputMethod.REAL,
    val tickRate: Int = 10,
    val network: NetworkConfig = NetworkConfig(),
    val evolution: EvolutionConfig = EvolutionConfig(),
    val rewards: RewardSetup = RewardSetup(),
    val launchCommand: String = "",
    val launchCount: Int = 2,
    val practiceKind: PracticeKind? = null,
    val brain: Brain? = null,
)

fun parseColor(hex: String): Int {
    val h = hex.trim().removePrefix("#")
    val v = when (h.length) {
        6 -> h.toIntOrNull(16)
        8 -> h.substring(2).toIntOrNull(16)
        3 -> h.map { "$it$it" }.joinToString("").toIntOrNull(16)
        else -> null
    }
    return v ?: 0xFF0000
}

fun colorToHex(rgb: Int): String {
    val v = rgb and 0xFFFFFF
    return "#" + v.toString(16).uppercase().padStart(6, '0')
}

/** Zahlen formatieren ohne String.format (gibt es in Kotlin-Common nicht). */
fun fmt(v: Double, decimals: Int = 2): String {
    if (v.isNaN() || v.isInfinite()) return "–"
    val neg = v < 0
    val a = kotlin.math.abs(v)
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val total = kotlin.math.round(a * factor).toLong()
    val ip = total / factor
    val fp = total % factor
    var s = ip.toString()
    if (decimals > 0) {
        val fs = fp.toString().padStart(decimals, '0').trimEnd('0')
        if (fs.isNotEmpty()) s += ".$fs"
    }
    return if (neg && total != 0L) "-$s" else s
}

fun fmt(v: Float, decimals: Int = 2) = fmt(v.toDouble(), decimals)
