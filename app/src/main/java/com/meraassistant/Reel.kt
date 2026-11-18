package com.meraassistant

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.sin

/**
 * 🎬 Selfie Reel — every selfie you two sent, played like Instagram stories:
 * progress bars on top, slow "Ken Burns" zoom, polaroids flying in, crossfades.
 * Tap right = next · tap left = back · hold = pause.
 */
class ReelActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var bg: ImageView
    private lateinit var stage: FrameLayout
    private lateinit var bars: LinearLayout
    private lateinit var fx: LoveFx
    private val fills = ArrayList<View>()
    private var days: List<Long> = emptyList()
    private var idx = -1
    private var anim: ValueAnimator? = null
    private var current: View? = null
    private var motion: View? = null
    private var downAt = 0L
    private val script: Typeface by lazy { Typeface.create("cursive", Typeface.BOLD) }

    /** slide 0 = intro, 1..n = days, n+1 = outro */
    private fun count() = days.size + 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Speaker.load(this)
        days = SelfieStreak.reelDays(this, intent.getIntExtra("back", 60))
        if (days.isEmpty()) {
            Toast.makeText(this, Ui.t(this, "No selfies yet 📸", "Abhi koi selfie nahi hai 📸"), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        root = FrameLayout(this).apply { setBackgroundColor(0xFF120B22.toInt()) }
        bg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.55f }
        root.addView(bg, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x66000000, 0x22000000, 0xAA000000.toInt()))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        stage = FrameLayout(this)
        root.addView(stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fx = LoveFx(this)
        root.addView(fx, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // story-style progress bars
        bars = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10f), dp(34f), dp(10f), 0)
        }
        for (i in 0 until count()) {
            val seg = FrameLayout(this).apply {
                background = GradientDrawable().apply { setColor(0x55FFFFFF); cornerRadius = dp(2f).toFloat() }
            }
            val fill = View(this).apply {
                background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(2f).toFloat() }
                pivotX = 0f
                scaleX = 0f
            }
            seg.addView(fill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            fills.add(fill)
            bars.addView(seg, LinearLayout.LayoutParams(0, dp(3f), 1f).apply { leftMargin = dp(2f); rightMargin = dp(2f) })
        }
        root.addView(bars, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        root.addView(TextView(this).apply {
            text = "✕"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
            setOnClickListener { finish() }
        }, FrameLayout.LayoutParams(dp(52f), dp(52f), Gravity.TOP or Gravity.END).apply { topMargin = dp(44f) })

        // tap left/right · hold to pause
        root.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = SystemClock.uptimeMillis()
                    anim?.pause()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val quick = SystemClock.uptimeMillis() - downAt < 250
                    anim?.resume()
                    if (quick && e.action == MotionEvent.ACTION_UP) {
                        if (e.x < v.width / 3f) go(idx - 1) else go(idx + 1)
                    }
                    v.performClick()
                }
            }
            true
        }
        setContentView(root)
        go(0)
    }

    override fun onPause() {
        anim?.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        anim?.resume()
    }

    override fun onDestroy() {
        anim?.cancel()
        super.onDestroy()
    }

    private fun dp(v: Float) = Ui.dp(this, v)

    private fun go(i: Int) {
        if (i >= count()) return
        val to = i.coerceAtLeast(0)
        anim?.removeAllListeners()
        anim?.cancel()
        idx = to
        for ((k, f) in fills.withIndex()) f.scaleX = if (k < to) 1f else 0f

        // crossfade old → new
        current?.let { old ->
            old.animate().alpha(0f).scaleX(1.06f).scaleY(1.06f).setDuration(380).withEndAction { stage.removeView(old) }.start()
        }
        val slide = when (to) {
            0 -> intro()
            count() - 1 -> outro()
            else -> daySlide(days[to - 1])
        }
        slide.alpha = 0f
        stage.addView(slide, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        slide.animate().alpha(1f).setDuration(450).start()
        current = slide

        val length = when (to) {
            0 -> 3000L
            count() - 1 -> 4500L
            else -> 4200L
        }
        val fill = fills[to]
        val mover = motion
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = length
            interpolator = LinearInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                fill.scaleX = f
                // 🎥 Ken Burns: slow zoom + drift
                mover?.let { m ->
                    m.scaleX = 1f + 0.08f * f
                    m.scaleY = 1f + 0.08f * f
                    m.translationX = -dp(12f) * f
                    m.translationY = sin(f * 3.14f) * dp(6f)
                }
                bg.scaleX = 1.1f + 0.1f * f
                bg.scaleY = 1.1f + 0.1f * f
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (idx == to && to < count() - 1) go(to + 1)
                }
            })
            start()
        }
    }

    // ---------- slides ----------
    private fun intro(): View {
        val p = Profile.load(this)
        motion = null
        bg.setImageBitmap(blurOf(days.last()))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val clap = TextView(this).apply { text = "🎬"; textSize = 64f; gravity = Gravity.CENTER }
        col.addView(clap)
        col.addView(TextView(this).apply {
            text = "${p.partner} & ${p.name}"
            textSize = 42f
            typeface = script
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setShadowLayer(14f, 0f, 0f, 0xFFFF6FB1.toInt())
        })
        val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
        col.addView(Ui.label(this, Ui.t(this, "Our selfie reel · ${days.size} days", "Hamari selfie reel · ${days.size} din"), 17f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(8f), 0, dp(4f))
        })
        col.addView(Ui.label(this, LocalDate.ofEpochDay(days.first()).format(fmt) + "  →  " + LocalDate.ofEpochDay(days.last()).format(fmt),
            14f, 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER })
        clap.scaleX = 0.2f
        clap.scaleY = 0.2f
        clap.animate().scaleX(1f).scaleY(1f).rotation(-8f).setDuration(600).setInterpolator(OvershootInterpolator(2.5f)).start()
        fx.burst("💞", 16)
        return FrameLayout(this).apply {
            addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
    }

    private fun daySlide(day: Long): View {
        Speaker.load(this)
        val p = Profile.load(this)
        val k = SelfieStreak.dayKind(this, day)
        val a = SelfieStreak.image(this, k, "me", day, 900)
        val b = SelfieStreak.image(this, k, "them", day, 900)
        bg.setImageBitmap(blurOf(day))
        val frame = FrameLayout(this)
        val group = FrameLayout(this)
        frame.addView(group, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        motion = group
        val sw = resources.displayMetrics.widthPixels
        val cardW = (sw * 0.60f).toInt()
        val shots = listOfNotNull(a?.let { it to Speaker.name(p) }, b?.let { it to Speaker.other(p) })
        if (shots.size == 1) {
            val c = polaroid(shots[0].first, shots[0].second, cardW + dp(30f), -3f)
            group.addView(c, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                bottomMargin = dp(40f)
            })
            flyIn(c, 0f, dp(200f).toFloat(), 0)
        } else if (shots.size == 2) {
            val c1 = polaroid(shots[0].first, shots[0].second, cardW, -7f)
            val c2 = polaroid(shots[1].first, shots[1].second, cardW, 6f)
            group.addView(c1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                leftMargin = (sw * 0.05f).toInt(); topMargin = dp(90f)
            })
            group.addView(c2, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = (sw * 0.05f).toInt(); bottomMargin = dp(150f)
            })
            flyIn(c1, -sw.toFloat(), -dp(60f).toFloat(), 0)
            flyIn(c2, sw.toFloat(), dp(60f).toFloat(), 200)
        }

        // caption: date + streak day
        val run = SelfieStreak.runEndingAt(this, day)
        val badge = when {
            k == "x" && SelfieStreak.complete(this, day) -> "🛟 " + Ui.t(this, "Saved by a dare!", "Dare se bachayi!")
            run > 0 -> "🔥 " + Ui.t(this, "Day $run", "Din $run")
            else -> "💔 " + Ui.t(this, "Missed day", "Chhoota hua din")
        }
        val cap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(46f))
        }
        cap.addView(TextView(this).apply {
            text = LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH))
            textSize = 34f
            typeface = script
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setShadowLayer(12f, 0f, 0f, Color.BLACK)
        })
        cap.addView(Ui.label(this, badge, 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(0x44FFFFFF); cornerRadius = dp(18f).toFloat() }
            setPadding(dp(16f), dp(6f), dp(16f), dp(6f))
        })
        frame.addView(cap, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        cap.translationY = dp(40f).toFloat()
        cap.alpha = 0f
        cap.animate().translationY(0f).alpha(1f).setStartDelay(350).setDuration(500).setInterpolator(DecelerateInterpolator()).start()
        if (run > 0 && run % 7 == 0) fx.burst("🔥", 18) else fx.burst(if (k == "x") "🛟" else "💕", 6)
        return frame
    }

    private fun outro(): View {
        motion = null
        val s = SelfieStreak.streak(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(30f), 0, dp(30f), 0)
        }
        val big = TextView(this).apply {
            text = if (s > 0) "🔥 $s" else "💞"
            textSize = 64f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFB37A.toInt())
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }
        col.addView(big)
        col.addView(TextView(this).apply {
            text = Ui.t(this@ReelActivity, "This is just the beginning…", "Abhi toh bas shuruaat hai…")
            textSize = 30f
            typeface = script
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        })
        col.addView(Ui.label(this, Ui.t(this, "Keep the fire going, together 💞", "Aag jalaaye rakhna, saath mein 💞"), 15f, 0xDDFFFFFF.toInt()).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(8f), 0, dp(26f))
        })
        val again = TextView(this).apply {
            text = Ui.t(this@ReelActivity, "▶️  Watch again", "▶️  Phir se dekho")
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFF2A1B4D.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(24f).toFloat() }
            setPadding(dp(28f), dp(14f), dp(28f), dp(14f))
            setOnClickListener { go(0) }
        }
        col.addView(again)
        big.scaleX = 0.3f
        big.scaleY = 0.3f
        big.animate().scaleX(1f).scaleY(1f).setDuration(700).setInterpolator(OvershootInterpolator(2f)).start()
        fx.burst("🔥", 26)
        return FrameLayout(this).apply {
            addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
    }

    // ---------- pieces ----------
    private fun polaroid(b: Bitmap, name: String, width: Int, tilt: Float): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(6f).toFloat() }
            elevation = dp(10f).toFloat()
            setPadding(dp(9f), dp(9f), dp(9f), dp(2f))
            rotation = tilt
        }
        card.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(b)
        }, LinearLayout.LayoutParams(width - dp(18f), ((width - dp(18f)) * 1.2f).toInt()))
        card.addView(TextView(this).apply {
            text = name
            textSize = 24f
            typeface = script
            gravity = Gravity.CENTER
            setTextColor(0xFF3B1F4A.toInt())
        })
        return card
    }

    private fun flyIn(v: View, fromX: Float, fromY: Float, delay: Long) {
        val r = v.rotation
        v.translationX = fromX
        v.translationY = fromY
        v.rotation = r * 4f
        v.animate().translationX(0f).translationY(0f).rotation(r).setStartDelay(delay).setDuration(700)
            .setInterpolator(OvershootInterpolator(1.1f)).start()
    }

    /** a tiny version stretched to full screen = soft blurry background */
    private fun blurOf(day: Long): Bitmap? {
        val k = SelfieStreak.dayKind(this, day)
        return SelfieStreak.image(this, k, "them", day, 24) ?: SelfieStreak.image(this, k, "me", day, 24)
    }
}
