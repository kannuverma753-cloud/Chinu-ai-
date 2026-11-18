package com.meraassistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

/**
 * 🛟 Care & everyday things for the two of us:
 *  🏠 one-tap status · 📍 location · 🆘 SOS · 🔋 partner's battery · ⏳ countdown to meeting
 *  📝 our shared list · ⏰ a reminder on the partner's phone
 */
object Care {
    const val REQ_LOC = 9130
    private val main = Handler(Looper.getMainLooper())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("care", Context.MODE_PRIVATE)

    private fun other(ctx: Context): String {
        Speaker.load(ctx)
        return Speaker.other(Profile.load(ctx))
    }

    private fun me(ctx: Context): String {
        Speaker.load(ctx)
        return Speaker.name(Profile.load(ctx))
    }

    private fun boy(ctx: Context): Boolean {
        Speaker.load(ctx)
        return Speaker.isPartner()
    }

    private fun toast(ctx: Context, en: String, hi: String) = Toast.makeText(ctx, Ui.t(ctx, en, hi), Toast.LENGTH_SHORT).show()

    private fun ago(ctx: Context, at: Long): String {
        val m = ((System.currentTimeMillis() - at) / 60_000L).coerceAtLeast(0)
        return when {
            m < 1 -> Ui.t(ctx, "just now", "abhi")
            m < 60 -> Ui.t(ctx, "$m min ago", "$m min pehle")
            m < 24 * 60 -> Ui.t(ctx, "${m / 60} h ago", "${m / 60} ghante pehle")
            else -> Ui.t(ctx, "${m / 1440} days ago", "${m / 1440} din pehle")
        }
    }

    // =============================================================== 🏠 one-tap status
    /** id, emoji, English, Hinglish (Chiku), Hinglish (Komal) */
    val PINGS = listOf(
        listOf("home", "🏠", "Reached home safely", "Ghar pahunch gaya", "Ghar pahunch gayi"),
        listOf("leave", "🚗", "Leaving now, on my way", "Nikal raha hoon, raaste mein hoon", "Nikal rahi hoon, raaste mein hoon"),
        listOf("free", "📞", "I'm free — call me", "Free hoon, call karo", "Free hoon, call karo"),
        listOf("busy", "🙏", "Busy right now, will call later", "Abhi busy hoon, baad mein call karta hoon", "Abhi busy hoon, baad mein call karti hoon"),
        listOf("ate", "🍽️", "Had my food", "Khana kha liya", "Khana kha liya"),
        listOf("sleep", "😴", "Going to sleep, good night", "So raha hoon, good night", "So rahi hoon, good night")
    )

    fun pingText(ctx: Context, id: String, isBoy: Boolean): String? {
        val r = PINGS.firstOrNull { it[0] == id } ?: return null
        return r[1] + "  " + if (Ui.hinglish(ctx)) r[if (isBoy) 3 else 4] else r[2]
    }

    fun sendPing(ctx: Context, id: String) {
        if (!CoupleLink.paired(ctx)) return
        val other = other(ctx)
        CoupleLink.send(ctx, "ping", JSONObject().put("id", id).put("boy", boy(ctx)).put("st", statusJson(ctx))) { ok ->
            if (ok) toast(ctx, "Sent to $other ✅", "$other ko bata diya ✅") else toast(ctx, "No internet 😕", "Internet nahi hai 😕")
        }
    }

    fun onPing(app: Context, d: JSONObject, from: String) {
        val text = pingText(app, d.optString("id"), d.optBoolean("boy")) ?: return
        saveStatus(app, d.optJSONObject("st"))
        prefs(app).edit().putString("lastPing", text).putLong("lastPingAt", System.currentTimeMillis()).apply()
        CoupleLink.notify(app, from, text, 381, Intent(app, CoupleActivity::class.java))
        CoupleLink.buzz(app, longArrayOf(0, 60, 50, 60))
        CoupleActivity.current?.refresh() ?: AssistantService.instance?.showLove(text.substringBefore(' '), "$from: ${text.substringAfter("  ")}")
    }

    fun lastPing(ctx: Context): String? {
        val t = prefs(ctx).getString("lastPing", "").orEmpty()
        val at = prefs(ctx).getLong("lastPingAt", 0L)
        if (t.isEmpty() || System.currentTimeMillis() - at > 24 * 3600_000L) return null
        return "$t · ${ago(ctx, at)}"
    }

    // =============================================================== 🔋 partner's phone
    fun statusJson(ctx: Context): JSONObject {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val pct = try {
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        } catch (e: Exception) {
            -1
        }
        val chg = try {
            bm?.isCharging ?: false
        } catch (e: Exception) {
            false
        }
        return JSONObject().put("bat", pct).put("chg", chg).put("at", System.currentTimeMillis())
    }

    private fun saveStatus(ctx: Context, st: JSONObject?) {
        if (st == null || st.optInt("bat", -1) < 0) return
        prefs(ctx).edit().putString("status", st.toString()).apply()
    }

    fun statusLine(ctx: Context): String? {
        val st = try {
            JSONObject(prefs(ctx).getString("status", "").orEmpty())
        } catch (e: Exception) {
            return null
        }
        val bat = st.optInt("bat", -1)
        if (bat < 0) return null
        val icon = if (st.optBoolean("chg")) "⚡" else if (bat <= 15) "🪫" else "🔋"
        val chg = if (st.optBoolean("chg")) Ui.t(ctx, " charging", " charge ho raha") else ""
        return "$icon $bat%$chg · ${ago(ctx, st.optLong("at"))}"
    }

    fun askStatus(ctx: Context) {
        if (!CoupleLink.paired(ctx)) return
        prefs(ctx).edit().putLong("askedAt", System.currentTimeMillis()).apply()
        CoupleLink.send(ctx, "status_ask")
    }

    fun onStatusAsk(app: Context) {
        CoupleLink.send(app, "status", statusJson(app))
    }

    fun onStatus(app: Context, d: JSONObject, from: String) {
        saveStatus(app, d)
        val asked = System.currentTimeMillis() - prefs(app).getLong("askedAt", 0L) < 3 * 60_000L
        if (asked) {
            prefs(app).edit().putLong("askedAt", 0L).apply()
            val line = statusLine(app) ?: return
            CoupleLink.notify(app, Ui.t(app, "$from's phone", "$from ka phone"), line, 382, Intent(app, CoupleActivity::class.java))
            if (CoupleActivity.current == null) AssistantService.instance?.showLove("🔋", Ui.t(app, "$from's phone: $line", "$from ka phone: $line"))
        }
        CoupleActivity.current?.refresh()
    }

    // =============================================================== 📍 location
    fun hasLoc(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun askLoc(a: Activity, then: String) {
        prefs(a).edit().putString("afterLoc", then).apply()
        a.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC)
    }

    /** Called from CoupleActivity.onRequestPermissionsResult */
    fun onPermission(a: Activity, requestCode: Int, grantResults: IntArray) {
        if (requestCode != REQ_LOC) return
        val then = prefs(a).getString("afterLoc", "").orEmpty()
        prefs(a).edit().remove("afterLoc").apply()
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            if (then == "loc") shareLocation(a)
        } else if (then == "loc") {
            toast(a, "Location permission is needed for this 📍", "Iske liye location ki permission chahiye 📍")
        }
    }

    @Suppress("MissingPermission")
    fun currentLocation(ctx: Context, done: (Location?) -> Unit) {
        if (!hasLoc(ctx)) {
            done(null)
            return
        }
        val lm = ctx.getSystemService(LocationManager::class.java)
        if (lm == null) {
            done(null)
            return
        }
        val last = try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (e: Exception) { null } }
                .maxByOrNull { it.time }
        } catch (e: Exception) {
            null
        }
        if (Build.VERSION.SDK_INT < 30) {
            done(last)
            return
        }
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            done(last)
            return
        }
        var finished = false
        val fallback = Runnable {
            if (!finished) {
                finished = true
                done(last)
            }
        }
        main.postDelayed(fallback, 12_000L)
        try {
            lm.getCurrentLocation(provider, null, ctx.mainExecutor) { loc ->
                if (!finished) {
                    finished = true
                    main.removeCallbacks(fallback)
                    done(loc ?: last)
                }
            }
        } catch (e: Exception) {
            if (!finished) {
                finished = true
                main.removeCallbacks(fallback)
                done(last)
            }
        }
    }

    fun shareLocation(a: Activity) {
        if (!CoupleLink.paired(a)) return
        if (!hasLoc(a)) {
            askLoc(a, "loc")
            return
        }
        val other = other(a)
        toast(a, "Finding your location… 📍", "Location dhoondh rahe hain… 📍")
        currentLocation(a) { loc ->
            if (loc == null) {
                toast(a, "Couldn't get the location — turn on GPS 📍", "Location nahi mili — GPS on karo 📍")
                return@currentLocation
            }
            CoupleLink.send(a, "loc", JSONObject().put("lat", loc.latitude).put("lng", loc.longitude)
                .put("acc", loc.accuracy.toDouble()).put("st", statusJson(a))) { ok ->
                if (ok) toast(a, "Location sent to $other 📍", "$other ko location bhej di 📍") else toast(a, "No internet 😕", "Internet nahi hai 😕")
            }
        }
    }

    fun mapsUrl(lat: Double, lng: Double) = "https://maps.google.com/?q=$lat,$lng"

    fun onLoc(app: Context, d: JSONObject, from: String) {
        if (!d.has("lat")) return
        val url = mapsUrl(d.optDouble("lat"), d.optDouble("lng"))
        saveStatus(app, d.optJSONObject("st"))
        prefs(app).edit().putString("lastLoc", url).putLong("lastLocAt", System.currentTimeMillis()).apply()
        CoupleLink.notify(app, "📍  " + Ui.t(app, "$from shared their location", "$from ne apni location bheji"),
            Ui.t(app, "Tap to open in Maps", "Maps mein dekhne ke liye tap karo"), 384, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        CoupleLink.buzz(app, longArrayOf(0, 60, 50, 60))
        CoupleActivity.current?.refresh()
    }

    fun lastLoc(ctx: Context): Pair<String, Long>? {
        val u = prefs(ctx).getString("lastLoc", "").orEmpty()
        if (u.isEmpty()) return null
        return u to prefs(ctx).getLong("lastLocAt", 0L)
    }

    // =============================================================== 🆘 SOS
    fun confirmSos(a: Activity) {
        if (!CoupleLink.paired(a)) return
        val other = other(a)
        AlertDialog.Builder(a)
            .setTitle("🆘  " + Ui.t(a, "Send SOS to $other?", "$other ko SOS bhejein?"))
            .setMessage(Ui.t(a, "Their phone will ring loudly with your location, even on silent.",
                "Unke phone par zor se alarm bajega aur aapki location jayegi."))
            .setPositiveButton(Ui.t(a, "🆘 Send SOS", "🆘 SOS bhejo")) { _, _ -> sendSos(a) }
            .setNegativeButton(Ui.t(a, "Cancel", "Rehne do"), null)
            .show()
    }

    private fun sendSos(a: Activity) {
        val other = other(a)
        val d = JSONObject().put("st", statusJson(a))
        // the alarm goes out at once; the location follows when the phone finds it
        CoupleLink.send(a, "sos", d) { ok ->
            if (ok) toast(a, "SOS sent to $other 🆘", "$other ko SOS chala gaya 🆘") else toast(a, "No internet 😕 Call directly!", "Internet nahi hai 😕 Seedha call karo!")
        }
        if (hasLoc(a)) currentLocation(a) { loc ->
            if (loc != null) CoupleLink.send(a, "loc", JSONObject().put("lat", loc.latitude).put("lng", loc.longitude)
                .put("acc", loc.accuracy.toDouble()).put("st", statusJson(a)))
        }
    }

    private var ring: Ringtone? = null

    fun onSos(app: Context, d: JSONObject, from: String) {
        saveStatus(app, d.optJSONObject("st"))
        val nm = app.getSystemService(NotificationManager::class.java)
        val alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        try {
            nm.createNotificationChannel(NotificationChannel("chinu_sos", "🆘 SOS", NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(alarm, attrs)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
                setBypassDnd(true)
            })
            val open = PendingIntent.getActivity(app, 395, Intent(app, CoupleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val call = PendingIntent.getActivity(app, 396, Intent(app, MainActivity::class.java).putExtra("wake", true).putExtra("command", "$from ko call karo")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(394, Notification.Builder(app, "chinu_sos")
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("🆘  " + Ui.t(app, "$from needs you — SOS!", "$from ko aapki zarurat hai — SOS!"))
                .setContentText(Ui.t(app, "Call them right now. Their location is coming…", "Abhi call karo. Unki location aa rahi hai…"))
                .setContentIntent(open)
                .setFullScreenIntent(open, true)
                .addAction(Notification.Action.Builder(null, Ui.t(app, "📞 Call", "📞 Call karo"), call).build())
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .build())
        } catch (e: Exception) {
        }
        // ring loudly (alarm volume) for up to 30 seconds, even on silent
        try {
            ring?.stop()
            ring = RingtoneManager.getRingtone(app, alarm)?.apply {
                audioAttributes = attrs
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
            main.postDelayed({ stopSos() }, 30_000L)
        } catch (e: Exception) {
        }
        CoupleLink.buzz(app, longArrayOf(0, 600, 300, 600, 300, 600, 300, 600))
        prefs(app).edit().putLong("sosAt", System.currentTimeMillis()).putString("sosFrom", from).apply()
        CoupleActivity.current?.refresh()
    }

    fun stopSos() {
        try {
            ring?.stop()
        } catch (e: Exception) {
        }
        ring = null
    }

    fun sosActive(ctx: Context) = System.currentTimeMillis() - prefs(ctx).getLong("sosAt", 0L) < 30 * 60_000L

    // =============================================================== ⏳ countdown to the next meeting
    fun meetDay(ctx: Context): Long? = prefs(ctx).getLong("meet", -1L).takeIf { it >= 0 }

    fun daysLeft(ctx: Context): Long? {
        val m = meetDay(ctx) ?: return null
        val left = m - LocalDate.now().toEpochDay()
        return if (left < 0) null else left
    }

    fun countdownLine(ctx: Context): String? {
        val left = daysLeft(ctx) ?: return null
        val date = LocalDate.ofEpochDay(meetDay(ctx) ?: return null).format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
        return when (left) {
            0L -> Ui.t(ctx, "⏳ We meet TODAY! 🥰", "⏳ Aaj milna hai! 🥰")
            1L -> Ui.t(ctx, "⏳ We meet tomorrow ($date) 🥰", "⏳ Kal milna hai ($date) 🥰")
            else -> Ui.t(ctx, "⏳ $left days until we meet ($date)", "⏳ Milne mein $left din baaki ($date)")
        }
    }

    fun pickMeet(a: Activity) {
        val c = Calendar.getInstance()
        meetDay(a)?.let { d ->
            val ld = LocalDate.ofEpochDay(d)
            c.set(ld.year, ld.monthValue - 1, ld.dayOfMonth)
        }
        DatePickerDialog(a, { _, y, m, day ->
            val epoch = LocalDate.of(y, m + 1, day).toEpochDay()
            val upd = System.currentTimeMillis()
            prefs(a).edit().putLong("meet", epoch).putLong("meetUpd", upd).apply()
            CoupleLink.send(a, "meet", JSONObject().put("day", epoch).put("upd", upd))
            toast(a, "Saved on both phones ⏳💞", "Dono phones mein save ho gaya ⏳💞")
            CoupleActivity.current?.refresh()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.minDate = System.currentTimeMillis() - 1000
        }.show()
    }

    fun onMeet(app: Context, d: JSONObject, from: String) {
        val upd = d.optLong("upd")
        if (upd <= prefs(app).getLong("meetUpd", 0L)) return
        val day = d.optLong("day", -1L)
        if (day < 0) return
        prefs(app).edit().putLong("meet", day).putLong("meetUpd", upd).apply()
        val date = LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
        CoupleLink.notify(app, "⏳  " + Ui.t(app, "$from set our meeting date", "$from ne milne ki date set ki"),
            "$date 💞  " + (countdownLine(app) ?: ""), 385, Intent(app, CoupleActivity::class.java))
        CoupleActivity.current?.refresh()
    }

    // =============================================================== 📝 our shared list
    private fun loadList(ctx: Context): MutableList<JSONObject> {
        val a = try {
            JSONArray(prefs(ctx).getString("list", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.toMutableList()
    }

    private fun saveList(ctx: Context, l: List<JSONObject>) {
        // forget deleted / done items after 30 days
        val cut = System.currentTimeMillis() - 30L * 24 * 3600_000L
        val keep = l.filter { !(it.optBoolean("del") || it.optBoolean("done")) || it.optLong("upd") > cut }.takeLast(200)
        prefs(ctx).edit().putString("list", JSONArray(keep).toString()).apply()
    }

    /** Visible items: open ones first, newest first */
    fun listItems(ctx: Context): List<JSONObject> =
        loadList(ctx).filter { !it.optBoolean("del") }.sortedWith(compareBy<JSONObject> { it.optBoolean("done") }.thenByDescending { it.optLong("at") })

    fun openCount(ctx: Context) = listItems(ctx).count { !it.optBoolean("done") }

    /** true when the item was new or newer than ours */
    private fun merge(ctx: Context, item: JSONObject): Boolean {
        val id = item.optString("id")
        if (id.isEmpty()) return false
        val l = loadList(ctx)
        val i = l.indexOfFirst { it.optString("id") == id }
        if (i >= 0 && l[i].optLong("upd") >= item.optLong("upd")) return false
        if (i >= 0) l[i] = item else l.add(item)
        saveList(ctx, l)
        return true
    }

    private fun put(ctx: Context, item: JSONObject) {
        item.put("upd", System.currentTimeMillis())
        merge(ctx, item)
        CoupleLink.send(ctx, "list_put", item)
    }

    fun addItem(ctx: Context, text: String) {
        val t = text.trim().take(120)
        if (t.isEmpty()) return
        val now = System.currentTimeMillis()
        put(ctx, JSONObject().put("id", "$now" + (100..999).random()).put("t", t).put("by", me(ctx)).put("at", now).put("done", false))
    }

    fun toggle(ctx: Context, item: JSONObject) = put(ctx, JSONObject(item.toString()).put("done", !item.optBoolean("done")))

    fun delete(ctx: Context, item: JSONObject) = put(ctx, JSONObject(item.toString()).put("del", true))

    fun onListPut(app: Context, d: JSONObject, from: String) {
        val known = loadList(app).any { it.optString("id") == d.optString("id") }
        if (!merge(app, d)) return
        if (!known && !d.optBoolean("del") && !d.optBoolean("done")) {
            CoupleLink.notify(app, "📝  " + Ui.t(app, "$from added to our list", "$from ne hamari list mein joda"),
                d.optString("t"), 386, Intent(app, CoupleActivity::class.java).putExtra("care", "list"))
        }
        CoupleActivity.current?.refresh()
        listRefresh?.invoke()
    }

    /** Send the whole list now and then, so both phones catch up on anything missed */
    fun syncList(ctx: Context, reply: Boolean = false) {
        if (!CoupleLink.paired(ctx)) return
        val p = prefs(ctx)
        if (!reply && System.currentTimeMillis() - p.getLong("listSync", 0L) < 10 * 60_000L) return
        p.edit().putLong("listSync", System.currentTimeMillis()).apply()
        CoupleLink.send(ctx, "list_all", JSONObject().put("items", JSONArray(loadList(ctx).takeLast(80))).put("reply", reply))
    }

    fun onListAll(app: Context, d: JSONObject) {
        val a = d.optJSONArray("items") ?: JSONArray()
        var changed = false
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { if (merge(app, it)) changed = true }
        if (changed) {
            CoupleActivity.current?.refresh()
            listRefresh?.invoke()
        }
        if (!d.optBoolean("reply")) syncList(app, reply = true)
    }

    private var listRefresh: (() -> Unit)? = null

    fun showList(a: Activity) {
        val dp = { v: Float -> Ui.dp(a, v) }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(8f), dp(18f), dp(8f))
        }
        val scroll = ScrollView(a).apply { addView(box) }
        fun render() {
            box.removeAllViews()
            val items = listItems(a)
            if (items.isEmpty()) box.addView(Ui.label(a, Ui.t(a, "Nothing yet — add milk, gifts, plans…", "Abhi khaali hai — doodh, gift, plans… kuch bhi jodo"),
                14f, 0xFF8E7A99.toInt()).apply { setPadding(0, dp(10f), 0, dp(10f)) })
            for (item in items) {
                val done = item.optBoolean("done")
                box.addView(TextView(a).apply {
                    text = (if (done) "✅  " else "⬜  ") + item.optString("t") + "   · " + item.optString("by")
                    textSize = 16f
                    setTextColor(if (done) 0xFF9A8AA5.toInt() else 0xFF3B1F4A.toInt())
                    if (done) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                    setPadding(0, dp(10f), 0, dp(10f))
                    setOnClickListener { _ ->
                        toggle(a, item)
                        render()
                    }
                    setOnLongClickListener { _ ->
                        AlertDialog.Builder(a).setTitle(Ui.t(a, "Delete “${item.optString("t")}”?", "“${item.optString("t")}” hata dein?"))
                            .setPositiveButton(Ui.t(a, "Delete", "Hatao")) { _, _ ->
                                delete(a, item)
                                render()
                            }.setNegativeButton(Ui.t(a, "Cancel", "Rehne do"), null).show()
                        true
                    }
                })
            }
        }
        render()
        listRefresh = { a.runOnUiThread { render() } }
        val dlg = AlertDialog.Builder(a)
            .setTitle("📝  " + Ui.t(a, "Our list", "Hamari list"))
            .setView(scroll)
            .setPositiveButton(Ui.t(a, "➕ Add", "➕ Jodo"), null)
            .setNegativeButton(Ui.t(a, "Close", "Band karo"), null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val input = EditText(a).apply { hint = Ui.t(a, "e.g. Milk, movie tickets…", "jaise Doodh, movie tickets…") }
                AlertDialog.Builder(a).setTitle("➕  " + Ui.t(a, "Add to our list", "List mein jodo")).setView(input)
                    .setPositiveButton(Ui.t(a, "Add", "Jodo")) { _, _ ->
                        input.text.toString().split('\n', ',').forEach { s -> addItem(a, s) }
                        render()
                    }.setNegativeButton(Ui.t(a, "Cancel", "Rehne do"), null).show()
            }
        }
        dlg.setOnDismissListener {
            listRefresh = null
            CoupleActivity.current?.refresh()
        }
        dlg.show()
    }

    // =============================================================== ⏰ a reminder on the partner's phone
    fun remindPartner(a: Activity) {
        if (!CoupleLink.paired(a)) return
        val other = other(a)
        val input = EditText(a).apply { hint = Ui.t(a, "e.g. Drink water 💧, take medicine 💊", "jaise Paani pi lo 💧, dawai le lo 💊") }
        AlertDialog.Builder(a).setTitle("⏰  " + Ui.t(a, "Reminder for $other", "$other ke liye reminder"))
            .setView(input)
            .setPositiveButton(Ui.t(a, "Pick time", "Time chuno")) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                val now = Calendar.getInstance()
                TimePickerDialog(a, { _, h, m ->
                    val c = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }
                    if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
                    val at = c.timeInMillis
                    CoupleLink.send(a, "rem_put", JSONObject().put("text", text).put("at", at)) { ok ->
                        val time = String.format(Locale.ENGLISH, "%d:%02d %s", if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM")
                        if (ok) toast(a, "Reminder set on $other's phone for $time ⏰", "$other ke phone par $time ka reminder lag gaya ⏰")
                        else toast(a, "No internet 😕", "Internet nahi hai 😕")
                    }
                }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), false).show()
            }
            .setNegativeButton(Ui.t(a, "Cancel", "Rehne do"), null)
            .show()
    }

    fun onRemPut(app: Context, d: JSONObject, from: String) {
        val text = d.optString("text").trim()
        val at = d.optLong("at")
        if (text.isEmpty() || at < System.currentTimeMillis() - 60_000L) return
        try {
            Reminders.add(app, me(app), "$text — $from 💞", false, 0, 0, 0, at)
        } catch (e: Exception) {
            return
        }
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val h = c.get(Calendar.HOUR_OF_DAY)
        val time = String.format(Locale.ENGLISH, "%d:%02d %s", if (h % 12 == 0) 12 else h % 12, c.get(Calendar.MINUTE), if (h < 12) "AM" else "PM")
        CoupleLink.notify(app, "⏰  " + Ui.t(app, "$from set a reminder for you", "$from ne aapke liye reminder lagaya"),
            "$time · $text", 387, Intent(app, CoupleActivity::class.java))
    }

    // =============================================================== actions opened from chat / notifications
    fun action(a: Activity, what: String) {
        when (what) {
            "loc" -> shareLocation(a)
            "sos" -> confirmSos(a)
            "list" -> showList(a)
            "meet" -> pickMeet(a)
            "remind" -> remindPartner(a)
            else -> Plus.action(a, what)
        }
    }

    // =============================================================== 💞 the card on the couple screen
    fun card(a: Activity, body: LinearLayout) {
        val dp = { v: Float -> Ui.dp(a, v) }
        val other = other(a)
        body.addView(Ui.label(a, Ui.t(a, "🛟 CARE & EVERYDAY", "🛟 KHAYAL & ROZ KE KAAM"), 12.5f, 0xFF8A4F9E.toInt(), true).apply {
            letterSpacing = 0.1f
            setPadding(dp(4f), dp(26f), 0, dp(8f))
        })
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glass(a, 24f, 0x66)
            setPadding(dp(16f), dp(14f), dp(16f), dp(16f))
        }
        // what we know about the partner right now
        if (sosActive(a)) {
            card.addView(Ui.label(a, "🆘  " + Ui.t(a, "${prefs(a).getString("sosFrom", other)} sent an SOS — call now!",
                "${prefs(a).getString("sosFrom", other)} ne SOS bheja — abhi call karo!"), 15f, 0xFFD62F4B.toInt(), true).apply {
                setPadding(0, 0, 0, dp(8f))
                setOnClickListener { stopSos() }
            })
        }
        val info = listOfNotNull(
            lastPing(a)?.let { "$other: $it" },
            statusLine(a)?.let { Ui.t(a, "$other's phone: $it", "$other ka phone: $it") },
            countdownLine(a)
        )
        for (line in info) card.addView(Ui.label(a, line, 14f, 0xFF3B1F4A.toInt()).apply { setPadding(0, dp(2f), 0, dp(4f)) })
        lastLoc(a)?.let { (url, at) ->
            card.addView(Ui.label(a, "📍 " + Ui.t(a, "$other's location · ${ago(a, at)} — open", "$other ki location · ${ago(a, at)} — kholo"),
                14f, 0xFF6B3FA0.toInt(), true).apply {
                setPadding(0, dp(2f), 0, dp(4f))
                setOnClickListener {
                    try {
                        a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: Exception) {
                    }
                }
            })
        }
        // 🏠 one-tap status
        card.addView(Ui.label(a, Ui.t(a, "Tell $other in one tap:", "$other ko ek tap mein batao:"), 13f, 0xFF8A4F9E.toInt(), true).apply {
            setPadding(0, dp(if (info.isEmpty()) 0f else 8f), 0, dp(6f))
        })
        val isBoy = boy(a)
        PINGS.chunked(2).forEach { pair ->
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, r ->
                row.addView(Ui.glassButton(a, pingText(a, r[0], isBoy) ?: r[1]) { sendPing(a, r[0]) }.apply {
                    textSize = 13.5f
                    setPadding(dp(6f), dp(12f), dp(6f), dp(12f))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i == 1) leftMargin = dp(8f) })
            }
            card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        }
        // tools
        fun toolRow(l: Pair<String, () -> Unit>, r: Pair<String, () -> Unit>) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Ui.glassButton(a, l.first, l.second).apply { textSize = 14f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Ui.glassButton(a, r.first, r.second).apply { textSize = 14f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8f) })
            card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })
        }
        toolRow(Ui.t(a, "📍 Send location", "📍 Location bhejo") to { shareLocation(a) },
            Ui.t(a, "🔋 Check $other's phone", "🔋 $other ka phone") to {
                askStatus(a)
                toast(a, "Asking $other's phone… 🔋", "$other ke phone se pooch rahe hain… 🔋")
            })
        val n = openCount(a)
        toolRow(Ui.t(a, "📝 Our list", "📝 Hamari list") + (if (n > 0) " ($n)" else "") to { showList(a) },
            Ui.t(a, "⏰ Remind $other", "⏰ $other ko reminder") to { remindPartner(a) })
        card.addView(Ui.glassButton(a, Ui.t(a, "⏳ Set our meeting date", "⏳ Milne ki date set karo")) { pickMeet(a) }.apply { textSize = 14f },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })
        // 🆘
        card.addView(TextView(a).apply {
            text = "🆘  " + Ui.t(a, "SOS — I need you", "SOS — mujhe aapki zarurat hai")
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFFF4D6D.toInt(), 0xFFD62F4B.toInt()))
                .apply { cornerRadius = dp(24f).toFloat() }
            setPadding(0, dp(14f), 0, dp(14f))
            setOnClickListener { confirmSos(a) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14f) })
        body.addView(card)
    }
}
