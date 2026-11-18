package com.meraassistant

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * 🎮 Little games for the three of us — Chiku, Komal and Chinu 🤖
 *  ✊ rock-paper-scissors · 🔤 word chain · 💭 think alike · 🔢 find the number
 *  ⚡ reaction race · 👆 tap battle · ❓ Chinu's quiz
 */
object Khel {
    private val main = Handler(Looper.getMainLooper())
    private val rounds = HashMap<String, JSONObject>()
    const val BOT = "Chinu 🤖"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("khel", Context.MODE_PRIVATE)

    private fun other(ctx: Context): String {
        Speaker.load(ctx)
        return Speaker.other(Profile.load(ctx))
    }

    private fun me(ctx: Context): String {
        Speaker.load(ctx)
        return Speaker.name(Profile.load(ctx))
    }

    private val NAMES = mapOf(
        "rps" to "✊✋✌️ Pathar-Kaagaz-Kainchi", "same" to "💭 Ek jaisa socho", "num" to "🔢 Number dhoondho",
        "react" to "⚡ Reaction race", "tap" to "👆 Tap battle", "quiz" to "❓ Chinu ka quiz", "wc" to "🔤 Shabd ki chain"
    )

    private fun send(ctx: Context, d: JSONObject) = CoupleLink.send(ctx, "khel", d)

    private fun round(k: String, id: Long) = rounds.getOrPut("$k:$id") { JSONObject().put("k", k).put("id", id) }

    private fun say(ctx: Context, title: String, msg: String, k: String? = null) {
        val a = CoupleActivity.current
        if (a != null) {
            val b = AlertDialog.Builder(a).setTitle(title).setMessage(msg).setPositiveButton("👍", null)
            if (k != null) b.setNeutralButton(Ui.t(a, "💬 Chat", "💬 Baat karo")) { _, _ -> chat(a, k) }
            b.show()
        } else CoupleLink.notify(ctx, title, msg, 430, Intent(ctx, CoupleActivity::class.java).putExtra("care", if (k != null) "kchat:$k" else "games"))
    }

    // ---------- 🏆 scores ----------
    private fun win(ctx: Context, who: String) {
        val key = when (who) {
            "me" -> "w_me"
            "them" -> "w_them"
            else -> "w_bot"
        }
        prefs(ctx).edit().putInt(key, prefs(ctx).getInt(key, 0) + 1).apply()
    }

    fun scoreLine(ctx: Context): String {
        val p = prefs(ctx)
        return "🏆 " + Ui.t(ctx, "You", "Aap") + " ${p.getInt("w_me", 0)}  ·  ${other(ctx)} ${p.getInt("w_them", 0)}  ·  Chinu 🤖 ${p.getInt("w_bot", 0)}"
    }

    /** values: me, them, bot → winners get a point; higher = better unless [lowWins] */
    private fun finish(ctx: Context, k: String, vals: List<Pair<String, Double>>, lowWins: Boolean, show: (Double) -> String) {
        val best = if (lowWins) vals.minOf { it.second } else vals.maxOf { it.second }
        val winners = vals.filter { it.second == best }.map { it.first }
        winners.forEach { win(ctx, it) }
        val nm = mapOf("me" to Ui.t(ctx, "You", "Aap"), "them" to other(ctx), "bot" to BOT)
        val lines = vals.joinToString("\n") { (w, v) -> (if (w in winners) "🏆 " else "      ") + "${nm[w]}: ${show(v)}" }
        say(ctx, NAMES[k] ?: "🎮", lines + "\n\n" + scoreLine(ctx), k)
        CoupleActivity.current?.refresh()
    }

    // =============================================================== starting & receiving
    fun start(a: Activity, k: String) {
        if (!CoupleLink.paired(a)) return
        val id = System.currentTimeMillis()
        val meta = JSONObject().put("k", k).put("id", id).put("t", "start")
        when (k) {
            "num" -> meta.put("s", (1..100).random())
            "react" -> meta.put("at", id + 4500 + (0..3000).random())
            "tap" -> meta.put("at", id + 5000)
            "quiz" -> meta.put("qi", QUIZ.indices.random())
            "same" -> meta.put("qi", SAME.indices.random())
        }
        rounds["$k:$id"] = JSONObject(meta.toString())
        send(a, meta)
        play(a, k, id)
    }

    fun on(app: Context, d: JSONObject, from: String) {
        val k = d.optString("k")
        if (k == "wc") return onWord(app, d, from)
        if (k == "chat") return onChat(app, d, from)
        val id = d.optLong("id")
        val r = round(k, id)
        if (d.optString("t") == "start") {
            for (key in d.keys()) if (key != "t") r.put(key, d.get(key))
            val a = CoupleActivity.current
            if (a != null) play(a, k, id)
            else {
                prefs(app).edit().putString("pending", "$k:$id").putString("pendingMeta", d.toString()).apply()
                CoupleLink.notify(app, "🎮  " + Ui.t(app, "$from started ${NAMES[k]}", "$from ne ${NAMES[k]} shuru kiya"),
                    Ui.t(app, "Tap to play now!", "Abhi khelne ke liye tap karo!"), 431, Intent(app, CoupleActivity::class.java).putExtra("care", "khel"))
                CoupleLink.buzz(app, longArrayOf(0, 60, 50, 60))
            }
        } else {
            r.put("them", d.opt("v"))
            check(app, k, id)
        }
    }

    /** opened from the notification */
    fun resume(a: Activity) {
        val p = prefs(a).getString("pending", "").orEmpty()
        val meta = try {
            JSONObject(prefs(a).getString("pendingMeta", "{}") ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }
        prefs(a).edit().remove("pending").remove("pendingMeta").apply()
        if (p.isEmpty()) return menu(a)
        val k = p.substringBefore(':')
        val id = p.substringAfter(':').toLongOrNull() ?: return
        val r = round(k, id)
        for (key in meta.keys()) if (key != "t" && !r.has(key)) r.put(key, meta.get(key))
        play(a, k, id)
    }

    private fun mine(a: Activity, k: String, id: Long, v: Any) {
        val r = round(k, id)
        if (r.has("me")) return
        r.put("me", v)
        send(a, JSONObject().put("k", k).put("id", id).put("t", "res").put("v", v))
        if (!r.has("them")) android.widget.Toast.makeText(a, Ui.t(a, "Waiting for ${other(a)}… ⏳", "${other(a)} ka intezaar… ⏳"), android.widget.Toast.LENGTH_SHORT).show()
        check(a, k, id)
    }

    private fun check(ctx: Context, k: String, id: Long) {
        val r = round(k, id)
        if (!r.has("me") || !r.has("them") || r.optBoolean("done")) return
        r.put("done", true)
        val seed = Math.floorMod(id, 1000L).toInt()
        when (k) {
            "rps" -> {
                val bot = seed % 3
                val picks = listOf("me" to r.optInt("me"), "them" to r.optInt("them"), "bot" to bot)
                val set = picks.map { it.second }.toSet()
                val winners = if (set.size != 2) emptyList() else {
                    val (x, y) = set.toList()
                    val g = if (Math.floorMod(x - y, 3) == 1) x else y
                    picks.filter { it.second == g }.map { it.first }
                }
                winners.forEach { win(ctx, it) }
                val nm = mapOf("me" to Ui.t(ctx, "You", "Aap"), "them" to other(ctx), "bot" to BOT)
                say(ctx, NAMES[k] ?: "", picks.joinToString("\n") { (w, p) -> (if (w in winners) "🏆 " else "      ") + "${nm[w]}: ${RPS[p]}" } +
                    "\n\n" + (if (winners.isEmpty()) Ui.t(ctx, "🤝 Draw!", "🤝 Barabar!") else "") + "\n" + scoreLine(ctx), k)
                CoupleActivity.current?.refresh()
            }
            "same" -> {
                val qi = r.optInt("qi")
                val bot = SAME[qi].second.split(',')[seed % SAME[qi].second.split(',').size].trim()
                val ans = listOf("me" to r.optString("me"), "them" to r.optString("them"), "bot" to bot)
                fun n(s: String) = NameMatcher.toLatin(s).replace(" ", "")
                val pts = ans.map { (w, x) -> w to ans.count { it.first != w && n(it.second) == n(x) && n(x).isNotEmpty() }.toDouble() }
                val nm = mapOf("me" to Ui.t(ctx, "You", "Aap"), "them" to other(ctx), "bot" to BOT)
                if (pts.any { it.second > 0 }) pts.filter { it.second > 0 }.forEach { win(ctx, it.first) }
                say(ctx, "💭 " + SAME[qi].first, ans.joinToString("\n") { (w, x) -> "${nm[w]}: $x" } + "\n\n" +
                    (if (pts.any { it.second > 0 }) Ui.t(ctx, "✨ Same thoughts! Matching ones get a point", "✨ Ek jaisa socha! Match walon ko point")
                    else Ui.t(ctx, "😅 Nobody matched this time", "😅 Is baar kisi ka match nahi hua")) + "\n" + scoreLine(ctx), k)
                CoupleActivity.current?.refresh()
            }
            "num" -> finish(ctx, k, listOf("me" to r.optDouble("me"), "them" to r.optDouble("them"), "bot" to (5 + seed % 4).toDouble()), true) {
                if (it >= 99) Ui.t(ctx, "gave up", "chhod diya") else Ui.t(ctx, "${it.toInt()} tries", "${it.toInt()} koshish")
            }
            "react" -> finish(ctx, k, listOf("me" to r.optDouble("me"), "them" to r.optDouble("them"), "bot" to (190 + seed % 170).toDouble()), true) {
                if (it >= 9999) Ui.t(ctx, "too early / missed ❌", "jaldi daba diya / miss ❌") else "${it.toInt()} ms"
            }
            "tap" -> finish(ctx, k, listOf("me" to r.optDouble("me"), "them" to r.optDouble("them"), "bot" to (42 + seed % 30).toDouble()), false) {
                Ui.t(ctx, "${it.toInt()} taps", "${it.toInt()} tap")
            }
            "quiz" -> {
                val q = QUIZ[r.optInt("qi")]
                val botRight = seed % 10 < 7
                fun score(v: Any?): Double {
                    val o = v as? JSONObject ?: return 999999.0
                    return if (o.optInt("a") == q.third) o.optDouble("ms") else 999999.0
                }
                val vals = listOf("me" to score(r.opt("me")), "them" to score(r.opt("them")), "bot" to if (botRight) (2500 + seed * 6).toDouble() else 999999.0)
                if (vals.all { it.second >= 999999.0 }) {
                    say(ctx, "❓ " + q.first, Ui.t(ctx, "Nobody got it 😅 Answer: ${q.second[q.third]}", "Kisi ka sahi nahi 😅 Jawab: ${q.second[q.third]}") + "\n" + scoreLine(ctx), k)
                } else finish(ctx, k, vals, true) {
                    if (it >= 999999.0) "❌" else "✅ " + String.format(java.util.Locale.ENGLISH, "%.1f s", it / 1000)
                }
            }
        }
    }

    // =============================================================== playing on this phone
    private val RPS = listOf("✊ Pathar", "✋ Kaagaz", "✌️ Kainchi")

    private fun play(a: Activity, k: String, id: Long) {
        val r = round(k, id)
        if (r.has("me")) return
        when (k) {
            "rps" -> AlertDialog.Builder(a).setTitle(NAMES[k]).setCancelable(false)
                .setItems(RPS.toTypedArray()) { _, i -> mine(a, k, id, i) }
                .setNeutralButton(Ui.t(a, "💬 Chat", "💬 Baat karo")) { _, _ -> chat(a, k) { play(a, k, id) } }.show()
            "same" -> {
                val (q, _) = SAME[r.optInt("qi")]
                val e = EditText(a).apply { hint = Ui.t(a, "One word…", "Ek shabd…") }
                AlertDialog.Builder(a).setTitle("💭 $q").setMessage(Ui.t(a, "Answer what you think ${other(a)} and Chinu will say!", "Wahi bolo jo lagta hai ${other(a)} aur Chinu bhi bolenge!"))
                    .setView(e).setCancelable(false)
                    .setPositiveButton("OK") { _, _ -> mine(a, k, id, e.text.toString().trim().ifEmpty { "—" }) }
                    .setNeutralButton(Ui.t(a, "💬 Chat", "💬 Baat karo")) { _, _ -> chat(a, k) { play(a, k, id) } }.show()
            }
            "num" -> guess(a, id, r.optInt("s"), 0, "")
            "react" -> react(a, id, r.optLong("at"))
            "tap" -> tapBattle(a, id, r.optLong("at"))
            "quiz" -> {
                val q = QUIZ[r.optInt("qi")]
                val shown = System.currentTimeMillis()
                AlertDialog.Builder(a).setTitle("❓ " + q.first).setCancelable(false)
                    .setItems(q.second.toTypedArray()) { _, i ->
                        mine(a, k, id, JSONObject().put("a", i).put("ms", System.currentTimeMillis() - shown))
                    }
                    .setNeutralButton(Ui.t(a, "💬 Chat", "💬 Baat karo")) { _, _ -> chat(a, k) { play(a, k, id) } }.show()
            }
        }
    }

    private fun guess(a: Activity, id: Long, secret: Int, tries: Int, hint: String) {
        val e = EditText(a).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            this.hint = "1 – 100"
        }
        AlertDialog.Builder(a).setTitle("🔢 " + Ui.t(a, "Chinu thought of a number (1-100)", "Chinu ne 1-100 mein number socha hai"))
            .setMessage((if (hint.isNotEmpty()) "$hint\n" else "") + Ui.t(a, "Tries: $tries", "Koshish: $tries"))
            .setView(e).setCancelable(false)
            .setPositiveButton(Ui.t(a, "Guess", "Bolo")) { _, _ ->
                val g = e.text.toString().toIntOrNull()
                when {
                    g == null -> guess(a, id, secret, tries, hint)
                    g == secret -> {
                        android.widget.Toast.makeText(a, Ui.t(a, "🎉 Yes! It was $secret", "🎉 Haan! Number $secret tha"), android.widget.Toast.LENGTH_SHORT).show()
                        mine(a, "num", id, tries + 1)
                    }
                    g < secret -> guess(a, id, secret, tries + 1, "⬆️ $g " + Ui.t(a, "— go higher", "— isse bada"))
                    else -> guess(a, id, secret, tries + 1, "⬇️ $g " + Ui.t(a, "— go lower", "— isse chhota"))
                }
            }
            .setNegativeButton(Ui.t(a, "Give up", "Chhod do")) { _, _ -> mine(a, "num", id, 99) }
            .setNeutralButton("💬") { _, _ -> chat(a, "num") { guess(a, id, secret, tries, hint) } }.show()
    }

    private fun bigText(a: Activity, t: String) = TextView(a).apply {
        text = t
        textSize = 30f
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, Ui.dp(a, 40f), 0, Ui.dp(a, 40f))
    }

    private fun react(a: Activity, id: Long, at: Long) {
        if (System.currentTimeMillis() > at + 3000) return mine(a, "react", id, 9999)
        val t = bigText(a, Ui.t(a, "🔴 Wait…", "🔴 Ruko…")).apply { setBackgroundColor(0xFFFFD6DE.toInt()) }
        val dlg = AlertDialog.Builder(a).setTitle(NAMES["react"]).setView(t).setCancelable(false).show()
        var green = false
        t.setOnClickListener {
            val ms = if (!green) 9999L else System.currentTimeMillis() - at
            dlg.dismiss()
            mine(a, "react", id, ms)
        }
        main.postDelayed({
            if (dlg.isShowing) {
                green = true
                t.text = Ui.t(a, "🟢 TAP NOW!", "🟢 ABHI TAP!")
                t.setBackgroundColor(0xFFB9F6CA.toInt())
                CoupleLink.buzz(a, longArrayOf(0, 40))
                main.postDelayed({ if (dlg.isShowing) { dlg.dismiss(); mine(a, "react", id, 9999) } }, 3000)
            }
        }, maxOf(0L, at - System.currentTimeMillis()))
    }

    private fun tapBattle(a: Activity, id: Long, at: Long) {
        if (System.currentTimeMillis() > at + 10_000) return mine(a, "tap", id, 0)
        var n = 0
        var live = false
        val t = bigText(a, "3…")
        val dlg = AlertDialog.Builder(a).setTitle(NAMES["tap"]).setView(t).setCancelable(false).show()
        t.setOnClickListener {
            if (live) {
                n++
                t.text = "👆 $n"
            }
        }
        val tick = object : Runnable {
            override fun run() {
                if (!dlg.isShowing) return
                val now = System.currentTimeMillis()
                when {
                    now < at -> t.text = "⏳ ${(at - now) / 1000 + 1}"
                    now < at + 10_000 -> {
                        if (!live) {
                            live = true
                            t.text = Ui.t(a, "👆 TAP TAP TAP!", "👆 TAP TAP TAP!")
                            CoupleLink.buzz(a, longArrayOf(0, 40))
                        }
                    }
                    else -> {
                        dlg.dismiss()
                        mine(a, "tap", id, n)
                        return
                    }
                }
                main.postDelayed(this, 100)
            }
        }
        main.post(tick)
    }

    // =============================================================== 🔤 word chain (you → Chinu → partner → Chinu …)
    private val WORDS = ("apple ant arrow anchor almond banana ball bottle butter bread cake candle camera carrot chair coffee " +
        "dance diamond doctor donkey dream eagle earth egg elephant engine flower forest fish frog garden giraffe glass gold guitar " +
        "honey horse house hammer island ice jacket jelly juice jungle kite kitchen king koala lemon lion ladder lamp mango moon " +
        "monkey mirror music nest night noodles nurse ocean onion orange owl panda parrot pencil pizza potato queen quilt rabbit " +
        "rainbow river rose ruby salt sun snake star sugar table tiger tomato train tree umbrella uncle unicorn violin village " +
        "van water watch window wolf xylophone yak yellow yogurt zebra zero zoo eraser envelope lotus yacht teddy toffee")
        .split(' ').filter { it.isNotBlank() }

    private fun chain(ctx: Context): MutableList<JSONObject> {
        val a = try {
            JSONArray(prefs(ctx).getString("wc", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.toMutableList()
    }

    private fun saveChain(ctx: Context, l: List<JSONObject>) = prefs(ctx).edit().putString("wc", JSONArray(l.takeLast(80)).toString()).apply()

    private fun lastLetter(w: String) = w.lowercase().lastOrNull { it.isLetter() }

    fun wordChain(a: Activity) {
        val l = chain(a)
        val me = me(a)
        val last = l.lastOrNull()
        val myTurn = l.isEmpty() || l.lastOrNull { it.optString("by") != BOT }?.optString("by") != me
        val letter = last?.let { lastLetter(it.optString("w")) }
        val text = if (l.isEmpty()) Ui.t(a, "Start with any word! Then Chinu answers, then ${other(a)}…", "Koi bhi shabd se shuru karo! Phir Chinu, phir ${other(a)}…")
        else l.takeLast(12).joinToString("  →  ") { it.optString("w") } + "\n\n" +
            (if (myTurn) Ui.t(a, "Your turn — a word starting with “${letter?.uppercaseChar()}”", "Aapki baari — “${letter?.uppercaseChar()}” se shabd")
            else Ui.t(a, "${other(a)}'s turn ⏳", "${other(a)} ki baari ⏳"))
        val b = AlertDialog.Builder(a).setTitle(NAMES["wc"]).setMessage(text)
            .setNegativeButton(Ui.t(a, "💬 Chat", "💬 Baat karo")) { _, _ -> chat(a, "wc") { wordChain(a) } }
            .setNeutralButton(Ui.t(a, "New game", "Naya game")) { _, _ ->
                saveChain(a, emptyList())
                send(a, JSONObject().put("k", "wc").put("reset", true))
                wordChain(a)
            }
        if (myTurn) b.setPositiveButton(Ui.t(a, "✍️ My word", "✍️ Mera shabd")) { _, _ ->
            val e = EditText(a).apply { hint = letter?.let { "$it…" } ?: "apple" }
            AlertDialog.Builder(a).setTitle(NAMES["wc"]).setView(e).setPositiveButton("OK") { _, _ ->
                val w = e.text.toString().trim().lowercase().split(' ').firstOrNull().orEmpty()
                val used = l.map { it.optString("w") }.toSet()
                when {
                    w.length < 2 -> wordChain(a)
                    letter != null && w.first() != letter -> {
                        android.widget.Toast.makeText(a, Ui.t(a, "Must start with “$letter”", "“$letter” se shuru hona chahiye"), android.widget.Toast.LENGTH_SHORT).show()
                        wordChain(a)
                    }
                    w in used -> {
                        android.widget.Toast.makeText(a, Ui.t(a, "Already used 😜", "Ye pehle aa chuka 😜"), android.widget.Toast.LENGTH_SHORT).show()
                        wordChain(a)
                    }
                    else -> {
                        val need = lastLetter(w)
                        val bot = WORDS.filter { it.first() == need && it !in used && it != w }.randomOrNull()
                        l.add(JSONObject().put("w", w).put("by", me))
                        val d = JSONObject().put("k", "wc").put("w", w)
                        if (bot != null) {
                            l.add(JSONObject().put("w", bot).put("by", BOT))
                            d.put("c", bot)
                        } else {
                            win(a, "me")
                            d.put("botLost", true)
                            android.widget.Toast.makeText(a, Ui.t(a, "🤖 Chinu couldn't find a word — you win a point! 🏆", "🤖 Chinu ko shabd nahi mila — aapko point! 🏆"),
                                android.widget.Toast.LENGTH_LONG).show()
                        }
                        saveChain(a, l)
                        send(a, d)
                        CoupleActivity.current?.refresh()
                        wordChain(a)
                    }
                }
            }.show()
        }
        b.show()
    }

    private fun onWord(app: Context, d: JSONObject, from: String) {
        if (d.optBoolean("reset")) {
            saveChain(app, emptyList())
            return
        }
        val l = chain(app)
        l.add(JSONObject().put("w", d.optString("w")).put("by", from))
        if (d.has("c")) l.add(JSONObject().put("w", d.optString("c")).put("by", BOT))
        if (d.optBoolean("botLost")) prefs(app).edit().putInt("w_them", prefs(app).getInt("w_them", 0) + 1).apply()
        saveChain(app, l)
        val next = d.optString("c").ifEmpty { d.optString("w") }
        CoupleLink.notify(app, "🔤  " + Ui.t(app, "Your turn in the word chain!", "Shabd ki chain mein aapki baari!"),
            "$from: ${d.optString("w")}" + (if (d.has("c")) "  →  Chinu: ${d.optString("c")}" else "") +
                "  ·  " + Ui.t(app, "next: “${lastLetter(next)}”", "agla: “${lastLetter(next)}”"), 432,
            Intent(app, CoupleActivity::class.java).putExtra("care", "wc"))
        CoupleActivity.current?.refresh()
    }


    // =============================================================== 💬 a chat inside every game (you, partner & Chinu)
    @Volatile
    private var chatOpen: String? = null
    private var chatRefresh: (() -> Unit)? = null

    private val BOT_LINES = mapOf(
        "*" to listOf("Main dekh raha hoon 👀 koi cheating nahi!", "Aaj to main hi jeetunga 😎", "Haha, maza aa raha hai 😂",
            "Tum dono ki jodi kamaal hai 💞", "Chalo chalo, jaldi karo ⏳", "Ye game mere liye bachchon ka khel hai 🤖"),
        "rps" to listOf("Mera secret weapon: kainchi ✌️ (ya shayad nahi 😏)", "Pathar se darr lagta hai kya? ✊"),
        "wc" to listOf("Mere paas poori dictionary hai 📚😎", "Agla shabd mushkil dena, warna maza nahi 😜"),
        "same" to listOf("Dekhte hain kiska dimaag mujhse milta hai 🧠", "Main sochta hoon tum dono ek jaisa sochoge 💭"),
        "num" to listOf("Number mere dil mein hai… aur dil ka number 💖", "Hint: wo 1 aur 100 ke beech hai 😂"),
        "react" to listOf("Meri reflexes robot wali hain ⚡🤖", "Jaldi mat dabana, warna out ❌"),
        "tap" to listOf("Ungliyan garam kar lo 🔥👆", "Main 1 second mein 100 tap karta hoon… sapne mein 😴"),
        "quiz" to listOf("Ye sawaal to aasaan tha 🤓", "Google mat karna 😜")
    )

    private fun chatList(ctx: Context, k: String): MutableList<JSONObject> {
        val a = try {
            JSONArray(prefs(ctx).getString("chat_$k", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.toMutableList()
    }

    private fun addChat(ctx: Context, k: String, by: String, t: String) {
        val l = chatList(ctx, k)
        l.add(JSONObject().put("by", by).put("t", t).put("at", System.currentTimeMillis()))
        val e = prefs(ctx).edit().putString("chat_$k", JSONArray(l.takeLast(60)).toString())
        if (chatOpen != k && by != me(ctx)) e.putInt("unread_$k", prefs(ctx).getInt("unread_$k", 0) + 1)
        e.apply()
    }

    fun unread(ctx: Context, k: String) = prefs(ctx).getInt("unread_$k", 0)

    fun chat(a: Activity, k: String, after: (() -> Unit)? = null) {
        prefs(a).edit().putInt("unread_$k", 0).apply()
        val dp = { v: Float -> Ui.dp(a, v) }
        val me = me(a)
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
        }
        val scroll = android.widget.ScrollView(a).apply { addView(box) }
        fun render() {
            box.removeAllViews()
            val l = chatList(a, k)
            if (l.isEmpty()) box.addView(Ui.label(a, Ui.t(a, "Say hi! Chinu 🤖 listens — call his name to talk to him", "Hi bolo! Chinu 🤖 bhi sun raha hai — naam lo to wo jawab dega"),
                13f, 0xFF8E7A99.toInt()))
            for (m in l.takeLast(40)) {
                val by = m.optString("by")
                val mine = by == me
                box.addView(TextView(a).apply {
                    text = if (mine) m.optString("t") else "$by: ${m.optString("t")}"
                    textSize = 14.5f
                    setTextColor(if (by == BOT) 0xFF6B3FA0.toInt() else 0xFF3B1F4A.toInt())
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(14f).toFloat()
                        setColor(when {
                            mine -> 0xFFFFD6E7.toInt()
                            by == BOT -> 0xFFEDE3FF.toInt()
                            else -> 0xFFE3F2FF.toInt()
                        })
                    }
                    setPadding(dp(12f), dp(7f), dp(12f), dp(7f))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = if (mine) Gravity.END else Gravity.START
                    topMargin = dp(5f)
                })
            }
            scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
        render()
        val input = EditText(a).apply { hint = Ui.t(a, "Type… (say “Chinu” to talk to him)", "Likho… (“Chinu” bolo to wo jawab dega)") }
        fun quick(t: String) = TextView(a).apply {
            text = t
            textSize = 20f
            setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
        }
        val sendBtn = quick("➤").apply { setTextColor(0xFFB0276E.toInt()) }
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), 0, dp(10f), 0)
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            for (e in listOf("😂", "🔥", "😜")) addView(quick(e).apply {
                setOnClickListener {
                    sendChat(a, k, e)
                    render()
                }
            })
            addView(sendBtn)
        }
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280f)))
            addView(row)
        }
        sendBtn.setOnClickListener {
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) {
                input.setText("")
                sendChat(a, k, t)
                render()
            }
        }
        chatOpen = k
        chatRefresh = { a.runOnUiThread { render() } }
        val dlg = AlertDialog.Builder(a).setTitle("💬  " + (NAMES[k] ?: "🎮"))
            .setView(root)
            .setPositiveButton(if (after != null) Ui.t(a, "Back to game", "Game pe wapas") else Ui.t(a, "Close", "Band karo"), null)
            .create()
        dlg.setOnDismissListener {
            chatOpen = null
            chatRefresh = null
            after?.invoke()
        }
        dlg.show()
    }

    private fun sendChat(a: Activity, k: String, t: String) {
        val me = me(a)
        addChat(a, k, me, t)
        send(a, JSONObject().put("k", "chat").put("g", k).put("by", me).put("t", t))
        // 🤖 Chinu joins in — only the sender's phone decides, so he never speaks twice
        if (t.lowercase().contains("chinu") || t.contains("चीनू")) {
            val prompt = "You are Chinu 🤖, a playful, funny AI friend playing the game '${NAMES[k]}' with ${me(a)} and ${other(a)}, a loving couple. " +
                "${me(a)} just said in the game chat: \"$t\". Reply with ONE short, witty line in Hinglish (max 20 words) with an emoji. Only the line."
            Brain.ask(a, prompt, "hi-IN") { r -> botSay(a, k, r.trim().lines().firstOrNull { it.isNotBlank() }?.take(160) ?: "😄") }
        } else if (Math.random() < 0.25) {
            main.postDelayed({ botSay(a, k, (BOT_LINES[k].orEmpty() + BOT_LINES["*"].orEmpty()).random()) }, 1200)
        }
    }

    private fun botSay(ctx: Context, k: String, t: String) {
        addChat(ctx, k, BOT, t)
        send(ctx, JSONObject().put("k", "chat").put("g", k).put("by", BOT).put("t", t))
        chatRefresh?.invoke()
    }

    private fun onChat(app: Context, d: JSONObject, from: String) {
        val k = d.optString("g")
        val by = d.optString("by").ifEmpty { from }
        val t = d.optString("t")
        if (t.isEmpty()) return
        addChat(app, k, by, t)
        if (chatOpen == k) chatRefresh?.invoke()
        else {
            CoupleLink.notify(app, "💬  $by · ${NAMES[k] ?: "🎮"}", t, 433, Intent(app, CoupleActivity::class.java).putExtra("care", "kchat:$k"))
            CoupleActivity.current?.refresh()
        }
    }

    fun chatMenu(a: Activity) {
        val keys = listOf("rps", "wc", "same", "num", "react", "tap", "quiz")
        AlertDialog.Builder(a).setTitle("💬  " + Ui.t(a, "Game chats", "Games ki chat"))
            .setItems(keys.map { (NAMES[it] ?: it) + (if (unread(a, it) > 0) "  🔴 ${unread(a, it)}" else "") }.toTypedArray()) { _, i -> chat(a, keys[i]) }
            .setNegativeButton(Ui.t(a, "Close", "Band karo"), null).show()
    }

    // =============================================================== content
    /** prompt → Chinu's possible answers */
    private val SAME = listOf(
        "Ek fruit bolo 🍎" to "aam, apple, kela, angoor", "Ek colour bolo 🎨" to "pink, red, blue, black",
        "Ek mithai bolo 🍬" to "rasgulla, gulab jamun, jalebi, laddoo", "Ek janwar bolo 🐾" to "dog, cat, lion, panda",
        "Ghoomne ki ek jagah bolo ✈️" to "goa, manali, paris, kashmir", "Ek fast food bolo 🍕" to "pizza, burger, momos, pasta",
        "Ek hero/heroine bolo 🎬" to "shahrukh, salman, deepika, alia", "Ek drink bolo 🥤" to "chai, coffee, coke, lassi",
        "Ek flower bolo 🌸" to "rose, lotus, sunflower, lily", "Ek season bolo 🌦️" to "winter, summer, monsoon, spring",
        "Ek game bolo 🎮" to "ludo, cricket, chess, pubg", "Ek gift bolo 🎁" to "teddy, chocolate, watch, flowers"
    )

    /** question, options, right index */
    private val QUIZ = listOf(
        Triple("Bharat ki rajdhani?", listOf("Mumbai", "Delhi", "Kolkata", "Jaipur"), 1),
        Triple("Indradhanush mein kitne rang?", listOf("5", "6", "7", "8"), 2),
        Triple("Sabse bada planet?", listOf("Earth", "Mars", "Jupiter", "Saturn"), 2),
        Triple("Taj Mahal kis shehar mein hai?", listOf("Agra", "Delhi", "Lucknow", "Bhopal"), 0),
        Triple("Ek saal mein kitne hafte?", listOf("48", "50", "52", "54"), 2),
        Triple("Paani ka formula?", listOf("CO2", "H2O", "O2", "NaCl"), 1),
        Triple("Sabse tez daudne wala janwar?", listOf("Sher", "Cheetah", "Ghoda", "Hiran"), 1),
        Triple("Cricket team mein kitne players?", listOf("9", "10", "11", "12"), 2),
        Triple("Rashtriya pakshi?", listOf("Tota", "Mor", "Kabootar", "Hans"), 1),
        Triple("Suraj kis disha mein ugta hai?", listOf("Pashchim", "Uttar", "Poorv", "Dakshin"), 2),
        Triple("Ek din mein kitne ghante?", listOf("12", "20", "24", "36"), 2),
        Triple("Sabse lambi nadi (duniya)?", listOf("Ganga", "Nile", "Amazon", "Yamuna"), 1),
        Triple("Honey kaun banata hai?", listOf("Titli", "Madhumakhi", "Chiti", "Machhar"), 1),
        Triple("Chaand pe pehla insaan?", listOf("Neil Armstrong", "Kalpana Chawla", "Rakesh Sharma", "Yuri Gagarin"), 0),
        Triple("Computer ka 'dimaag'?", listOf("Mouse", "CPU", "Keyboard", "Monitor"), 1),
        Triple("Rashtriya khel (traditionally)?", listOf("Cricket", "Hockey", "Kabaddi", "Football"), 1),
        Triple("Ek dozen mein kitne?", listOf("10", "12", "14", "20"), 1),
        Triple("Sabse thanda mahadweep?", listOf("Asia", "Europe", "Antarctica", "Africa"), 2),
        Triple("Leap year mein February ke din?", listOf("28", "29", "30", "31"), 1),
        Triple("Pyaar ka rang kaunsa maana jaata hai? 💖", listOf("Hara", "Neela", "Laal", "Peela"), 2)
    )

    // =============================================================== menu + 💞 card
    fun menu(a: Activity) {
        val keys = listOf("rps", "wc", "same", "num", "react", "tap", "quiz")
        AlertDialog.Builder(a).setTitle("🎮  " + Ui.t(a, "Play — you, ${other(a)} & Chinu 🤖", "Khelo — aap, ${other(a)} aur Chinu 🤖"))
            .setItems(keys.map { NAMES[it] ?: it }.toTypedArray()) { _, i ->
                if (keys[i] == "wc") wordChain(a) else start(a, keys[i])
            }.setNegativeButton(Ui.t(a, "Close", "Band karo"), null).show()
    }

    fun action(a: Activity, what: String) {
        if (what.startsWith("kchat:")) return chat(a, what.substringAfter(':'))
        when (what) {
            "khel" -> resume(a)
            "kchat" -> chatMenu(a)
            "wc" -> wordChain(a)
            "games" -> menu(a)
            else -> Pyaar.action(a, what)
        }
    }

    fun card(a: Activity, body: LinearLayout) {
        val dp = { v: Float -> Ui.dp(a, v) }
        body.addView(Ui.label(a, Ui.t(a, "🎮 GAMES FOR THE 3 OF US", "🎮 HUM TEENO KE GAMES"), 12.5f, 0xFF8A4F9E.toInt(), true).apply {
            letterSpacing = 0.1f
            setPadding(dp(4f), dp(26f), 0, dp(8f))
        })
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glass(a, 24f, 0x66)
            setPadding(dp(16f), dp(14f), dp(16f), dp(16f))
        }
        card.addView(Ui.label(a, scoreLine(a), 14.5f, 0xFF3B1F4A.toInt(), true))
        val keys = listOf("rps", "wc", "same", "num", "react", "tap", "quiz")
        keys.chunked(2).forEach { pair ->
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, k ->
                row.addView(Ui.glassButton(a, NAMES[k] ?: k) { if (k == "wc") wordChain(a) else start(a, k) }.apply {
                    textSize = 13.5f
                    setPadding(dp(4f), dp(12f), dp(4f), dp(12f))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i == 1) leftMargin = dp(8f) })
            }
            if (pair.size == 1) row.addView(android.view.View(a), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(8f) })
            card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        }
        val un = listOf("rps", "wc", "same", "num", "react", "tap", "quiz").sumOf { unread(a, it) }
        card.addView(Ui.glassButton(a, Ui.t(a, "💬 Game chats", "💬 Games ki chat") + if (un > 0) "  🔴 $un" else "") { chatMenu(a) }.apply { textSize = 14f },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })
        card.addView(Ui.label(a, Ui.t(a, "Chinu 🤖 plays too! Both phones need the 💞 screen open for ⚡ and 👆.",
            "Chinu 🤖 bhi khelta hai! ⚡ aur 👆 ke liye dono ka 💞 screen khula hona chahiye."), 11.5f, 0xFF8E7A99.toInt()).apply { setPadding(0, dp(10f), 0, 0) })
        body.addView(card)
    }
}
