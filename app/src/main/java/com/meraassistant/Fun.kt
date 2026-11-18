package com.meraassistant

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 🎉 Fun together:
 *  🔒 secret (read-once) message · 🗳️ decide together (poll) · 💡 date ideas
 *  🎬 3-2-1 start together · 🌦️ weather where the partner is · 💖 reasons I love you
 */
object Fun {
    private val main = Handler(Looper.getMainLooper())
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("fun", Context.MODE_PRIVATE)

    private fun other(ctx: Context): String {
        Speaker.load(ctx)
        return Speaker.other(Profile.load(ctx))
    }

    private fun toast(ctx: Context, en: String, hi: String) = Toast.makeText(ctx, Ui.t(ctx, en, hi), Toast.LENGTH_SHORT).show()

    private fun sent(ctx: Context, ok: Boolean, en: String, hi: String) =
        if (ok) toast(ctx, en, hi) else toast(ctx, "No internet 😕", "Internet nahi hai 😕")

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

    private fun input(a: Activity, hint: String, multi: Boolean = false) = EditText(a).apply {
        this.hint = hint
        if (multi) minLines = 2
    }

    // =============================================================== 🔒 secret, read-once message
    fun secretDialog(a: Activity) {
        val other = other(a)
        val e = input(a, Ui.t(a, "Only $other can read it, just once 🤫", "Sirf $other padh payenge, wo bhi ek baar 🤫"), true)
        AlertDialog.Builder(a).setTitle("🔒  " + Ui.t(a, "Secret message", "Secret message")).setView(e)
            .setPositiveButton(Ui.t(a, "Send", "Bhejo")) { _, _ ->
                val t = e.text.toString().trim()
                if (t.isNotEmpty()) CoupleLink.send(a, "secret", JSONObject().put("id", System.currentTimeMillis()).put("text", t)) { ok ->
                    sent(a, ok, "Secret sent 🔒 It vanishes after $other reads it", "Secret chala gaya 🔒 $other ke padhte hi gayab")
                }
            }.setNegativeButton(Ui.t(a, "Cancel", "Rehne do"), null).show()
    }

    fun onSecret(app: Context, d: JSONObject, from: String) {
        val t = d.optString("text")
        if (t.isEmpty()) return
        val l = arr(app, "secrets")
        if (l.any { it.optLong("id") == d.optLong("id") }) return
        l.add(JSONObject().put("id", d.optLong("id")).put("text", t).put("from", from))
        save(app, "secrets", l, 20)
        CoupleLink.notify(app, "🔒  " + Ui.t(app, "$from sent you a secret", "$from ne aapko ek secret bheja"),
            Ui.t(app, "Tap to read — it disappears after one look 🤫", "Padhne ke liye tap karo — ek baar ke baad gayab 🤫"), 392,
            Intent(app, CoupleActivity::class.java).putExtra("care", "secret"))
        CoupleLink.buzz(app, longArrayOf(0, 40, 40, 40, 40, 40))
        CoupleActivity.current?.refresh()
    }

    fun secretsWaiting(ctx: Context) = arr(ctx, "secrets").size

    fun readSecret(a: Activity) {
        val l = arr(a, "secrets")
        if (l.isEmpty()) {
            toast(a, "No secrets waiting 🤫", "Koi secret nahi hai 🤫")
            return
        }
        val s = l.removeAt(0)
        save(a, "secrets", l, 20)
        CoupleLink.send(a, "secret_read", JSONObject().put("id", s.optLong("id")))
        AlertDialog.Builder(a).setTitle("🔒  " + Ui.t(a, "From ${s.optString("from")}", "${s.optString("from")} ki taraf se"))
            .setMessage(s.optString("text") + "\n\n" + Ui.t(a, "(This message is now gone 💨)", "(Ye message ab gayab ho gaya 💨)"))
            .setPositiveButton("💖", null)
            .setOnDismissListener { CoupleActivity.current?.refresh() }
            .show()
    }

    fun onSecretRead(app: Context, from: String) {
        CoupleLink.notify(app, "👀  " + Ui.t(app, "$from read your secret", "$from ne aapka secret padh liya"),
            Ui.t(app, "…and it's gone forever 💨", "…aur wo hamesha ke liye gayab 💨"), 393, Intent(app, CoupleActivity::class.java))
    }

    // =============================================================== 🗳️ decide together
    private fun polls(ctx: Context) = arr(ctx, "polls")

    fun latestPoll(ctx: Context) = polls(ctx).lastOrNull()

    private fun putPoll(ctx: Context, p: JSONObject) {
        val l = polls(ctx)
        val i = l.indexOfFirst { it.optLong("id") == p.optLong("id") }
        if (i >= 0) l[i] = p else l.add(p)
        save(ctx, "polls", l, 10)
    }

    fun newPoll(a: Activity) {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(a, 18f), Ui.dp(a, 4f), Ui.dp(a, 18f), 0)
        }
        val q = input(a, Ui.t(a, "Question, e.g. What shall we eat?", "Sawaal, jaise Aaj kya khayein?"))
        val o = input(a, Ui.t(a, "Options with commas: Pizza, Momos, Biryani", "Options comma se: Pizza, Momos, Biryani"))
        box.addView(q)
        box.addView(o)
        AlertDialog.Builder(a).setTitle("🗳️  " + Ui.t(a, "Decide together", "Saath mein faisla")).setView(box)
            .setPositiveButton(Ui.t(a, "Ask", "Poocho")) { _, _ ->
                val opts = o.text.toString().split(',', '/', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(5)
                val question = q.text.toString().trim().ifEmpty { Ui.t(a, "What do you choose?", "Aap kya chunoge?") }
                if (opts.size < 2) {
                    toast(a, "Give at least 2 options", "Kam se kam 2 options likho")
                    return@setPositiveButton
                }
                val p = JSONObject().put("id", System.currentTimeMillis()).put("q", question).put("opts", JSONArray(opts))
                    .put("me", -1).put("them", -1)
                putPoll(a, p)
                CoupleLink.send(a, "poll", JSONObject().put("id", p.optLong("id")).put("q", question).put("opts", JSONArray(opts))) { ok ->
                    sent(a, ok, "Asked ${other(a)} 🗳️ Now vote yourself too", "${other(a)} se pooch liya 🗳️ Ab aap bhi vote karo")
                }
                showPoll(a)
            }.setNegativeButton(Ui.t(a, "Cancel", "Rehne do"), null).show()
    }

    fun onPoll(app: Context, d: JSONObject, from: String) {
        val p = JSONObject().put("id", d.optLong("id")).put("q", d.optString("q")).put("opts", d.optJSONArray("opts") ?: JSONArray())
            .put("me", -1).put("them", -1)
        putPoll(app, p)
        CoupleLink.notify(app, "🗳️  " + Ui.t(app, "$from asks: ${p.optString("q")}", "$from pooch rahe hain: ${p.optString("q")}"),
            Ui.t(app, "Tap to vote", "Vote karne ke liye tap karo"), 394, Intent(app, CoupleActivity::class.java).putExtra("care", "poll"))
        CoupleLink.buzz(app, longArrayOf(0, 60, 50, 60))
        CoupleActivity.current?.refresh()
    }

    fun onVote(app: Context, d: JSONObject, from: String) {
        val p = polls(app).firstOrNull { it.optLong("id") == d.optLong("id") } ?: return
        p.put("them", d.optInt("i", -1))
        putPoll(app, p)
        val opts = p.optJSONArray("opts") ?: JSONArray()
        CoupleLink.notify(app, "🗳️  " + Ui.t(app, "$from voted", "$from ne vote kiya"),
            pollResult(app, p) ?: Ui.t(app, "Your turn to vote!", "Ab aapki baari!"), 394,
            Intent(app, CoupleActivity::class.java).putExtra("care", "poll"))
        if (opts.length() == 0) return
        CoupleActivity.current?.refresh()
    }

    fun pollResult(ctx: Context, p: JSONObject): String? {
        val opts = p.optJSONArray("opts") ?: return null
        val me = p.optInt("me", -1)
        val them = p.optInt("them", -1)
        if (me < 0 || them < 0) return null
        return if (me == them) Ui.t(ctx, "🎉 Both chose “${opts.optString(me)}”!", "🎉 Dono ne “${opts.optString(me)}” chuna!")
        else Ui.t(ctx, "🤔 You: ${opts.optString(me)} · ${other(ctx)}: ${opts.optString(them)} — toss it? 🪙",
            "🤔 Aap: ${opts.optString(me)} · ${other(ctx)}: ${opts.optString(them)} — toss karein? 🪙")
    }

    fun showPoll(a: Activity) {
        val p = latestPoll(a)
        if (p == null) {
            newPoll(a)
            return
        }
        val opts = p.optJSONArray("opts") ?: JSONArray()
        val other = other(a)
        val me = p.optInt("me", -1)
        val them = p.optInt("them", -1)
        val labels = (0 until opts.length()).map { i ->
            opts.optString(i) + (if (i == me) "  ✅ " + Ui.t(a, "you", "aap") else "") + (if (i == them && me >= 0) "  💞 $other" else "")
        }.toTypedArray()
        val msg = pollResult(a, p) ?: if (me >= 0) Ui.t(a, "Waiting for $other's vote ⏳", "$other ke vote ka intezaar ⏳")
        else if (them >= 0) Ui.t(a, "$other has voted 🤫 Your turn", "$other vote kar chuke 🤫 Ab aap")
        else Ui.t(a, "Pick one", "Ek chuno")
        val b = AlertDialog.Builder(a).setTitle("🗳️  " + p.optString("q"))
            .setItems(labels) { _, i ->
                p.put("me", i)
                putPoll(a, p)
                CoupleLink.send(a, "vote", JSONObject().put("id", p.optLong("id")).put("i", i))
                main.postDelayed({ showPoll(a) }, 250)
            }
            .setNegativeButton(Ui.t(a, "Close", "Band karo"), null)
            .setNeutralButton(Ui.t(a, "New question", "Naya sawaal")) { _, _ -> newPoll(a) }
        if (me >= 0 && them >= 0 && me != them) b.setPositiveButton(Ui.t(a, "🪙 Toss", "🪙 Toss")) { _, _ ->
            val pick = opts.optString(if (Math.random() < 0.5) me else them)
            val text = Ui.t(a, "🪙 Toss result: “$pick”!", "🪙 Toss ka faisla: “$pick”!")
            CoupleLink.send(a, "note", JSONObject().put("text", "${p.optString("q")} → $text"))
            AlertDialog.Builder(a).setTitle(text).setPositiveButton("🎉", null).show()
        }
        val dlg = b.create()
        dlg.show()
        Toast.makeText(a, msg, Toast.LENGTH_LONG).show()
    }

    // =============================================================== 💡 date ideas
    private val IDEAS = listOf(
        "🍿 Movie night — ek hi movie saath mein, phone pe call chalu rakh ke",
        "🍳 Video call pe saath mein ek hi dish banao",
        "🌙 Raat ko chhat pe taare dekho aur call pe baatein karo",
        "📸 Purani photos dekho aur unki kahani ek-dusre ko sunao",
        "🎮 Chinu mein saath mein Zero-Kata ya Love Wheel khelo",
        "☕ Ek naye café mein chai/coffee date",
        "🚶 Shaam ki lambi walk, bina phone dekhe",
        "🎤 Ek-dusre ke liye ek gaana gao (bura gaana bhi chalega 😜)",
        "✍️ Ek-dusre ko haath se chitthi likho aur photo bhejo",
        "🍦 Ice cream date — ek-dusre ka favourite flavour try karo",
        "🛍️ Window shopping aur ek-dusre ke liye ₹200 ka gift dhoondho",
        "🌅 Sunrise ya sunset saath mein dekho",
        "🧩 Online puzzle ya quiz saath mein solve karo",
        "📚 Ek hi kitaab ka ek chapter padho aur discuss karo",
        "🎨 Ek-dusre ki drawing banao aur bhejo 😂",
        "🍕 Pizza order karo dono ke ghar, call pe saath khao",
        "🗺️ Agle trip ka plan banao — kahan, kab, kaise",
        "💌 10 cheezein likho jo tumhe ek-dusre mein pasand hain",
        "🎬 Apni love story ko ek movie samjho — cast aur naam decide karo",
        "🏞️ Paas ke kisi park ya jheel pe picnic",
        "🕯️ Candle-light dinner, chahe ghar pe hi ho",
        "🎶 Ek playlist banao jisme sirf hamare gaane hon",
        "🤳 Aaj ka din 10 selfies mein — Aaj ke pal mein bhejo",
        "🧁 Ek-dusre ke liye kuch meetha banao ya mangwao"
    )

    fun ideaDialog(a: Activity) {
        val idea = IDEAS.random()
        AlertDialog.Builder(a).setTitle("💡  " + Ui.t(a, "Date idea", "Date idea"))
            .setMessage(idea)
            .setPositiveButton(Ui.t(a, "Send to ${other(a)} 💌", "${other(a)} ko bhejo 💌")) { _, _ ->
                CoupleLink.send(a, "note", JSONObject().put("text", "💡 " + Ui.t(a, "Date idea: ", "Date idea: ") + idea)) { ok ->
                    sent(a, ok, "Sent 💡", "Bhej diya 💡")
                }
            }
            .setNeutralButton(Ui.t(a, "Another", "Aur dikhao")) { _, _ -> ideaDialog(a) }
            .setNegativeButton(Ui.t(a, "Close", "Band karo"), null).show()
    }

    fun randomIdea() = IDEAS.random()

    // =============================================================== 🎬 3-2-1 start together
    fun startTogether(a: Activity) {
        val at = System.currentTimeMillis() + 8000L
        CoupleLink.send(a, "sync", JSONObject().put("at", at)) { ok ->
            if (!ok) toast(a, "No internet 😕", "Internet nahi hai 😕")
        }
        countdown(a, at, other(a))
    }

    private fun countdown(a: Activity, at: Long, partner: String) {
        val t = TextView(a).apply {
            textSize = 64f
            gravity = Gravity.CENTER
            setTextColor(0xFFB0276E.toInt())
            setPadding(0, Ui.dp(a, 20f), 0, Ui.dp(a, 20f))
        }
        val dlg = AlertDialog.Builder(a).setTitle("🎬  " + Ui.t(a, "Starting together with $partner", "$partner ke saath shuru"))
            .setView(t).setNegativeButton("OK", null).show()
        val tick = object : Runnable {
            override fun run() {
                val left = at - System.currentTimeMillis()
                if (left <= 0) {
                    t.text = "▶️"
                    CoupleLink.buzz(a, longArrayOf(0, 300))
                    main.postDelayed({
                        try {
                            dlg.dismiss()
                        } catch (e: Exception) {
                        }
                    }, 1500)
                    return
                }
                t.text = ((left + 999) / 1000).toString()
                main.postDelayed(this, 100)
            }
        }
        main.post(tick)
    }

    fun onSync(app: Context, d: JSONObject, from: String) {
        val at = d.optLong("at")
        val left = at - System.currentTimeMillis()
        if (left < -2000) return
        val open = CoupleActivity.current
        if (open != null) {
            countdown(open, at, from)
            return
        }
        CoupleLink.notify(app, "🎬  " + Ui.t(app, "$from: start together in ${maxOf(0, left / 1000)} s!", "$from: ${maxOf(0, left / 1000)} second mein saath shuru!"),
            Ui.t(app, "Press play when your phone buzzes ▶️", "Phone vibrate ho tab play dabao ▶️"), 395, Intent(app, CoupleActivity::class.java))
        main.postDelayed({ CoupleLink.buzz(app, longArrayOf(0, 300)) }, maxOf(0L, left))
    }

    // =============================================================== 🌦️ weather where the partner is
    private fun wxName(code: Int, ctx: Context): String = when (code) {
        0 -> Ui.t(ctx, "☀️ clear", "☀️ saaf aasmaan")
        1, 2 -> Ui.t(ctx, "🌤️ partly cloudy", "🌤️ halke baadal")
        3 -> Ui.t(ctx, "☁️ cloudy", "☁️ baadal")
        45, 48 -> Ui.t(ctx, "🌫️ fog", "🌫️ kohra")
        in 51..67 -> Ui.t(ctx, "🌧️ rain", "🌧️ baarish")
        in 71..77 -> Ui.t(ctx, "❄️ snow", "❄️ barf")
        in 80..82 -> Ui.t(ctx, "🌦️ showers", "🌦️ bauchhar")
        in 95..99 -> Ui.t(ctx, "⛈️ thunderstorm", "⛈️ toofan")
        else -> Ui.t(ctx, "🌡️", "🌡️")
    }

    private fun fetchWeather(lat: Double, lng: Double): JSONObject? = try {
        val c = URL(String.format(Locale.ENGLISH,
            "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code", lat, lng))
            .openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 10000
        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optJSONObject("current")
        c.disconnect()
        if (j == null) null else JSONObject().put("t", j.optDouble("temperature_2m")).put("c", j.optInt("weather_code"))
    } catch (e: Exception) {
        null
    }

    fun askWeather(ctx: Context) {
        if (!CoupleLink.paired(ctx)) return
        prefs(ctx).edit().putLong("wxAsked", System.currentTimeMillis()).apply()
        CoupleLink.send(ctx, "wx_ask")
    }

    /** Partner asked: find our (approximate) location, get the weather here, send only the weather back */
    fun onWeatherAsk(app: Context) {
        Care.currentLocation(app) { loc ->
            if (loc == null) {
                CoupleLink.send(app, "wx", JSONObject().put("none", true))
                return@currentLocation
            }
            Thread {
                val w = fetchWeather(loc.latitude, loc.longitude)
                main.post { CoupleLink.send(app, "wx", w ?: JSONObject().put("none", true)) }
            }.start()
        }
    }

    fun onWeather(app: Context, d: JSONObject, from: String) {
        val line = if (d.optBoolean("none")) Ui.t(app, "couldn't tell (location off)", "pata nahi chala (location band hai)")
        else "${Math.round(d.optDouble("t"))}°C · ${wxName(d.optInt("c"), app)}"
        prefs(app).edit().putString("wx", line).putLong("wxAt", System.currentTimeMillis()).apply()
        if (System.currentTimeMillis() - prefs(app).getLong("wxAsked", 0L) < 3 * 60_000L) {
            prefs(app).edit().putLong("wxAsked", 0L).apply()
            CoupleLink.notify(app, "🌦️  " + Ui.t(app, "Weather at $from's place", "$from ke yahan mausam"), line, 396, Intent(app, CoupleActivity::class.java))
            if (CoupleActivity.current == null) AssistantService.instance?.showLove("🌦️", Ui.t(app, "$from's place: $line", "$from ke yahan: $line"))
        }
        CoupleActivity.current?.refresh()
    }

    fun weatherLine(ctx: Context): String? {
        val w = prefs(ctx).getString("wx", "").orEmpty()
        if (w.isEmpty() || System.currentTimeMillis() - prefs(ctx).getLong("wxAt", 0L) > 6 * 3600_000L) return null
        return w
    }

    // =============================================================== 💖 reasons I love you
    fun reasonDialog(a: Activity) {
        val other = other(a)
        val got = arr(a, "reasons")
        val e = input(a, Ui.t(a, "I love you because…", "Main tumse pyaar karta/karti hoon kyunki…"), true)
        val b = AlertDialog.Builder(a).setTitle("💖  " + Ui.t(a, "Reasons I love you", "Kyun pyaar hai"))
            .setMessage(Ui.t(a, "Write one reason for $other. They collect in their jar 🫙", "$other ke liye ek reason likho. Unke jar mein jama hoga 🫙"))
            .setView(e)
            .setPositiveButton(Ui.t(a, "Add to their jar", "Jar mein daalo")) { _, _ ->
                val t = e.text.toString().trim()
                if (t.isNotEmpty()) CoupleLink.send(a, "reason", JSONObject().put("id", System.currentTimeMillis()).put("text", t)) { ok ->
                    sent(a, ok, "Added to $other's jar 💖", "$other ke jar mein daal diya 💖")
                }
            }
            .setNegativeButton(Ui.t(a, "Close", "Band karo"), null)
        if (got.isNotEmpty()) b.setNeutralButton(Ui.t(a, "Read one (${got.size}) 🫙", "Ek padho (${got.size}) 🫙")) { _, _ -> readReason(a) }
        b.show()
    }

    fun readReason(a: Activity) {
        val got = arr(a, "reasons")
        if (got.isEmpty()) return
        val r = got.random()
        AlertDialog.Builder(a).setTitle("💖  " + Ui.t(a, "${r.optString("from")} loves you because…", "${r.optString("from")} aapse pyaar karte hain kyunki…"))
            .setMessage(r.optString("text"))
            .setPositiveButton("🥰", null)
            .setNeutralButton(Ui.t(a, "Another", "Aur ek")) { _, _ -> readReason(a) }
            .show()
    }

    fun onReason(app: Context, d: JSONObject, from: String) {
        val t = d.optString("text")
        if (t.isEmpty()) return
        val l = arr(app, "reasons")
        if (l.any { it.optLong("id") == d.optLong("id") }) return
        l.add(JSONObject().put("id", d.optLong("id")).put("text", t).put("from", from))
        save(app, "reasons", l, 300)
        CoupleLink.notify(app, "💖  " + Ui.t(app, "$from added a reason to your jar", "$from ne aapke jar mein ek reason daala"),
            Ui.t(app, "Reason #${l.size} — tap to read 🫙", "Reason #${l.size} — padhne ke liye tap karo 🫙"), 397,
            Intent(app, CoupleActivity::class.java).putExtra("care", "reasons"))
        CoupleActivity.current?.refresh()
    }

    fun reasonCount(ctx: Context) = arr(ctx, "reasons").size


    // =============================================================== 🪣 our bucket list (things to do together)
    private fun bucket(ctx: Context) = arr(ctx, "bucket")

    private fun mergeBucket(ctx: Context, item: JSONObject): Boolean {
        val l = bucket(ctx)
        val i = l.indexOfFirst { it.optString("id") == item.optString("id") }
        if (i >= 0 && l[i].optLong("upd") >= item.optLong("upd")) return false
        if (i >= 0) l[i] = item else l.add(item)
        save(ctx, "bucket", l, 150)
        return true
    }

    private fun putBucket(ctx: Context, item: JSONObject) {
        item.put("upd", System.currentTimeMillis())
        mergeBucket(ctx, item)
        CoupleLink.send(ctx, "bucket_put", item)
    }

    fun onBucket(app: Context, d: JSONObject, from: String) {
        val known = bucket(app).any { it.optString("id") == d.optString("id") }
        if (!mergeBucket(app, d)) return
        if (!d.optBoolean("del")) {
            val title = when {
                !known -> Ui.t(app, "$from added to our bucket list", "$from ne bucket list mein joda")
                d.optBoolean("done") -> Ui.t(app, "Done together! 🎉", "Saath mein kar liya! 🎉")
                else -> null
            }
            if (title != null) CoupleLink.notify(app, "🪣  $title", d.optString("t"), 398, Intent(app, CoupleActivity::class.java).putExtra("care", "bucket"))
        }
        CoupleActivity.current?.refresh()
    }

    fun bucketCounts(ctx: Context): Pair<Int, Int> {
        val l = bucket(ctx).filter { !it.optBoolean("del") }
        return l.count { it.optBoolean("done") } to l.size
    }

    fun showBucket(a: Activity) {
        val items = bucket(a).filter { !it.optBoolean("del") }.sortedWith(compareBy<JSONObject> { it.optBoolean("done") }.thenByDescending { it.optLong("at") })
        val labels = items.map { (if (it.optBoolean("done")) "✅ " else "🌟 ") + it.optString("t") }.toTypedArray()
        val b = AlertDialog.Builder(a).setTitle("🪣  " + Ui.t(a, "Our bucket list", "Hamari bucket list") +
            if (items.isNotEmpty()) "  (${items.count { it.optBoolean("done") }}/${items.size})" else "")
        if (items.isEmpty()) b.setMessage(Ui.t(a, "Dreams to do together: Goa trip, sunrise at the beach, cooking class…",
            "Saath mein karne wale sapne: Goa trip, beach pe sunrise, cooking class…"))
        else b.setItems(labels) { _, i ->
            val item = items[i]
            AlertDialog.Builder(a).setTitle(item.optString("t"))
                .setPositiveButton(if (item.optBoolean("done")) Ui.t(a, "Not done yet", "Abhi nahi hua") else Ui.t(a, "✅ We did it!", "✅ Kar liya!")) { _, _ ->
                    putBucket(a, JSONObject(item.toString()).put("done", !item.optBoolean("done")))
                    showBucket(a)
                }
                .setNeutralButton(Ui.t(a, "Delete", "Hatao")) { _, _ ->
                    putBucket(a, JSONObject(item.toString()).put("del", true))
                    showBucket(a)
                }
                .setNegativeButton(Ui.t(a, "Back", "Wapas"), null).show()
        }
        b.setPositiveButton(Ui.t(a, "➕ Add a dream", "➕ Sapna jodo")) { _, _ ->
            val e = input(a, Ui.t(a, "e.g. Trip to the mountains 🏔️", "jaise Pahadon ki trip 🏔️"))
            AlertDialog.Builder(a).setTitle("🪣  " + Ui.t(a, "Add to our bucket list", "Bucket list mein jodo")).setView(e)
                .setPositiveButton(Ui.t(a, "Add", "Jodo")) { _, _ ->
                    val t = e.text.toString().trim().take(100)
                    if (t.isNotEmpty()) {
                        val now = System.currentTimeMillis()
                        putBucket(a, JSONObject().put("id", "$now" + (100..999).random()).put("t", t).put("at", now).put("done", false))
                        CoupleActivity.current?.refresh()
                        showBucket(a)
                    }
                }.setNegativeButton(Ui.t(a, "Cancel", "Rehne do"), null).show()
        }.setNegativeButton(Ui.t(a, "Close", "Band karo"), null).show()
    }

    // =============================================================== 😍 compliment of the moment
    private val COMPLIMENTS = listOf(
        "Tumhari smile meri favourite cheez hai 😍",
        "Tum ho to har din achha lagta hai 💖",
        "Tumse baat karke saari thakaan gayab ho jaati hai 🥰",
        "Duniya ki sabse cute insaan tum ho 🧸",
        "Tumhari aawaz sun ke din ban jaata hai 🎶",
        "Tum meri lucky charm ho 🍀",
        "Tumhare saath sab kuch easy lagta hai 🤗",
        "Tum bahut strong ho, mujhe tum par proud hai 💪💖",
        "Tumhari aankhein… bas kya hi bolun 😍✨",
        "Tum jaisa koi nahi, sach mein 💯",
        "Tum meri sabse pyaari aadat ho 💕",
        "Aaj bhi tum utni hi pyaari lag rahi/rahe ho jitni pehle din 🌸"
    )

    fun sendCompliment(ctx: Context) {
        if (!CoupleLink.paired(ctx)) return
        val c = COMPLIMENTS.random()
        CoupleLink.send(ctx, "note", JSONObject().put("text", "😍 $c")) { ok -> sent(ctx, ok, "Compliment sent 😍", "Compliment bhej diya 😍") }
    }

    // =============================================================== actions + 💞 card
    fun action(a: Activity, what: String) {
        when (what) {
            "secret" -> if (secretsWaiting(a) > 0) readSecret(a) else secretDialog(a)
            "poll" -> showPoll(a)
            "idea" -> ideaDialog(a)
            "sync" -> startTogether(a)
            "wx" -> {
                askWeather(a)
                toast(a, "Asking ${other(a)}'s phone… 🌦️", "${other(a)} ke phone se pooch rahe hain… 🌦️")
            }
            "reasons" -> if (reasonCount(a) > 0) readReason(a) else reasonDialog(a)
            "bucket" -> showBucket(a)
            "compliment" -> sendCompliment(a)
            else -> More.action(a, what)
        }
    }

    fun card(a: Activity, body: LinearLayout) {
        val dp = { v: Float -> Ui.dp(a, v) }
        val other = other(a)
        body.addView(Ui.label(a, Ui.t(a, "🎉 FUN TOGETHER", "🎉 SAATH MEIN MASTI"), 12.5f, 0xFF8A4F9E.toInt(), true).apply {
            letterSpacing = 0.1f
            setPadding(dp(4f), dp(26f), 0, dp(8f))
        })
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glass(a, 24f, 0x66)
            setPadding(dp(16f), dp(14f), dp(16f), dp(16f))
        }
        val waiting = secretsWaiting(a)
        if (waiting > 0) card.addView(Ui.label(a, "🔒 " + Ui.t(a, "$waiting secret(s) from $other — tap to read once", "$other ke $waiting secret — ek baar padhne ke liye tap karo"),
            14.5f, 0xFFB0276E.toInt(), true).apply {
            setPadding(0, 0, 0, dp(6f))
            setOnClickListener { readSecret(a) }
        })
        latestPoll(a)?.let { p ->
            val res = pollResult(a, p) ?: if (p.optInt("me", -1) < 0) Ui.t(a, "your vote is pending", "aapka vote baaki hai") else Ui.t(a, "waiting for $other", "$other ka intezaar")
            card.addView(Ui.label(a, "🗳️ ${p.optString("q")} — $res", 14f, 0xFF3B1F4A.toInt()).apply {
                setPadding(0, dp(2f), 0, dp(4f))
                setOnClickListener { showPoll(a) }
            })
        }
        weatherLine(a)?.let { card.addView(Ui.label(a, "🌦️ " + Ui.t(a, "$other's place: $it", "$other ke yahan: $it"), 14f, 0xFF3B1F4A.toInt())) }
        fun row(l: Pair<String, () -> Unit>, r: Pair<String, () -> Unit>) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Ui.glassButton(a, l.first, l.second).apply { textSize = 14f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Ui.glassButton(a, r.first, r.second).apply { textSize = 14f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8f) })
            card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        }
        row(Ui.t(a, "🔒 Secret message", "🔒 Secret message") to { secretDialog(a) },
            Ui.t(a, "🗳️ Decide together", "🗳️ Saath mein faisla") to { showPoll(a) })
        row(Ui.t(a, "💡 Date idea", "💡 Date idea") to { ideaDialog(a) },
            Ui.t(a, "🎬 3-2-1 together", "🎬 3-2-1 saath mein") to { startTogether(a) })
        val rc = reasonCount(a)
        row(Ui.t(a, "🌦️ $other's weather", "🌦️ $other ka mausam") to { action(a, "wx") },
            Ui.t(a, "💖 Reasons", "💖 Kyun pyaar hai") + (if (rc > 0) " ($rc)" else "") to { reasonDialog(a) })
        val (bd, bt) = bucketCounts(a)
        row(Ui.t(a, "🪣 Bucket list", "🪣 Bucket list") + (if (bt > 0) " ($bd/$bt)" else "") to { showBucket(a) },
            Ui.t(a, "😍 Send a compliment", "😍 Compliment bhejo") to { sendCompliment(a) })
        body.addView(card)
    }
}
