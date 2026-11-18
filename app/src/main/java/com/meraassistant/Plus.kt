package com.meraassistant

import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale

/**
 * ✨ More everyday things:
 *  ↩️ reply right from the notification · 📸 ask for a photo · 🎵 send a song
 *  💰 our shared expenses · ☀️🌙 automatic good morning / good night
 */
object Plus {
    private val main = Handler(Looper.getMainLooper())
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("plus", Context.MODE_PRIVATE)

    private fun other(ctx: Context): String {
        Speaker.load(ctx)
        return Speaker.other(Profile.load(ctx))
    }

    private fun me(ctx: Context): String {
        Speaker.load(ctx)
        return Speaker.name(Profile.load(ctx))
    }

    private fun toast(ctx: Context, en: String, hi: String) = Toast.makeText(ctx, Ui.t(ctx, en, hi), Toast.LENGTH_SHORT).show()

    private fun mutableFlag() = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0

    private fun channel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("couple", "Chiku ❤ Komal", NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
    }

    private fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    // =============================================================== ↩️ reply right from the notification
    /** The partner's message, with "Reply" and "❤️" buttons right in the notification */
    fun noteNotify(app: Context, from: String, text: String) {
        if (!canNotify(app)) return
        try {
            channel(app)
            val open = PendingIntent.getActivity(app, 360, Intent(app, CoupleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val input = RemoteInput.Builder(PlusReceiver.KEY_REPLY).setLabel(Ui.t(app, "Reply to $from…", "$from ko jawab…")).build()
            val replyPi = PendingIntent.getBroadcast(app, 4802, Intent(app, PlusReceiver::class.java).setAction(PlusReceiver.REPLY),
                mutableFlag() or PendingIntent.FLAG_UPDATE_CURRENT)
            val lovePi = PendingIntent.getBroadcast(app, 4803, Intent(app, PlusReceiver::class.java).setAction(PlusReceiver.LOVE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            app.getSystemService(NotificationManager::class.java).notify(360, Notification.Builder(app, "couple")
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("💬  " + Ui.t(app, "Message from $from", "$from ka message"))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(null, Ui.t(app, "💬 Reply", "💬 Jawab do"), replyPi).addRemoteInput(input).build())
                .addAction(Notification.Action.Builder(null, "❤️ Love you", lovePi).build())
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build())
        } catch (e: Exception) {
        }
    }

    /** 💬 A normal message the partner gave to their Chinu: on screen + spoken aloud + notification */
    fun onChinuMsg(app: Context, d: JSONObject, from: String) {
        val text = d.optString("text").trim()
        if (text.isEmpty()) return
        val me = me(app)
        val shown = Ui.t(app, "💬 $from says: $text", "💬 $from ne kaha: $text")
        val spoken = Ui.t(app, "$me, $from says: $text", "$me, $from ne kaha hai: $text")
        prefs(app).edit().putString("lastMsg", "$from: $text").putLong("lastMsgAt", System.currentTimeMillis()).apply()
        noteNotify(app, from, text)
        val chat = MainActivity.current
        if (chat != null) chat.incoming(shown, spoken)
        else {
            // over any app (when Chinu's accessibility is on) + Chinu says it out loud
            AssistantService.instance?.showLove("💬", "$from: $text")
            Voice.say(app, spoken)
        }
        CoupleLink.buzz(app, longArrayOf(0, 80, 60, 80))
        CoupleActivity.current?.refresh()
    }

    /** After replying, the notification shows what was sent (and stops the spinner) */
    fun replied(app: Context, text: String, ok: Boolean) {
        try {
            channel(app)
            app.getSystemService(NotificationManager::class.java).notify(360, Notification.Builder(app, "couple")
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(if (ok) Ui.t(app, "✅ Sent to ${other(app)}", "✅ ${other(app)} ko chala gaya") else Ui.t(app, "😕 Not sent — no internet", "😕 Nahi gaya — internet nahi hai"))
                .setContentText(text)
                .setAutoCancel(true)
                .setTimeoutAfter(8000)
                .build())
        } catch (e: Exception) {
        }
    }

    // =============================================================== 📸 ask for a photo
    fun askPhoto(ctx: Context) {
        if (!CoupleLink.paired(ctx)) return
        val other = other(ctx)
        CoupleLink.send(ctx, "photo_ask") { ok ->
            if (ok) toast(ctx, "Asked $other for a photo 📸", "$other se photo maang li 📸") else toast(ctx, "No internet 😕", "Internet nahi hai 😕")
        }
    }

    fun onPhotoAsk(app: Context, from: String) {
        CoupleLink.notify(app, "📸  " + Ui.t(app, "$from wants to see you!", "$from aapko dekhna chahte hain!"),
            Ui.t(app, "Tap and send a photo right now 🥺", "Tap karo aur abhi ek photo bhejo 🥺"), 388,
            Intent(app, SelfieActivity::class.java).putExtra("snap", true))
        CoupleLink.buzz(app, longArrayOf(0, 70, 60, 70))
        if (CoupleActivity.current == null) AssistantService.instance?.showLove("📸", Ui.t(app, "$from wants a photo of you!", "$from ko aapki photo chahiye!"))
    }

    // =============================================================== 🎵 send a song
    fun songUrl(q: String) = "https://www.youtube.com/results?search_query=" + Uri.encode(q)

    fun sendSong(ctx: Context, q: String) {
        val song = q.trim()
        if (song.isEmpty() || !CoupleLink.paired(ctx)) return
        val other = other(ctx)
        CoupleLink.send(ctx, "song", JSONObject().put("q", song)) { ok ->
            if (ok) toast(ctx, "Sent “$song” to $other 🎵", "$other ko “$song” bhej diya 🎵") else toast(ctx, "No internet 😕", "Internet nahi hai 😕")
        }
    }

    fun songDialog(a: Activity) {
        val input = EditText(a).apply { hint = Ui.t(a, "Song name, e.g. Kesariya", "Gaane ka naam, jaise Kesariya") }
        AlertDialog.Builder(a).setTitle("🎵  " + Ui.t(a, "A song for ${other(a)}", "${other(a)} ke liye gaana"))
            .setView(input)
            .setPositiveButton(Ui.t(a, "Send", "Bhejo")) { _, _ -> sendSong(a, input.text.toString()) }
            .setNegativeButton(Ui.t(a, "Cancel", "Rehne do"), null).show()
    }

    fun onSong(app: Context, d: JSONObject, from: String) {
        val q = d.optString("q").trim()
        if (q.isEmpty()) return
        prefs(app).edit().putString("lastSong", q).putString("lastSongFrom", from).apply()
        CoupleLink.notify(app, "🎵  " + Ui.t(app, "$from sent you a song", "$from ne aapke liye gaana bheja"),
            "“$q” — " + Ui.t(app, "tap to listen 🎧", "sunne ke liye tap karo 🎧"), 389, Intent(Intent.ACTION_VIEW, Uri.parse(songUrl(q))))
        CoupleLink.buzz(app, longArrayOf(0, 60, 50, 60))
        CoupleActivity.current?.refresh()
    }

    // =============================================================== 💰 our shared expenses
    private fun loadExp(ctx: Context): MutableList<JSONObject> {
        val a = try {
            JSONArray(prefs(ctx).getString("exp", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.toMutableList()
    }

    private fun saveExp(ctx: Context, l: List<JSONObject>) {
        prefs(ctx).edit().putString("exp", JSONArray(l.takeLast(400)).toString()).apply()
    }

    private fun mergeExp(ctx: Context, item: JSONObject): Boolean {
        val id = item.optString("id")
        if (id.isEmpty()) return false
        val l = loadExp(ctx)
        val i = l.indexOfFirst { it.optString("id") == id }
        if (i >= 0 && l[i].optLong("upd") >= item.optLong("upd")) return false
        if (i >= 0) l[i] = item else l.add(item)
        saveExp(ctx, l)
        return true
    }

    fun expenses(ctx: Context) = loadExp(ctx).filter { !it.optBoolean("del") }.sortedByDescending { it.optLong("at") }

    private fun money(v: Double) = "₹" + if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.ENGLISH, "%.2f", v)

    /** "Komal paid ₹300 more" style summary (each pays half of everything) */
    fun balanceLine(ctx: Context): String? {
        val list = expenses(ctx)
        if (list.isEmpty()) return null
        val myName = me(ctx)
        val mine = list.filter { it.optString("by") == myName }.sumOf { it.optDouble("amt", 0.0) }
        val theirs = list.filter { it.optString("by") != myName }.sumOf { it.optDouble("amt", 0.0) }
        val diff = (mine - theirs) / 2.0
        val other = other(ctx)
        return when {
            Math.abs(diff) < 1 -> Ui.t(ctx, "💰 All settled — total ${money(mine + theirs)}", "💰 Hisaab barabar — total ${money(mine + theirs)}")
            diff > 0 -> Ui.t(ctx, "💰 $other owes you ${money(diff)}", "💰 $other ko aapko ${money(diff)} dene hain")
            else -> Ui.t(ctx, "💰 You owe $other ${money(-diff)}", "💰 Aapko $other ko ${money(-diff)} dene hain")
        }
    }

    fun addExpense(ctx: Context, amt: Double, what: String) {
        if (amt <= 0) return
        val now = System.currentTimeMillis()
        val item = JSONObject().put("id", "$now" + (100..999).random()).put("amt", amt).put("what", what.trim().take(60))
            .put("by", me(ctx)).put("at", now).put("upd", now)
        mergeExp(ctx, item)
        CoupleLink.send(ctx, "exp_put", item)
    }

    private fun deleteExpense(ctx: Context, item: JSONObject) {
        val d = JSONObject(item.toString()).put("del", true).put("upd", System.currentTimeMillis())
        mergeExp(ctx, d)
        CoupleLink.send(ctx, "exp_put", d)
    }

    fun onExpPut(app: Context, d: JSONObject, from: String) {
        val known = loadExp(app).any { it.optString("id") == d.optString("id") }
        if (!mergeExp(app, d)) return
        if (!known && !d.optBoolean("del")) {
            CoupleLink.notify(app, "💰  " + Ui.t(app, "$from added an expense", "$from ne kharcha joda"),
                money(d.optDouble("amt")) + " · " + d.optString("what") + "\n" + (balanceLine(app) ?: ""), 390,
                Intent(app, CoupleActivity::class.java).putExtra("care", "exp"))
        }
        CoupleActivity.current?.refresh()
        expRefresh?.invoke()
    }

    fun syncExp(ctx: Context, reply: Boolean = false) {
        if (!CoupleLink.paired(ctx)) return
        val p = prefs(ctx)
        if (!reply && System.currentTimeMillis() - p.getLong("expSync", 0L) < 10 * 60_000L) return
        p.edit().putLong("expSync", System.currentTimeMillis()).apply()
        CoupleLink.send(ctx, "exp_all", JSONObject().put("items", JSONArray(loadExp(ctx).takeLast(120))).put("reply", reply))
    }

    fun onExpAll(app: Context, d: JSONObject) {
        val a = d.optJSONArray("items") ?: JSONArray()
        var changed = false
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { if (mergeExp(app, it)) changed = true }
        if (changed) {
            CoupleActivity.current?.refresh()
            expRefresh?.invoke()
        }
        if (!d.optBoolean("reply")) syncExp(app, reply = true)
    }

    private var expRefresh: (() -> Unit)? = null

    fun showExpenses(a: Activity) {
        val dp = { v: Float -> Ui.dp(a, v) }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(8f), dp(18f), dp(8f))
        }
        val fmt = java.text.SimpleDateFormat("d MMM", Locale.ENGLISH)
        fun render() {
            box.removeAllViews()
            box.addView(Ui.label(a, balanceLine(a) ?: Ui.t(a, "No expenses yet. Add what you pay — we split it half-half.",
                "Abhi kuch nahi. Jo kharcha karo wo jodo — aadha-aadha hisaab hoga."), 15f, 0xFF3B1F4A.toInt(), true).apply {
                setPadding(0, dp(6f), 0, dp(10f))
            })
            for (item in expenses(a).take(60)) {
                box.addView(TextView(a).apply {
                    text = "${money(item.optDouble("amt"))} · ${item.optString("what").ifBlank { "—" }}   (${item.optString("by")}, ${fmt.format(java.util.Date(item.optLong("at")))})"
                    textSize = 14.5f
                    setTextColor(0xFF3B1F4A.toInt())
                    setPadding(0, dp(8f), 0, dp(8f))
                    setOnLongClickListener { _ ->
                        AlertDialog.Builder(a).setTitle(Ui.t(a, "Delete this expense?", "Ye kharcha hata dein?"))
                            .setPositiveButton(Ui.t(a, "Delete", "Hatao")) { _, _ ->
                                deleteExpense(a, item)
                                render()
                            }.setNegativeButton(Ui.t(a, "Cancel", "Rehne do"), null).show()
                        true
                    }
                })
            }
        }
        render()
        expRefresh = { a.runOnUiThread { render() } }
        val dlg = AlertDialog.Builder(a)
            .setTitle("💰  " + Ui.t(a, "Our expenses", "Hamara hisaab"))
            .setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton(Ui.t(a, "➕ I paid", "➕ Maine diye"), null)
            .setNegativeButton(Ui.t(a, "Close", "Band karo"), null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val form = LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18f), dp(4f), dp(18f), 0)
                }
                val amt = EditText(a).apply {
                    hint = Ui.t(a, "Amount (₹)", "Kitne (₹)")
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                }
                val what = EditText(a).apply { hint = Ui.t(a, "For what? e.g. Pizza", "Kis cheez ka? jaise Pizza") }
                form.addView(amt)
                form.addView(what)
                AlertDialog.Builder(a).setTitle("➕  " + Ui.t(a, "What did you pay?", "Aapne kya diya?")).setView(form)
                    .setPositiveButton(Ui.t(a, "Add", "Jodo")) { _, _ ->
                        val v = amt.text.toString().replace(",", "").toDoubleOrNull() ?: 0.0
                        if (v > 0) {
                            addExpense(a, v, what.text.toString())
                            render()
                        }
                    }.setNegativeButton(Ui.t(a, "Cancel", "Rehne do"), null).show()
            }
        }
        dlg.setOnDismissListener {
            expRefresh = null
            CoupleActivity.current?.refresh()
        }
        dlg.show()
    }

    // =============================================================== ☀️🌙 automatic good morning / good night
    private val MORNING = listOf(
        "Good morning %s ☀️ Aaj ka din tumhari smile jaisa pyaara ho 💖",
        "Uth gaye %s? ☀️ Pehla khayal tumhara hi aaya 🥰",
        "Good morning jaan %s 🌸 Paani pi lena aur nashta zaroor karna 💧🍳",
        "Rise and shine %s ☀️ Aaj bhi tum meri favourite ho 💕",
        "Good morning %s 🌼 Aaj ka din tumhara hai, sab achha hoga 🤞",
        "Subah ki pehli dua tumhare liye, %s 🙏💖 Good morning!"
    )
    private val NIGHT = listOf(
        "Good night %s 🌙 Sweet dreams, sapne mein milte hain 💫",
        "So jao %s 😴 Kal phir se tumhe pareshan karunga 😜 Good night 💖",
        "Good night %s 🌙 Aaj ke din ke liye thank you, tum ho to sab achha hai 💕",
        "Aankhein band karo %s, main yahin hoon 🤗 Good night 🌌",
        "Good night %s ✨ Kal ka din aur bhi pyaara hoga 💖",
        "Chand ko bol diya hai tumhara khayal rakhe, %s 🌙 Good night 😘"
    )

    fun autoOn(ctx: Context, kind: String) = prefs(ctx).getBoolean("${kind}On", false)
    fun autoTime(ctx: Context, kind: String): Pair<Int, Int> =
        prefs(ctx).getInt("${kind}H", if (kind == "gm") 7 else 22) to prefs(ctx).getInt("${kind}M", if (kind == "gm") 30 else 30)

    private fun hm(h: Int, m: Int) = String.format(Locale.ENGLISH, "%d:%02d %s", if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM")

    fun autoLine(ctx: Context, kind: String): String {
        val (h, m) = autoTime(ctx, kind)
        val name = if (kind == "gm") Ui.t(ctx, "☀️ Auto good morning", "☀️ Auto good morning") else Ui.t(ctx, "🌙 Auto good night", "🌙 Auto good night")
        return "$name: " + if (autoOn(ctx, kind)) hm(h, m) + " ✅" else "OFF"
    }

    fun autoDialog(a: Activity, kind: String) {
        val on = autoOn(a, kind)
        val (h, m) = autoTime(a, kind)
        val title = if (kind == "gm") Ui.t(a, "☀️ Good morning for ${other(a)} every day", "☀️ Roz ${other(a)} ko good morning")
        else Ui.t(a, "🌙 Good night for ${other(a)} every day", "🌙 Roz ${other(a)} ko good night")
        val b = AlertDialog.Builder(a).setTitle(title)
            .setMessage(Ui.t(a, "Chinu sends a sweet, different message automatically at your time.",
                "Chinu aapke time par roz ek naya pyaara message apne aap bhejega."))
            .setPositiveButton(if (on) Ui.t(a, "Change time", "Time badlo") else Ui.t(a, "Turn on", "Chalu karo")) { _, _ ->
                TimePickerDialog(a, { _, hh, mm ->
                    prefs(a).edit().putBoolean("${kind}On", true).putInt("${kind}H", hh).putInt("${kind}M", mm).apply()
                    schedule(a, kind)
                    toast(a, "Done! Every day at ${hm(hh, mm)} ✅", "Ho gaya! Roz ${hm(hh, mm)} baje ✅")
                    CoupleActivity.current?.refresh()
                }, h, m, false).show()
            }
            .setNegativeButton(Ui.t(a, "Cancel", "Rehne do"), null)
        if (on) b.setNeutralButton(Ui.t(a, "Turn off", "Band karo")) { _, _ ->
            prefs(a).edit().putBoolean("${kind}On", false).apply()
            schedule(a, kind)
            CoupleActivity.current?.refresh()
        }
        b.show()
    }

    private fun pending(ctx: Context, kind: String) = PendingIntent.getBroadcast(ctx, if (kind == "gm") 4810 else 4811,
        Intent(ctx, PlusReceiver::class.java).setAction(PlusReceiver.AUTO).putExtra("kind", kind),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun schedule(ctx: Context, kind: String) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(ctx, kind)
        am.cancel(pi)
        if (!autoOn(ctx, kind)) return
        val (h, m) = autoTime(ctx, kind)
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= System.currentTimeMillis() + 5000) c.add(Calendar.DAY_OF_YEAR, 1)
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.timeInMillis, pi)
        } catch (e: Exception) {
        }
    }

    fun scheduleAll(ctx: Context) {
        schedule(ctx, "gm")
        schedule(ctx, "gn")
    }

    fun fireAuto(ctx: Context, kind: String, done: () -> Unit) {
        schedule(ctx, kind)
        if (!autoOn(ctx, kind) || !CoupleLink.paired(ctx)) {
            done()
            return
        }
        val list = if (kind == "gm") MORNING else NIGHT
        val p = prefs(ctx)
        val last = p.getInt("${kind}Last", -1)
        var i = (list.indices).random()
        if (i == last) i = (i + 1) % list.size
        p.edit().putInt("${kind}Last", i).apply()
        val text = String.format(list[i], other(ctx))
        CoupleLink.send(ctx, "note", JSONObject().put("text", text)) { _ -> done() }
    }

    // =============================================================== actions + 💞 card
    fun action(a: Activity, what: String) {
        when (what) {
            "photo" -> askPhoto(a)
            "song" -> songDialog(a)
            "exp" -> showExpenses(a)
            "update" -> Updater.check(a, true)
            "gm", "gn" -> autoDialog(a, what)
            else -> Fun.action(a, what)
        }
    }

    fun card(a: Activity, body: LinearLayout) {
        val dp = { v: Float -> Ui.dp(a, v) }
        val other = other(a)
        body.addView(Ui.label(a, Ui.t(a, "✨ MORE FOR US", "✨ AUR BHI"), 12.5f, 0xFF8A4F9E.toInt(), true).apply {
            letterSpacing = 0.1f
            setPadding(dp(4f), dp(26f), 0, dp(8f))
        })
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glass(a, 24f, 0x66)
            setPadding(dp(16f), dp(14f), dp(16f), dp(16f))
        }
        val lines = listOfNotNull(
            balanceLine(a),
            prefs(a).getString("lastSong", null)?.let { s ->
                "🎵 " + Ui.t(a, "Last song from ${prefs(a).getString("lastSongFrom", other)}: “$s”", "${prefs(a).getString("lastSongFrom", other)} ka gaana: “$s”")
            }
        )
        for (l in lines) card.addView(Ui.label(a, l, 14f, 0xFF3B1F4A.toInt()).apply {
            setPadding(0, dp(2f), 0, dp(4f))
            if (l.startsWith("🎵")) setOnClickListener {
                try {
                    a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(songUrl(prefs(a).getString("lastSong", "").orEmpty()))))
                } catch (e: Exception) {
                }
            }
        })
        fun row(l: Pair<String, () -> Unit>, r: Pair<String, () -> Unit>) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Ui.glassButton(a, l.first, l.second).apply { textSize = 14f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Ui.glassButton(a, r.first, r.second).apply { textSize = 14f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8f) })
            card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        }
        row(Ui.t(a, "📸 Ask $other for a photo", "📸 $other se photo maango") to { askPhoto(a) },
            Ui.t(a, "🎵 Send a song", "🎵 Gaana bhejo") to { songDialog(a) })
        row(Ui.t(a, "💰 Our expenses", "💰 Hamara hisaab") to { showExpenses(a) },
            Ui.t(a, "🔄 App update", "🔄 App update") to { Updater.check(a, true) })
        row(autoLine(a, "gm") to { autoDialog(a, "gm") }, autoLine(a, "gn") to { autoDialog(a, "gn") })
        card.addView(Ui.label(a, Ui.t(a, "Version ${BuildConfig.VERSION_NAME}", "Version ${BuildConfig.VERSION_NAME}"), 11.5f, 0xFF8E7A99.toInt()).apply {
            setPadding(0, dp(10f), 0, 0)
        })
        body.addView(card)
    }
}

/** 🔄 Updates itself from the GitHub Releases page — no uninstall needed */
object Updater {
    private val main = Handler(Looper.getMainLooper())
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("plus", Context.MODE_PRIVATE)

    private fun latest(): Pair<Int, String>? = try {
        val c = URL("https://api.github.com/repos/${BuildConfig.REPO}/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        c.disconnect()
        val tag = j.optString("tag_name")                         // v3.57
        val run = tag.substringAfterLast('.').toIntOrNull()
        val assets = j.optJSONArray("assets") ?: JSONArray()
        var url = ""
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
        }
        if (run == null || url.isEmpty()) null else run to url
    } catch (e: Exception) {
        null
    }

    /** Quietly checks a few times a day; shows a notification when a new version is out */
    fun autoCheck(ctx: Context) {
        val app = ctx.applicationContext
        if (BuildConfig.RUN <= 0) return
        if (System.currentTimeMillis() - prefs(app).getLong("updCheck", 0L) < 6 * 3600_000L) return
        prefs(app).edit().putLong("updCheck", System.currentTimeMillis()).apply()
        Thread {
            val l = latest() ?: return@Thread
            if (l.first > BuildConfig.RUN) main.post {
                CoupleLink.notify(app, "🔄  " + Ui.t(app, "New Chinu update (v3.${l.first})", "Chinu ka naya update (v3.${l.first})"),
                    Ui.t(app, "Tap to update — your data stays safe 💖", "Tap karke update karo — sab data safe rahega 💖"), 391,
                    Intent(app, CoupleActivity::class.java).putExtra("care", "update"))
            }
        }.start()
    }

    fun check(a: Activity, manual: Boolean) {
        if (manual) Toast.makeText(a, Ui.t(a, "Checking for updates… 🔄", "Update dhoondh rahe hain… 🔄"), Toast.LENGTH_SHORT).show()
        Thread {
            val l = latest()
            main.post {
                if (a.isFinishing) return@post
                when {
                    l == null -> if (manual) Toast.makeText(a, Ui.t(a, "Couldn't reach GitHub 😕", "GitHub tak nahi pahunch paye 😕"), Toast.LENGTH_LONG).show()
                    l.first <= BuildConfig.RUN -> if (manual) Toast.makeText(a, Ui.t(a, "You already have the latest version ✅", "Aapke paas latest version hai ✅"),
                        Toast.LENGTH_LONG).show()
                    else -> AlertDialog.Builder(a)
                        .setTitle("🔄  " + Ui.t(a, "Update to v3.${l.first}?", "v3.${l.first} par update karein?"))
                        .setMessage(Ui.t(a, "It installs over the current app — chats, photos and settings stay.",
                            "Ye purane app ke upar hi install hoga — chats, photos, settings sab rahenge."))
                        .setPositiveButton(Ui.t(a, "Update", "Update karo")) { _, _ -> download(a, l.second) }
                        .setNegativeButton(Ui.t(a, "Later", "Baad mein"), null).show()
                }
            }
        }.start()
    }

    private fun download(a: Activity, url: String) {
        if (!a.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(a, Ui.t(a, "Allow Chinu to install updates, then tap Update again", "Chinu ko 'install' ki permission do, phir dobara Update dabao"),
                Toast.LENGTH_LONG).show()
            try {
                a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
            } catch (e: Exception) {
            }
            return
        }
        val status = TextView(a).apply {
            text = "0%"
            textSize = 22f
            gravity = android.view.Gravity.CENTER
            setPadding(0, Ui.dp(a, 18f), 0, Ui.dp(a, 18f))
        }
        val dlg = AlertDialog.Builder(a).setTitle("🔄  " + Ui.t(a, "Downloading update…", "Update download ho raha hai…"))
            .setView(status).setCancelable(false).show()
        Thread {
            val f = File(a.cacheDir, "chinu_update.apk")
            var ok = false
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 60000
                c.instanceFollowRedirects = true
                val total = c.contentLengthLong
                c.inputStream.use { input ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    main.post { status.text = "$pct%" }
                                }
                            }
                        }
                    }
                }
                c.disconnect()
                ok = f.length() > 100_000
            } catch (e: Exception) {
            }
            main.post {
                try {
                    dlg.dismiss()
                } catch (e: Exception) {
                }
                if (!ok) {
                    Toast.makeText(a, Ui.t(a, "Download failed 😕 Try again", "Download nahi hua 😕 Dobara try karo"), Toast.LENGTH_LONG).show()
                    return@post
                }
                Thread { install(a.applicationContext, f) }.start()
            }
        }.start()
    }

    /** Hands the APK to Android's installer (same signing key → installs over the old app) */
    private fun install(ctx: Context, f: File) {
        try {
            val pi = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(ctx.packageName)
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                s.openWrite("chinu", 0, f.length()).use { out ->
                    f.inputStream().use { it.copyTo(out) }
                    s.fsync(out)
                }
                val back = PendingIntent.getBroadcast(ctx, 4805, Intent(ctx, PlusReceiver::class.java).setAction(PlusReceiver.INSTALLED),
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0) or PendingIntent.FLAG_UPDATE_CURRENT)
                s.commit(back.intentSender)
            }
        } catch (e: Exception) {
            main.post { Toast.makeText(ctx, Ui.t(ctx, "Couldn't install 😕", "Install nahi ho paya 😕"), Toast.LENGTH_LONG).show() }
        }
    }
}

/** ↩️ notification replies · ☀️🌙 auto wishes · 🔄 installer status · re-schedule after reboot / update */
class PlusReceiver : BroadcastReceiver() {
    companion object {
        const val REPLY = "com.meraassistant.PLUS_REPLY"
        const val LOVE = "com.meraassistant.PLUS_LOVE"
        const val AUTO = "com.meraassistant.PLUS_AUTO"
        const val INSTALLED = "com.meraassistant.PLUS_INSTALLED"
        const val KEY_REPLY = "reply"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()?.trim().orEmpty()
                if (text.isEmpty()) return
                val r = goAsync()
                CoupleLink.send(app, "cmsg", JSONObject().put("text", text)) { ok ->
                    Plus.replied(app, text, ok)
                    r.finish()
                }
            }
            LOVE -> {
                val r = goAsync()
                CoupleLink.send(app, "love", JSONObject().put("k", "love")) { ok ->
                    Plus.replied(app, "❤️ I love you", ok)
                    r.finish()
                }
            }
            AUTO -> {
                val r = goAsync()
                Plus.fireAuto(app, intent.getStringExtra("kind") ?: "gm") { r.finish() }
            }
            INSTALLED -> {
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        if (confirm != null) try {
                            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: Exception) {
                        }
                    }
                    PackageInstaller.STATUS_SUCCESS -> {}
                    else -> Toast.makeText(app, Ui.t(app, "Update didn't install 😕 ", "Update install nahi hua 😕 ") +
                        (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""), Toast.LENGTH_LONG).show()
                }
            }
            Special.CUPID -> Special.cupidCheck(app)
            else -> {                            // boot / app updated
                Plus.scheduleAll(app)
                Special.scheduleCupid(app)
            }
        }
    }
}
