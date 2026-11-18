package com.meraassistant

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 🎈 Games & memories:
 *  🧠 how well do you know me · 😀 emoji riddle · ✍️ our story, one line each · 🐰 our pet
 *  📊 our stats · ❤️‍🔥 love meter · 🔮 love horoscope · 📅 on this day · 🍬 nickname of the day
 *  🎵 our song · 📜 shayari · 🪙 coin & dice together
 */
object Xtra {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("xtra", Context.MODE_PRIVATE)

    private fun other(ctx: Context): String {
        Speaker.load(ctx)
        return Speaker.other(Profile.load(ctx))
    }

    private fun me(ctx: Context): String {
        Speaker.load(ctx)
        return Speaker.name(Profile.load(ctx))
    }

    private fun today() = LocalDate.now().toEpochDay()
    private fun toast(ctx: Context, en: String, hi: String) = Toast.makeText(ctx, Ui.t(ctx, en, hi), Toast.LENGTH_SHORT).show()
    private fun sent(ctx: Context, ok: Boolean) = if (!ok) toast(ctx, "No internet 😕", "Internet nahi hai 😕") else Unit
    private fun openTo(app: Context, what: String) = Intent(app, CoupleActivity::class.java).putExtra("care", what)

    private fun arr(ctx: Context, key: String): MutableList<JSONObject> {
        val a = try {
            JSONArray(prefs(ctx).getString(key, "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.toMutableList()
    }

    private fun save(ctx: Context, key: String, l: List<JSONObject>, keep: Int = 60) =
        prefs(ctx).edit().putString(key, JSONArray(l.takeLast(keep)).toString()).apply()

    private fun edit(a: Activity, hint: String) = EditText(a).apply { this.hint = hint }

    private fun daily(list: List<String>, salt: Int) = list[Math.floorMod(today() * 31 + salt, list.size.toLong()).toInt()]

    // =============================================================== 🧠 + 😀 guessing games (the asker's phone judges the answer)
    private val QUESTIONS = listOf(
        "Mera favourite colour kaunsa hai?", "Mera favourite khana kya hai?", "Meri favourite movie kaunsi hai?",
        "Main sabse zyada kis cheez se darta/darti hoon?", "Mera dream destination kaunsa hai?", "Mera favourite gaana kaunsa hai?",
        "Meri sabse buri aadat kya hai?", "Bachpan mein mujhe ghar pe kya bulate the?", "Free time mein mujhe kya karna pasand hai?",
        "Meri favourite mithai kaunsi hai?", "Mera lucky number kya hai?", "Mujhe sabse zyada gussa kis baat pe aata hai?",
        "Mera favourite season kaunsa hai?", "Main chai pasand karta/karti hoon ya coffee?", "Mera favourite cartoon kaunsa tha?"
    )

    fun scores(ctx: Context) = prefs(ctx).getInt("scoreMe", 0) to prefs(ctx).getInt("scoreThem", 0)

    fun newGuess(a: Activity) {
        AlertDialog.Builder(a).setTitle("🎮  " + Ui.t(a, "Which game?", "Kaunsa game?"))
            .setItems(arrayOf(Ui.t(a, "🧠 How well do you know me?", "🧠 Mujhe kitna jaante ho?"), Ui.t(a, "😀 Emoji riddle", "😀 Emoji paheli"))) { _, i ->
                if (i == 0) AlertDialog.Builder(a).setTitle("🧠  " + Ui.t(a, "Pick a question about you", "Apne baare mein sawaal chuno"))
                    .setItems(QUESTIONS.toTypedArray()) { _, q -> askAnswer(a, "quiz", QUESTIONS[q]) }.show()
                else {
                    val e = edit(a, Ui.t(a, "Emojis, e.g. 🦁👑", "Emojis, jaise 🦁👑"))
                    AlertDialog.Builder(a).setTitle("😀  " + Ui.t(a, "A movie / song / thing in emojis", "Movie / gaana / cheez emojis mein"))
                        .setView(e).setPositiveButton(Ui.t(a, "Next", "Aage")) { _, _ ->
                            val em = e.text.toString().trim()
                            if (em.isNotEmpty()) askAnswer(a, "emoji", em)
                        }.setNegativeButton(Ui.t(a, "Cancel", "Rehne do"), null).show()
                }
            }.show()
    }

    private fun askAnswer(a: Activity, mode: String, q: String) {
        val e = edit(a, Ui.t(a, "The right answer (only your phone knows it 🤫)", "Sahi jawab (sirf aapke phone ko pata hoga 🤫)"))
        AlertDialog.Builder(a).setTitle(q).setView(e)
            .setPositiveButton(Ui.t(a, "Ask ${other(a)}", "${other(a)} se poocho")) { _, _ ->
                val ans = e.text.toString().trim()
                if (ans.isEmpty()) return@setPositiveButton
                val id = System.currentTimeMillis()
                val mine = arr(a, "asked")
                mine.add(JSONObject().put("id", id).put("mode", mode).put("q", q).put("ans", ans))
                save(a, "asked", mine, 30)
                CoupleLink.send(a, "guess_q", JSONObject().put("id", id).put("mode", mode).put("q", q)) { ok ->
                    if (ok) toast(a, "Sent! Let's see if ${other(a)} gets it 😏", "Bhej diya! Dekhte hain ${other(a)} bata paate hain ya nahi 😏") else sent(a, false)
                }
            }.setNegativeButton(Ui.t(a, "Cancel", "Rehne do"), null).show()
    }

    fun onGuessQ(app: Context, d: JSONObject, from: String) {
        val l = arr(app, "toGuess")
        if (l.any { it.optLong("id") == d.optLong("id") }) return
        l.add(JSONObject().put("id", d.optLong("id")).put("mode", d.optString("mode")).put("q", d.optString("q")).put("from", from))
        save(app, "toGuess", l, 20)
        val title = if (d.optString("mode") == "emoji") Ui.t(app, "$from sent an emoji riddle", "$from ne emoji paheli bheji")
        else Ui.t(app, "$from asks: how well do you know me?", "$from pooch rahe hain: mujhe kitna jaante ho?")
        CoupleLink.notify(app, "🎮  $title", d.optString("q"), 411, openTo(app, "guess"))
        CoupleActivity.current?.refresh()
    }

    fun guessWaiting(ctx: Context) = arr(ctx, "toGuess").size

    fun answerGuess(a: Activity) {
        val l = arr(a, "toGuess")
        val g = l.firstOrNull() ?: return newGuess(a)
        val e = edit(a, Ui.t(a, "Your guess…", "Aapka jawab…"))
        AlertDialog.Builder(a).setTitle((if (g.optString("mode") == "emoji") "😀 " else "🧠 ") + g.optString("from"))
            .setMessage(g.optString("q")).setView(e)
            .setPositiveButton(Ui.t(a, "Answer", "Jawab do")) { _, _ ->
                val guess = e.text.toString().trim()
                if (guess.isEmpty()) return@setPositiveButton
                l.removeAll { it.optLong("id") == g.optLong("id") }
                save(a, "toGuess", l, 20)
                CoupleLink.send(a, "guess_a", JSONObject().put("id", g.optLong("id")).put("g", guess)) { ok ->
                    if (ok) toast(a, "Answer sent — waiting for the verdict 🤞", "Jawab chala gaya — result ka intezaar 🤞") else sent(a, false)
                }
                CoupleActivity.current?.refresh()
            }.setNegativeButton(Ui.t(a, "Later", "Baad mein"), null).show()
    }

    private fun norm(s: String) = NameMatcher.toLatin(s).replace(" ", "")

    private fun matches(ans: String, guess: String): Boolean {
        val a = norm(ans)
        val g = norm(guess)
        if (a.isEmpty() || g.isEmpty()) return false
        return a == g || (g.length >= 3 && a.contains(g)) || (a.length >= 3 && g.contains(a)) || NameMatcher.score(guess, ans) >= 0.75
    }

    fun onGuessA(app: Context, d: JSONObject, from: String) {
        val q = arr(app, "asked").firstOrNull { it.optLong("id") == d.optLong("id") } ?: return
        val guess = d.optString("g")
        val ok = matches(q.optString("ans"), guess)
        if (ok) prefs(app).edit().putInt("scoreThem", prefs(app).getInt("scoreThem", 0) + 1).apply()
        CoupleLink.send(app, "guess_r", JSONObject().put("id", d.optLong("id")).put("ok", ok).put("ans", q.optString("ans")).put("q", q.optString("q")))
        CoupleLink.notify(app, (if (ok) "✅  " else "❌  ") + Ui.t(app, "$from answered: “$guess”", "$from ka jawab: “$guess”"),
            q.optString("q") + " → " + q.optString("ans") + if (ok) "  🎉" else "  😜", 412, Intent(app, CoupleActivity::class.java))
        CoupleActivity.current?.refresh()
    }

    fun onGuessR(app: Context, d: JSONObject, from: String) {
        val ok = d.optBoolean("ok")
        if (ok) prefs(app).edit().putInt("scoreMe", prefs(app).getInt("scoreMe", 0) + 1).apply()
        CoupleLink.notify(app, if (ok) "🎉  " + Ui.t(app, "Correct! You know $from so well", "Sahi jawab! Aap $from ko achhe se jaante ho") else "😜  " + Ui.t(app, "Oops, wrong!", "Oops, galat!"),
            d.optString("q") + " → " + d.optString("ans"), 413, Intent(app, CoupleActivity::class.java))
        CoupleActivity.current?.celebrate(if (ok) "🎉" else "😜", if (ok) Ui.t(app, "Correct!", "Sahi jawab!") else Ui.t(app, "Right answer: ${d.optString("ans")}", "Sahi jawab tha: ${d.optString("ans")}"))
    }

    // =============================================================== ✍️ our story, one line each
    fun showStory(a: Activity) {
        val lines = arr(a, "story")
        val text = if (lines.isEmpty()) Ui.t(a, "Start our story with the first line… ✨", "Pehli line se hamari kahani shuru karo… ✨")
        else lines.takeLast(40).joinToString("\n\n") { "${it.optString("t")}\n      — ${it.optString("by")}" }
        val myTurn = lines.isEmpty() || lines.last().optString("by") != me(a)
        val tv = TextView(a).apply {
            this.text = text
            textSize = 15.5f
            setTextColor(0xFF3B1F4A.toInt())
            setPadding(Ui.dp(a, 20f), Ui.dp(a, 10f), Ui.dp(a, 20f), Ui.dp(a, 10f))
        }
        val b = AlertDialog.Builder(a).setTitle("✍️  " + Ui.t(a, "Our story", "Hamari kahani"))
            .setView(ScrollView(a).apply { addView(tv) })
            .setNegativeButton(Ui.t(a, "Close", "Band karo"), null)
        if (myTurn) b.setPositiveButton(Ui.t(a, "➕ Add a line", "➕ Line jodo")) { _, _ ->
            val e = edit(a, Ui.t(a, "…and then", "…aur phir")).apply { minLines = 2 }
            AlertDialog.Builder(a).setTitle("✍️  " + Ui.t(a, "Your line", "Aapki line")).setView(e)
                .setPositiveButton(Ui.t(a, "Add", "Jodo")) { _, _ ->
                    val t = e.text.toString().trim().take(300)
                    if (t.isEmpty()) return@setPositiveButton
                    val item = JSONObject().put("id", System.currentTimeMillis()).put("t", t).put("by", me(a))
                    val l = arr(a, "story")
                    l.add(item)
                    save(a, "story", l, 200)
                    CoupleLink.send(a, "ourstory", item) { ok -> sent(a, ok) }
                    showStory(a)
                }.setNegativeButton(Ui.t(a, "Cancel", "Rehne do"), null).show()
        } else b.setMessage(Ui.t(a, "Waiting for ${other(a)}'s line ⏳", "${other(a)} ki line ka intezaar ⏳"))
        b.show()
    }

    fun onStory(app: Context, d: JSONObject, from: String) {
        val l = arr(app, "story")
        if (l.any { it.optLong("id") == d.optLong("id") }) return
        l.add(d)
        save(app, "story", l, 200)
        CoupleLink.notify(app, "✍️  " + Ui.t(app, "$from continued our story — your turn!", "$from ne kahani aage badhayi — ab aapki baari!"),
            d.optString("t"), 414, openTo(app, "story"))
        CoupleActivity.current?.refresh()
    }

    // =============================================================== 🐰 our pet (both have to care for it)
    private fun hoursSince(t: Long) = if (t == 0L) 99.0 else (System.currentTimeMillis() - t) / 3600_000.0

    fun petStats(ctx: Context): Pair<Int, Int> {
        val food = (100 - hoursSince(prefs(ctx).getLong("petFed", 0L)) * 7).toInt().coerceIn(0, 100)
        val fun1 = (100 - hoursSince(prefs(ctx).getLong("petPlay", 0L)) * 6).toInt().coerceIn(0, 100)
        return food to fun1
    }

    fun petLine(ctx: Context): String {
        val (food, joy) = petStats(ctx)
        val name = prefs(ctx).getString("petName", "Bunny") ?: "Bunny"
        val face = when {
            food < 25 -> Ui.t(ctx, "is hungry 😢", "ko bhookh lagi hai 😢")
            joy < 25 -> Ui.t(ctx, "is bored 🥱", "bore ho raha hai 🥱")
            food > 70 && joy > 70 -> Ui.t(ctx, "is super happy 🥰", "bahut khush hai 🥰")
            else -> Ui.t(ctx, "is okay 🙂", "theek hai 🙂")
        }
        return "🐰 $name $face  ·  🍎 $food%  ·  🧸 $joy%"
    }

    fun petAct(ctx: Context, act: String) {
        val now = System.currentTimeMillis()
        prefs(ctx).edit().putLong(if (act == "feed") "petFed" else "petPlay", now).apply()
        CoupleLink.send(ctx, "pet", JSONObject().put("act", act).put("at", now))
        toast(ctx, if (act == "feed") "🍎 Yum! Thank you 🐰" else "🧸 Wheee! 🐰", if (act == "feed") "🍎 Yummy! Thank you 🐰" else "🧸 Maza aa gaya! 🐰")
        CoupleActivity.current?.refresh()
    }

    fun onPet(app: Context, d: JSONObject) {
        val at = d.optLong("at")
        val key = if (d.optString("act") == "feed") "petFed" else "petPlay"
        if (at > prefs(app).getLong(key, 0L)) prefs(app).edit().putLong(key, at).apply()
        if (d.has("name")) prefs(app).edit().putString("petName", d.optString("name")).apply()
        CoupleActivity.current?.refresh()
    }

    fun petDialog(a: Activity) {
        AlertDialog.Builder(a).setTitle(petLine(a))
            .setItems(arrayOf(Ui.t(a, "🍎 Feed", "🍎 Khana khilao"), Ui.t(a, "🧸 Play", "🧸 Saath khelo"), Ui.t(a, "✏️ Rename", "✏️ Naam rakho"))) { _, i ->
                when (i) {
                    0 -> petAct(a, "feed")
                    1 -> petAct(a, "play")
                    else -> {
                        val e = edit(a, "Bunny")
                        AlertDialog.Builder(a).setTitle("🐰  " + Ui.t(a, "Name our pet", "Pet ka naam")).setView(e)
                            .setPositiveButton("OK") { _, _ ->
                                val n = e.text.toString().trim().take(20)
                                if (n.isNotEmpty()) {
                                    prefs(a).edit().putString("petName", n).apply()
                                    CoupleLink.send(a, "pet", JSONObject().put("act", "name").put("name", n).put("at", 0L))
                                    CoupleActivity.current?.refresh()
                                }
                            }.show()
                    }
                }
            }.show()
    }

    // =============================================================== 📊 our stats · ❤️‍🔥 love meter
    fun statsDialog(a: Activity) {
        val jar = LoveJar.all(a)
        fun c(k: String, mine: Boolean) = jar.count { it.kind == k && it.mine == mine }
        val other = other(a)
        var pics = 0
        for (back in 0L..30L) pics += SelfieStreak.moments(a, today() - back, "me").size + SelfieStreak.moments(a, today() - back, "them").size
        val (sMe, sThem) = scores(a)
        val rows = listOf(
            "🤗 " + Ui.t(a, "Hugs", "Jhappiyan") to (c("hug", true) to c("hug", false)),
            "😘 Kisses" to (c("kiss", true) to c("kiss", false)),
            "🥺 Miss you" to (c("miss", true) to c("miss", false)),
            "❤️ Love you" to (c("love", true) to c("love", false)),
            "💌 " + Ui.t(a, "Notes", "Messages") to (c("note", true) to c("note", false)),
            "🎤 " + Ui.t(a, "Voice messages", "Voice messages") to (VoiceNote.all(a).count { it.mine } to VoiceNote.all(a).count { !it.mine }),
            "🧠 " + Ui.t(a, "Guessed right", "Sahi jawab") to (sMe to sThem)
        )
        val text = Ui.t(a, "You  ·  $other\n\n", "Aap  ·  $other\n\n") + rows.joinToString("\n") { (k, v) -> "$k:   ${v.first}  ·  ${v.second}" } +
            "\n\n📸 " + Ui.t(a, "Selfie streak: ${SelfieStreak.streak(a)} (best ${SelfieStreak.best(a)})", "Selfie streak: ${SelfieStreak.streak(a)} (best ${SelfieStreak.best(a)})") +
            "\n📷 " + Ui.t(a, "Photos shared (30 days): $pics", "Photos bheji (30 din): $pics")
        AlertDialog.Builder(a).setTitle("📊  " + Ui.t(a, "Our stats", "Hamare stats")).setMessage(text).setPositiveButton("💞", null).show()
    }

    fun loveMeter(ctx: Context): Int {
        val start = LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val jar = LoveJar.all(ctx).filter { it.at >= start }
        var s = jar.size * 8 + jar.map { it.mine }.distinct().size * 10
        if (SelfieStreak.complete(ctx, today())) s += 20
        s += minOf(20, (SelfieStreak.moments(ctx, today(), "me").size + SelfieStreak.moments(ctx, today(), "them").size) * 4)
        s += VoiceNote.all(ctx).count { it.at >= start } * 6
        return s.coerceIn(5, 100)
    }

    fun meterLine(ctx: Context): String {
        val v = loveMeter(ctx)
        val bar = "▰".repeat(v / 10) + "▱".repeat(10 - v / 10)
        return "❤️‍🔥 " + Ui.t(ctx, "Today's love meter", "Aaj ka love meter") + ": $v%  $bar"
    }

    // =============================================================== 🔮 love horoscope · 🍬 nickname of the day
    private val HOROSCOPE = listOf(
        "Aaj ek chhoti si baat pe bahut hansi aayegi 😂", "Aaj kisi purani yaad se dil khush hoga 🥹",
        "Aaj ek surprise milne wala hai 🎁 (ya dene wala 😉)", "Aaj thoda zyada miss karoge — call kar lena 📞",
        "Aaj ka din compliments ka hai — ek dena zaroor 😍", "Aaj khane pe bahas ho sakti hai 🍕 vs 🍜 — pizza jeetega",
        "Aaj neend mein ek-dusre ke sapne aayenge 💭", "Aaj ki shaam bahut pyaari jayegi 🌇",
        "Aaj thoda sabr rakhna, kal sab achha 🤞", "Aaj ek naya plan banega jo yaad rahega 🗺️",
        "Aaj voice message bhejoge to din ban jayega 🎤", "Aaj ki lucky cheez: ek pyaari selfie 🤳",
        "Aaj 'sorry' bolne se pehle hi baat ban jayegi 🤗", "Aaj dono ek hi cheez sochoge — try karke dekho 🤯",
        "Aaj chocolate ka din hai 🍫", "Aaj ek gaana dono ko ek saath yaad aayega 🎵",
        "Aaj thoda dramatic mood rahega — jhappi se theek 🤗", "Aaj ek-dusre ki tareef karke dekho, magic hoga ✨"
    )
    private val NICKS = listOf(
        "Gulab Jamun 🍬", "Golgappa 🫓", "Cutie Pie 🥧", "Teddy 🧸", "Laddoo 🟡", "Jalebi 🍥", "Chhota Bheem 💪", "Pari 🧚",
        "Momo 🥟", "Rasmalai 🍮", "Panda 🐼", "Sunshine ☀️", "Kaju Katli 💎", "Bunny 🐰", "Chocolate 🍫", "Butterfly 🦋",
        "Sona 🌟", "Mishti 🍬", "Kitkat 🍫", "Pikachu ⚡", "Cupcake 🧁", "Babu 👶", "Rasgulla ⚪", "Jaanu 💖", "Smiley 😄"
    )

    fun horoscope() = daily(HOROSCOPE, 5)
    fun nickname() = daily(NICKS, 11)

    fun sendNickname(ctx: Context) {
        val n = nickname()
        CoupleLink.send(ctx, "note", JSONObject().put("text", "🍬 " + Ui.t(ctx, "Today you are my $n", "Aaj se aap mere $n ho"))) { ok ->
            if (ok) toast(ctx, "Sent “$n” 🍬", "“$n” bhej diya 🍬") else sent(ctx, false)
        }
    }

    // =============================================================== 📅 on this day
    private fun picFor(ctx: Context, day: Long): Pair<Bitmap, String>? {
        for (who in listOf("me", "them")) for (k in listOf("d", "x")) {
            if (SelfieStreak.hasImage(ctx, k, who, day)) SelfieStreak.image(ctx, k, who, day, 900)?.let { return it to (if (who == "me") me(ctx) else other(ctx)) }
        }
        for (who in listOf("me", "them")) SelfieStreak.moments(ctx, day, who).firstOrNull()?.let { f ->
            SelfieStreak.load(f, 900)?.let { return it to (if (who == "me") me(ctx) else other(ctx)) }
        }
        return null
    }

    fun onThisDay(a: Activity) {
        val t = today()
        for ((back, en, hi) in listOf(Triple(365L, "One year ago today", "Ek saal pehle aaj"), Triple(30L, "One month ago today", "Ek mahina pehle aaj"),
            Triple(7L, "One week ago today", "Ek hafta pehle aaj"), Triple(1L, "Yesterday", "Kal"))) {
            val pic = picFor(a, t - back) ?: continue
            val img = ImageView(a).apply {
                adjustViewBounds = true
                setImageBitmap(pic.first)
            }
            AlertDialog.Builder(a).setTitle("📅  " + Ui.t(a, en, hi) + " · " +
                LocalDate.ofEpochDay(t - back).format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)) + " · " + pic.second)
                .setView(img).setPositiveButton("🥹", null).show()
            return
        }
        toast(a, "No old photos yet — make memories today 📸", "Abhi purani photos nahi — aaj yaadein banao 📸")
    }

    // =============================================================== 🎵 our song
    fun ourSong(ctx: Context) = prefs(ctx).getString("song", "").orEmpty()

    fun songDialog(a: Activity) {
        val cur = ourSong(a)
        val e = edit(a, Ui.t(a, "Song name — e.g. Tum Hi Ho", "Gaane ka naam — jaise Tum Hi Ho")).apply { setText(cur) }
        val b = AlertDialog.Builder(a).setTitle("🎵  " + Ui.t(a, "Our song", "Hamara gaana")).setView(e)
            .setPositiveButton(Ui.t(a, "Save for both", "Dono ke liye save")) { _, _ ->
                val s = e.text.toString().trim().take(80)
                if (s.isNotEmpty()) {
                    prefs(a).edit().putString("song", s).apply()
                    CoupleLink.send(a, "oursong", JSONObject().put("q", s)) { ok -> sent(a, ok) }
                    CoupleActivity.current?.refresh()
                }
            }.setNegativeButton(Ui.t(a, "Cancel", "Rehne do"), null)
        if (cur.isNotEmpty()) b.setNeutralButton("▶️ Play") { _, _ -> playSong(a) }
        b.show()
    }

    fun playSong(a: Activity) {
        val s = ourSong(a)
        if (s.isEmpty()) return songDialog(a)
        try {
            a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Plus.songUrl(s))))
        } catch (e: Exception) {
        }
    }

    fun onOurSong(app: Context, d: JSONObject, from: String) {
        val q = d.optString("q")
        if (q.isEmpty()) return
        prefs(app).edit().putString("song", q).apply()
        CoupleLink.notify(app, "🎵  " + Ui.t(app, "$from set “our song”", "$from ne “hamara gaana” set kiya"), q, 415, Intent(Intent.ACTION_VIEW, Uri.parse(Plus.songUrl(q))))
        CoupleActivity.current?.refresh()
    }

    // =============================================================== 📜 shayari (our own lines)
    private val SHAYARI = listOf(
        "Chaand bhi poochta hai raat ko,\nkiski yaad mein jaage ho itni der tak 🌙",
        "Tumse baat na ho to din adhoora lagta hai,\nhar khushi mein bhi kuch kam sa lagta hai 💭",
        "Dooriyan sirf raaste ki hain,\ndil to roz tumhare paas se guzarta hai 💞",
        "Tumhari ek smile ke liye,\nmain saari duniya se lad jaaun 😊",
        "Phone ki screen pe tumhara naam aaye,\nto lagta hai jaise din mein dhoop aa gayi ☀️",
        "Tum ho to har mausam suhana hai,\nwarna baarish bhi bas paani hai 🌧️",
        "Kuch log dil mein rehte hain,\ntum to dhadkan ban gaye ho 💓",
        "Har subah tumhara khayal pehle aata hai,\nchai baad mein ☕",
        "Tumse milke laga,\nkhwaab bhi sach hote hain ✨",
        "Na chaand chahiye na taare,\nbas tum raho saath hamare 🌌",
        "Thodi si naraazgi, thoda sa pyaar,\nyahi to hai hamara sansaar 🤗",
        "Tumhari yaad bhi tumhari tarah hai,\nbina bataye aa jaati hai 🥰"
    )

    fun sendShayari(a: Activity) {
        val s = SHAYARI.random()
        AlertDialog.Builder(a).setTitle("📜  Shayari").setMessage(s)
            .setPositiveButton(Ui.t(a, "Send to ${other(a)}", "${other(a)} ko bhejo")) { _, _ ->
                CoupleLink.send(a, "note", JSONObject().put("text", "📜 $s")) { ok -> if (ok) toast(a, "Sent 📜", "Bhej di 📜") else sent(a, false) }
            }
            .setNeutralButton(Ui.t(a, "Another", "Aur ek")) { _, _ -> sendShayari(a) }
            .setNegativeButton(Ui.t(a, "Close", "Band karo"), null).show()
    }

    // =============================================================== 🪙🎲 coin & dice — both phones see the same result
    fun toss(a: Activity) {
        AlertDialog.Builder(a).setTitle(Ui.t(a, "🪙 Coin or 🎲 dice?", "🪙 Sikka ya 🎲 paasa?"))
            .setItems(arrayOf(Ui.t(a, "🪙 Flip a coin", "🪙 Sikka uchhalo"), Ui.t(a, "🎲 Roll a dice", "🎲 Paasa phenko"))) { _, i ->
                val r = if (i == 0) (if (Math.random() < 0.5) Ui.t(a, "Heads 👑", "Heads 👑") else Ui.t(a, "Tails 🦅", "Tails 🦅"))
                else "🎲 " + (1..6).random()
                CoupleLink.send(a, "toss", JSONObject().put("r", r))
                AlertDialog.Builder(a).setTitle(r).setMessage(Ui.t(a, "${other(a)} sees the same result 👀", "${other(a)} ko bhi yahi result dikhega 👀"))
                    .setPositiveButton("OK", null).show()
            }.show()
    }

    fun onToss(app: Context, d: JSONObject, from: String) {
        val r = d.optString("r")
        CoupleLink.notify(app, Ui.t(app, "$from tossed: $r", "$from ka toss: $r"), Ui.t(app, "Fair and square 😇", "Bilkul imaandaari se 😇"), 416,
            Intent(app, CoupleActivity::class.java))
        CoupleActivity.current?.celebrate(if (r.startsWith("🎲")) "🎲" else "🪙", Ui.t(app, "$from: $r", "$from: $r"))
    }

    // =============================================================== actions + 💞 card
    fun action(a: Activity, what: String) {
        when (what) {
            "guess" -> if (guessWaiting(a) > 0) answerGuess(a) else newGuess(a)
            "story" -> showStory(a)
            "pet" -> petDialog(a)
            "stats" -> statsDialog(a)
            "thisday" -> onThisDay(a)
            "oursong" -> playSong(a)
            "shayari" -> sendShayari(a)
            "toss" -> toss(a)
            else -> Special.action(a, what)
        }
    }

    fun card(a: Activity, body: LinearLayout) {
        val dp = { v: Float -> Ui.dp(a, v) }
        val other = other(a)
        body.addView(Ui.label(a, Ui.t(a, "🎈 GAMES & MEMORIES", "🎈 KHEL & YAADEIN"), 12.5f, 0xFF8A4F9E.toInt(), true).apply {
            letterSpacing = 0.1f
            setPadding(dp(4f), dp(26f), 0, dp(8f))
        })
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glass(a, 24f, 0x66)
            setPadding(dp(16f), dp(14f), dp(16f), dp(16f))
        }
        fun line(t: String, bold: Boolean = false, click: (() -> Unit)? = null) {
            card.addView(Ui.label(a, t, 14f, if (bold) 0xFFB0276E.toInt() else 0xFF3B1F4A.toInt(), bold).apply {
                setPadding(0, dp(2f), 0, dp(5f))
                if (click != null) setOnClickListener { click() }
            })
        }
        val g = guessWaiting(a)
        if (g > 0) line("🎮 " + Ui.t(a, "$other is waiting for your answer — tap!", "$other aapke jawab ka intezaar kar rahe hain — tap karo!"), true) { answerGuess(a) }
        val story = arr(a, "story")
        if (story.isNotEmpty() && story.last().optString("by") != me(a)) line("✍️ " + Ui.t(a, "Your turn in our story", "Kahani mein aapki baari"), true) { showStory(a) }
        line(meterLine(a))
        line(petLine(a)) { petDialog(a) }
        line("🔮 " + horoscope())
        line("🍬 " + Ui.t(a, "Nickname of the day: ", "Aaj ka nickname: ") + nickname())
        val song = ourSong(a)
        if (song.isNotEmpty()) line("🎵 " + Ui.t(a, "Our song: $song ▶️", "Hamara gaana: $song ▶️")) { playSong(a) }
        val (sMe, sThem) = scores(a)
        if (sMe + sThem > 0) line("🧠 " + Ui.t(a, "Guess score — you $sMe · $other $sThem", "Guess score — aap $sMe · $other $sThem"))
        fun row(l: Pair<String, () -> Unit>, r: Pair<String, () -> Unit>) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Ui.glassButton(a, l.first, l.second).apply { textSize = 14f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Ui.glassButton(a, r.first, r.second).apply { textSize = 14f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8f) })
            card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        }
        row(Ui.t(a, "🧠 Guess game", "🧠 Guess game") to { if (guessWaiting(a) > 0) answerGuess(a) else newGuess(a) },
            Ui.t(a, "✍️ Our story", "✍️ Hamari kahani") to { showStory(a) })
        row(Ui.t(a, "🐰 Our pet", "🐰 Hamara pet") to { petDialog(a) },
            Ui.t(a, "📊 Our stats", "📊 Hamare stats") to { statsDialog(a) })
        row(Ui.t(a, "📅 On this day", "📅 Purani yaad") to { onThisDay(a) },
            Ui.t(a, "🎵 Our song", "🎵 Hamara gaana") to { songDialog(a) })
        row(Ui.t(a, "📜 Shayari", "📜 Shayari") to { sendShayari(a) },
            Ui.t(a, "🪙 Toss / 🎲", "🪙 Toss / 🎲") to { toss(a) })
        row(Ui.t(a, "🍬 Send nickname", "🍬 Nickname bhejo") to { sendNickname(a) },
            Ui.t(a, "🔮 Send horoscope", "🔮 Rashifal bhejo") to {
                CoupleLink.send(a, "note", JSONObject().put("text", "🔮 " + Ui.t(a, "Our love horoscope today: ", "Aaj ka hamara love rashifal: ") + horoscope())) { ok ->
                    if (ok) toast(a, "Sent 🔮", "Bhej diya 🔮") else sent(a, false)
                }
            })
        body.addView(card)
    }
}
