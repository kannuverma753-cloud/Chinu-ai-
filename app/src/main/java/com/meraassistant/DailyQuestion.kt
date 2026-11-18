package com.meraassistant

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

/**
 * ❓ Aaj ka Sawaal — one sweet question a day, the same on both phones.
 * Your partner's answer stays HIDDEN until you write yours, then both are revealed together. 🎉
 * Answer together every day to grow a 🔥 streak.
 */
object DailyQ {
    /** English, Hinglish */
    val QUESTIONS = listOf(
        "What was your very first impression of me?" to "Mujhe pehli baar dekh ke aapne kya socha tha?",
        "Which moment with me would you replay forever?" to "Mere saath ka kaunsa pal aap baar-baar jeena chahoge?",
        "What's one small thing I do that makes you smile?" to "Meri kaunsi chhoti si aadat aapko smile kara deti hai?",
        "If we could teleport anywhere tonight, where would we go?" to "Aaj raat kahin bhi teleport kar sakein, to kahan chalein?",
        "What song reminds you of us?" to "Kaunsa gaana sunke aapko hum dono yaad aate hain?",
        "What do you love most about our talks?" to "Hamari baaton mein aapko sabse accha kya lagta hai?",
        "Describe me in 3 words." to "Mujhe 3 shabdon mein describe karo.",
        "What's a dream you've never told anyone?" to "Aisa kaunsa sapna hai jo aapne kisi ko nahi bataya?",
        "What's our perfect Sunday?" to "Hamara perfect Sunday kaisa hoga?",
        "What food should we cook together one day?" to "Ek din hum saath mein kya banayenge?",
        "When did you first realise you liked me?" to "Aapko kab pehli baar laga ki aap mujhe pasand karte ho?",
        "What's one thing you want us to do before this year ends?" to "Is saal khatam hone se pehle hum dono kya karein?",
        "What nickname should I call you from now on?" to "Ab se main aapko kis naam se bulaun?",
        "What made you laugh the most today?" to "Aaj aapko sabse zyada hasi kis baat pe aayi?",
        "If our love story was a movie, what would it be called?" to "Hamari love story ek movie hoti, to uska naam kya hota?",
        "What's your favourite photo of us, and why?" to "Hamari kaunsi photo aapki favourite hai, aur kyun?",
        "What's a habit of mine you secretly find cute?" to "Meri kaunsi aadat aapko chupke se cute lagti hai?",
        "Where should our first big trip be?" to "Hamari pehli badi trip kahan honi chahiye?",
        "What's something you want to learn together?" to "Hum saath mein kya naya seekhein?",
        "What do you miss most when we're apart?" to "Jab hum door hote hain, aap sabse zyada kya miss karte ho?",
        "What's the best gift you've ever received?" to "Aapko ab tak ka sabse accha gift kaunsa mila hai?",
        "Which day with me was the happiest?" to "Mere saath kaunsa din sabse khush wala tha?",
        "What would our dream home look like?" to "Hamara sapno ka ghar kaisa hoga?",
        "What's one thing I should never stop doing?" to "Ek cheez jo main kabhi band na karun?",
        "What's your comfort food on a bad day?" to "Bure din pe aapka comfort food kya hai?",
        "If you could ask me anything right now, what would it be?" to "Abhi mujhse kuch bhi pooch sakte ho, to kya poochoge?",
        "What do you think we'll be doing 10 years from now?" to "10 saal baad hum dono kya kar rahe honge?",
        "What's a little promise you want from me?" to "Aap mujhse kaunsa chhota sa promise chahte ho?",
        "What's your favourite way to spend a rainy day with me?" to "Baarish wale din mere saath kya karna pasand karoge?",
        "Which of my messages do you remember the most?" to "Mera kaunsa message aapko sabse zyada yaad hai?",
        "What makes you feel most loved?" to "Aapko sabse zyada pyaar kab mehsoos hota hai?",
        "What's a silly fear you have?" to "Aapka koi funny sa darr kya hai?",
        "If you had a superpower for a day, what would you do for us?" to "Ek din ki superpower mile, to hamare liye kya karoge?",
        "What's a song you'd dedicate to me today?" to "Aaj aap mujhe kaunsa gaana dedicate karoge?",
        "What's one thing about today you want to tell me?" to "Aaj ki ek baat jo aap mujhe batana chahte ho?",
        "What should our couple name be?" to "Hamara couple name kya hona chahiye?",
        "Which festival do you want to celebrate together first?" to "Kaunsa tyohaar hum sabse pehle saath manayenge?",
        "What's your favourite thing about my voice?" to "Meri awaaz mein aapko kya accha lagta hai?",
        "What would you write on a note for me to find tomorrow?" to "Kal mujhe ek chitthi milni ho, to usme kya likhoge?",
        "What's the sweetest thing someone ever did for you?" to "Kisi ne aapke liye sabse pyaari cheez kya ki hai?",
        "Which emoji is most 'us'?" to "Kaunsa emoji bilkul 'hum dono' jaisa hai?",
        "What's one thing you're proud of me for?" to "Aapko mujh par kis baat ka garv hai?",
        "What's your idea of a perfect date?" to "Aapke hisaab se perfect date kaisi hogi?",
        "What would you name our pet?" to "Hamare pet ka naam kya rakhoge?",
        "What made you think of me today?" to "Aaj kis cheez ne aapko meri yaad dilayi?",
        "What's one word that describes our love?" to "Ek shabd jo hamare pyaar ko describe kare?",
        "If you could relive one conversation with me, which one?" to "Meri kaunsi baatcheet dobara jeena chahoge?",
        "What's something new you'd like to try with me?" to "Mere saath kaunsi nayi cheez try karna chahoge?",
        "What are you grateful for today?" to "Aaj aap kis baat ke liye shukraguzaar ho?",
        "Good night thought: what do you want to dream about tonight?" to "Good night sawaal: aaj raat aap kiska sapna dekhna chahoge?"
    )

    fun today(): Long = LocalDate.now().toEpochDay()

    fun question(ctx: Context, day: Long = today()): String {
        val q = QUESTIONS[(((day % QUESTIONS.size) + QUESTIONS.size) % QUESTIONS.size).toInt()]
        return if (Ui.hinglish(ctx)) q.second else q.first
    }

    fun number(day: Long = today()) = (day - 20_000L).coerceAtLeast(1L)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("dailyq", Context.MODE_PRIVATE)

    fun mine(ctx: Context, day: Long = today()) = prefs(ctx).getString("me_$day", "").orEmpty()
    fun theirs(ctx: Context, day: Long = today()) = prefs(ctx).getString("them_$day", "").orEmpty()

    fun answer(ctx: Context, text: String) {
        val day = today()
        prefs(ctx).edit().putString("me_$day", text.take(600)).apply()
        CoupleLink.send(ctx, "q_ans", JSONObject().put("day", day).put("a", text.take(600)))
    }

    /** Days in a row when BOTH answered (today counts once both are in) */
    fun streak(ctx: Context): Int {
        var d = today()
        if (mine(ctx, d).isEmpty() || theirs(ctx, d).isEmpty()) d -= 1
        var n = 0
        while (mine(ctx, d).isNotEmpty() && theirs(ctx, d).isNotEmpty()) {
            n++
            d--
        }
        return n
    }

    fun onAnswer(app: Context, d: JSONObject, from: String) {
        val day = d.optLong("day", -1L)
        val a = d.optString("a")
        if (day < 0 || a.isBlank()) return
        val before = theirs(app, day)
        prefs(app).edit().putString("them_$day", a).apply()
        QuestionActivity.current?.render(reveal = mine(app, day).isNotEmpty() && before.isEmpty())
        CoupleActivity.current?.refresh()
        if (day != today() || before.isNotEmpty() || QuestionActivity.current != null) return
        val open = Intent(app, QuestionActivity::class.java)
        if (mine(app, day).isEmpty()) {
            CoupleLink.notify(app, "👀  " + Ui.t(app, "$from answered today's question!", "$from ne aaj ke sawaal ka jawab de diya!"),
                Ui.t(app, "Their answer is hidden until you write yours 🤫\n❓ ${question(app)}",
                    "Unka jawab tab dikhega jab aap apna likhoge 🤫\n❓ ${question(app)}"), 450, open)
        } else {
            CoupleLink.notify(app, "🎉  " + Ui.t(app, "Both answers are in!", "Dono ke jawab aa gaye!"),
                Ui.t(app, "Tap to see what $from wrote 💖", "Dekho $from ne kya likha 💖"), 451, open)
        }
        CoupleLink.buzz(app, longArrayOf(0, 70, 60, 70))
    }

    fun onNudge(app: Context, from: String) {
        if (mine(app).isNotEmpty()) return
        CoupleLink.notify(app, "❓  " + Ui.t(app, "$from is waiting for your answer 👀", "$from aapke jawab ka intezaar kar rahe hain 👀"),
            question(app), 452, Intent(app, QuestionActivity::class.java))
        CoupleLink.buzz(app, longArrayOf(0, 60, 50, 60))
    }

    /** Re-send my last 10 answers so a phone that was offline catches up */
    fun hello(ctx: Context, reply: Boolean = false) {
        val ans = JSONObject()
        val t = today()
        for (day in t - 9..t) mine(ctx, day).takeIf { it.isNotEmpty() }?.let { ans.put(day.toString(), it) }
        CoupleLink.send(ctx, "q_hello", JSONObject().put("ans", ans).put("reply", reply))
    }

    fun onHello(ctx: Context, d: JSONObject) {
        val ans = d.optJSONObject("ans") ?: JSONObject()
        val e = prefs(ctx).edit()
        for (k in ans.keys()) e.putString("them_$k", ans.optString(k))
        e.apply()
        QuestionActivity.current?.render()
        if (!d.optBoolean("reply")) hello(ctx, reply = true)
    }

    // ---------- 🌙 9 PM reminder (only if not answered yet) ----------
    fun schedule(ctx: Context) {
        val pi = PendingIntent.getBroadcast(ctx, 4500,
            Intent(ctx, LoveExtrasReceiver::class.java).setAction(LoveExtrasReceiver.QUESTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 21); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        try {
            ctx.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.timeInMillis, pi)
        } catch (e: Exception) {
        }
    }

    fun fire(ctx: Context) {
        schedule(ctx)
        if (!CoupleLink.paired(ctx) || mine(ctx).isNotEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("daily_q", "Aaj ka sawaal", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(ctx, 4501, Intent(ctx, QuestionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val s = streak(ctx)
        val title = "❓ " + Ui.t(ctx, "Today's question", "Aaj ka sawaal") + if (s > 0) "  ·  🔥 $s" else ""
        nm.notify(4502, Notification.Builder(ctx, "daily_q")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(question(ctx))
            .setStyle(Notification.BigTextStyle().bigText(question(ctx)))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build())
    }
}

/** ❓ The daily question screen */
class QuestionActivity : Activity() {
    private lateinit var body: LinearLayout
    private lateinit var fx: LoveFx
    private val hand: Typeface by lazy { Typeface.create("casual", Typeface.NORMAL) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Speaker.load(this)
        val root = FrameLayout(this)
        root.addView(GlassBackground(this))
        val scroll = ScrollView(this)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(44f), dp(18f), dp(30f))
        }
        scroll.addView(body)
        root.addView(scroll)
        fx = LoveFx(this)
        root.addView(fx, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        ScrollFx.attach(scroll, body)
    }

    override fun onResume() {
        super.onResume()
        current = this
        CoupleLink.start(this)
        val sp = getSharedPreferences("dailyq", MODE_PRIVATE)
        if (CoupleLink.paired(this) && System.currentTimeMillis() - sp.getLong("lastHello", 0) > 15 * 60_000L) {
            sp.edit().putLong("lastHello", System.currentTimeMillis()).apply()
            DailyQ.hello(this)
        }
        render()
    }

    override fun onPause() {
        if (current === this) current = null
        super.onPause()
    }

    private fun dp(v: Float) = Ui.dp(this, v)

    fun render(reveal: Boolean = false) {
        body.removeAllViews()
        val p = Profile.load(this)
        val me = Speaker.name(p)
        val other = Speaker.other(p)
        val mine = DailyQ.mine(this)
        val theirs = DailyQ.theirs(this)
        val streak = DailyQ.streak(this)

        body.addView(Ui.label(this, Ui.t(this, "❓ Today's Question", "❓ Aaj ka Sawaal"), 28f, 0xFF3B1F4A.toInt(), true))
        body.addView(Ui.label(this, "#${DailyQ.number()}" + if (streak > 0) Ui.t(this, "  ·  🔥 $streak day streak", "  ·  🔥 $streak din ki streak") else "",
            14f, 0xFF6B4E7A.toInt(), true).apply { setPadding(dp(2f), dp(2f), 0, dp(16f)) })

        // the question
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glass(this@QuestionActivity, 28f, 0x88)
            setPadding(dp(20f), dp(22f), dp(20f), dp(22f))
            addView(Ui.label(this@QuestionActivity, DailyQ.question(this@QuestionActivity), 22f, 0xFF2E1A38.toInt(), true).apply {
                gravity = Gravity.CENTER
                setLineSpacing(dp(4f).toFloat(), 1f)
            })
        })

        // my answer
        sectionTitle(Ui.t(this, "YOUR ANSWER", "AAPKA JAWAB") + " · $me")
        if (mine.isEmpty()) {
            val input = EditText(this).apply {
                hint = Ui.t(this@QuestionActivity, "Be honest… 💭", "Dil se likho… 💭")
                textSize = 18f
                typeface = hand
                minLines = 3
                gravity = Gravity.TOP
                background = Ui.glass(this@QuestionActivity, 22f, 0x66)
                setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            body.addView(input)
            body.addView(Ui.primaryButton(this, Ui.t(this, "Lock my answer 🔐", "Jawab lock karo 🔐")) {
                val t = input.text.toString().trim()
                if (t.isEmpty()) return@primaryButton
                DailyQ.answer(this, t)
                fx.burst(if (DailyQ.theirs(this).isNotEmpty()) "🎉" else "💭", 18)
                render(reveal = DailyQ.theirs(this).isNotEmpty())
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })
        } else answerCard(mine, 0xFF5B5BD6.toInt())

        // partner's answer
        sectionTitle(Ui.t(this, "$other's ANSWER", "$other ka JAWAB").uppercase(Locale.ENGLISH))
        when {
            theirs.isNotEmpty() && mine.isNotEmpty() -> {
                answerCard(theirs, 0xFFD63384.toInt())
                if (reveal) {
                    fx.burst("💖", 28)
                    Narrator.say(this, Ui.t(this, "$other says: $theirs", "$other ne likha: $theirs"))
                }
            }
            theirs.isNotEmpty() -> body.addView(Ui.label(this, Ui.t(this,
                "🔒 $other has answered!\nWrite yours to unlock it 🤫", "🔒 $other ne jawab de diya!\nApna likho to khulega 🤫"),
                16f, 0xFF3B1F4A.toInt(), true).apply {
                gravity = Gravity.CENTER
                background = Ui.glass(this@QuestionActivity, 22f, 0x44)
                setPadding(dp(16f), dp(22f), dp(16f), dp(22f))
            })
            else -> {
                body.addView(Ui.label(this, Ui.t(this, "⏳ Waiting for $other…", "⏳ $other ke jawab ka intezaar…"),
                    15f, 0xFF6B4E7A.toInt()).apply { setPadding(dp(6f), 0, 0, dp(8f)) })
                if (mine.isNotEmpty() && CoupleLink.paired(this)) {
                    body.addView(Ui.glassButton(this, Ui.t(this, "👉 Nudge $other", "👉 $other ko yaad dilao")) {
                        CoupleLink.send(this, "q_nudge") { ok ->
                            Toast.makeText(this, if (ok) Ui.t(this, "Nudged 👀", "Yaad dila diya 👀") else Ui.t(this, "No internet 😕", "Internet nahi hai 😕"),
                                Toast.LENGTH_SHORT).show()
                        }
                    })
                }
            }
        }

        // memories: earlier days where both answered
        val past = (1L..14L).map { DailyQ.today() - it }.filter { DailyQ.mine(this, it).isNotEmpty() && DailyQ.theirs(this, it).isNotEmpty() }
        if (past.isNotEmpty()) {
            sectionTitle(Ui.t(this, "📖 EARLIER", "📖 PEHLE KE SAWAAL"))
            val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
            for (day in past) {
                body.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = Ui.glass(this@QuestionActivity, 20f, 0x44)
                    setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
                    addView(Ui.label(this@QuestionActivity, LocalDate.ofEpochDay(day).format(fmt) + " · " + DailyQ.question(this@QuestionActivity, day),
                        13f, 0xFF8A4F9E.toInt(), true))
                    addView(Ui.label(this@QuestionActivity, "$me: ${DailyQ.mine(this@QuestionActivity, day)}", 14.5f, 0xFF5B5BD6.toInt()).apply {
                        typeface = hand; setPadding(0, dp(6f), 0, 0)
                    })
                    addView(Ui.label(this@QuestionActivity, "$other: ${DailyQ.theirs(this@QuestionActivity, day)}", 14.5f, 0xFFD63384.toInt()).apply {
                        typeface = hand; setPadding(0, dp(4f), 0, 0)
                    })
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
            }
        }
    }

    private fun answerCard(text: String, color: Int) {
        body.addView(TextView(this).apply {
            this.text = text
            textSize = 18f
            typeface = hand
            setTextColor(color)
            background = Ui.glass(this@QuestionActivity, 22f, 0x77)
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            setLineSpacing(dp(4f).toFloat(), 1f)
        })
    }

    private fun sectionTitle(t: String) {
        body.addView(Ui.label(this, t, 12.5f, 0xFF8A4F9E.toInt(), true).apply {
            letterSpacing = 0.1f
            setPadding(dp(4f), dp(22f), 0, dp(8f))
        })
    }

    companion object {
        @Volatile
        var current: QuestionActivity? = null
    }
}
