package com.meraassistant

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

/**
 * 💝 Even more for us:
 *  🎁 wishlists · 💧 water challenge · 🎯 daily couple challenge
 *  📏 how far apart we are · 🙏 sorry button · 🕰️ time capsule
 */
object More {
    const val WATER_GOAL = 8

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("more", Context.MODE_PRIVATE)

    private fun other(ctx: Context): String {
        Speaker.load(ctx)
        return Speaker.other(Profile.load(ctx))
    }

    private fun today() = LocalDate.now().toEpochDay()

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

    private fun save(ctx: Context, key: String, l: List<JSONObject>, keep: Int = 80) =
        prefs(ctx).edit().putString(key, JSONArray(l.takeLast(keep)).toString()).apply()

    private fun edit(a: Activity, hint: String) = EditText(a).apply { this.hint = hint }

    // =============================================================== 🎁 wishlists (so the other knows what to gift)
    private fun sendWishes(ctx: Context) {
        CoupleLink.send(ctx, "wish", JSONObject().put("items", JSONArray(arr(ctx, "wishMine"))))
    }

    fun onWish(app: Context, d: JSONObject, from: String) {
        val a = d.optJSONArray("items") ?: JSONArray()
        val old = arr(app, "wishTheirs").size
        prefs(app).edit().putString("wishTheirs", a.toString()).apply()
        if (a.length() > old) CoupleLink.notify(app, "🎁  " + Ui.t(app, "$from updated their wishlist", "$from ne apni wishlist mein kuch joda"),
            (a.optJSONObject(a.length() - 1)?.optString("t") ?: "") + " 👀", 401, Intent(app, CoupleActivity::class.java).putExtra("care", "wish"))
        CoupleActivity.current?.refresh()
    }

    fun showWishes(a: Activity) {
        val other = other(a)
        val theirs = arr(a, "wishTheirs")
        val lines = if (theirs.isEmpty()) Ui.t(a, "$other hasn't added anything yet.", "$other ne abhi kuch nahi joda.")
        else theirs.joinToString("\n") { "🎁 " + it.optString("t") }
        AlertDialog.Builder(a).setTitle("🎁  " + Ui.t(a, "$other's wishlist", "$other ki wishlist"))
            .setMessage(lines + "\n\n" + Ui.t(a, "(Psst… perfect gift ideas 🤫)", "(Gift ke liye perfect ideas 🤫)"))
            .setPositiveButton(Ui.t(a, "My wishlist", "Meri wishlist")) { _, _ -> myWishes(a) }
            .setNegativeButton(Ui.t(a, "Close", "Band karo"), null).show()
    }

    private fun myWishes(a: Activity) {
        val mine = arr(a, "wishMine")
        val labels = mine.map { "🎁 " + it.optString("t") + "   ✖" }.toTypedArray()
        val b = AlertDialog.Builder(a).setTitle("🎁  " + Ui.t(a, "My wishlist (tap to remove)", "Meri wishlist (hatane ke liye tap)"))
        if (labels.isEmpty()) b.setMessage(Ui.t(a, "Add things you'd love to get 💝", "Jo cheezein aapko chahiye wo jodo 💝"))
        else b.setItems(labels) { _, i ->
            mine.removeAt(i)
            save(a, "wishMine", mine)
            sendWishes(a)
            myWishes(a)
        }
        b.setPositiveButton(Ui.t(a, "➕ Add", "➕ Jodo")) { _, _ ->
            val e = edit(a, Ui.t(a, "e.g. White sneakers 👟", "jaise White sneakers 👟"))
            AlertDialog.Builder(a).setTitle("🎁  " + Ui.t(a, "I wish for…", "Mujhe chahiye…")).setView(e)
                .setPositiveButton(Ui.t(a, "Add", "Jodo")) { _, _ ->
                    val t = e.text.toString().trim().take(80)
                    if (t.isNotEmpty()) {
                        mine.add(JSONObject().put("t", t).put("at", System.currentTimeMillis()))
                        save(a, "wishMine", mine, 40)
                        sendWishes(a)
                        toast(a, "Added — ${other(a)} can see it 🎁", "Jud gaya — ${other(a)} dekh sakte hain 🎁")
                        myWishes(a)
                    }
                }.setNegativeButton(Ui.t(a, "Cancel", "Rehne do"), null).show()
        }.setNegativeButton(Ui.t(a, "Close", "Band karo"), null).show()
    }

    // =============================================================== 💧 water challenge (8 glasses each)
    fun water(ctx: Context, who: String, day: Long = today()) = prefs(ctx).getInt("w_${who}_$day", 0)

    fun addWater(ctx: Context) {
        val day = today()
        val n = (water(ctx, "me", day) + 1).coerceAtMost(20)
        prefs(ctx).edit().putInt("w_me_$day", n).apply()
        CoupleLink.send(ctx, "water", JSONObject().put("day", day).put("n", n))
        if (n == WATER_GOAL) toast(ctx, "🎉 $WATER_GOAL glasses done! Well done 💧", "🎉 $WATER_GOAL glass poore! Shabaash 💧")
        else toast(ctx, "💧 $n / $WATER_GOAL", "💧 $n / $WATER_GOAL")
        CoupleActivity.current?.refresh()
    }

    fun onWater(app: Context, d: JSONObject, from: String) {
        val day = d.optLong("day", -1L)
        val n = d.optInt("n")
        if (day != today()) return
        prefs(app).edit().putInt("w_them_$day", n).apply()
        if (n == WATER_GOAL) CoupleLink.notify(app, "💧🎉  " + Ui.t(app, "$from finished $WATER_GOAL glasses!", "$from ne $WATER_GOAL glass poore kar liye!"),
            Ui.t(app, "You: ${water(app, "me")}/$WATER_GOAL — catch up! 😜", "Aap: ${water(app, "me")}/$WATER_GOAL — jaldi karo! 😜"), 402,
            Intent(app, CoupleActivity::class.java))
        CoupleActivity.current?.refresh()
    }

    fun waterLine(ctx: Context) = "💧 " + Ui.t(ctx, "Water today — you ${water(ctx, "me")}/$WATER_GOAL · ${other(ctx)} ${water(ctx, "them")}/$WATER_GOAL",
        "Aaj ka paani — aap ${water(ctx, "me")}/$WATER_GOAL · ${other(ctx)} ${water(ctx, "them")}/$WATER_GOAL")

    // =============================================================== 🎯 today's couple challenge (same on both phones)
    private val CHALLENGES = listOf(
        "Ek-dusre ko 3 cheezein batao jo aaj achhi lagi 🌟",
        "Ek purani photo bhejo jo aapko bahut pasand hai 📸",
        "5 minute ka video call — sirf hasna hai, koi serious baat nahi 😂",
        "Ek-dusre ke liye ek chhota sa poem ya shayari likho ✍️",
        "Aaj ek-dusre ko 'thank you' bolo kisi ek cheez ke liye 🙏",
        "Apna aaj ka outfit ki photo bhejo 👗👕",
        "Ek gaana bhejo jo tumhe ek-dusre ki yaad dilata hai 🎵",
        "Bina emoji ke 5 line ka pyaara message likho 💌",
        "Ek cheez batao jo aapne pehli baar ek-dusre mein notice ki thi 👀",
        "Aaj 8 glass paani dono poora karo 💧",
        "Ek-dusre ka funny nickname banao aur aaj din bhar wahi bulao 😜",
        "Apne bachpan ki ek kahani sunao 🧸",
        "Ek future plan likho jo saath mein karna hai 🗺️",
        "Ek voice message bhejo jisme sirf 'I love you' alag andaaz mein 🎤",
        "Ek-dusre ko ek compliment do jo pehle kabhi nahi diya 😍",
        "Aaj raat sone se pehle 2 minute baat zaroor karo 🌙",
        "Ek-dusre ki favourite cheez aaj khao aur photo bhejo 🍫",
        "Ek drawing banao — 'hum dono 10 saal baad' 🎨",
        "Aaj ka sabse achha pal ek line mein batao ✨",
        "Ek-dusre se ek sawaal poocho jo kabhi nahi poocha 🤔",
        "Phone pe ek selfie saath mein — video call screenshot 🤳",
        "Ek-dusre ke liye ek dua/wish likho 🙏💖",
        "Aaj kisi teesre insaan ko bhi khush karo, phir ek-dusre ko batao 😊",
        "Ek purani chat ka screenshot bhejo jo yaad hai 📱",
        "Ek-dusre ko batao aaj kis baat pe proud ho 💪"
    )

    fun challenge(day: Long = today()) = CHALLENGES[Math.floorMod(day * 7 + 3, CHALLENGES.size.toLong()).toInt()]

    fun challengeDone(ctx: Context, who: String) = prefs(ctx).getBoolean("c_${who}_${today()}", false)

    fun doneChallenge(ctx: Context) {
        val day = today()
        prefs(ctx).edit().putBoolean("c_me_$day", true).apply()
        CoupleLink.send(ctx, "chal_done", JSONObject().put("day", day))
        toast(ctx, if (challengeDone(ctx, "them")) "🎉 Both done today!" else "✅ Done! Waiting for ${other(ctx)}",
            if (challengeDone(ctx, "them")) "🎉 Aaj dono ne kar liya!" else "✅ Ho gaya! Ab ${other(ctx)} ki baari")
        CoupleActivity.current?.refresh()
    }

    fun onChallengeDone(app: Context, d: JSONObject, from: String) {
        val day = d.optLong("day", -1L)
        if (day != today()) return
        prefs(app).edit().putBoolean("c_them_$day", true).apply()
        CoupleLink.notify(app, "🎯  " + Ui.t(app, "$from did today's challenge!", "$from ne aaj ka challenge kar liya!"),
            if (challengeDone(app, "me")) Ui.t(app, "Both done 🎉", "Dono ne kar liya 🎉") else challenge(), 403,
            Intent(app, CoupleActivity::class.java))
        CoupleActivity.current?.refresh()
    }

    // =============================================================== 📏 how far apart we are
    private fun partnerLoc(ctx: Context): Pair<Double, Double>? {
        val url = Care.lastLoc(ctx)?.first ?: return null
        val q = url.substringAfter("q=", "")
        val lat = q.substringBefore(',').toDoubleOrNull() ?: return null
        val lng = q.substringAfter(',').toDoubleOrNull() ?: return null
        return lat to lng
    }

    @Suppress("MissingPermission")
    private fun myLastLoc(ctx: Context): Location? {
        if (!Care.hasLoc(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p ->
                try {
                    lm.getLastKnownLocation(p)
                } catch (e: Exception) {
                    null
                }
            }.maxByOrNull { it.time }
    }

    fun distanceLine(ctx: Context): String? {
        val (lat, lng) = partnerLoc(ctx) ?: return null
        val me = myLastLoc(ctx) ?: return null
        val out = FloatArray(1)
        Location.distanceBetween(me.latitude, me.longitude, lat, lng, out)
        val km = out[0] / 1000f
        return when {
            km < 0.3f -> Ui.t(ctx, "📏 You're together right now! 🥰", "📏 Aap dono abhi saath ho! 🥰")
            km < 10f -> "📏 " + Ui.t(ctx, "Just ${String.format(Locale.ENGLISH, "%.1f", km)} km apart 🤏", "Sirf ${String.format(Locale.ENGLISH, "%.1f", km)} km door 🤏")
            else -> "📏 " + Ui.t(ctx, "${km.toInt()} km apart — but close at heart 💞", "${km.toInt()} km door — par dil ke paas 💞")
        }
    }

    fun askDistance(a: Activity) {
        val line = distanceLine(a)
        if (line != null) {
            AlertDialog.Builder(a).setTitle(line).setPositiveButton("💞", null)
                .setNeutralButton(Ui.t(a, "Ask again", "Phir se poocho")) { _, _ -> askLocation(a) }.show()
            return
        }
        if (!Care.hasLoc(a)) {
            Care.shareLocation(a)       // asks for the location permission first
            return
        }
        askLocation(a)
    }

    private fun askLocation(a: Activity) {
        CoupleLink.send(a, "loc_ask") { ok ->
            sent(a, ok, "Asked ${other(a)} to share their location 📏", "${other(a)} se location maangi 📏")
        }
    }

    fun onLocAsk(app: Context, from: String) {
        CoupleLink.notify(app, "📏  " + Ui.t(app, "$from wants to know how far you are", "$from jaanna chahte hain aap kitni door ho"),
            Ui.t(app, "Tap to share your location 📍", "Location bhejne ke liye tap karo 📍"), 404,
            Intent(app, CoupleActivity::class.java).putExtra("care", "loc"))
    }

    // =============================================================== 🙏 sorry button
    fun sorryDialog(a: Activity) {
        val other = other(a)
        val e = edit(a, Ui.t(a, "(optional) Sorry for…", "(chahe to) Sorry kyunki…"))
        AlertDialog.Builder(a).setTitle("🥺  " + Ui.t(a, "Say sorry to $other", "$other ko sorry bolo"))
            .setView(e)
            .setPositiveButton(Ui.t(a, "🙏 Send sorry", "🙏 Sorry bhejo")) { _, _ ->
                CoupleLink.send(a, "sorry", JSONObject().put("text", e.text.toString().trim())) { ok ->
                    sent(a, ok, "Sorry sent 🥺 Fingers crossed 🤞", "Sorry chala gaya 🥺 Ab dua karo 🤞")
                }
            }.setNegativeButton(Ui.t(a, "Cancel", "Rehne do"), null).show()
    }

    fun onSorry(app: Context, d: JSONObject, from: String) {
        prefs(app).edit().putString("sorryFrom", from).putString("sorryText", d.optString("text")).putLong("sorryAt", System.currentTimeMillis()).apply()
        CoupleLink.notify(app, "🥺  " + Ui.t(app, "$from is saying sorry", "$from sorry bol rahe hain"),
            d.optString("text").ifBlank { Ui.t(app, "Tap to forgive… or not 😤", "Maaf karne ke liye tap karo… ya nahi 😤") }, 405,
            Intent(app, CoupleActivity::class.java).putExtra("care", "sorry_in"))
        CoupleLink.buzz(app, longArrayOf(0, 80, 80, 80))
        CoupleActivity.current?.refresh()
    }

    fun sorryWaiting(ctx: Context) = System.currentTimeMillis() - prefs(ctx).getLong("sorryAt", 0L) < 48 * 3600_000L

    fun answerSorry(a: Activity) {
        if (!sorryWaiting(a)) return
        val from = prefs(a).getString("sorryFrom", other(a)) ?: other(a)
        val text = prefs(a).getString("sorryText", "").orEmpty()
        fun reply(ok: Boolean) {
            prefs(a).edit().putLong("sorryAt", 0L).apply()
            CoupleLink.send(a, "sorry_reply", JSONObject().put("ok", ok))
            CoupleActivity.current?.refresh()
        }
        AlertDialog.Builder(a).setTitle("🥺  " + Ui.t(a, "$from says sorry", "$from sorry bol rahe hain"))
            .setMessage(text.ifBlank { "🙏🥺" })
            .setPositiveButton(Ui.t(a, "🤗 Forgiven", "🤗 Maaf kiya")) { _, _ -> reply(true) }
            .setNegativeButton(Ui.t(a, "😤 Not yet", "😤 Abhi nahi")) { _, _ -> reply(false) }
            .show()
    }

    fun onSorryReply(app: Context, d: JSONObject, from: String) {
        val ok = d.optBoolean("ok")
        CoupleLink.notify(app, if (ok) "🤗  " + Ui.t(app, "$from forgave you!", "$from ne maaf kar diya!") else "😤  " + Ui.t(app, "$from is still upset…", "$from abhi bhi naraz hain…"),
            if (ok) Ui.t(app, "Send a hug now 🤗💖", "Ab ek jhappi bhejo 🤗💖") else Ui.t(app, "Try harder — chocolate works 🍫😜", "Aur manao — chocolate kaam karti hai 🍫😜"),
            406, Intent(app, CoupleActivity::class.java))
        CoupleActivity.current?.celebrate(if (ok) "🤗" else "😤", if (ok) Ui.t(app, "$from forgave you!", "$from ne maaf kar diya!") else Ui.t(app, "$from is still upset", "$from abhi naraz hain"))
    }

    // =============================================================== 🕰️ time capsule (opens on a chosen day)
    fun capsuleDialog(a: Activity) {
        val other = other(a)
        val e = edit(a, Ui.t(a, "A message for the future…", "Future ke liye ek message…")).apply { minLines = 3 }
        AlertDialog.Builder(a).setTitle("🕰️  " + Ui.t(a, "Time capsule for $other", "$other ke liye time capsule"))
            .setMessage(Ui.t(a, "It stays locked on their phone until the day you pick.", "Ye unke phone par chuni hui date tak band rahega."))
            .setView(e)
            .setPositiveButton(Ui.t(a, "Pick the day", "Din chuno")) { _, _ ->
                val text = e.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 7) }
                DatePickerDialog(a, { _, y, m, d ->
                    val day = LocalDate.of(y, m + 1, d).toEpochDay()
                    CoupleLink.send(a, "capsule", JSONObject().put("id", System.currentTimeMillis()).put("text", text).put("day", day)) { ok ->
                        sent(a, ok, "Capsule sealed 🕰️ Opens on ${fmt(day)}", "Capsule band ho gaya 🕰️ ${fmt(day)} ko khulega")
                    }
                }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
                    datePicker.minDate = System.currentTimeMillis() + 24 * 3600_000L
                }.show()
            }.setNegativeButton(Ui.t(a, "Cancel", "Rehne do"), null).show()
    }

    private fun fmt(day: Long) = LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))

    fun onCapsule(app: Context, d: JSONObject, from: String) {
        val l = arr(app, "capsules")
        if (l.any { it.optLong("id") == d.optLong("id") }) return
        l.add(JSONObject().put("id", d.optLong("id")).put("text", d.optString("text")).put("day", d.optLong("day")).put("from", from))
        save(app, "capsules", l, 50)
        CoupleLink.notify(app, "🕰️  " + Ui.t(app, "$from sealed a time capsule for you", "$from ne aapke liye time capsule bheja"),
            Ui.t(app, "It opens on ${fmt(d.optLong("day"))} — no peeking! 🤫", "${fmt(d.optLong("day"))} ko khulega — tab tak sabr 🤫"), 407,
            Intent(app, CoupleActivity::class.java))
        CoupleActivity.current?.refresh()
    }

    fun capsules(ctx: Context) = arr(ctx, "capsules")

    fun openCapsule(a: Activity) {
        val l = capsules(a)
        val c = l.filter { it.optLong("day") <= today() }.minByOrNull { it.optLong("day") }
        if (c == null) {
            val next = l.minByOrNull { it.optLong("day") }
            toast(a, next?.let { "🔒 Opens on ${fmt(it.optLong("day"))}" } ?: "No capsules yet",
                next?.let { "🔒 ${fmt(it.optLong("day"))} ko khulega" } ?: "Abhi koi capsule nahi")
            return
        }
        l.removeAll { it.optLong("id") == c.optLong("id") }
        save(a, "capsules", l, 50)
        CoupleLink.send(a, "capsule_open", JSONObject().put("id", c.optLong("id")))
        AlertDialog.Builder(a).setTitle("🕰️  " + Ui.t(a, "From ${c.optString("from")}, sealed for today", "${c.optString("from")} ki taraf se, aaj ke liye"))
            .setMessage(c.optString("text")).setPositiveButton("💖", null)
            .setOnDismissListener { CoupleActivity.current?.refresh() }.show()
    }

    fun onCapsuleOpen(app: Context, from: String) {
        CoupleLink.notify(app, "🕰️  " + Ui.t(app, "$from opened your time capsule", "$from ne aapka time capsule khola"), "💖", 408,
            Intent(app, CoupleActivity::class.java))
    }


    // =============================================================== 💓 heart touched while my screen is closed
    private var lastHeartCall = 0L

    fun heartCall(app: Context, from: String) {
        if (System.currentTimeMillis() - lastHeartCall < 60_000L) return
        lastHeartCall = System.currentTimeMillis()
        CoupleLink.notify(app, "💓  " + Ui.t(app, "$from is touching the heart!", "$from dil ko choo rahe hain!"),
            Ui.t(app, "Open now and touch it too — both phones will beat together 💞", "Abhi kholo aur aap bhi choo lo — dono phones saath dhadkenge 💞"),
            409, Intent(app, CoupleActivity::class.java))
        CoupleLink.buzz(app, longArrayOf(0, 90, 120, 140))
    }

    // =============================================================== actions + 💞 card
    fun action(a: Activity, what: String) {
        when (what) {
            "wish" -> showWishes(a)
            "sorry_in" -> answerSorry(a)
            "sorry" -> sorryDialog(a)
            "capsule" -> openCapsule(a)
            "far" -> askDistance(a)
            "water" -> addWater(a)
            else -> Xtra.action(a, what)
        }
    }

    fun card(a: Activity, body: LinearLayout) {
        val dp = { v: Float -> Ui.dp(a, v) }
        val other = other(a)
        body.addView(Ui.label(a, Ui.t(a, "💝 EVEN MORE", "💝 AUR PYAAR"), 12.5f, 0xFF8A4F9E.toInt(), true).apply {
            letterSpacing = 0.1f
            setPadding(dp(4f), dp(26f), 0, dp(8f))
        })
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glass(a, 24f, 0x66)
            setPadding(dp(16f), dp(14f), dp(16f), dp(16f))
        }
        fun line(t: String, color: Int = 0xFF3B1F4A.toInt(), bold: Boolean = false, click: (() -> Unit)? = null) {
            card.addView(Ui.label(a, t, 14f, color, bold).apply {
                setPadding(0, dp(2f), 0, dp(5f))
                if (click != null) setOnClickListener { click() }
            })
        }
        if (sorryWaiting(a)) line("🥺 " + Ui.t(a, "${prefs(a).getString("sorryFrom", other)} said sorry — tap to answer",
            "${prefs(a).getString("sorryFrom", other)} ne sorry bola — jawab do"), 0xFFB0276E.toInt(), true) { answerSorry(a) }
        val caps = capsules(a)
        val ready = caps.count { it.optLong("day") <= today() }
        if (ready > 0) line("🕰️ " + Ui.t(a, "A time capsule opened today — tap!", "Aaj ek time capsule khula — tap karo!"), 0xFFB0276E.toInt(), true) { openCapsule(a) }
        else caps.minByOrNull { it.optLong("day") }?.let { line("🕰️ " + Ui.t(a, "${caps.size} capsule(s) locked · next ${fmt(it.optLong("day"))}",
            "${caps.size} capsule band · agla ${fmt(it.optLong("day"))}")) }
        val ch = challenge()
        val meC = challengeDone(a, "me")
        val themC = challengeDone(a, "them")
        line("🎯 " + Ui.t(a, "Today's challenge: ", "Aaj ka challenge: ") + ch, bold = false)
        line("     " + Ui.t(a, "You ", "Aap ") + (if (meC) "✅" else "⏳") + "  ·  $other " + (if (themC) "✅" else "⏳"), 0xFF6B4E7A.toInt())
        line(waterLine(a))
        distanceLine(a)?.let { line(it) }
        fun row(l: Pair<String, () -> Unit>, r: Pair<String, () -> Unit>) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Ui.glassButton(a, l.first, l.second).apply { textSize = 14f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Ui.glassButton(a, r.first, r.second).apply { textSize = 14f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8f) })
            card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        }
        row((if (meC) Ui.t(a, "🎯 Done ✅", "🎯 Ho gaya ✅") else Ui.t(a, "🎯 I did it!", "🎯 Maine kar liya!")) to { if (!meC) doneChallenge(a) },
            Ui.t(a, "💧 +1 glass", "💧 +1 glass") to { addWater(a) })
        row(Ui.t(a, "🎁 Wishlists", "🎁 Wishlist") to { showWishes(a) },
            Ui.t(a, "📏 How far?", "📏 Kitni door?") to { askDistance(a) })
        row(Ui.t(a, "🙏 Say sorry", "🙏 Sorry bolo") to { sorryDialog(a) },
            Ui.t(a, "🕰️ Time capsule", "🕰️ Time capsule") to { capsuleDialog(a) })
        body.addView(card)
    }
}
