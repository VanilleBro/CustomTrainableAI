package com.customtrainableai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.customtrainableai.engine.PracticeGame
import com.customtrainableai.engine.PracticeLayout
import com.customtrainableai.model.PracticeKind
import com.customtrainableai.model.RelRect

private fun c(v: Int) = Color(v)

/**
 * Das Übungsspiel als Oberfläche. Steuerung per Maus/Touch, Tasten (A/D, Pfeile, W/Leertaste) oder durch die KI.
 * [onPlaced] meldet die Lage der Spielfläche (für "Bereich an Übungsspiel anpassen").
 */
@Composable
fun PracticeGameView(
    kind: PracticeKind,
    modifier: Modifier = Modifier,
    onPlaced: (LayoutCoordinates) -> Unit = {},
    onGame: (PracticeGame) -> Unit = {},
) {
    val game = remember(kind) { PracticeGame(kind) }
    LaunchedEffect(game) { onGame(game) }
    var tick by remember { mutableLongStateOf(0L) }
    val focus = remember { FocusRequester() }
    val measurer = rememberTextMeasurer()

    LaunchedEffect(game) {
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                if (last != 0L) {
                    // Physik in kleinen Schritten, unabhängig von der Bildrate
                    var dt = ((t - last) / 1e9).coerceAtMost(0.1)
                    while (dt > 0) {
                        val d = minOf(dt, 1.0 / 120)
                        game.update(d)
                        dt -= d
                    }
                }
                last = t
                tick = t
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Canvas(
        modifier.fillMaxSize()
            .onGloballyPositioned(onPlaced)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { ev ->
                val name = when (ev.key) {
                    Key.W -> "W"; Key.A -> "A"; Key.S -> "S"; Key.D -> "D"
                    Key.DirectionUp -> "UP"; Key.DirectionLeft -> "LEFT"; Key.DirectionDown -> "DOWN"; Key.DirectionRight -> "RIGHT"
                    Key.Spacebar -> "SPACE"; Key.Enter -> "ENTER"
                    else -> null
                } ?: return@onKeyEvent false
                when (ev.type) {
                    KeyEventType.KeyDown -> game.key(name, true)
                    KeyEventType.KeyUp -> game.key(name, false)
                }
                true
            }
            .pointerInput(game) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull() ?: continue
                        val x = ch.position.x / size.width
                        val y = ch.position.y / size.height
                        when (ev.type) {
                            PointerEventType.Press -> {
                                runCatching { focus.requestFocus() }
                                game.pointerDown(x, y)
                            }
                            PointerEventType.Move -> if (ch.pressed) game.pointerMove(x, y)
                            PointerEventType.Release -> game.pointerUp()
                        }
                    }
                }
            },
    ) {
        @Suppress("UNUSED_EXPRESSION") tick
        val L = PracticeLayout
        fun r(rr: RelRect) = Rect(rr.x * size.width, rr.y * size.height, (rr.x + rr.w) * size.width, (rr.y + rr.h) * size.height)
        fun fill(rr: RelRect, col: Int, round: Float = 0f) {
            val q = r(rr)
            drawRoundRect(c(col), q.topLeft, q.size, CornerRadius(round, round))
        }
        val text = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)

        drawRect(c(L.C_BG))
        fill(RelRect(0f, L.FIELD_TOP, 1f, L.FIELD_BOTTOM - L.FIELD_TOP), L.C_FIELD)
        fill(RelRect(0f, L.CONTROL_TOP, 1f, 1f - L.CONTROL_TOP), L.C_CONTROL)
        fill(L.STATUS, if (game.over) L.C_OVER else L.C_OK, 4f)
        val score = measurer.measure("${game.score}", text.copy(fontSize = 20.sp))
        drawText(score, topLeft = Offset((L.SCORE.x + L.SCORE.w) * size.width - score.size.width, L.SCORE.y * size.height))
        when (kind) {
            PracticeKind.CATCH -> {
                for (i in 0 until game.lives) fill(RelRect(L.LIVES.x + i * 0.07f, L.LIVES.y, 0.05f, L.LIVES.h), L.C_BALL, 3f)
                fill(L.LEFT_BTN, if (game.holdLeft) L.C_BUTTON_ON else L.C_BUTTON, 14f)
                fill(L.RIGHT_BTN, if (game.holdRight) L.C_BUTTON_ON else L.C_BUTTON, 14f)
                fill(L.JOY_PAD, L.C_BUTTON, 40f)
                centered("◀", r(L.LEFT_BTN), measurer, text)
                centered("▶", r(L.RIGHT_BTN), measurer, text)
                drawCircle(c(L.C_BUTTON_ON), size.width * 0.035f, Offset((L.JOY_CENTER.x + game.joyDx * L.JOY_RANGE) * size.width, L.JOY_CENTER.y * size.height))
                fill(RelRect(game.paddleX - L.PADDLE_W / 2, L.PADDLE_Y, L.PADDLE_W, L.PADDLE_H), L.C_PADDLE, 6f)
                drawCircle(c(L.C_BALL), L.BALL_R * size.width, Offset(game.ballX * size.width, game.ballY * size.height))
            }
            PracticeKind.JUMP -> {
                fill(L.JUMP_BTN, if (game.jumpHeld) L.C_BUTTON_ON else L.C_BUTTON, 14f)
                centered("SPRUNG (tippen / halten)", r(L.JUMP_BTN), measurer, text.copy(fontSize = 13.sp))
                for (p in game.pipes) {
                    fill(RelRect(p.x, L.FIELD_TOP, L.PIPE_W, p.gapY - L.GAP / 2 - L.FIELD_TOP), L.C_PIPE)
                    fill(RelRect(p.x, p.gapY + L.GAP / 2, L.PIPE_W, L.FIELD_BOTTOM - (p.gapY + L.GAP / 2)), L.C_PIPE)
                }
                drawOval(
                    c(L.C_BIRD),
                    Offset((L.BIRD_X - L.BIRD_RX) * size.width, (game.birdY - L.BIRD_RY) * size.height),
                    Size(L.BIRD_RX * 2 * size.width, L.BIRD_RY * 2 * size.height),
                )
            }
        }
        if (game.over) {
            fill(L.RESTART, L.C_RESTART, 12f)
            centered("Neustart", r(L.RESTART), measurer, text)
        } else if (game.readyLeft > 0f) {
            centered("Los!", r(RelRect(0.3f, 0.3f, 0.4f, 0.1f)), measurer, text.copy(fontSize = 22.sp))
        }
    }
}

private fun DrawScope.centered(s: String, box: Rect, m: androidx.compose.ui.text.TextMeasurer, style: TextStyle) {
    val l = m.measure(s, style)
    drawText(l, topLeft = Offset(box.center.x - l.size.width / 2, box.center.y - l.size.height / 2))
}
