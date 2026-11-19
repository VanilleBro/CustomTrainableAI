package com.customtrainableai.ai

import com.customtrainableai.engine.Drag
import com.customtrainableai.engine.InstanceCommand
import com.customtrainableai.model.ActionConfig
import com.customtrainableai.model.ActionMode
import com.customtrainableai.model.KeyLayout
import com.customtrainableai.model.ScreenPoint
import com.customtrainableai.model.ScreenRect
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Übersetzt die Netz-Ausgabe in Eingaben. Die Ausgabe besteht aus "Köpfen":
 * jeder Kopf ist eine Auswahl (argmax), z.B. Richtung (9 Optionen) und Klick (nichts, A, B, ...).
 * [touchOnly] = Android: WASD wird als gedrückte Bildschirmpunkte statt Tasten ausgeführt.
 */
class ActionSpace(val cfg: ActionConfig, val touchOnly: Boolean) {
    val directions = cfg.joystickDirections.coerceIn(4, 16)
    val extra = cfg.extraClicks.coerceIn(1, 4)

    val heads: IntArray = when (cfg.mode) {
        ActionMode.CLICK_1 -> intArrayOf(2)
        ActionMode.CLICK_2 -> intArrayOf(3)
        ActionMode.WASD -> intArrayOf(9)
        ActionMode.WASD_CLICKS -> intArrayOf(9, 1 + extra)
        ActionMode.JOYSTICK -> intArrayOf(1 + directions)
        ActionMode.JOYSTICK_CLICKS -> intArrayOf(1 + directions, 1 + extra)
        ActionMode.HOLD_1 -> intArrayOf(2)
    }
    val outputSize = heads.sum()

    /** Optionsnamen pro Kopf (für die Anzeige). */
    fun optionNames(): List<List<String>> = when (cfg.mode) {
        ActionMode.CLICK_1 -> listOf(listOf("nichts", "Klick A"))
        ActionMode.CLICK_2 -> listOf(listOf("nichts", "Klick A", "Klick B"))
        ActionMode.WASD -> listOf(DIR_NAMES)
        ActionMode.WASD_CLICKS -> listOf(DIR_NAMES, clickNames())
        ActionMode.JOYSTICK -> listOf(joyNames())
        ActionMode.JOYSTICK_CLICKS -> listOf(joyNames(), clickNames())
        ActionMode.HOLD_1 -> listOf(listOf("loslassen", "halten"))
    }

    private fun clickNames() = listOf("kein Klick") + (0 until extra).map { "Klick ${'A' + it}" }
    private fun joyNames() = listOf("loslassen") + (0 until directions).map { "${(it * 360 / directions)}°" }

    fun command(decision: IntArray, area: ScreenRect): InstanceCommand {
        return when (cfg.mode) {
            ActionMode.CLICK_1, ActionMode.CLICK_2 -> {
                val c = decision[0]
                if (c == 0) InstanceCommand.NONE else InstanceCommand(taps = listOf(area.point(cfg.clickPoint(c - 1))))
            }
            ActionMode.HOLD_1 -> if (decision[0] == 1) InstanceCommand(holds = listOf(area.point(cfg.clickPoint(0)))) else InstanceCommand.NONE
            ActionMode.WASD -> direction(decision[0], area, InstanceCommand.NONE)
            ActionMode.WASD_CLICKS -> direction(decision[0], area, clicks(decision[1], area))
            ActionMode.JOYSTICK -> joystick(decision[0], area, InstanceCommand.NONE)
            ActionMode.JOYSTICK_CLICKS -> joystick(decision[0], area, clicks(decision[1], area))
        }
    }

    private fun clicks(c: Int, area: ScreenRect): InstanceCommand =
        if (c <= 0) InstanceCommand.NONE else InstanceCommand(taps = listOf(area.point(cfg.clickPoint(c - 1))))

    private fun direction(d: Int, area: ScreenRect, base: InstanceCommand): InstanceCommand {
        val dirs = DIRS[d.coerceIn(0, 8)]
        if (dirs.isEmpty()) return base
        return if (touchOnly) {
            base.copy(holds = base.holds + dirs.map { area.point(cfg.keyPoint(it)) })
        } else {
            val names = if (cfg.keyLayout == KeyLayout.ARROWS) ARROW_KEYS else WASD_KEYS
            base.copy(keys = dirs.map { names[it] }.toSet())
        }
    }

    private fun joystick(d: Int, area: ScreenRect, base: InstanceCommand): InstanceCommand {
        if (d <= 0) return base
        val angle = -PI / 2 + (d - 1) * 2 * PI / directions
        val center = area.point(cfg.joystickCenter)
        val r = cfg.joystickRadius * area.w
        val to = ScreenPoint(center.x + (cos(angle) * r).roundToInt(), center.y + (sin(angle) * r).roundToInt())
        return base.copy(drag = Drag(center, to))
    }

    companion object {
        /** Index der Taste: 0=W/hoch, 1=A/links, 2=S/runter, 3=D/rechts. */
        val DIRS: List<List<Int>> = listOf(
            emptyList(), listOf(0), listOf(0, 3), listOf(3), listOf(2, 3), listOf(2), listOf(2, 1), listOf(1), listOf(0, 1),
        )
        val DIR_NAMES = listOf("nichts", "W", "W+D", "D", "S+D", "S", "S+A", "A", "W+A")
        val WASD_KEYS = listOf("W", "A", "S", "D")
        val ARROW_KEYS = listOf("UP", "LEFT", "DOWN", "RIGHT")
    }
}
