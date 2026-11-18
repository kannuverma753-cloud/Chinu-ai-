package com.meraassistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * 🐦 Chinu's mascot: a realistic green parakeet (hara totta) on a branch.
 * Idle: gently bobs and blinks · Listening: flaps wings with your voice
 * Thinking: looks around with "..." · Speaking: beak moves while talking
 */
class BirdView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    var state: State = State.IDLE
        set(value) {
            field = value
            invalidate()
        }

    private var level = 0f
    private var targetLevel = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    private val green = 0xFF34C759.toInt()

    // realistic rose-ringed parakeet palette
    private val headHi = 0xFF7EDB5A.toInt()
    private val headLo = 0xFF3FAE3A.toInt()
    private val bodyHi = 0xFF6CCB4E.toInt()
    private val bodyLo = 0xFF2E8B37.toInt()
    private val bellyHi = 0xFFC7EC86.toInt()
    private val bellyLo = 0xFF8FCB5A.toInt()
    private val wingHi = 0xFF3FA845.toInt()
    private val wingLo = 0xFF1F6E33.toInt()
    private val flight = 0xFF1C5C4A.toInt()
    private val flightTip = 0xFF2E6F86.toInt()
    private val tailHi = 0xFF4FB866.toInt()
    private val tailLo = 0xFF3C7F96.toInt()
    private val beakHi = 0xFFE8383A.toInt()
    private val beakLo = 0xFFA51C22.toInt()
    private val beakDark = 0xFF5E0E12.toInt()
    private val barkHi = 0xFF8A6A4E.toInt()
    private val barkLo = 0xFF4E3826.toInt()
    private val feetC = 0xFF9A8F8A.toInt()

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            level += (targetLevel - level) * 0.25f
            if (state != State.LISTENING) targetLevel *= 0.9f
            invalidate()
        }
    }

    /** Pass SpeechRecognizer's onRmsChanged here */
    fun setLevel(rmsDb: Float) {
        targetLevel = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    private fun alpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    private fun lg(x0: Float, y0: Float, x1: Float, y1: Float, a: Int, b: Int) =
        LinearGradient(x0, y0, x1, y1, a, b, Shader.TileMode.CLAMP)

    private fun rg(x: Float, y: Float, rad: Float, a: Int, b: Int) =
        RadialGradient(x, y, rad, a, b, Shader.TileMode.CLAMP)

    private fun fill(c: Int) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = c
    }

    private fun stroke(c: Int, w: Float) {
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = w
        paint.color = c
    }

    /**
     * Drawn in a 100 x 100 box: a green parakeet (hara totta) perched on a branch,
     * facing right, long tail hanging down to the left.
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val t = SystemClock.uptimeMillis() / 1000f
        val s = min(width, height).toFloat()

        canvas.save()
        canvas.translate((width - s) / 2f, (height - s) / 2f)
        canvas.scale(s / 100f, s / 100f)

        // soft rings while listening / speaking
        if (state == State.LISTENING || state == State.SPEAKING) {
            for (k in 0 until 2) {
                val pr = (t * 0.7f + k * 0.5f) % 1f
                stroke(alpha(green, ((1f - pr) * 90).toInt()), 1.6f)
                canvas.drawCircle(52f, 50f, 40f + pr * 9f + level * 5f, paint)
            }
        }

        // ---------- branch ----------
        path.reset()
        path.moveTo(2f, 84f)
        path.cubicTo(30f, 79f, 60f, 80f, 98f, 72f)
        path.lineTo(98f, 77f)
        path.cubicTo(60f, 86f, 30f, 85f, 2f, 90f)
        path.close()
        paint.style = Paint.Style.FILL
        paint.shader = lg(0f, 76f, 0f, 90f, barkHi, barkLo)
        canvas.drawPath(path, paint)
        stroke(0x44000000, 0.5f)
        canvas.drawLine(20f, 85f, 34f, 83.4f, paint)
        canvas.drawLine(66f, 80.5f, 80f, 77.6f, paint)
        // a small leaf on the branch
        path.reset()
        path.moveTo(84f, 75f)
        path.quadTo(90f, 64f, 97f, 62f)
        path.quadTo(94f, 71f, 84f, 75f)
        paint.style = Paint.Style.FILL
        paint.shader = lg(84f, 75f, 97f, 62f, 0xFF5E9E3A.toInt(), 0xFF2F6B24.toInt())
        canvas.drawPath(path, paint)

        // ---------- bird (bobs a little) ----------
        val bob = when (state) {
            State.IDLE -> sin(t * 2f) * 0.8f
            State.LISTENING -> -abs(sin(t * 5f)) * (0.6f + level * 3f)
            State.THINKING -> sin(t * 3f) * 0.6f
            State.SPEAKING -> sin(t * 7f) * 0.6f
        }
        canvas.save()
        canvas.translate(0f, bob)

        // tail: two long central feathers + side feathers
        val sway = sin(t * 1.3f) * 1.5f
        canvas.save()
        canvas.rotate(sway, 40f, 66f)
        for ((dx, len, c) in listOf(Triple(-3.5f, 0.82f, tailLo), Triple(3f, 0.86f, tailLo), Triple(0f, 1f, tailHi))) {
            path.reset()
            path.moveTo(37f + dx, 62f)
            path.cubicTo(30f + dx, 74f, 24f + dx, 86f, 40f - 26f * len + dx, 66f + 32f * len)
            path.cubicTo(28f + dx, 86f, 36f + dx, 74f, 44f + dx, 64f)
            path.close()
            paint.style = Paint.Style.FILL
            paint.shader = lg(40f, 62f, 14f, 98f, c, tailLo)
            canvas.drawPath(path, paint)
        }
        stroke(0x33000000, 0.35f)
        canvas.drawLine(40f, 64f, 15.5f, 97f, paint)
        canvas.restore()

        // feet gripping the branch
        stroke(feetC, 1.6f)
        for (fx in listOf(47f, 55f)) {
            val fy = 80.5f - (fx - 47f) * 0.18f - bob
            canvas.drawLine(fx, 72f, fx + 0.5f, fy - 0.5f, paint)
            path.reset()
            path.moveTo(fx - 2.6f, fy + 1.2f)
            path.quadTo(fx, fy - 1.6f, fx + 3f, fy + 1f)
            canvas.drawPath(path, paint)
        }

        // body: upright teardrop
        path.reset()
        path.moveTo(56f, 30f)
        path.cubicTo(70f, 34f, 70f, 58f, 60f, 70f)
        path.cubicTo(54f, 76f, 44f, 76f, 39f, 68f)
        path.cubicTo(34f, 58f, 40f, 38f, 56f, 30f)
        path.close()
        paint.style = Paint.Style.FILL
        paint.shader = rg(62f, 42f, 36f, bodyHi, bodyLo)
        canvas.drawPath(path, paint)

        // chest / belly: lighter yellow-green
        path.reset()
        path.moveTo(62f, 38f)
        path.cubicTo(70f, 46f, 66f, 64f, 58f, 72f)
        path.cubicTo(55f, 74f, 52f, 73f, 52f, 70f)
        path.cubicTo(58f, 60f, 60f, 48f, 62f, 38f)
        path.close()
        paint.shader = lg(66f, 40f, 54f, 72f, bellyHi, bellyLo)
        canvas.drawPath(path, paint)
        // soft feather scallops on the chest
        stroke(0x2A1F5A1F, 0.45f)
        for (row in 0 until 5) {
            val y = 46f + row * 5f
            val x0 = 63.5f - row * 1.2f
            for (k in 0 until 2) {
                rect.set(x0 - 3f - k * 3.4f, y - 1.6f, x0 - k * 3.4f, y + 1.6f)
                canvas.drawArc(rect, 20f, 140f, false, paint)
            }
        }

        // wing (folded along the side, lifts when listening)
        val flap = when (state) {
            State.LISTENING -> -abs(sin(t * 12f)) * (4f + level * 26f)
            State.SPEAKING -> sin(t * 8f) * 3f
            State.THINKING -> -2f + sin(t * 2f) * 1.5f
            State.IDLE -> sin(t * 1.5f) * 1.2f
        }
        canvas.save()
        canvas.rotate(flap, 55f, 40f)
        path.reset()
        path.moveTo(57f, 37f)
        path.cubicTo(62f, 46f, 56f, 62f, 44f, 76f)
        path.cubicTo(41f, 80f, 37f, 80f, 38f, 74f)
        path.cubicTo(40f, 60f, 44f, 44f, 57f, 37f)
        path.close()
        paint.style = Paint.Style.FILL
        paint.shader = lg(56f, 38f, 40f, 78f, wingHi, wingLo)
        canvas.drawPath(path, paint)
        // covert feather rows
        stroke(0x33103A1A, 0.5f)
        for (i in 0 until 4) {
            val y = 45f + i * 5f
            path.reset()
            path.moveTo(56f - i * 1.6f, y)
            path.quadTo(50f - i * 1.6f, y + 3f, 45f - i * 1.4f, y + 1.5f)
            canvas.drawPath(path, paint)
        }
        // long flight feathers (dark teal tips)
        for (i in 0 until 4) {
            path.reset()
            val ox = 49f - i * 2.2f
            val oy = 62f + i * 1.5f
            path.moveTo(ox + 3f, oy - 4f)
            path.quadTo(ox - 2f, oy + 8f, ox - 7f + i, oy + 15f - i * 1.2f)
            path.quadTo(ox + 1f, oy + 6f, ox + 5.5f, oy - 2f)
            path.close()
            paint.style = Paint.Style.FILL
            paint.shader = lg(ox + 3f, oy - 4f, ox - 6f, oy + 14f, flight, flightTip)
            canvas.drawPath(path, paint)
        }
        canvas.restore()

        // ---------- head (tilts while thinking / listening) ----------
        val tilt = when (state) {
            State.THINKING -> sin(t * 1.6f) * 9f
            State.LISTENING -> -6f
            else -> sin(t * 0.9f) * 2f
        }
        canvas.save()
        canvas.rotate(tilt, 56f, 34f)

        paint.style = Paint.Style.FILL
        paint.shader = rg(60f, 20f, 16f, headHi, headLo)
        canvas.drawCircle(59f, 25f, 12.5f, paint)
        // faint bluish sheen on the back of the head
        paint.shader = rg(54f, 22f, 6f, 0x4460A8C8, 0x0060A8C8)
        canvas.drawCircle(54f, 22f, 6f, paint)
        // cheek feathers
        stroke(0x221F5A1F, 0.45f)
        rect.set(58f, 26f, 66f, 33f)
        canvas.drawArc(rect, 20f, 120f, false, paint)

        // beak: hooked red upper mandible + dark lower one (opens while speaking)
        val open = when (state) {
            State.SPEAKING -> abs(sin(t * 11f)) * 16f
            State.LISTENING -> 4f
            else -> 0f
        }
        canvas.save()
        canvas.rotate(open, 69f, 29f)
        path.reset()
        path.moveTo(69f, 29.5f)
        path.quadTo(73f, 31.5f, 73.5f, 34.5f)
        path.quadTo(70.5f, 34.5f, 68f, 31.5f)
        path.close()
        fill(beakDark)
        canvas.drawPath(path, paint)
        canvas.restore()
        path.reset()
        path.moveTo(68f, 21.5f)
        path.cubicTo(74f, 20.5f, 79.5f, 25f, 78f, 33.5f)      // big curved hook
        path.quadTo(76.5f, 36f, 75.2f, 33f)
        path.cubicTo(74.5f, 30f, 72f, 29f, 68.5f, 30f)
        path.close()
        paint.style = Paint.Style.FILL
        paint.shader = lg(70f, 21f, 77f, 35f, beakHi, beakLo)
        canvas.drawPath(path, paint)
        // highlight on the beak
        stroke(0x66FFFFFF, 0.6f)
        path.reset()
        path.moveTo(70f, 23f)
        path.quadTo(74f, 23f, 76f, 27f)
        canvas.drawPath(path, paint)

        // eye: pale ring, cream iris, black pupil, sparkle; blinks
        val ex = 62.5f
        val ey = 22f
        val blink = if ((t % 3.7f) < 0.13f) 0.1f else 1f
        val look = if (state == State.THINKING) sin(t * 2.5f) * 0.6f else 0.3f
        fill(0xFFE9F3D6.toInt())
        rect.set(ex - 3.3f, ey - 3.3f * blink, ex + 3.3f, ey + 3.3f * blink)
        canvas.drawOval(rect, paint)
        fill(0xFFF3E7B8.toInt())
        rect.set(ex - 2.3f + look, ey - 2.3f * blink, ex + 2.3f + look, ey + 2.3f * blink)
        canvas.drawOval(rect, paint)
        fill(0xFF111111.toInt())
        rect.set(ex - 1.4f + look, ey - 1.4f * blink, ex + 1.4f + look, ey + 1.4f * blink)
        canvas.drawOval(rect, paint)
        if (blink > 0.5f) {
            fill(0xEEFFFFFF.toInt())
            canvas.drawCircle(ex + look + 0.6f, ey - 0.7f, 0.55f, paint)
        } else {
            stroke(headLo, 0.8f)
            canvas.drawLine(ex - 3.3f, ey, ex + 3.3f, ey, paint)
        }
        canvas.restore()      // head
        canvas.restore()      // bob

        // thinking dots
        if (state == State.THINKING) {
            for (i in 0 until 3) {
                val a = ((sin(t * 6f - i * 0.9f) + 1f) / 2f)
                fill(alpha(0xFF8E8E93.toInt(), (80 + a * 175).toInt()))
                canvas.drawCircle(70f + i * 5f, 8f - a * 1.2f, 1.6f, paint)
            }
        }
        paint.shader = null
        canvas.restore()
    }
}
