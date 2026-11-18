package com.meraassistant

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/**
 * 💗 Dil Se:
 *  💌 "Open when…" letters · 🎟️ love coupons · 🔮 dream meanings · 😴 sleep together (soft sounds)
 */
object Pyaar {
    private val main = Handler(Looper.getMainLooper())
    private val rnd = Random()
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("pyaar", Context.MODE_PRIVATE)

    private fun other(ctx: Context): String {
        Speaker.load(ctx)
        return Speaker.other(Profile.load(ctx))
    }

    private fun me(ctx: Context): String {
        Speaker.load(ctx)
        return Speaker.name(Profile.load(ctx))
    }

    private fun toast(ctx: Context, en: String, hi: String) = main.post { Toast.makeText(ctx, Ui.t(ctx, en, hi), Toast.LENGTH_SHORT).show() }
    private fun arr(ctx: Context, k: String): JSONArray = try {
        JSONArray(prefs(ctx).getString(k, "[]"))
    } catch (e: Exception) {
        JSONArray()
    }

    private fun save(ctx: Context, k: String, items: List<JSONObject>) {
        val a = JSONArray()
        items.takeLast(60).forEach { a.put(it) }
        prefs(ctx).edit().putString(k, a.toString()).apply()
    }

    private fun items(ctx: Context, k: String): MutableList<JSONObject> {
        val a = arr(ctx, k)
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.toMutableList()
    }

    private fun newId() = java.lang.Long.toString(System.currentTimeMillis(), 36) + rnd.nextInt(1000)
    private fun sent(ctx: Context, ok: Boolean) =
        if (ok) toast(ctx, "Sent to ${other(ctx)} 💞", "${other(ctx)} ko bhej diya 💞") else toast(ctx, "No internet 😕", "Internet nahi hai 😕")

    private fun open(app: Context) = Intent(app, CoupleActivity::class.java)
    private fun refresh() = main.post { CoupleActivity.current?.refresh() }

    // =============================================================== 💌 Open-when letters
    private val OW = listOf(
        "sad" to ("😢 you're sad" to "😢 udaas ho"),
        "miss" to ("🥺 you miss me" to "🥺 meri yaad aaye"),
        "sleep" to ("🌙 you can't sleep" to "🌙 neend na aaye"),
        "angry" to ("😤 you're angry with me" to "😤 mujhse naraz ho"),
        "happy" to ("🥳 you're super happy" to "🥳 bahut khush ho"),
        "tired" to ("💪 you need strength" to "💪 himmat chahiye")
    )

    private fun owName(ctx: Context, k: String): String =
        OW.firstOrNull { it.first == k }?.second?.let { Ui.t(ctx, "Open when " + it.first, "Kholna jab " + it.second) } ?: k

    fun writeLetter(a: Activity) {
        val names = OW.map { owName(a, it.first) }.toTypedArray()
        AlertDialog.Builder(a).setTitle("💌  " + Ui.t(a, "A letter for ${other(a)}", "${other(a)} ke liye letter"))
            .setItems(names) { _, i ->
                val e = EditText(a).apply {
                    hint = Ui.t(a, "Write from your heart…", "Dil se likho…")
                    minLines = 4
                }
                val dlg = AlertDialog.Builder(a).setTitle(names[i]).setView(e)
                    .setPositiveButton(Ui.t(a, "Seal & send 💌", "Band karke bhejo 💌")) { _, _ ->
                        val t = e.text.toString().trim()
                        if (t.isEmpty()) return@setPositiveButton
                        val d = JSONObject().put("id", newId()).put("k", OW[i].first).put("text", t)
                        CoupleLink.send(a, "ow_put", d) { ok -> sent(a, ok) }
                    }
                    .setNeutralButton(Ui.t(a, "✨ Chinu, help", "✨ Chinu madad karo"), null)
                    .setNegativeButton(Ui.t(a, "Cancel", "Rehne do"), null).show()
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    toast(a, "Writing… ✨", "Likh rahi hoon… ✨")
                    val prompt = "Write a short, warm letter (4-6 sentences) in Hinglish (Hindi in English letters, simple words) from ${me(a)} to " +
                        "${other(a)}, a loving couple. It will be read only '${OW[i].second.first}'. Make it comforting, personal and sweet " +
                        "with 2 emojis. Reply with ONLY the letter text."
                    Brain.ask(a, prompt, "hi-IN") { t -> main.post { if (!a.isFinishing) e.setText(t.trim()) } }
                }
            }.setNegativeButton(Ui.t(a, "Close", "Band karo"), null).show()
    }

    fun onOwPut(app: Context, d: JSONObject, from: String) {
        val list = items(app, "ow_in")
        if (list.any { it.optString("id") == d.optString("id") }) return
        list.add(JSONObject().put("id", d.optString("id")).put("k", d.optString("k")).put("text", d.optString("text"))
            .put("from", from).put("open", false))
        save(app, "ow_in", list)
        CoupleLink.notify(app, "💌  " + Ui.t(app, "$from sealed a letter for you", "$from ne aapke liye ek letter rakha hai"),
            "🔒 " + owName(app, d.optString("k")), 490, open(app).putExtra("care", "letters"))
        refresh()
    }

    fun letters(a: Activity) {
        val list = items(a, "ow_in")
        if (list.isEmpty()) {
            toast(a, "No letters yet — ask ${other(a)} to write one 💌", "Abhi koi letter nahi — ${other(a)} ko bolo ek likhein 💌")
            return
        }
        val rows = list.reversed()
        val names = rows.map { (if (it.optBoolean("open")) "💌 " else "🔒 ") + owName(a, it.optString("k")) + " · " + it.optString("from") }
        AlertDialog.Builder(a).setTitle("📬  " + Ui.t(a, "My letters", "Mere letters"))
            .setItems(names.toTypedArray()) { _, i ->
                val l = rows[i]
                if (l.optBoolean("open")) show(a, l)
                else AlertDialog.Builder(a).setTitle("🔒 " + owName(a, l.optString("k")))
                    .setMessage(Ui.t(a, "Open it only if it's true right now 🥺", "Sirf tabhi kholna jab sach mein aisa ho 🥺"))
                    .setPositiveButton(Ui.t(a, "Open 💌", "Kholo 💌")) { _, _ ->
                        l.put("open", true)
                        save(a, "ow_in", list)
                        CoupleLink.send(a, "ow_open", JSONObject().put("k", l.optString("k")))
                        show(a, l)
                        refresh()
                    }.setNegativeButton(Ui.t(a, "Later", "Baad mein"), null).show()
            }.setNegativeButton(Ui.t(a, "Close", "Band karo"), null).show()
    }

    private fun show(a: Activity, l: JSONObject) {
        AlertDialog.Builder(a).setTitle("💌 " + owName(a, l.optString("k")))
            .setMessage(l.optString("text") + "\n\n— " + l.optString("from") + " 💞")
            .setPositiveButton(Ui.t(a, "❤️ Close", "❤️ Band karo"), null).show()
    }

    fun onOwOpen(app: Context, d: JSONObject, from: String) {
        CoupleLink.buzz(app, longArrayOf(0, 120, 90, 120))
        CoupleLink.notify(app, "💌  " + Ui.t(app, "$from opened your letter", "$from ne aapka letter khola"),
            owName(app, d.optString("k")) + Ui.t(app, " — maybe give them a call now 📞", " — shayad abhi ek call kar lo 📞"), 491, open(app))
    }

    // =============================================================== 🎟️ Love coupons
    private val COUPONS = listOf(
        "🤗 One tight hug" to "🤗 Ek kas ke jhappi",
        "🎬 Movie of your choice" to "🎬 Tumhari pasand ki movie",
        "🍕 A treat on me" to "🍕 Meri taraf se treat",
        "😇 One no-anger day" to "😇 Ek din bilkul gussa nahi",
        "💆 Head massage" to "💆 Sar ki maalish",
        "📞 1-hour call, no excuses" to "📞 1 ghante ki call, koi bahana nahi",
        "🙋 I'll say sorry first" to "🙋 Sorry main pehle bolunga/bolungi",
        "🛍️ A shopping day together" to "🛍️ Saath mein shopping",
        "🎤 I'll sing you a song" to "🎤 Main tumhare liye gaana gaunga/gaungi"
    )

    fun giveCoupon(a: Activity) {
        val names = COUPONS.map { Ui.t(a, it.first, it.second) } + Ui.t(a, "✏️ Write my own", "✏️ Apna likho")
        AlertDialog.Builder(a).setTitle("🎟️  " + Ui.t(a, "Give ${other(a)} a love coupon", "${other(a)} ko love coupon do"))
            .setItems(names.toTypedArray()) { _, i ->
                if (i < COUPONS.size) sendCoupon(a, names[i])
                else {
                    val e = EditText(a).apply { hint = Ui.t(a, "e.g. 🍫 A chocolate whenever you want", "jaise 🍫 Jab chaho chocolate") }
                    AlertDialog.Builder(a).setTitle("✏️ Coupon").setView(e)
                        .setPositiveButton(Ui.t(a, "Give 🎟️", "Do 🎟️")) { _, _ ->
                            val t = e.text.toString().trim()
                            if (t.isNotEmpty()) sendCoupon(a, "🎟️ $t")
                        }.setNegativeButton(Ui.t(a, "Cancel", "Rehne do"), null).show()
                }
            }.setNegativeButton(Ui.t(a, "Close", "Band karo"), null).show()
    }

    private fun sendCoupon(a: Activity, text: String) =
        CoupleLink.send(a, "cp_put", JSONObject().put("id", newId()).put("text", text)) { ok -> sent(a, ok) }

    fun onCpPut(app: Context, d: JSONObject, from: String) {
        val list = items(app, "cp_in")
        if (list.any { it.optString("id") == d.optString("id") }) return
        list.add(JSONObject().put("id", d.optString("id")).put("text", d.optString("text")).put("from", from).put("used", false))
        save(app, "cp_in", list)
        CoupleLink.notify(app, "🎟️  " + Ui.t(app, "$from gave you a love coupon!", "$from ne aapko love coupon diya!"),
            d.optString("text") + Ui.t(app, " — use it whenever you want 😄", " — jab chaho use karo 😄"), 492, open(app).putExtra("care", "coupons"))
        refresh()
    }

    /** Add a coupon straight into my wallet (used by the birthday surprise) */
    fun addCoupon(ctx: Context, text: String, from: String) {
        val list = items(ctx, "cp_in")
        list.add(JSONObject().put("id", newId()).put("text", text).put("from", from).put("used", false))
        save(ctx, "cp_in", list)
    }

    private fun unusedCoupons(ctx: Context) = items(ctx, "cp_in").count { !it.optBoolean("used") }

    fun wallet(a: Activity) {
        val list = items(a, "cp_in")
        if (list.isEmpty()) {
            toast(a, "No coupons yet 🎟️", "Abhi koi coupon nahi 🎟️")
            return
        }
        val rows = list.sortedBy { it.optBoolean("used") }
        val names = rows.map { (if (it.optBoolean("used")) "✅ " else "🎟️ ") + it.optString("text") + " · " + it.optString("from") }
        AlertDialog.Builder(a).setTitle("👛  " + Ui.t(a, "My coupons", "Mere coupons"))
            .setItems(names.toTypedArray()) { _, i ->
                val c = rows[i]
                if (c.optBoolean("used")) return@setItems
                AlertDialog.Builder(a).setTitle(c.optString("text"))
                    .setMessage(Ui.t(a, "Use this coupon now? ${c.optString("from")} will be told 😄",
                        "Abhi ye coupon use karna hai? ${c.optString("from")} ko pata chal jayega 😄"))
                    .setPositiveButton(Ui.t(a, "Use it! 🎉", "Use karo! 🎉")) { _, _ ->
                        c.put("used", true)
                        save(a, "cp_in", list)
                        CoupleLink.send(a, "cp_use", JSONObject().put("text", c.optString("text"))) { ok -> sent(a, ok) }
                        refresh()
                    }.setNegativeButton(Ui.t(a, "Keep it", "Sambhal ke rakho"), null).show()
            }.setNegativeButton(Ui.t(a, "Close", "Band karo"), null).show()
    }

    fun onCpUse(app: Context, d: JSONObject, from: String) {
        CoupleLink.buzz(app, longArrayOf(0, 200, 100, 200, 100, 400))
        CoupleLink.notify(app, "🎟️  " + Ui.t(app, "$from used a coupon!", "$from ne coupon use kar liya!"),
            d.optString("text") + Ui.t(app, " — a promise is a promise 😄", " — ab nibhana padega 😄"), 493, open(app))
        Voice.say(app, Ui.t(app, "$from used your coupon. ", "$from ne aapka coupon use kar liya. ") + d.optString("text").replace(Regex("[^\\p{L}\\p{N} ]"), ""))
    }

    // =============================================================== 🔮 Dream meanings
    fun dream(a: Activity) {
        val e = EditText(a).apply {
            hint = Ui.t(a, "What did you dream last night?", "Raat ko kya sapna dekha?")
            minLines = 3
        }
        AlertDialog.Builder(a).setTitle("🔮  " + Ui.t(a, "Dream diary", "Sapno ki diary")).setView(e)
            .setPositiveButton(Ui.t(a, "What does it mean? 🔮", "Iska matlab? 🔮")) { _, _ ->
                val t = e.text.toString().trim()
                if (t.isNotEmpty()) meaning(a, t)
            }.setNegativeButton(Ui.t(a, "Cancel", "Rehne do"), null).show()
    }

    private fun meaning(a: Activity, dream: String) {
        toast(a, "Reading your dream… 🔮", "Sapna padh rahi hoon… 🔮")
        val prompt = "${me(a)} (in love with ${other(a)}) had this dream: \"$dream\". As Chinu, a sweet playful assistant, give a fun, positive, " +
            "light-hearted 'dream meaning' in Hinglish (Hindi in English letters) in 3-4 short sentences with 2 emojis. Keep it hopeful, " +
            "never scary. Reply with ONLY the meaning."
        Brain.ask(a, prompt, "hi-IN") { m ->
            main.post {
                if (a.isFinishing) return@post
                val mean = m.trim()
                val list = items(a, "dreams")
                list.add(JSONObject().put("who", me(a)).put("text", dream).put("m", mean).put("at", System.currentTimeMillis()))
                save(a, "dreams", list)
                AlertDialog.Builder(a).setTitle("🔮  " + Ui.t(a, "Your dream", "Aapka sapna")).setMessage(mean)
                    .setPositiveButton(Ui.t(a, "Tell ${other(a)} 🌙", "${other(a)} ko batao 🌙")) { _, _ ->
                        CoupleLink.send(a, "dream", JSONObject().put("text", dream).put("m", mean)) { ok -> sent(a, ok) }
                    }.setNegativeButton(Ui.t(a, "Close", "Band karo"), null).show()
            }
        }
    }

    fun onDream(app: Context, d: JSONObject, from: String) {
        val list = items(app, "dreams")
        list.add(JSONObject().put("who", from).put("text", d.optString("text")).put("m", d.optString("m")).put("at", System.currentTimeMillis()))
        save(app, "dreams", list)
        CoupleLink.notify(app, "🌙  " + Ui.t(app, "$from's dream", "$from ka sapna"), d.optString("text"), 494, open(app).putExtra("care", "dreams"))
    }

    fun dreams(a: Activity) {
        val list = items(a, "dreams").reversed().take(15)
        if (list.isEmpty()) {
            toast(a, "No dreams saved yet 🌙", "Abhi koi sapna nahi 🌙")
            return
        }
        val text = list.joinToString("\n\n") { "🌙 ${it.optString("who")}: ${it.optString("text")}\n🔮 ${it.optString("m")}" }
        AlertDialog.Builder(a).setTitle("🌙  " + Ui.t(a, "Our dreams", "Hamare sapne")).setMessage(text)
            .setPositiveButton(Ui.t(a, "Close", "Band karo"), null).show()
    }

    // =============================================================== 😴 Sleep together (soft sounds)
    @Volatile
    private var playing = false
    private var stopAt = 0L

    fun sleeping() = playing

    fun sleep(a: Activity) {
        if (playing) {
            stopSleep()
            toast(a, "Sound stopped", "Awaaz band")
            refresh()
            return
        }
        val sounds = arrayOf(Ui.t(a, "🌧️ Rain", "🌧️ Baarish"), Ui.t(a, "🌊 Sea waves", "🌊 Samundar ki lehrein"), Ui.t(a, "🤍 Soft hum", "🤍 Halki gungunahat"))
        AlertDialog.Builder(a).setTitle("😴  " + Ui.t(a, "Sleep together", "Saath mein so jao"))
            .setItems(sounds) { _, s ->
                val mins = intArrayOf(15, 30, 60)
                AlertDialog.Builder(a).setTitle(Ui.t(a, "Play for…", "Kitni der chale…"))
                    .setItems(mins.map { "$it min" }.toTypedArray()) { _, m ->
                        startSound(s, mins[m])
                        prefs(a).edit().putLong("mySleep", System.currentTimeMillis()).apply()
                        CoupleLink.send(a, "sleep", JSONObject())
                        if (System.currentTimeMillis() - prefs(a).getLong("otherSleep", 0L) < 45 * 60_000L)
                            Voice.say(a, Ui.t(a, "You're both going to sleep together. Good night!", "Aap dono saath so rahe ho. Good night, sweet dreams!"))
                        else toast(a, "Good night 🌙 ${other(a)} will know", "Good night 🌙 ${other(a)} ko pata chal jayega")
                        refresh()
                    }.show()
            }.setNegativeButton(Ui.t(a, "Close", "Band karo"), null).show()
    }

    fun stopSleep() {
        playing = false
    }

    private fun startSound(kind: Int, minutes: Int) {
        playing = true
        stopAt = System.currentTimeMillis() + minutes * 60_000L
        Thread({
            val rate = 22050
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(maxOf(minBuf, 8192)).setTransferMode(AudioTrack.MODE_STREAM).build()
            } catch (e: Exception) {
                playing = false
                return@Thread
            }
            val buf = ShortArray(2048)
            var brown = 0.0
            var t = 0L
            try {
                track.play()
                while (playing) {
                    val left = stopAt - System.currentTimeMillis()
                    if (left <= 0) break
                    val fade = when {
                        t < rate * 3L -> t / (rate * 3.0)
                        left < 60_000 -> left / 60_000.0
                        else -> 1.0
                    }
                    for (i in buf.indices) {
                        val white = rnd.nextDouble() * 2 - 1
                        brown = (brown + 0.02 * white) / 1.02
                        val sec = t.toDouble() / rate
                        val v = when (kind) {
                            0 -> brown * 2.6 + white * 0.05 * (0.7 + 0.3 * sin(sec * 1.3))
                            1 -> brown * 3.2 * (0.35 + 0.65 * (0.5 + 0.5 * sin(2 * PI * sec / 9.0)))
                            else -> 0.18 * sin(2 * PI * 110 * sec) + 0.08 * sin(2 * PI * 165 * sec) + 0.05 * sin(2 * PI * 220.5 * sec) +
                                brown * 0.8
                        }
                        buf[i] = (v.coerceIn(-1.0, 1.0) * 9000 * fade).toInt().toShort()
                        t++
                    }
                    track.write(buf, 0, buf.size)
                }
            } catch (e: Exception) {
            } finally {
                try {
                    track.stop()
                } catch (e: Exception) {
                }
                track.release()
                playing = false
                refresh()
            }
        }, "chinu-sleep").start()
    }

    fun onSleep(app: Context, from: String) {
        prefs(app).edit().putLong("otherSleep", System.currentTimeMillis()).apply()
        val both = System.currentTimeMillis() - prefs(app).getLong("mySleep", 0L) < 45 * 60_000L
        CoupleLink.notify(app, "😴  " + Ui.t(app, "$from is going to sleep", "$from so rahe hain"),
            if (both) Ui.t(app, "You both are sleeping together tonight 💞", "Aaj aap dono saath so rahe ho 💞")
            else Ui.t(app, "Say good night 🌙 (tap → Sleep together)", "Good night bol do 🌙 (tap → Saath mein so jao)"), 495,
            open(app).putExtra("care", "sleep"))
        refresh()
    }

    // =============================================================== actions + 💗 card
    fun action(a: Activity, what: String) {
        when (what) {
            "letter" -> writeLetter(a)
            "letters" -> letters(a)
            "coupon" -> giveCoupon(a)
            "coupons" -> wallet(a)
            "dream" -> dream(a)
            "dreams" -> dreams(a)
            "sleep" -> sleep(a)
        }
    }

    fun card(a: Activity, body: LinearLayout) {
        val dp = { v: Float -> Ui.dp(a, v) }
        body.addView(Ui.label(a, Ui.t(a, "💗 FROM THE HEART", "💗 DIL SE"), 12.5f, 0xFF8A4F9E.toInt(), true).apply {
            letterSpacing = 0.1f
            setPadding(dp(4f), dp(26f), 0, dp(8f))
        })
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glass(a, 24f, 0x66)
            setPadding(dp(16f), dp(14f), dp(16f), dp(16f))
        }
        val sealed = items(a, "ow_in").count { !it.optBoolean("open") }
        val coupons = unusedCoupons(a)
        val lines = listOfNotNull(
            if (sealed > 0) Ui.t(a, "🔒 $sealed sealed letter(s) waiting for the right moment", "🔒 $sealed band letter sahi waqt ka intezaar kar rahe hain") else null,
            if (coupons > 0) Ui.t(a, "🎟️ You have $coupons love coupon(s)", "🎟️ Aapke paas $coupons love coupon hain") else null,
            if (playing) Ui.t(a, "😴 Sleep sound is playing…", "😴 Neend wali awaaz chal rahi hai…") else null
        )
        for (l in lines) card.addView(Ui.label(a, l, 14f, 0xFF3B1F4A.toInt()).apply { setPadding(0, dp(2f), 0, dp(5f)) })
        fun row(l: Pair<String, () -> Unit>, r: Pair<String, () -> Unit>?) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Ui.glassButton(a, l.first, l.second).apply { textSize = 14f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (r != null) row.addView(Ui.glassButton(a, r.first, r.second).apply { textSize = 14f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8f) })
            card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        }
        row(Ui.t(a, "💌 Open-when letter", "💌 'Kholna jab' letter") to { writeLetter(a) },
            Ui.t(a, "📬 My letters", "📬 Mere letters") + (if (sealed > 0) " ($sealed)" else "") to { letters(a) })
        row(Ui.t(a, "🎟️ Give a coupon", "🎟️ Coupon do") to { giveCoupon(a) },
            Ui.t(a, "👛 My coupons", "👛 Mere coupons") + (if (coupons > 0) " ($coupons)" else "") to { wallet(a) })
        row(Ui.t(a, "🔮 Dream meaning", "🔮 Sapne ka matlab") to { dream(a) },
            Ui.t(a, "🌙 Our dreams", "🌙 Hamare sapne") to { dreams(a) })
        row((if (playing) Ui.t(a, "⏹️ Stop sleep sound", "⏹️ Neend wali awaaz band karo") else Ui.t(a, "😴 Sleep together (rain / waves)", "😴 Saath so jao (baarish / lehrein)")) to { sleep(a) }, null)
        body.addView(card)
    }
}
