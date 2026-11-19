package com.customtrainableai.engine

import com.customtrainableai.model.PracticeKind
import com.customtrainableai.model.RelPoint
import com.customtrainableai.model.RelRect
import com.customtrainableai.model.ScreenRect
import kotlin.math.abs
import kotlin.random.Random

/** Feste Anordnung des Übungsspiels (relativ zur Spielfläche) – die Vorlagen-Profile benutzen dieselben Werte. */
object PracticeLayout {
    val STATUS = RelRect(0.03f, 0.02f, 0.08f, 0.045f)
    val STATUS_POINT = RelPoint(0.07f, 0.0425f)
    val SCORE = RelRect(0.45f, 0.012f, 0.52f, 0.065f)
    /** Leben (nur Fangen): drei Quadrate neben dem Statusfeld. */
    val LIVES = RelRect(0.14f, 0.02f, 0.2f, 0.045f)
    const val FIELD_TOP = 0.1f
    const val FIELD_BOTTOM = 0.8f
    const val CONTROL_TOP = 0.82f
    val LEFT_BTN = RelRect(0.03f, 0.84f, 0.26f, 0.14f)
    val RIGHT_BTN = RelRect(0.71f, 0.84f, 0.26f, 0.14f)
    val JOY_PAD = RelRect(0.35f, 0.84f, 0.30f, 0.14f)
    val JOY_CENTER = RelPoint(0.5f, 0.91f)
    const val JOY_RANGE = 0.12f
    val JUMP_BTN = RelRect(0.2f, 0.84f, 0.6f, 0.14f)
    val RESTART = RelRect(0.25f, 0.44f, 0.5f, 0.1f)
    val RESTART_POINT = RelPoint(0.5f, 0.49f)

    const val PADDLE_Y = 0.75f
    const val PADDLE_W = 0.24f
    const val PADDLE_H = 0.022f
    const val BALL_R = 0.035f
    const val BIRD_X = 0.25f
    const val BIRD_RX = 0.035f
    const val BIRD_RY = 0.022f
    const val PIPE_W = 0.15f
    const val GAP = 0.3f

    fun center(r: RelRect) = RelPoint(r.x + r.w / 2, r.y + r.h / 2)

    const val C_BG = 0xFF0F172A.toInt()
    const val C_FIELD = 0xFF13203A.toInt()
    const val C_CONTROL = 0xFF1E293B.toInt()
    const val C_OK = 0xFF22C55E.toInt()
    const val C_OVER = 0xFFEF4444.toInt()
    const val C_PADDLE = 0xFFF8FAFC.toInt()
    const val C_BALL = 0xFFFACC15.toInt()
    const val C_BIRD = 0xFFF97316.toInt()
    const val C_PIPE = 0xFF16A34A.toInt()
    const val C_BUTTON = 0xFF334155.toInt()
    const val C_BUTTON_ON = 0xFF64748B.toInt()
    const val C_RESTART = 0xFF3B82F6.toInt()
    const val C_TEXT = 0xFFFFFFFF.toInt()
}

/**
 * Eingebautes Übungsspiel (reine Logik, alle Koordinaten relativ 0..1).
 * FANGEN: Ball fällt, Schläger unten bewegen (Knöpfe links/rechts, Joystick, A/D).
 * SPRINGEN: Vogel durch Lücken steuern (Klick = Sprung, Halten = Schub).
 */
class PracticeGame(val kind: PracticeKind, seed: Int = Random.nextInt()) {
    private val rnd = Random(seed)
    var over = false; private set
    var score = 0; private set
    var time = 0.0; private set
    var lives = LIVES; private set
    /** Kurze Pause nach dem Neustart, bevor sich etwas bewegt. */
    var readyLeft = READY_TIME; private set

    var paddleX = 0.5f; private set
    var ballX = 0.5f; private set
    var ballY = 0.12f; private set

    var birdY = 0.45f; private set
    var birdV = 0f; private set

    class Pipe(var x: Float, val gapY: Float, var passed: Boolean = false)
    val pipes = mutableListOf<Pipe>()

    var holdLeft = false; private set
    var holdRight = false; private set
    var joyActive = false; private set
    var joyDx = 0f; private set
    var jumpHeld = false; private set
    private val keysDown = mutableSetOf<String>()

    init { restart() }

    fun restart() {
        over = false; score = 0; time = 0.0; lives = LIVES; readyLeft = READY_TIME
        paddleX = 0.5f; spawnBall()
        birdY = 0.45f; birdV = 0f
        pipes.clear(); pipes += Pipe(0.85f, randomGap())
        holdLeft = false; holdRight = false; joyActive = false; joyDx = 0f; jumpHeld = false
    }

    private fun spawnBall() {
        ballX = 0.1f + rnd.nextFloat() * 0.8f
        ballY = PracticeLayout.FIELD_TOP + 0.03f
    }

    private fun randomGap() = 0.35f + rnd.nextFloat() * 0.2f

    fun pointerDown(x: Float, y: Float) {
        if (over) {
            if (PracticeLayout.RESTART.contains(x, y)) restart()
            return
        }
        when (kind) {
            PracticeKind.CATCH -> when {
                PracticeLayout.LEFT_BTN.contains(x, y) -> { movePaddle(-STEP); holdLeft = true }
                PracticeLayout.RIGHT_BTN.contains(x, y) -> { movePaddle(STEP); holdRight = true }
                PracticeLayout.JOY_PAD.contains(x, y) -> { joyActive = true; joyDx = joyValue(x) }
            }
            PracticeKind.JUMP -> { jump(); jumpHeld = true }
        }
    }

    fun pointerMove(x: Float, @Suppress("UNUSED_PARAMETER") y: Float) {
        if (joyActive) joyDx = joyValue(x)
    }

    fun pointerUp() {
        holdLeft = false; holdRight = false; joyActive = false; joyDx = 0f; jumpHeld = false
    }

    fun tap(x: Float, y: Float) {
        pointerDown(x, y)
        pointerUp()
    }

    fun key(name: String, down: Boolean) {
        val k = name.uppercase()
        if (down) {
            if (k in keysDown) return
            keysDown += k
        } else {
            keysDown -= k
            return
        }
        if (over) {
            if (k == "SPACE" || k == "ENTER") restart()
            return
        }
        when (kind) {
            PracticeKind.CATCH -> {
                if (k == "A" || k == "LEFT") movePaddle(-STEP)
                if (k == "D" || k == "RIGHT") movePaddle(STEP)
            }
            PracticeKind.JUMP -> if (k == "W" || k == "UP" || k == "SPACE") jump()
        }
    }

    private fun held(vararg names: String) = names.any { it in keysDown }

    private fun joyValue(x: Float) = ((x - PracticeLayout.JOY_CENTER.x) / PracticeLayout.JOY_RANGE).coerceIn(-1f, 1f)

    private fun movePaddle(d: Float) {
        val half = PracticeLayout.PADDLE_W / 2
        paddleX = (paddleX + d).coerceIn(half, 1f - half)
    }

    private fun jump() {
        birdV = -JUMP_V
    }

    fun update(dt: Double) {
        if (over) return
        val d = dt.toFloat().coerceIn(0f, 0.1f)
        time += d
        if (readyLeft > 0f) {
            readyLeft -= d
            return
        }
        when (kind) {
            PracticeKind.CATCH -> {
                var v = 0f
                if (holdLeft || held("A", "LEFT")) v -= HOLD_SPEED
                if (holdRight || held("D", "RIGHT")) v += HOLD_SPEED
                if (joyActive) v += joyDx * JOY_SPEED
                movePaddle(v * d)
                ballY += (BALL_SPEED + 0.008f * score.coerceAtMost(30)) * d
                if (ballY >= PracticeLayout.PADDLE_Y) {
                    if (abs(ballX - paddleX) <= PracticeLayout.PADDLE_W / 2 + PracticeLayout.BALL_R * 0.6f) {
                        score++
                        spawnBall()
                    } else {
                        lives--
                        if (lives <= 0) {
                            ballY = PracticeLayout.PADDLE_Y
                            over = true
                        } else {
                            spawnBall()
                        }
                    }
                }
            }
            PracticeKind.JUMP -> {
                birdV += GRAVITY * d
                if (jumpHeld || held("W", "UP", "SPACE")) birdV -= THRUST * d
                birdV = birdV.coerceIn(-0.7f, 0.8f)
                birdY += birdV * d
                val speed = PIPE_SPEED + 0.004f * score.coerceAtMost(30)
                for (p in pipes) {
                    p.x -= speed * d
                    if (!p.passed && p.x + PracticeLayout.PIPE_W < PracticeLayout.BIRD_X - PracticeLayout.BIRD_RX) {
                        p.passed = true
                        score++
                    }
                }
                pipes.removeAll { it.x + PracticeLayout.PIPE_W < -0.05f }
                if ((pipes.lastOrNull()?.x ?: 0f) < 1f - PIPE_SPACING) pipes += Pipe(1.02f, randomGap())
                val top = birdY - PracticeLayout.BIRD_RY
                val bottom = birdY + PracticeLayout.BIRD_RY
                if (top < PracticeLayout.FIELD_TOP) { // Decke ist nicht tödlich
                    birdY = PracticeLayout.FIELD_TOP + PracticeLayout.BIRD_RY
                    birdV = 0f
                }
                if (bottom > PracticeLayout.FIELD_BOTTOM) over = true
                val left = PracticeLayout.BIRD_X - PracticeLayout.BIRD_RX
                val right = PracticeLayout.BIRD_X + PracticeLayout.BIRD_RX
                for (p in pipes) {
                    if (right > p.x && left < p.x + PracticeLayout.PIPE_W) {
                        if (top < p.gapY - PracticeLayout.GAP / 2 || bottom > p.gapY + PracticeLayout.GAP / 2) over = true
                    }
                }
            }
        }
    }

    companion object {
        const val LIVES = 3
        const val READY_TIME = 0.5f
        const val STEP = 0.07f
        const val HOLD_SPEED = 0.55f
        const val JOY_SPEED = 0.9f
        const val BALL_SPEED = 0.2f
        const val GRAVITY = 1.1f
        const val JUMP_V = 0.34f
        const val THRUST = 2.6f
        const val PIPE_SPEED = 0.22f
        const val PIPE_SPACING = 0.6f
    }
}

/** Zeichnet das Übungsspiel in ein Pixel-Array (für Simulation und Tests). */
object PracticeRenderer {
    private val DIGITS = arrayOf(
        "111101101101111", "010110010010111", "111001111100111", "111001111001111", "101101111001001",
        "111100111001111", "111100111101111", "111001001001001", "111101111101111", "111101111001111",
    )

    fun render(g: PracticeGame, w: Int, h: Int, out: IntArray = IntArray(w * h)): IntArray {
        val L = PracticeLayout
        fill(out, w, h, 0f, 0f, 1f, 1f, L.C_BG)
        fill(out, w, h, 0f, L.FIELD_TOP, 1f, L.FIELD_BOTTOM - L.FIELD_TOP, L.C_FIELD)
        fill(out, w, h, 0f, L.CONTROL_TOP, 1f, 1f - L.CONTROL_TOP, L.C_CONTROL)
        fill(out, w, h, L.STATUS.x, L.STATUS.y, L.STATUS.w, L.STATUS.h, if (g.over) L.C_OVER else L.C_OK)
        drawScore(out, w, h, g.score)
        if (g.kind == PracticeKind.CATCH) {
            for (i in 0 until g.lives) fill(out, w, h, L.LIVES.x + i * 0.07f, L.LIVES.y, 0.05f, L.LIVES.h, L.C_BALL)
        }
        when (g.kind) {
            PracticeKind.CATCH -> {
                fill(out, w, h, L.LEFT_BTN.x, L.LEFT_BTN.y, L.LEFT_BTN.w, L.LEFT_BTN.h, if (g.holdLeft) L.C_BUTTON_ON else L.C_BUTTON)
                fill(out, w, h, L.RIGHT_BTN.x, L.RIGHT_BTN.y, L.RIGHT_BTN.w, L.RIGHT_BTN.h, if (g.holdRight) L.C_BUTTON_ON else L.C_BUTTON)
                fill(out, w, h, L.JOY_PAD.x, L.JOY_PAD.y, L.JOY_PAD.w, L.JOY_PAD.h, L.C_BUTTON)
                fill(out, w, h, L.JOY_CENTER.x - 0.03f + g.joyDx * L.JOY_RANGE, L.JOY_CENTER.y - 0.02f, 0.06f, 0.04f, L.C_BUTTON_ON)
                fill(out, w, h, g.paddleX - L.PADDLE_W / 2, L.PADDLE_Y, L.PADDLE_W, L.PADDLE_H, L.C_PADDLE)
                circle(out, w, h, g.ballX, g.ballY, L.BALL_R, L.C_BALL)
            }
            PracticeKind.JUMP -> {
                fill(out, w, h, L.JUMP_BTN.x, L.JUMP_BTN.y, L.JUMP_BTN.w, L.JUMP_BTN.h, if (g.jumpHeld) L.C_BUTTON_ON else L.C_BUTTON)
                for (p in g.pipes) {
                    fill(out, w, h, p.x, L.FIELD_TOP, L.PIPE_W, p.gapY - L.GAP / 2 - L.FIELD_TOP, L.C_PIPE)
                    fill(out, w, h, p.x, p.gapY + L.GAP / 2, L.PIPE_W, L.FIELD_BOTTOM - (p.gapY + L.GAP / 2), L.C_PIPE)
                }
                fill(out, w, h, L.BIRD_X - L.BIRD_RX, g.birdY - L.BIRD_RY, L.BIRD_RX * 2, L.BIRD_RY * 2, L.C_BIRD)
            }
        }
        if (g.over) fill(out, w, h, L.RESTART.x, L.RESTART.y, L.RESTART.w, L.RESTART.h, L.C_RESTART)
        return out
    }

    private fun fill(out: IntArray, w: Int, h: Int, rx: Float, ry: Float, rw: Float, rh: Float, c: Int) {
        if (rw <= 0f || rh <= 0f) return
        val x0 = (rx * w).toInt().coerceIn(0, w)
        val y0 = (ry * h).toInt().coerceIn(0, h)
        val x1 = ((rx + rw) * w).toInt().coerceIn(0, w)
        val y1 = ((ry + rh) * h).toInt().coerceIn(0, h)
        for (y in y0 until y1) {
            val row = y * w
            for (x in x0 until x1) out[row + x] = c
        }
    }

    private fun circle(out: IntArray, w: Int, h: Int, cx: Float, cy: Float, r: Float, c: Int) {
        val pr = r * w
        val px = cx * w
        val py = cy * h
        val x0 = (px - pr).toInt().coerceIn(0, w - 1)
        val x1 = (px + pr).toInt().coerceIn(0, w - 1)
        val y0 = (py - pr).toInt().coerceIn(0, h - 1)
        val y1 = (py + pr).toInt().coerceIn(0, h - 1)
        for (y in y0..y1) for (x in x0..x1) {
            val dx = x + 0.5f - px
            val dy = y + 0.5f - py
            if (dx * dx + dy * dy <= pr * pr) out[y * w + x] = c
        }
    }

    private fun drawScore(out: IntArray, w: Int, h: Int, score: Int) {
        val L = PracticeLayout
        val text = score.toString()
        val cellH = L.SCORE.h * 0.8f / 5
        val cellW = cellH * h / w
        var x = L.SCORE.x + L.SCORE.w - text.length * cellW * 4
        val y = L.SCORE.y + L.SCORE.h * 0.1f
        for (ch in text) {
            val pattern = DIGITS[ch - '0']
            for (i in 0 until 15) if (pattern[i] == '1') {
                fill(out, w, h, x + (i % 3) * cellW, y + (i / 3) * cellH, cellW, cellH, L.C_TEXT)
            }
            x += cellW * 4
        }
    }
}

/** Simuliert Bildschirm + Eingabe mit Übungsspielen – zum Testen ohne echten Bildschirm. */
class PracticeSim(
    kind: PracticeKind,
    private val areas: List<ScreenRect>,
    private val clock: Clock,
    private val resW: Int = 100,
    private val resH: Int = 160,
    seed: Int = 1,
) : ScreenCapturer, InputController {
    val games = areas.indices.map { PracticeGame(kind, seed + it * 7919) }
    private val gameTime = DoubleArray(areas.size) { clock.now() }
    private val pressed = arrayOfNulls<Drag>(areas.size)
    private val keys = Array(areas.size) { mutableSetOf<String>() }

    /** Letztes Bild von Instanz 1 (für die Vorschau in der App). */
    @kotlin.concurrent.Volatile
    var lastFrame: Frame? = null
        private set

    /** Letztes Bild jeder Instanz (für die Agenten-Ansicht im Simulator). */
    val frames: Array<Frame?> = arrayOfNulls(areas.size)

    private fun advance(i: Int) {
        val now = clock.now()
        while (gameTime[i] < now) {
            val d = minOf(1.0 / 60, now - gameTime[i])
            games[i].update(d)
            gameTime[i] += d
        }
    }

    override suspend fun capture(areas: List<ScreenRect>): List<Frame?> {
        val used = BooleanArray(this.areas.size)
        return areas.map { a ->
            val i = this.areas.indices.firstOrNull { !used[it] && this.areas[it].normalized() == a } ?: return@map null
            used[i] = true
            advance(i)
            Frame(resW, resH, PracticeRenderer.render(games[i], resW, resH), a.w.toFloat() / resW).also {
                frames[i] = it
                if (i == 0) lastFrame = it
            }
        }
    }

    override suspend fun fullScreenshot(): Frame? = null

    private fun rel(i: Int, p: com.customtrainableai.model.ScreenPoint): Pair<Float, Float> {
        val a = areas[i]
        return (p.x - a.x).toFloat() / a.w to (p.y - a.y).toFloat() / a.h
    }

    override suspend fun apply(commands: Map<Int, InstanceCommand>) {
        for (i in areas.indices) {
            val cmd = commands[i] ?: InstanceCommand.NONE
            advance(i)
            val g = games[i]
            val want = cmd.drag ?: cmd.holds.firstOrNull()?.let { Drag(it, it) }
            val cur = pressed[i]
            if (want == null && cur != null) g.pointerUp()
            if (want != null) {
                if (cur == null || cur.from != want.from) {
                    if (cur != null) g.pointerUp()
                    val (fx, fy) = rel(i, want.from)
                    g.pointerDown(fx, fy)
                }
                val (tx, ty) = rel(i, want.to)
                g.pointerMove(tx, ty)
            }
            pressed[i] = want
            for (t in cmd.taps) {
                val (x, y) = rel(i, t)
                if (want != null) g.pointerUp()
                g.tap(x, y)
                if (want != null) {
                    val (fx, fy) = rel(i, want.from); g.pointerDown(fx, fy)
                    val (tx, ty) = rel(i, want.to); g.pointerMove(tx, ty)
                }
            }
            for (k in keys[i].toList()) if (k !in cmd.keys) { g.key(k, false); keys[i] -= k }
            for (k in cmd.keys) if (k !in keys[i]) { g.key(k, true); keys[i] += k }
            for (k in cmd.keyTaps) { g.key(k, true); g.key(k, false) }
        }
    }

    override suspend fun releaseAll() {
        for (i in areas.indices) {
            games[i].pointerUp()
            pressed[i] = null
            for (k in keys[i]) games[i].key(k, false)
            keys[i].clear()
        }
    }
}

