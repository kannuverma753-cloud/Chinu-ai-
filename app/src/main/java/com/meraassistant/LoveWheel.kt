package com.meraassistant

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** 🎡 The spinning love wheel */
class LoveWheelView(ctx: Context) : android.view.View(ctx) {
    var rot = 0f
    private val d = ctx.resources.displayMetrics.density
    private val seg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xEEFFFFFF.toInt() }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = 0xFFFFFFFF.toInt() }
    private val hub = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF2E7E.toInt() }
    private val r = RectF()
    private val colors = intArrayOf(0xFFFF5C9A.toInt(), 0xFFB06BFF.toInt(), 0xFFFF8E6B.toInt(), 0xFF6B8CFF.toInt(),
        0xFFFF6FD8.toInt(), 0xFF8E5CFF.toInt(), 0xFFFFA94D.toInt(), 0xFF4FC3F7.toInt())

    override fun onMeasure(w: Int, h: Int) {
        val s = MeasureSpec.getSize(w)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val rad = min(cx, cy) - 14 * d
        val n = LoveWheel.SEGS.size
        val sweep = 360f / n
        r.set(cx - rad, cy - rad, cx + rad, cy + rad)
        c.save()
        c.rotate(rot, cx, cy)
        for (i in 0 until n) {
            seg.color = colors[i % colors.size]
            c.drawArc(r, -90f + i * sweep, sweep, true, seg)
            // emoji + word along the middle of the slice
            val a = Math.toRadians((-90f + i * sweep + sweep / 2).toDouble())
            val tx = cx + cos(a).toFloat() * rad * 0.66f
            val ty = cy + sin(a).toFloat() * rad * 0.66f
            c.save()
            c.rotate(-90f + i * sweep + sweep / 2 + 90f, tx, ty)
            txt.textSize = rad * 0.17f
            c.drawText(LoveWheel.SEGS[i].first, tx, ty, txt)
            txt.textSize = rad * 0.085f
            txt.isFakeBoldText = true
            c.drawText(LoveWheel.label(context, i), tx, ty + rad * 0.12f, txt)
            txt.isFakeBoldText = false
            c.restore()
        }
        rim.strokeWidth = 5 * d
        c.drawCircle(cx, cy, rad, rim)
        c.restore()
        hub.shader = android.graphics.RadialGradient(cx - rad * 0.05f, cy - rad * 0.05f, rad * 0.2f,
            0xFFFFFFFF.toInt(), 0xFFFFD1E6.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rad * 0.16f, hub)
        txt.textSize = rad * 0.15f
        c.drawText("💖", cx, cy + rad * 0.055f, txt)
        // pointer at the top
        val p = Path()
        p.moveTo(cx, cy - rad + 22 * d)
        p.lineTo(cx - 14 * d, cy - rad - 10 * d)
        p.lineTo(cx + 14 * d, cy - rad - 10 * d)
        p.close()
        c.drawPath(p, pin)
    }
}

object LoveWheel {
    /** emoji, English, Hinglish */
    val SEGS = listOf(
        Triple("😘", "Kiss", "Kiss"), Triple("🤗", "Hug", "Jhappi"), Triple("💌", "Compliment", "Tareef"),
        Triple("🎵", "Sing", "Gaana"), Triple("🙈", "Truth", "Sach"), Triple("😜", "Dare", "Dare"),
        Triple("📸", "Selfie", "Selfie"), Triple("💭", "Memory", "Yaad")
    )

    fun label(ctx: Context, i: Int) = if (Ui.hinglish(ctx)) SEGS[i].third else SEGS[i].second

    private val TRUTHS = listOf(
        "What was the first thing you noticed about me?" to "Mujhme sabse pehle tumne kya notice kiya tha?",
        "When did you realise you liked me?" to "Tumhe kab pata chala ki tum mujhe pasand karte ho?",
        "What's your favourite photo of us?" to "Hamari kaun si photo tumhari favourite hai?",
        "What do you miss most when we're apart?" to "Jab hum door hote hain to sabse zyada kya miss karte ho?",
        "What's one thing you want us to do together?" to "Ek cheez batao jo tum mere saath karna chahte ho?",
        "What nickname of mine do you love the most?" to "Mera kaun sa nickname tumhe sabse accha lagta hai?"
    )
    private val DARES = listOf(
        "Call and say ‘I love you’ in a funny voice 😂" to "Call karke funny awaaz mein ‘I love you’ bolo 😂",
        "Send your cutest selfie right now 🤳" to "Abhi apni sabse cute selfie bhejo 🤳",
        "Send a voice note singing one romantic line 🎤" to "Ek romantic line gaa ke voice note bhejo 🎤",
        "Write 5 things you love about your partner 📝" to "Partner ki 5 achhi baatein likh ke bhejo 📝",
        "Make a heart with your hands in a photo 🫶" to "Haathon se dil banake photo bhejo 🫶",
        "Plan our next date in 1 minute ⏱️" to "1 minute mein agli date plan karo ⏱️"
    )

    /** What to do for slice i (r = shared random number so both phones show the same thing) */
    fun task(ctx: Context, i: Int, r: Int, other: String): String {
        val h = Ui.hinglish(ctx)
        fun pick(l: List<Pair<String, String>>) = l[Math.floorMod(r, l.size)].let { if (h) it.second else it.first }
        return when (i) {
            0 -> if (h) "Kiss time! 😘 $other ko ek kiss chala gaya 💋" else "Kiss time! 😘 A kiss just flew to $other 💋"
            1 -> if (h) "Jhappi time! 🤗 $other ko virtual hug bhej diya — agli baar asli wali!" else "Hug time! 🤗 A virtual hug went to $other — a real one next time!"
            2 -> if (h) "$other ki 3 pyaari tareefein karo 💌" else "Give $other 3 sweet compliments 💌"
            3 -> if (h) "$other ke liye unka favourite gaana gungunao 🎵" else "Sing $other their favourite song 🎵"
            4 -> "🙈 " + pick(TRUTHS)
            5 -> "😜 " + pick(DARES)
            6 -> if (h) "Abhi ek cute selfie $other ko bhejo 📸" else "Send $other a cute selfie right now 📸"
            else -> if (h) "Hamari sabse pyaari yaad batao 💭" else "Share your favourite memory together 💭"
        }
    }
}

class WheelActivity : Activity() {
    private lateinit var wheel: LoveWheelView
    private lateinit var result: TextView
    private lateinit var fx: LoveFx
    private var spinning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Speaker.load(this)
        val root = FrameLayout(this)
        root.addView(GlassBackground(this, GlassBackground.LOVE))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Ui.dp(context, 20f), Ui.dp(context, 44f), Ui.dp(context, 20f), Ui.dp(context, 20f))
        }
        val p = Profile.load(this)
        col.addView(Ui.label(this, "🎡  Love Wheel", 30f, 0xFF3B1F4A.toInt(), true))
        col.addView(Ui.label(this, Ui.t(this, "${p.partner} & ${p.name} · spin and do what it says!",
            "${p.partner} & ${p.name} · ghumao aur jo aaye wo karo!"), 14f, 0xFF6B4E7A.toInt()).apply {
            gravity = Gravity.CENTER; setPadding(0, Ui.dp(context, 4f), 0, Ui.dp(context, 14f))
        })
        wheel = LoveWheelView(this)
        col.addView(wheel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        result = Ui.label(this, Ui.t(this, "Tap Spin 💫", "Spin dabao 💫"), 17f, 0xFF3B1F4A.toInt(), true).apply {
            gravity = Gravity.CENTER
            background = Ui.glass(this@WheelActivity, 24f, 0x77)
            setPadding(Ui.dp(context, 16f), Ui.dp(context, 16f), Ui.dp(context, 16f), Ui.dp(context, 16f))
        }
        col.addView(result, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(this@WheelActivity, 16f)
        })
        col.addView(Ui.primaryButton(this, Ui.t(this, "💫  Spin!", "💫  Ghumao!")) { spinMine() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(this@WheelActivity, 14f)
            })
        root.addView(col)
        fx = LoveFx(this)
        root.addView(fx, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        // opened from the partner's spin notification
        if (intent.hasExtra("i")) wheel.post { spin(intent.getIntExtra("i", 0), intent.getIntExtra("r", 0), intent.getStringExtra("by"), false) }
    }

    override fun onResume() {
        super.onResume()
        current = this
        CoupleLink.start(this)
    }

    override fun onPause() {
        if (current === this) current = null
        super.onPause()
    }

    private fun spinMine() {
        if (spinning) return
        val i = (0 until LoveWheel.SEGS.size).random()
        val r = (0..999).random()
        CoupleLink.send(this, "wheel", JSONObject().put("i", i).put("r", r))
        spin(i, r, null, true)
    }

    /** by = who spun (null = me). Both phones animate to the same slice. */
    fun spin(i: Int, r: Int, by: String?, mine: Boolean) {
        if (spinning) return
        spinning = true
        val n = LoveWheel.SEGS.size
        val sweep = 360f / n
        // slice i must end under the pointer (top): rotation = -(i*sweep + sweep/2)
        val target = 360f * 6 - (i * sweep + sweep / 2)
        val start = wheel.rot % 360f
        result.text = if (by != null) Ui.t(this, "$by is spinning… 💫", "$by ghuma rahe hain… 💫") else "💫 …"
        ValueAnimator.ofFloat(start, target + (if (target < start) 360f else 0f)).apply {
            duration = 3600
            interpolator = DecelerateInterpolator(2.2f)
            addUpdateListener {
                wheel.rot = it.animatedValue as Float
                wheel.invalidate()
            }
            doOnEnd { landed(i, r, mine) }
            start()
        }
    }

    private fun ValueAnimator.doOnEnd(f: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: android.animation.Animator) = f()
        })
    }

    private fun landed(i: Int, r: Int, mine: Boolean) {
        spinning = false
        val p = Profile.load(this)
        val other = Speaker.other(p)
        val text = LoveWheel.task(this, i, r, if (mine) other else Speaker.name(p))
        result.text = text
        fx.burst(LoveWheel.SEGS[i].first, 22)
        // Kiss / Hug slices really send a kiss / hug to the partner
        if (mine && (i == 0 || i == 1)) CoupleLink.send(this, "love", JSONObject().put("k", if (i == 0) "kiss" else "hug"))
        try {
            getSystemService(android.os.Vibrator::class.java)?.vibrate(android.os.VibrationEffect.createOneShot(60, 180))
        } catch (e: Exception) {
        }
    }

    companion object {
        @Volatile
        var current: WheelActivity? = null

        fun intentFor(ctx: Context, i: Int, r: Int, by: String) =
            Intent(ctx, WheelActivity::class.java).putExtra("i", i).putExtra("r", r).putExtra("by", by)
    }
}
