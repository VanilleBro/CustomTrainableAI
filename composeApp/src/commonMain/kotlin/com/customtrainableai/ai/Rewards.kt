package com.customtrainableai.ai

import com.customtrainableai.engine.Frame
import com.customtrainableai.model.Condition
import com.customtrainableai.model.Measure
import com.customtrainableai.model.RelRect
import com.customtrainableai.model.RewardRule
import com.customtrainableai.model.RuleType
import com.customtrainableai.model.Timing
import com.customtrainableai.model.TrackGoal
import com.customtrainableai.model.parseColor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Wertet die Belohnungsregeln für eine Runde (Episode) einer Instanz aus. */
class RewardTracker(private val rules: List<RewardRule>) {
    private class State {
        var initialized = false
        var prevTrue = false
        var fired = false
        var lastFire = -1e9
        var prevSample: FloatArray? = null
        var still = 0.0
        var prevAmount = Double.NaN
        var best = Double.NaN
        var prevProgress = Double.NaN
    }

    private val colors = rules.map { parseColor(it.color) }
    private val colors2 = rules.map { parseColor(it.color2) }
    private val states = rules.map { State() }

    var ended = false
        private set
    var endReason = ""
        private set

    /** Letzte Belohnung pro Regel (für die Anzeige). */
    val lastRewards = DoubleArray(rules.size)

    /** Werte der Browser-Regeln (BROWSER_JS) für diesen Schritt, gleiche Reihenfolge wie die Regeln; NaN = unbekannt. */
    val jsValues = DoubleArray(rules.size) { Double.NaN }
    val usesJs get() = rules.any { it.type == RuleType.BROWSER_JS }
    val jsScripts get() = rules.map { if (it.type == RuleType.BROWSER_JS) it.script else "" }

    fun reset() {
        for (s in states) {
            s.initialized = false; s.prevTrue = false; s.fired = false; s.lastFire = -1e9
            s.prevSample = null; s.still = 0.0; s.prevAmount = Double.NaN; s.best = Double.NaN; s.prevProgress = Double.NaN
        }
        ended = false
        endReason = ""
        lastRewards.fill(0.0)
    }

    /** Ein Schritt: Belohnung seit dem letzten Schritt. [t] = Sekunden seit Rundenbeginn. */
    fun step(f: Frame, t: Double, dt: Double, actionEvents: Int): Double {
        var total = 0.0
        for (i in rules.indices) {
            val rule = rules[i]
            val s = states[i]
            val active = t >= rule.delaySec
            val r = when (rule.type) {
                RuleType.SURVIVAL -> rule.reward * dt
                RuleType.ACTION_COST -> rule.reward.toDouble() * actionEvents
                RuleType.PIXEL_COLOR -> {
                    val c = averageColor(f, rule.point.x, rule.point.y)
                    val match = colorDist(c, colors[i]) <= rule.tolerance
                    val cond = if (rule.condition == Condition.MATCH) match else !match
                    if (active) timed(rule, s, cond, dt) else { s.prevTrue = cond; 0.0 }
                }
                RuleType.REGION_CHANGE -> {
                    val change = regionChange(f, rule.region, s)
                    if (active && change >= rule.threshold && t - s.lastFire >= rule.cooldownSec) {
                        s.lastFire = t
                        if (rule.endsEpisode) end(rule)
                        rule.reward.toDouble()
                    } else 0.0
                }
                RuleType.REGION_STILL -> {
                    val change = regionChange(f, rule.region, s)
                    if (change < rule.threshold) s.still += dt else s.still = 0.0
                    if (active && s.still >= rule.seconds) {
                        s.still = 0.0
                        if (rule.endsEpisode) end(rule)
                        rule.reward.toDouble()
                    } else 0.0
                }
                RuleType.COLOR_AMOUNT -> {
                    val amount = colorAmount(f, rule.region, colors[i], rule.tolerance)
                    var r = 0.0
                    if (active) {
                        r = if (rule.measure == Measure.LEVEL) {
                            rule.reward * amount * dt
                        } else {
                            if (s.prevAmount.isNaN()) 0.0 else rule.reward * (amount - s.prevAmount) * 10.0
                        }
                        if (rule.endsEpisode && amount <= rule.threshold) end(rule)
                    }
                    s.prevAmount = amount
                    r
                }
                RuleType.OBJECT_TRACK -> if (active) track(f, rule, colors[i], s, dt, colors2[i]) else 0.0
                RuleType.BROWSER_JS -> {
                    val v = jsValues[i]
                    if (v.isNaN()) {
                        0.0
                    } else if (rule.endsEpisode) {
                        if (active) timed(rule, s, v != 0.0, dt) else { s.prevTrue = v != 0.0; 0.0 }
                    } else if (rule.measure == Measure.LEVEL) {
                        // pro Sekunde × Wert (z.B. 1 solange das Spiel läuft)
                        if (active) rule.reward * v * dt else 0.0
                    } else {
                        // Zahl steigt (z.B. Punktestand): Belohnung pro Zunahme; der erste Wert der Runde ist die Grundlinie
                        var r = 0.0
                        if (s.best.isNaN()) s.best = v
                        else if (v > s.best) {
                            if (active) r = rule.reward * (v - s.best)
                            s.best = v
                        }
                        r
                    }
                }
            }
            lastRewards[i] = r
            total += r
            s.initialized = true
        }
        return total
    }

    /** Ist eine Runden-Ende-Bedingung gerade sichtbar? (z.B. Game-Over-Bildschirm noch da) */
    fun endConditionVisible(f: Frame): Boolean {
        for (i in rules.indices) {
            val rule = rules[i]
            if (!rule.endsEpisode) continue
            when (rule.type) {
                RuleType.PIXEL_COLOR -> {
                    val match = colorDist(averageColor(f, rule.point.x, rule.point.y), colors[i]) <= rule.tolerance
                    if (if (rule.condition == Condition.MATCH) match else !match) return true
                }
                RuleType.COLOR_AMOUNT -> if (colorAmount(f, rule.region, colors[i], rule.tolerance) <= rule.threshold) return true
                RuleType.BROWSER_JS -> if (!jsValues[i].isNaN() && jsValues[i] != 0.0) return true
                else -> {}
            }
        }
        return false
    }

    private fun end(rule: RewardRule) {
        if (!ended) {
            ended = true
            endReason = rule.displayName
        }
    }

    private fun timed(rule: RewardRule, s: State, cond: Boolean, dt: Double): Double {
        var r = 0.0
        when (rule.timing) {
            Timing.PER_SECOND -> if (cond) r = rule.reward * dt
            Timing.ONCE -> if (cond && !s.fired) { r = rule.reward.toDouble(); s.fired = true }
            Timing.ON_ENTER -> if (cond && !s.prevTrue && s.initialized) r = rule.reward.toDouble()
        }
        if (cond && rule.endsEpisode) {
            // Strafe auch dann geben, wenn die Bedingung schon im ersten Bild galt
            if (rule.timing == Timing.ON_ENTER && !s.initialized) r = rule.reward.toDouble()
            end(rule)
        }
        s.prevTrue = cond
        return r
    }

    private fun track(f: Frame, rule: RewardRule, color: Int, s: State, dt: Double, color2: Int = 0): Double {
        var sx = 0.0
        var sy = 0.0
        var n = 0
        forSamples(f, rule.region, 3000) { x, y, c ->
            if (colorDist(c, color) <= rule.tolerance) {
                sx += x; sy += y; n++
            }
        }
        if (n < rule.minPixels.coerceAtLeast(1)) return 0.0
        val cx = (sx / n + 0.5) / f.width
        val cy = (sy / n + 0.5) / f.height
        if (rule.goal == TrackGoal.ZONE) {
            return timed(rule, s, rule.zone.contains(cx.toFloat(), cy.toFloat()), dt)
        }
        if (rule.goal == TrackGoal.FOLLOW_Y) {
            // Ziel = nächstes (am weitesten links liegendes) Objekt der 2. Farbe in der Zone, z.B. die Kanten der nächsten Lücke
            var minX = Int.MAX_VALUE
            val xs = ArrayList<Int>(256)
            val ys = ArrayList<Int>(256)
            forSamples(f, rule.zone, 4000) { x, y, c ->
                if (colorDist(c, color2) <= rule.tolerance) {
                    xs += x; ys += y
                    if (x < minX) minX = x
                }
            }
            var ty = rule.target.y.toDouble()
            if (xs.size >= rule.minPixels.coerceAtLeast(1)) {
                val limit = minX + (f.width * 0.12).toInt().coerceAtLeast(2)
                var sum = 0.0
                var n = 0
                for (k in xs.indices) if (xs[k] <= limit) { sum += ys[k]; n++ }
                if (n > 0) ty = (sum / n + 0.5) / f.height
            }
            val closeness = (1.0 - abs(cy - ty) / 0.3).coerceAtLeast(0.0)
            return rule.reward * closeness * dt
        }
        val progress = when (rule.goal) {
            TrackGoal.RIGHT -> cx
            TrackGoal.LEFT -> 1 - cx
            TrackGoal.UP -> 1 - cy
            TrackGoal.DOWN -> cy
            else -> {
                val dx = cx - rule.target.x
                val dy = cy - rule.target.y
                -sqrt(dx * dx + dy * dy)
            }
        }
        var r = 0.0
        if (rule.onlyNewBest) {
            if (s.best.isNaN()) s.best = progress
            else if (progress > s.best) { r = rule.reward * (progress - s.best) * 10.0; s.best = progress }
        } else {
            if (!s.prevProgress.isNaN()) r = rule.reward * (progress - s.prevProgress) * 10.0
            s.prevProgress = progress
        }
        if (rule.endsEpisode && rule.goal == TrackGoal.TARGET && -progress <= rule.threshold) end(rule)
        return r
    }

    private fun regionChange(f: Frame, region: RelRect, s: State): Double {
        val cur = ArrayList<Float>(1024)
        forSamples(f, region, 1600) { _, _, c -> cur += gray(c) }
        val prev = s.prevSample
        val arr = cur.toFloatArray()
        s.prevSample = arr
        if (prev == null || prev.size != arr.size || arr.isEmpty()) return 0.0
        var changed = 0
        for (i in arr.indices) if (abs(arr[i] - prev[i]) > 25f) changed++
        return changed.toDouble() / arr.size
    }

    companion object {
        fun gray(c: Int): Float = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)

        fun colorDist(argb: Int, rgb: Int): Int {
            val dr = abs(((argb shr 16) and 0xFF) - ((rgb shr 16) and 0xFF))
            val dg = abs(((argb shr 8) and 0xFF) - ((rgb shr 8) and 0xFF))
            val db = abs((argb and 0xFF) - (rgb and 0xFF))
            return max(dr, max(dg, db))
        }

        fun averageColor(f: Frame, rx: Float, ry: Float): Int {
            val cx = (rx * f.width).toInt()
            val cy = (ry * f.height).toInt()
            val rad = (minOf(f.width, f.height) / 200).coerceIn(1, 4)
            var r = 0; var g = 0; var b = 0; var n = 0
            for (dy in -rad..rad) for (dx in -rad..rad) {
                val c = f.pixel(cx + dx, cy + dy)
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
            }
            return ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }

        fun colorAmount(f: Frame, region: RelRect, color: Int, tolerance: Int): Double {
            var hit = 0
            var n = 0
            forSamples(f, region, 2500) { _, _, c ->
                n++
                if (colorDist(c, color) <= tolerance) hit++
            }
            return if (n == 0) 0.0 else hit.toDouble() / n
        }

        inline fun forSamples(f: Frame, region: RelRect, maxSamples: Int, block: (Int, Int, Int) -> Unit) {
            val x0 = (region.x * f.width).toInt().coerceIn(0, f.width - 1)
            val y0 = (region.y * f.height).toInt().coerceIn(0, f.height - 1)
            val x1 = ((region.x + region.w) * f.width).toInt().coerceIn(x0 + 1, f.width)
            val y1 = ((region.y + region.h) * f.height).toInt().coerceIn(y0 + 1, f.height)
            val area = (x1 - x0).toDouble() * (y1 - y0)
            val stride = sqrt(area / maxSamples).toInt().coerceAtLeast(1)
            var y = y0
            while (y < y1) {
                var x = x0
                val row = y * f.width
                while (x < x1) {
                    block(x, y, f.argb[row + x])
                    x += stride
                }
                y += stride
            }
        }
    }
}
