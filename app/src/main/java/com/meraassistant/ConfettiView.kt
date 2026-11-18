package com.meraassistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

/** 🎉 रंगीन confetti की बारिश (touch को नहीं रोकता) */
class ConfettiView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private class Piece(
        var x: Float, var y: Float, var vx: Float, val vy: Float,
        var rot: Float, val vr: Float, val color: Int, val w: Float, val h: Float, val phase: Float
    )

    private val pieces = mutableListOf<Piece>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colors = intArrayOf(
        0xFFFF4FD8.toInt(), 0xFF00C6FF.toInt(), 0xFFFFD166.toInt(),
        0xFF00E6A8.toInt(), 0xFF6A5CFF.toInt(), 0xFFFF6B6B.toInt(), 0xFFFFFFFF.toInt()
    )
    private var last = 0L
    private var clock = 0f

    fun burst(count: Int = 160) {
        if (width == 0 || height == 0) {
            post { burst(count) }
            return
        }
        val d = resources.displayMetrics.density
        repeat(count) {
            pieces.add(
                Piece(
                    x = Random.nextFloat() * width,
                    y = -Random.nextFloat() * height * 0.6f,
                    vx = (Random.nextFloat() - 0.5f) * 80f * d,
                    vy = (140f + Random.nextFloat() * 220f) * d,
                    rot = Random.nextFloat() * 360f,
                    vr = (Random.nextFloat() - 0.5f) * 500f,
                    color = colors[Random.nextInt(colors.size)],
                    w = (5f + Random.nextFloat() * 5f) * d,
                    h = (9f + Random.nextFloat() * 8f) * d,
                    phase = Random.nextFloat() * 6.28f
                )
            )
        }
        last = 0L
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pieces.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1000f).coerceAtMost(0.05f)
        last = now
        clock += dt
        val d = resources.displayMetrics.density
        for (p in pieces) {
            p.x += (p.vx + sin(clock * 3f + p.phase) * 40f * d) * dt
            p.y += p.vy * dt
            p.rot += p.vr * dt
            paint.color = p.color
            canvas.save()
            canvas.translate(p.x, p.y)
            canvas.rotate(p.rot)
            canvas.drawRect(-p.w / 2, -p.h / 2, p.w / 2, p.h / 2, paint)
            canvas.restore()
        }
        pieces.removeAll { it.y > height + 40 * d }
        if (pieces.isNotEmpty()) postInvalidateOnAnimation() else last = 0L
    }
}
