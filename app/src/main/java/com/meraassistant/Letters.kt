package com.meraassistant

import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
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
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 💌 "Open when…" letters.
 * Write a sealed letter for your partner: "open when you're sad", "…when you miss me",
 * "…when you can't sleep", or "open on 26 March". The envelope stays sealed until then.
 * The secret magic: when they open a "sad" letter, YOUR phone quietly tells you —
 * so you know exactly when to call. 🥺📞
 */
object Letters {
    /** key, emoji, English, Hinglish */
    class Kind(val key: String, val emoji: String, val en: String, val hi: String, val alertEn: String, val alertHi: String)

    val KINDS = listOf(
        Kind("sad", "🥺", "Open when you're sad", "Jab udaas ho tab kholna",
            "opened your “when you're sad” letter 🥺 Maybe call now?", "ne aapka “jab udaas ho” wala khat abhi khola 🥺 Ek call kar lo na"),
        Kind("miss", "💭", "Open when you miss me", "Jab meri yaad aaye",
            "opened your “when you miss me” letter 💭 They're missing you!", "ne “jab meri yaad aaye” wala khat khola 💭 Aapko yaad kar rahe hain!"),
        Kind("sleep", "🌙", "Open when you can't sleep", "Jab neend na aaye",
            "can't sleep and opened your 🌙 letter. Say good night?", "ko neend nahi aa rahi, 🌙 wala khat khola. Good night bol do?"),
        Kind("angry", "😤", "Open when you're angry with me", "Jab mujhse naraz ho",
            "is upset and opened your “when you're angry” letter 😤 Time to say sorry 🙏", "naraz hain aur “jab naraz ho” wala khat khola 😤 Sorry bolne ka time 🙏"),
        Kind("strong", "💪", "Open when you need courage", "Jab himmat chahiye",
            "needed courage and opened your 💪 letter. Cheer them on!", "ko himmat chahiye thi, 💪 wala khat khola. Unka hausla badhao!"),
        Kind("happy", "😊", "Open when you're really happy", "Jab bahut khush ho",
            "is super happy and opened your 😊 letter!", "bahut khush hain aur 😊 wala khat khola!"),
        Kind("date", "📅", "Open on a special day", "Ek khaas din kholna",
            "opened the letter you saved for today 📅💖", "ne aaj ke din wala khat khol liya 📅💖")
    )

    fun kind(k: String) = KINDS.firstOrNull { it.key == k } ?: KINDS[0]

    class Letter(
        val id: String, val kind: String, val text: String, val author: String,
        val unlockAt: Long, val at: Long, val upd: Long, val opened: Long, val del: Boolean
    ) {
        fun json(): JSONObject = JSONObject().put("id", id).put("kind", kind).put("text", text).put("author", author)
            .put("unlockAt", unlockAt).put("at", at).put("upd", upd).put("opened", opened).put("del", del)

        fun locked() = kind == "date" && System.currentTimeMillis() < unlockAt

        companion object {
            fun of(j: JSONObject) = Letter(j.optString("id"), j.optString("kind", "sad"), j.optString("text"), j.optString("author"),
                j.optLong("unlockAt"), j.optLong("at"), j.optLong("upd"), j.optLong("opened"), j.optBoolean("del"))
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("letters", Context.MODE_PRIVATE)

    private fun raw(ctx: Context): MutableList<Letter> = try {
        val a = JSONArray(prefs(ctx).getString("all", "[]"))
        (0 until a.length()).map { Letter.of(a.getJSONObject(it)) }.toMutableList()
    } catch (e: Exception) {
        mutableListOf()
    }

    private fun store(ctx: Context, l: List<Letter>) {
        val a = JSONArray()
        l.sortedBy { it.at }.takeLast(300).forEach { a.put(it.json()) }
        prefs(ctx).edit().putString("all", a.toString()).apply()
    }

    fun get(ctx: Context, id: String) = raw(ctx).firstOrNull { it.id == id }

    fun me(ctx: Context): String {
        Speaker.load(ctx)
        return Speaker.name(Profile.load(ctx))
    }

    fun mine(ctx: Context) = raw(ctx).filter { !it.del && it.author.equals(me(ctx), true) }.sortedByDescending { it.at }
    fun forMe(ctx: Context) = raw(ctx).filter { !it.del && !it.author.equals(me(ctx), true) }.sortedByDescending { it.at }

    /** Letters waiting for me that I haven't opened yet */
    fun sealedCount(ctx: Context) = forMe(ctx).count { it.opened == 0L }

    private fun save(ctx: Context, n: Letter) {
        val l = raw(ctx)
        l.removeAll { it.id == n.id }
        l.add(n)
        store(ctx, l)
        CoupleLink.send(ctx, "letter_put", n.json())
    }

    fun write(ctx: Context, kind: String, text: String, unlockAt: Long) {
        val now = System.currentTimeMillis()
        save(ctx, Letter(now.toString(36) + (100..999).random(), kind, text.take(3000), me(ctx), unlockAt, now, now, 0L, false))
    }

    fun open(ctx: Context, l: Letter) {
        if (l.opened != 0L) return
        val now = System.currentTimeMillis()
        save(ctx, Letter(l.id, l.kind, l.text, l.author, l.unlockAt, l.at, now, now, l.del))
    }

    fun delete(ctx: Context, l: Letter) =
        save(ctx, Letter(l.id, l.kind, "", l.author, l.unlockAt, l.at, System.currentTimeMillis(), l.opened, true))

    /** A letter (new, opened or deleted) arrived from the partner */
    fun onPut(app: Context, d: JSONObject, from: String) {
        val n = Letter.of(d)
        if (n.id.isEmpty()) return
        val l = raw(app)
        val old = l.firstOrNull { it.id == n.id }
        if (old != null && old.upd >= n.upd) return
        l.removeAll { it.id == n.id }
        l.add(n)
        store(app, l)
        LettersActivity.current?.render()
        CoupleActivity.current?.refresh()
        val k = kind(n.kind)
        val iWrote = n.author.equals(me(app), true)
        val open = Intent(app, LettersActivity::class.java)
        when {
            // 🤫 they just opened MY letter → tell me
            iWrote && n.opened != 0L && (old == null || old.opened == 0L) && !n.del -> {
                val girl = from.equals(Profile.load(app).name, true)
                var hi = "$from ${k.alertHi}"
                if (girl) hi = hi.replace("rahe hain", "rahi hain")
                CoupleLink.notify(app, "${k.emoji}  $from opened your letter", if (Ui.hinglish(app)) hi else "$from ${k.alertEn}",
                    420 + KINDS.indexOf(k), open)
                CoupleLink.buzz(app, longArrayOf(0, 90, 70, 90, 70, 160))
            }
            // 💌 a brand-new letter for me
            !iWrote && old == null && !n.del -> {
                val title = "💌  $from left you a sealed letter"
                val body = if (n.kind == "date")
                    Ui.t(app, "It unlocks on ${fmtDay(n.unlockAt)} — no peeking! 🤫", "Ye ${fmtDay(n.unlockAt)} ko khulega — tab tak no peeking! 🤫")
                else "${k.emoji} " + Ui.t(app, k.en, k.hi)
                CoupleLink.notify(app, title, body, 410, open)
                CoupleLink.buzz(app, longArrayOf(0, 80, 60, 80))
                if (n.kind == "date") schedule(app, n)
            }
        }
    }

    // ---------- syncing missed letters (same idea as the diary) ----------
    fun hello(ctx: Context, reply: Boolean = false) {
        val have = JSONObject()
        raw(ctx).sortedByDescending { it.upd }.take(100).forEach { have.put(it.id, it.upd) }
        CoupleLink.send(ctx, "letter_hello", JSONObject().put("have", have).put("reply", reply))
    }

    fun onHello(ctx: Context, d: JSONObject) {
        val have = d.optJSONObject("have") ?: JSONObject()
        for (n in raw(ctx).sortedByDescending { it.upd }.take(100)) {
            if (have.optLong(n.id, -1L) < n.upd) CoupleLink.send(ctx, "letter_put", n.json())
        }
        if (!d.optBoolean("reply")) hello(ctx, reply = true)
    }

    // ---------- 📅 dated letters: a notification on the day they unlock ----------
    private fun pending(ctx: Context, id: String): PendingIntent =
        PendingIntent.getBroadcast(ctx, 41000 + (id.hashCode() and 0xFFF),
            Intent(ctx, LoveExtrasReceiver::class.java).setAction(LoveExtrasReceiver.LETTER).putExtra("id", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun schedule(ctx: Context, l: Letter) {
        if (l.kind != "date" || l.unlockAt <= System.currentTimeMillis() || l.opened != 0L) return
        try {
            ctx.getSystemService(AlarmManager::class.java)
                .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, l.unlockAt, pending(ctx, l.id))
        } catch (e: Exception) {
        }
    }

    fun scheduleAll(ctx: Context) = forMe(ctx).forEach { schedule(ctx, it) }

    fun fireUnlock(ctx: Context, id: String) {
        val l = get(ctx, id) ?: return
        if (l.del || l.opened != 0L) return
        CoupleLink.notify(ctx, "📅💌  " + Ui.t(ctx, "Today's the day!", "Aaj woh din aa gaya!"),
            Ui.t(ctx, "${l.author}'s letter is unlocked. Open it now 💖", "${l.author} ka khat ab khul gaya hai. Abhi kholo 💖"),
            415, Intent(ctx, LettersActivity::class.java))
        CoupleLink.buzz(ctx, longArrayOf(0, 120, 80, 120, 80, 240))
    }

    fun fmtDay(t: Long): String = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(Date(t))
}

/** 💌 The letters screen */
class LettersActivity : Activity() {
    private lateinit var body: LinearLayout
    private lateinit var fx: LoveFx
    private val hand: Typeface by lazy { Typeface.create("casual", Typeface.NORMAL) }
    private val title: Typeface by lazy { Typeface.create("cursive", Typeface.BOLD) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Speaker.load(this)
        val root = FrameLayout(this)
        root.addView(GlassBackground(this, GlassBackground.LOVE))
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
        val sp = getSharedPreferences("letters", MODE_PRIVATE)
        if (CoupleLink.paired(this) && System.currentTimeMillis() - sp.getLong("lastHello", 0) > 10 * 60_000L) {
            sp.edit().putLong("lastHello", System.currentTimeMillis()).apply()
            Letters.hello(this)
        }
        render()
    }

    override fun onPause() {
        if (current === this) current = null
        super.onPause()
    }

    private fun dp(v: Float) = Ui.dp(this, v)

    fun render() {
        body.removeAllViews()
        val p = Profile.load(this)
        val other = Speaker.other(p)
        body.addView(TextView(this).apply {
            text = Ui.t(this@LettersActivity, "Open-When Letters", "Open-When Khat")
            textSize = 36f
            typeface = title
            setTextColor(0xFF3B1F4A.toInt())
        })
        body.addView(Ui.label(this, Ui.t(this,
            "Sealed letters for the moments that matter. When $other opens one, you'll know 🤫",
            "Band khat, khaas palon ke liye. Jab $other koi khat kholenge, aapko pata chal jayega 🤫"),
            14f, 0xFF6B4E7A.toInt()).apply { setPadding(dp(2f), 0, 0, dp(16f)) })

        body.addView(Ui.primaryButton(this, Ui.t(this, "✍️  Write a letter for $other", "✍️  $other ke liye khat likho")) { pickKind() })

        // ---- for me ----
        section(Ui.t(this, "💌 FOR YOU", "💌 AAPKE LIYE"))
        val forMe = Letters.forMe(this)
        if (forMe.isEmpty()) hint(Ui.t(this, "No letters yet… maybe ask $other to write one 😉", "Abhi koi khat nahi… $other se ek likhwao 😉"))
        for (l in forMe) envelope(l, true)

        // ---- written by me ----
        section(Ui.t(this, "✉️ WRITTEN BY YOU", "✉️ AAPNE LIKHE"))
        val mine = Letters.mine(this)
        if (mine.isEmpty()) hint(Ui.t(this, "Write your first one — they'll love it 💖", "Pehla khat likho — unhe bahut accha lagega 💖"))
        for (l in mine) envelope(l, false)
    }

    private fun section(t: String) {
        body.addView(Ui.label(this, t, 12.5f, 0xFF8A4F9E.toInt(), true).apply {
            letterSpacing = 0.1f
            setPadding(dp(4f), dp(24f), 0, dp(8f))
        })
    }

    private fun hint(t: String) {
        body.addView(Ui.label(this, t, 14.5f, 0xFF6B4E7A.toInt()).apply { typeface = hand; setPadding(dp(6f), dp(4f), 0, 0) })
    }

    private fun envelope(l: Letters.Letter, forMe: Boolean) {
        val k = Letters.kind(l.kind)
        val other = Speaker.other(Profile.load(this))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.glass(this@LettersActivity, 22f, if (forMe && l.opened == 0L && !l.locked()) 0x99 else 0x55)
            setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
        }
        val icon = when {
            l.locked() -> "🔒"
            l.opened != 0L -> "💌"
            else -> "✉️"
        }
        row.addView(Ui.label(this, icon, 30f, 0xFF3B1F4A.toInt()).apply { setPadding(0, 0, dp(12f), 0) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val label = if (l.kind == "date") "${k.emoji} " + Ui.t(this, "Open on ${Letters.fmtDay(l.unlockAt)}", "${Letters.fmtDay(l.unlockAt)} ko kholna")
        else "${k.emoji} " + Ui.t(this, k.en, k.hi)
        col.addView(Ui.label(this, label, 16f, 0xFF3B1F4A.toInt(), true))
        val status = when {
            forMe && l.locked() -> {
                val days = ((l.unlockAt - System.currentTimeMillis()) / 86_400_000L) + 1
                Ui.t(this, "Sealed · $days day${if (days == 1L) "" else "s"} to go ⏳", "Band hai · $days din baaki ⏳")
            }
            forMe && l.opened == 0L -> Ui.t(this, "From ${l.author} · tap to open 💖", "${l.author} se · kholne ke liye tap karo 💖")
            forMe -> Ui.t(this, "From ${l.author} · opened ${Letters.fmtDay(l.opened)}", "${l.author} se · khola ${Letters.fmtDay(l.opened)}")
            l.opened != 0L -> Ui.t(this, "💖 $other opened it on ${Letters.fmtDay(l.opened)}", "💖 $other ne ${Letters.fmtDay(l.opened)} ko khola")
            else -> Ui.t(this, "Still sealed · hold to delete", "Abhi band hai · delete ke liye dabake rakho")
        }
        col.addView(Ui.label(this, status, 12.5f, 0xFF6B4E7A.toInt()))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (forMe) row.setOnClickListener { tryOpen(l) }
        else {
            row.setOnClickListener { read(l) }
            row.setOnLongClickListener {
                if (l.opened == 0L) AlertDialog.Builder(this).setTitle(Ui.t(this, "Delete this letter?", "Ye khat delete karein?"))
                    .setPositiveButton("Delete") { _, _ -> Letters.delete(this, l); render() }
                    .setNegativeButton("Cancel", null).show()
                true
            }
        }
        body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10f)
        })
    }

    private fun tryOpen(l: Letters.Letter) {
        if (l.locked()) {
            Toast.makeText(this, Ui.t(this, "No peeking! 🤫 It opens on ${Letters.fmtDay(l.unlockAt)}",
                "No peeking! 🤫 Ye ${Letters.fmtDay(l.unlockAt)} ko khulega"), Toast.LENGTH_LONG).show()
            return
        }
        if (l.opened != 0L || l.kind == "date") {
            Letters.open(this, l)
            read(l)
            return
        }
        val k = Letters.kind(l.kind)
        AlertDialog.Builder(this)
            .setTitle("${k.emoji}  " + Ui.t(this, k.en, k.hi))
            .setMessage(Ui.t(this, "Open it only when you truly feel this way 💖\n(${l.author} will know you opened it)",
                "Sirf tab kholna jab sach mein aisa lag raha ho 💖\n(${l.author} ko pata chal jayega ki aapne khola)"))
            .setPositiveButton(Ui.t(this, "Open 💌", "Kholo 💌")) { _, _ ->
                Letters.open(this, l)
                read(l)
                render()
            }
            .setNegativeButton(Ui.t(this, "Not now", "Abhi nahi"), null).show()
    }

    /** Show the letter on "paper" in handwriting */
    private fun read(l: Letters.Letter) {
        fx.burst("💌", 18)
        val paper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFF6EC.toInt())
            setPadding(dp(22f), dp(20f), dp(22f), dp(10f))
        }
        val k = Letters.kind(l.kind)
        paper.addView(Ui.label(this, "${k.emoji}  " + if (l.kind == "date") Letters.fmtDay(l.unlockAt) else Ui.t(this, k.en, k.hi),
            13f, 0xFF8A4F9E.toInt(), true))
        paper.addView(TextView(this).apply {
            text = l.text
            textSize = 19f
            typeface = hand
            setTextColor(0xFF2E1A38.toInt())
            setLineSpacing(dp(5f).toFloat(), 1f)
            setPadding(0, dp(14f), 0, dp(14f))
        })
        paper.addView(TextView(this).apply {
            text = "— ${l.author} 💖"
            textSize = 24f
            typeface = title
            gravity = Gravity.END
            setTextColor(if (l.author.equals(Profile.load(this@LettersActivity).name, true)) 0xFFD63384.toInt() else 0xFF5B5BD6.toInt())
        })
        val sc = ScrollView(this).apply { addView(paper) }
        AlertDialog.Builder(this).setView(sc)
            .setPositiveButton("💖") { _, _ -> }
            .setNeutralButton(Ui.t(this, "🔊 Read aloud", "🔊 Sunao")) { _, _ -> Narrator.say(this, l.text) }
            .show()
    }

    private fun pickKind() {
        if (!CoupleLink.paired(this)) {
            Toast.makeText(this, Ui.t(this, "Connect both phones first in 💞", "Pehle 💞 mein dono phones jodo"), Toast.LENGTH_LONG).show()
            return
        }
        val items = Letters.KINDS.map { "${it.emoji}  " + Ui.t(this, it.en, it.hi) }.toTypedArray()
        AlertDialog.Builder(this).setTitle(Ui.t(this, "When should they open it?", "Ye khat kab khulega?"))
            .setItems(items) { _, i ->
                val k = Letters.KINDS[i]
                if (k.key == "date") pickDate() else compose(k.key, 0L)
            }.show()
    }

    private fun pickDate() {
        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        DatePickerDialog(this, { _, y, m, d ->
            val t = Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
            compose("date", t)
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.minDate = System.currentTimeMillis()
        }.show()
    }

    private fun compose(kind: String, unlockAt: Long) {
        val k = Letters.kind(kind)
        val input = EditText(this).apply {
            hint = Ui.t(this@LettersActivity, "My dear…", "Meri jaan…")
            typeface = hand
            textSize = 18f
            minLines = 6
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val wrap = FrameLayout(this).apply { setPadding(dp(20f), dp(8f), dp(20f), 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle("${k.emoji}  " + if (kind == "date") Letters.fmtDay(unlockAt) else Ui.t(this, k.en, k.hi))
            .setView(wrap)
            .setPositiveButton(Ui.t(this, "Seal & send 💌", "Band karke bhejo 💌")) { _, _ ->
                val t = input.text.toString().trim()
                if (t.isEmpty()) return@setPositiveButton
                Letters.write(this, kind, t, unlockAt)
                fx.burst("💌", 16)
                Toast.makeText(this, Ui.t(this, "Sealed & sent 💌", "Khat band karke bhej diya 💌"), Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton("Cancel", null).show()
    }

    companion object {
        @Volatile
        var current: LettersActivity? = null
    }
}
