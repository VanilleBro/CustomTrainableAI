package com.customtrainableai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.customtrainableai.engine.Frame
import com.customtrainableai.model.ActionMode
import com.customtrainableai.model.Profile
import com.customtrainableai.model.RelPoint
import com.customtrainableai.model.RelRect
import com.customtrainableai.model.RewardRule
import com.customtrainableai.model.RuleType
import com.customtrainableai.model.ScreenRect
import com.customtrainableai.model.StepType
import com.customtrainableai.model.TrackGoal
import com.customtrainableai.model.colorToHex
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val C_INSTANCE = Color(0xFF22D3EE)
private val C_CLICK = Color(0xFFF87171)
private val C_JOY = Color(0xFFC084FC)
private val C_KEYS = Color(0xFFFBBF24)
private val C_REWARD = Color(0xFF4ADE80)
private val C_RESET = Color(0xFFFFFFFF)

/** Bearbeitungszustand des Editors (alles in Bildschirm-Einheiten). */
class EditorSession(val frame: Frame, val image: ImageBitmap, p: Profile, val touchOnly: Boolean) {
    val screenW = frame.width * frame.unitsPerPixel
    val screenH = frame.height * frame.unitsPerPixel

    var instances by mutableStateOf(
        p.instances.ifEmpty {
            listOf(ScreenRect((screenW * 0.3f).toInt(), (screenH * 0.2f).toInt(), (screenW * 0.4f).toInt(), (screenH * 0.6f).toInt()))
        },
    )
    var actions by mutableStateOf(p.actions)
    var rewards by mutableStateOf(p.rewards)
    var showRewards by mutableStateOf(true)
    var pipette by mutableStateOf(false)
    var selectedRule by mutableStateOf(-1)
    var toolbarTop by mutableStateOf(true)
    var info by mutableStateOf("Ziehe Rechtecke und Punkte an die richtige Stelle.")

    val area get() = instances[0]

    fun colorAt(sx: Float, sy: Float): Int =
        frame.pixel((sx / frame.unitsPerPixel).toInt(), (sy / frame.unitsPerPixel).toInt()) and 0xFFFFFF

    fun rel(p: Offset): RelPoint = area.rel(p.x, p.y)
    fun scr(p: RelPoint, a: ScreenRect = area) = Offset(a.x + p.x * a.w, a.y + p.y * a.h)
    fun scrRect(r: RelRect, a: ScreenRect = area) = Rect(a.x + r.x * a.w, a.y + r.y * a.h, a.x + (r.x + r.w) * a.w, a.y + (r.y + r.h) * a.h)
    fun relRect(r: Rect): RelRect {
        val a = area
        return RelRect((r.left - a.x) / a.w, (r.top - a.y) / a.h, r.width / a.w, r.height / a.h).normalized()
    }

    fun setInstance(i: Int, r: Rect) {
        val list = instances.toMutableList()
        list[i] = ScreenRect(r.left.roundToInt(), r.top.roundToInt(), max(20f, r.width).roundToInt(), max(20f, r.height).roundToInt())
        instances = list
    }

    fun addInstance() {
        val last = instances.last()
        var nx = last.x + last.w + 10
        var ny = last.y
        if (nx + last.w > screenW) { nx = instances[0].x; ny = last.y + last.h + 10 }
        if (ny + last.h > screenH) { nx = last.x + 30; ny = last.y + 30 }
        instances = instances + ScreenRect(nx, ny, last.w, last.h)
        info = "Instanz ${instances.size} hinzugefügt – auf das zweite Spielfenster ziehen."
    }

    fun removeInstance() {
        if (instances.size > 1) instances = instances.dropLast(1)
    }

    fun updateRule(i: Int, f: (RewardRule) -> RewardRule) {
        val list = rewards.rules.toMutableList()
        list[i] = f(list[i])
        rewards = rewards.copy(rules = list)
    }

    fun areaCrop(): Frame {
        val a = area
        val u = frame.unitsPerPixel
        return frame.crop((a.x / u).toInt(), (a.y / u).toInt(), (a.w / u).toInt(), (a.h / u).toInt())
    }
}

private class Handle(
    val pos: Offset,
    val color: Color,
    val label: String,
    val ruleIndex: Int = -1,
    val big: Boolean = true,
    val onMove: (Offset) -> Unit,
)

private data class Tf(val s: Float, val ox: Float, val oy: Float) {
    fun toCanvas(p: Offset) = Offset(ox + p.x * s, oy + p.y * s)
    fun toScreen(p: Offset) = Offset((p.x - ox) / s, (p.y - oy) / s)
}

private fun rectHandles(r: Rect, color: Color, label: String, ruleIndex: Int, set: (Rect) -> Unit): List<Handle> = listOf(
    Handle(r.center, color, label, ruleIndex, big = true) { c ->
        set(Rect(Offset(c.x - r.width / 2, c.y - r.height / 2), r.size))
    },
    Handle(r.topLeft, color, "", ruleIndex, big = false) { p ->
        set(Rect(min(p.x, r.right - 8), min(p.y, r.bottom - 8), r.right, r.bottom))
    },
    Handle(r.bottomRight, color, "", ruleIndex, big = false) { p ->
        set(Rect(r.left, r.top, max(p.x, r.left + 8), max(p.y, r.top + 8)))
    },
)

private fun buildHandles(e: EditorSession): List<Handle> {
    val out = ArrayList<Handle>()
    val a = e.actions
    // Belohnungs-Marker (zuerst = niedrigste Priorität beim Anfassen)
    if (e.showRewards) {
        e.rewards.rules.forEachIndexed { i, r ->
            val tag = "R${i + 1}"
            when (r.type) {
                RuleType.PIXEL_COLOR -> out += Handle(e.scr(r.point), C_REWARD, tag, i) { p -> e.updateRule(i) { it.copy(point = e.rel(p).clamped()) } }
                RuleType.REGION_CHANGE, RuleType.REGION_STILL, RuleType.COLOR_AMOUNT ->
                    out += rectHandles(e.scrRect(r.region), C_REWARD, tag, i) { nr -> e.updateRule(i) { it.copy(region = e.relRect(nr)) } }
                RuleType.OBJECT_TRACK -> {
                    out += rectHandles(e.scrRect(r.region), C_REWARD, "$tag Suche", i) { nr -> e.updateRule(i) { it.copy(region = e.relRect(nr)) } }
                    if (r.goal == TrackGoal.TARGET) out += Handle(e.scr(r.target), C_REWARD, "$tag Ziel", i) { p -> e.updateRule(i) { it.copy(target = e.rel(p).clamped()) } }
                    if (r.goal == TrackGoal.ZONE) out += rectHandles(e.scrRect(r.zone), C_REWARD, "$tag Zone", i) { nr -> e.updateRule(i) { it.copy(zone = e.relRect(nr)) } }
                }
                else -> {}
            }
        }
        e.rewards.episode.resetSteps.forEachIndexed { i, st ->
            if (st.type == StepType.CLICK) out += Handle(e.scr(st.point), C_RESET, "N${i + 1}") { p ->
                val steps = e.rewards.episode.resetSteps.toMutableList()
                steps[i] = st.copy(point = e.rel(p).clamped())
                e.rewards = e.rewards.copy(episode = e.rewards.episode.copy(resetSteps = steps))
            }
        }
    }
    // Aktionspunkte
    for (i in 0 until a.usedClickPoints) {
        val name = if (a.mode == ActionMode.HOLD_1) "Halten" else "${'A' + i}"
        out += Handle(e.scr(a.clickPoint(i)), C_CLICK, name) { p -> e.actions = e.actions.withClickPoint(i, e.rel(p)) }
    }
    if (a.usesJoystick) {
        val c = e.scr(a.joystickCenter)
        val r = a.joystickRadius * e.area.w
        out += Handle(c, C_JOY, "Joystick") { p -> e.actions = e.actions.copy(joystickCenter = e.rel(p).clamped()) }
        out += Handle(Offset(c.x + r, c.y), C_JOY, "Radius", big = false) { p ->
            val nr = (hypot(p.x - c.x, p.y - c.y) / e.area.w).coerceIn(0.01f, 0.5f)
            e.actions = e.actions.copy(joystickRadius = nr)
        }
    }
    if (a.usesKeys && e.touchOnly) {
        listOf("W", "A", "S", "D").forEachIndexed { i, k ->
            out += Handle(e.scr(a.keyPoint(i)), C_KEYS, k) { p -> e.actions = e.actions.withKeyPoint(i, e.rel(p)) }
        }
    }
    // Instanz-Rechtecke (zuletzt = höchste Priorität)
    e.instances.forEachIndexed { i, r ->
        out += rectHandles(Rect(r.x.toFloat(), r.y.toFloat(), r.right.toFloat(), r.bottom.toFloat()), C_INSTANCE, "Instanz ${i + 1}", -1) { nr -> e.setInstance(i, nr) }
    }
    return out
}

@Composable
fun EditorScreen(e: EditorSession, onDone: (apply: Boolean) -> Unit, onSaveShot: (() -> Unit)? = null) {
    val measurer = rememberTextMeasurer()
    var canvasSize by remember { mutableStateOf(IntSize(1, 1)) }
    val s = min(canvasSize.width / e.screenW, canvasSize.height / e.screenH).coerceAtLeast(0.01f)
    val tf = Tf(s, (canvasSize.width - e.screenW * s) / 2f, (canvasSize.height - e.screenH * s) / 2f)
    val tfState = rememberUpdatedState(tf)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(
            Modifier.fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(e) {
                    var grabbed: Handle? = null
                    var startPos = Offset.Zero
                    var startPointer = Offset.Zero
                    detectDragGestures(
                        onDragStart = { pt ->
                            val t = tfState.value
                            val sp = t.toScreen(pt)
                            val hit = 26f / t.s
                            val handles = buildHandles(e)
                            grabbed = handles.filter { hypot(it.pos.x - sp.x, it.pos.y - sp.y) <= hit }.minByOrNull { hypot(it.pos.x - sp.x, it.pos.y - sp.y) }
                            if (grabbed == null) {
                                // Klick in ein Instanz-Rechteck verschiebt es
                                val idx = e.instances.indexOfLast { sp.x >= it.x && sp.x <= it.right && sp.y >= it.y && sp.y <= it.bottom }
                                if (idx >= 0) {
                                    val r = e.instances[idx]
                                    val rect = Rect(r.x.toFloat(), r.y.toFloat(), r.right.toFloat(), r.bottom.toFloat())
                                    grabbed = Handle(rect.center, C_INSTANCE, "") { c -> e.setInstance(idx, Rect(Offset(c.x - rect.width / 2, c.y - rect.height / 2), rect.size)) }
                                }
                            }
                            grabbed?.let { if (it.ruleIndex >= 0) e.selectedRule = it.ruleIndex }
                            startPos = grabbed?.pos ?: sp
                            startPointer = sp
                        },
                        onDrag = { change, _ ->
                            val sp = tfState.value.toScreen(change.position)
                            grabbed?.onMove(startPos + (sp - startPointer))
                            change.consume()
                        },
                        onDragEnd = { grabbed = null },
                        onDragCancel = { grabbed = null },
                    )
                }
                .pointerInput(e) {
                    detectTapGestures { pt ->
                        val t = tfState.value
                        val sp = t.toScreen(pt)
                        val sel = e.selectedRule
                        if (e.pipette && sel >= 0 && sel < e.rewards.rules.size) {
                            val hex = colorToHex(e.colorAt(sp.x, sp.y))
                            e.updateRule(sel) { it.copy(color = hex) }
                            e.pipette = false
                            e.info = "Farbe $hex für „${e.rewards.rules[sel].displayName}“ übernommen."
                        } else {
                            val hit = 26f / t.s
                            val h = buildHandles(e).filter { it.ruleIndex >= 0 && hypot(it.pos.x - sp.x, it.pos.y - sp.y) <= hit }.minByOrNull { hypot(it.pos.x - sp.x, it.pos.y - sp.y) }
                            e.selectedRule = h?.ruleIndex ?: -1
                            val c = colorToHex(e.colorAt(sp.x, sp.y))
                            val r = e.area.rel(sp.x, sp.y)
                            e.info = "Punkt x=${com.customtrainableai.model.fmt(r.x, 3)} y=${com.customtrainableai.model.fmt(r.y, 3)} (relativ zu Instanz 1) · Farbe $c"
                        }
                    }
                },
        ) {
            drawImage(
                e.image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(e.image.width, e.image.height),
                dstOffset = IntOffset(tf.ox.roundToInt(), tf.oy.roundToInt()),
                dstSize = IntSize((e.screenW * s).roundToInt(), (e.screenH * s).roundToInt()),
            )
            drawEditor(e, tf, measurer)
        }

        // Werkzeugleiste
        Column(
            Modifier.align(if (e.toolbarTop) Alignment.TopCenter else Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(8.dp)
                .widthIn(max = 980.dp)
                .background(Color(0xE6111A2E), RoundedCornerShape(12.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ButtonRow {
                Button(onClick = { onDone(true) }) { Text("✓ Übernehmen") }
                OutlinedButton(onClick = { onDone(false) }) { Text("Abbrechen") }
                FilledTonalButton(onClick = { e.addInstance() }) { Text("+ Instanz") }
                FilledTonalButton(onClick = { e.removeInstance() }, enabled = e.instances.size > 1) { Text("− Instanz") }
                FilledTonalButton(onClick = { e.showRewards = !e.showRewards }) { Text(if (e.showRewards) "Belohnungen ausblenden" else "Belohnungen zeigen") }
                val sel = e.rewards.rules.getOrNull(e.selectedRule)
                FilledTonalButton(onClick = { e.pipette = !e.pipette }, enabled = sel?.usesColor == true) {
                    Text(if (e.pipette) "Pipette: jetzt tippen" else "Pipette")
                }
                onSaveShot?.let { FilledTonalButton(onClick = it) { Text("Bereich-Screenshot speichern") } }
                OutlinedButton(onClick = { e.toolbarTop = !e.toolbarTop }) { Text("⇅") }
            }
            val sel = e.rewards.rules.getOrNull(e.selectedRule)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sel != null) {
                    Text("R${e.selectedRule + 1}: ${sel.displayName}", color = C_REWARD, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    if (sel.usesColor) {
                        ColorSwatch(sel.color)
                        Text(sel.color, fontSize = 13.sp)
                    }
                }
                Text(e.info, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "Türkis = Instanzen (Mitte ziehen = verschieben, Ecken = Größe) · Rot = Klickpunkte · Lila = Joystick · Gelb = Tasten · Grün = Belohnungen (R…) · Weiß = Neustart-Klicks (N…)",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun DrawScope.drawEditor(e: EditorSession, tf: Tf, m: TextMeasurer) {
    fun label(text: String, at: Offset, color: Color) {
        if (text.isEmpty()) return
        val layout = m.measure(text, TextStyle(color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
        val tl = Offset(at.x + 10, at.y - layout.size.height - 4)
        drawRect(color, tl - Offset(3f, 1f), Size(layout.size.width + 6f, layout.size.height + 2f))
        drawText(layout, topLeft = tl)
    }

    fun rect(r: Rect, color: Color, width: Float = 2.5f, dashed: Boolean = false) {
        val a = tf.toCanvas(r.topLeft)
        val b = tf.toCanvas(r.bottomRight)
        drawRect(color, a, Size(b.x - a.x, b.y - a.y), style = Stroke(width, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 6f)) else null))
    }

    // Instanzen
    e.instances.forEachIndexed { i, r ->
        val rr = Rect(r.x.toFloat(), r.y.toFloat(), r.right.toFloat(), r.bottom.toFloat())
        drawRect(C_INSTANCE.copy(alpha = 0.06f), tf.toCanvas(rr.topLeft), Size(rr.width * tf.s, rr.height * tf.s))
        rect(rr, C_INSTANCE, 3f)
        label("Instanz ${i + 1}", tf.toCanvas(rr.topLeft) + Offset(-10f, 22f), C_INSTANCE)
    }
    val a = e.actions
    // Geister-Markierungen auf den anderen Instanzen
    for (inst in e.instances.drop(1)) {
        for (i in 0 until a.usedClickPoints) drawCircle(C_CLICK.copy(alpha = 0.45f), 7f, tf.toCanvas(e.scr(a.clickPoint(i), inst)))
        if (a.usesJoystick) drawCircle(C_JOY.copy(alpha = 0.45f), a.joystickRadius * inst.w * tf.s, tf.toCanvas(e.scr(a.joystickCenter, inst)), style = Stroke(2f))
    }
    if (a.usesJoystick) {
        drawCircle(C_JOY, a.joystickRadius * e.area.w * tf.s, tf.toCanvas(e.scr(a.joystickCenter)), style = Stroke(2.5f))
    }
    if (e.showRewards) {
        e.rewards.rules.forEachIndexed { i, r ->
            val sel = i == e.selectedRule
            val col = if (sel) Color.White else C_REWARD
            when (r.type) {
                RuleType.REGION_CHANGE, RuleType.REGION_STILL, RuleType.COLOR_AMOUNT -> rect(e.scrRect(r.region), col, if (sel) 3f else 2f, true)
                RuleType.OBJECT_TRACK -> {
                    rect(e.scrRect(r.region), col, 2f, true)
                    if (r.goal == TrackGoal.ZONE) rect(e.scrRect(r.zone), col, 2f)
                }
                else -> {}
            }
        }
    }
    // Griffe
    for (h in buildHandles(e)) {
        val c = tf.toCanvas(h.pos)
        if (h.big) {
            drawCircle(Color.Black.copy(alpha = 0.6f), 11f, c)
            drawCircle(h.color, 8f, c)
            label(h.label, c, h.color)
        } else {
            drawRect(h.color, c - Offset(6f, 6f), Size(12f, 12f))
        }
    }
}
