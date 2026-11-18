package com.meraassistant

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * 🌙 Bedtime story: Chinu writes a tiny, cosy story starring Chiku & Komal
 * (with today's mood and how many days you've been together), reads it softly,
 * and you can send it to your partner's phone so Chinu reads it to them at night.
 */
object Bedtime {
    /** emoji, English place, Hindi place */
    val THEMES = listOf(
        Triple("🌌", "under a sky full of stars", "तारों भरे आसमान के नीचे"),
        Triple("🏔️", "in a snowy mountain cabin", "बर्फ़ीले पहाड़ों के एक छोटे से घर में"),
        Triple("🌊", "on a quiet beach at night", "रात के शांत समुद्र किनारे पर"),
        Triple("🚀", "on a trip to the moon", "चाँद की सैर पर"),
        Triple("🐦", "on a magical adventure with Chinu the green parrot", "हरे तोते चिनू के साथ एक जादुई सफ़र पर"),
        Triple("🌧️", "on a rainy evening with chai", "बारिश वाली शाम, चाय के साथ")
    )

    /** Writes a story. Hinglish mode → simple Hindi (Devanagari) so the voice reads it beautifully. */
    fun write(ctx: Context, theme: Int, done: (String) -> Unit) {
        val app = ctx.applicationContext
        Speaker.load(app)
        val p = Profile.load(app)
        val hindi = Ui.hinglish(app)
        val t = THEMES[theme.coerceIn(0, THEMES.size - 1)]
        val days = Together.days(app)
        val pm = app.getSharedPreferences("couple", Context.MODE_PRIVATE).getInt("pMood", -1)
        val mood = if (pm in Mood.LIST.indices) "${Speaker.other(p)} is feeling ${Mood.LIST[pm].second} today. " else ""
        val main = Handler(Looper.getMainLooper())
        Thread {
            val k = Brain.key(app)
            val prompt = "Write a very short, warm, cosy bedtime story (110-150 words) starring ${p.partner} (boy) and ${p.name} (girl), " +
                "a real young couple from India" + (if (days > 0) " who have been together for $days days" else "") + ". " +
                "Setting: ${t.second}. $mood" +
                "Make it gentle, a little funny, magical and very loving, with a sleepy ending where they wish each other good night. " +
                (if (hindi) "Write it in simple, sweet spoken Hindi using Devanagari script. " else "Write it in simple English. ") +
                "No title, no lists, no emojis. Plain text only."
            val story = (if (k.isNotEmpty()) Brain.oneShot(k, prompt, 700, 0.9) else null)?.takeIf { it.length > 60 }
                ?: offline(p, hindi, t)
            main.post { done(story) }
        }.start()
    }

    private fun offline(p: Profile.Data, hindi: Boolean, t: Triple<String, String, String>): String =
        if (hindi) "एक बार की बात है, ${t.third}, ${p.partner} और ${p.name} साथ बैठे थे। " +
            "${p.name} ने कहा, \"आज का दिन बहुत लंबा था।\" ${p.partner} मुस्कुराए और बोले, \"तो चलो, सारी थकान यहीं छोड़ देते हैं।\" " +
            "तभी एक छोटा सा हरा तोता, चिनू, उड़ता हुआ आया और बोला, \"मैं तुम दोनों के सपनों का पहरेदार हूँ!\" " +
            "दोनों हँस पड़े। हवा धीरे-धीरे लोरी गाने लगी, और तारे एक-एक करके उनके लिए चमकने लगे। " +
            "${p.name} की आँखें भारी होने लगीं। ${p.partner} ने धीरे से कहा, \"गुड नाइट, मेरी जान। कल फिर मिलेंगे, सपनों में भी।\" " +
            "और चिनू ने अपने पंख फैलाकर दोनों के सपनों को प्यार से ढक दिया। शुभ रात्रि।"
        else "Once upon a time, ${t.second}, ${p.partner} and ${p.name} sat side by side. " +
            "\"Long day?\" asked ${p.partner}. ${p.name} nodded and rested her head on his shoulder. " +
            "Just then a little green parrot named Chinu fluttered down and announced, \"I am the official guard of your dreams tonight!\" " +
            "They both laughed. The wind began to hum a lullaby, and the stars switched on one by one, just for them. " +
            "${p.name}'s eyes grew heavy. ${p.partner} whispered, \"Good night, my love. See you in our dreams.\" " +
            "And Chinu spread his wings over both of them, keeping every bad dream far, far away. Good night."

    fun send(ctx: Context, text: String, done: (Boolean) -> Unit) =
        CoupleLink.send(ctx, "story", JSONObject().put("text", text.take(2500)), done)

    fun onStory(app: Context, d: JSONObject, from: String) {
        val text = d.optString("text")
        if (text.isBlank()) return
        app.getSharedPreferences("bedtime", Context.MODE_PRIVATE).edit()
            .putString("lastText", text).putString("lastFrom", from).putLong("lastAt", System.currentTimeMillis()).apply()
        val open = Intent(app, StoryActivity::class.java).putExtra("text", text).putExtra("from", from).putExtra("play", true)
        CoupleLink.notify(app, "🌙  " + Ui.t(app, "$from sent you a bedtime story", "$from ne aapke liye sone se pehle ki kahani bheji hai"),
            Ui.t(app, "Tap and Chinu will read it to you 💤", "Tap karo, Chinu aapko sunayegi 💤"), 460, open)
        CoupleLink.buzz(app, longArrayOf(0, 60, 80, 60))
    }
}

/** 🔊 Soft, slow voice for long texts (letters, stories) */
object Narrator {
    private var tts: TextToSpeech? = null
    private val main = Handler(Looper.getMainLooper())

    fun say(ctx: Context, text: String, onDone: (() -> Unit)? = null) {
        stop()
        val app = ctx.applicationContext
        val devanagari = text.any { it in 'ऀ'..'ॿ' }
        var t: TextToSpeech? = null
        t = TextToSpeech(app, TextToSpeech.OnInitListener { st ->
            val e = t ?: return@OnInitListener
            if (st != TextToSpeech.SUCCESS) {
                e.shutdown()
                return@OnInitListener
            }
            LangUtil.applyTts(e, if (devanagari || Ui.hinglish(app)) "hi-IN" else "en-IN")
            e.setSpeechRate(0.88f)
            e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    main.post { onDone?.invoke() }
                }

                @Deprecated("old API")
                override fun onError(id: String?) {
                    main.post { onDone?.invoke() }
                }
            })
            e.speak(LangUtil.speakable(text), TextToSpeech.QUEUE_FLUSH, null, "narrator")
        })
        tts = t
    }

    fun stop() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
        }
        tts = null
    }
}

/** 🌙 The story screen (night theme) */
class StoryActivity : Activity() {
    private lateinit var storyView: TextView
    private lateinit var fromView: TextView
    private lateinit var fx: LoveFx
    private var story = ""
    private var theme = (0 until Bedtime.THEMES.size).random()
    private var busy = false
    private val hand: Typeface by lazy { Typeface.create("casual", Typeface.NORMAL) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Speaker.load(this)
        val root = FrameLayout(this)
        root.addView(NightSky(this))
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(44f), dp(18f), dp(30f))
        }
        val p = Profile.load(this)
        col.addView(Ui.label(this, "🌙  " + Ui.t(this, "Bedtime Story", "Sone se pehle ki kahani"), 26f, 0xFFFFF4D6.toInt(), true))
        col.addView(Ui.label(this, Ui.t(this, "A tiny story about ${p.partner} & ${p.name}, written fresh every night ✨",
            "${p.partner} & ${p.name} ki ek chhoti si kahani, har raat nayi ✨"), 14f, 0xCCFFFFFF.toInt()).apply {
            setPadding(dp(2f), dp(2f), 0, dp(16f))
        })

        // theme chips
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Bedtime.THEMES.forEachIndexed { i, th ->
            chips.addView(TextView(this).apply {
                text = th.first
                textSize = 26f
                gravity = Gravity.CENTER
                background = Ui.glass(this@StoryActivity, 20f, 0x33)
                setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
                setOnClickListener { theme = i; newStory() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8f) })
        }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

        fromView = Ui.label(this, "", 13f, 0xFFFFC2E0.toInt(), true).apply { setPadding(dp(4f), dp(16f), 0, 0) }
        col.addView(fromView)
        storyView = TextView(this).apply {
            textSize = 19f
            typeface = hand
            setTextColor(0xFFFFF4D6.toInt())
            setLineSpacing(dp(6f).toFloat(), 1f)
            background = Ui.glass(this@StoryActivity, 26f, 0x22)
            setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
        }
        col.addView(storyView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Ui.glassButton(this, Ui.t(this, "✨ New story", "✨ Nayi kahani")) { newStory() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.glassButton(this, Ui.t(this, "🔊 Read", "🔊 Sunao")) { if (story.isNotEmpty()) Narrator.say(this, story) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10f) })
        col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16f) })
        col.addView(Ui.primaryButton(this, Ui.t(this, "💌 Send to ${Speaker.other(p)}", "💌 ${Speaker.other(p)} ko bhejo")) { sendIt() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12f) })

        scroll.addView(col)
        root.addView(scroll)
        fx = LoveFx(this)
        root.addView(fx, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        val got = intent.getStringExtra("text")
        if (!got.isNullOrBlank()) show(got, intent.getStringExtra("from").orEmpty(), intent.getBooleanExtra("play", false))
        else newStory()
    }

    override fun onDestroy() {
        Narrator.stop()
        super.onDestroy()
    }

    private fun dp(v: Float) = Ui.dp(this, v)

    private fun show(text: String, from: String, play: Boolean) {
        story = text
        storyView.text = text
        fromView.text = if (from.isNotBlank()) Ui.t(this, "💌 From $from", "💌 $from ki taraf se") else ""
        fx.burst("✨", 14)
        if (play) Narrator.say(this, text)
    }

    private fun newStory() {
        if (busy) return
        busy = true
        Narrator.stop()
        fromView.text = ""
        storyView.text = Ui.t(this, "Chinu is writing your story… ✍️🌙", "Chinu aapki kahani likh rahi hai… ✍️🌙")
        Bedtime.write(this, theme) { s ->
            busy = false
            if (!isFinishing) show(s, "", true)
        }
    }

    private fun sendIt() {
        if (story.isEmpty() || busy) return
        if (!CoupleLink.paired(this)) {
            Toast.makeText(this, Ui.t(this, "Connect both phones first in 💞", "Pehle 💞 mein dono phones jodo"), Toast.LENGTH_LONG).show()
            return
        }
        Bedtime.send(this, story) { ok ->
            if (ok) fx.burst("🌙", 16)
            Toast.makeText(this, if (ok) Ui.t(this, "Sent 🌙 Chinu will read it to them", "Bhej diya 🌙 Chinu unhe sunayegi")
            else Ui.t(this, "No internet 😕", "Internet nahi hai 😕"), Toast.LENGTH_SHORT).show()
        }
    }
}

/** ⏰ Alarms for the new love features (+ re-arm after reboot / app update) */
class LoveExtrasReceiver : BroadcastReceiver() {
    companion object {
        const val LETTER = "com.meraassistant.LETTER_UNLOCK"
        const val QUESTION = "com.meraassistant.DAILY_QUESTION"
        const val SELFIE = "com.meraassistant.SELFIE_STREAK"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            LETTER -> Letters.fireUnlock(context, intent.getStringExtra("id").orEmpty())
            QUESTION -> DailyQ.fire(context)
            SELFIE -> SelfieStreak.fire(context, intent.getIntExtra("slot", 20))
            else -> {
                SelfieStreak.schedule(context)
                DailyQ.schedule(context)
                Letters.scheduleAll(context)
            }
        }
    }
}

/** 🌌 Dark night sky with softly twinkling stars and a moon */
class NightSky(ctx: Context) : View(ctx) {
    private val bg = Paint()
    private val star = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val moon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFF1C1.toInt() }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFF1C1 }
    private val stars = Array(70) { floatArrayOf(Math.random().toFloat(), Math.random().toFloat(), 0.6f + Math.random().toFloat() * 1.6f, Math.random().toFloat() * 6f) }
    private val d = ctx.resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        bg.shader = LinearGradient(0f, 0f, 0f, h, 0xFF0E0B26.toInt(), 0xFF2A1B4D.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, bg)
        val t = SystemClock.uptimeMillis() / 1000f
        for (s in stars) {
            star.alpha = (110 + 145 * (0.5f + 0.5f * kotlin.math.sin(t * 1.3f + s[3]))).toInt().coerceIn(0, 255)
            c.drawCircle(s[0] * w, s[1] * h, s[2] * d, star)
        }
        c.drawCircle(w * 0.82f, h * 0.1f, 46 * d, glow)
        c.drawCircle(w * 0.82f, h * 0.1f, 26 * d, moon)
        postInvalidateDelayed(60)
    }
}
