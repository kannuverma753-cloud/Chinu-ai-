package com.meraassistant

import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

/**
 * 🌟 Special:
 *  ✍️ AI love poem / letter · 🖼️ today's couple collage · 🌕 the same moon
 *  💘 Chinu Cupid (gentle nudges) · 🗓️ our love-story timeline · 📞 call date
 */
object Special {
    const val CUPID = "com.meraassistant.CUPID"
    private val main = Handler(Looper.getMainLooper())
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("special", Context.MODE_PRIVATE)

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
    private fun edit(a: Activity, hint: String) = EditText(a).apply { this.hint = hint }
    private fun fmt(day: Long) = LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
    private fun hm(h: Int, m: Int) = String.format(Locale.ENGLISH, "%d:%02d %s", if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM")

    // =============================================================== ✍️ AI love poem / letter
    fun aiWriter(a: Activity) {
        val other = other(a)
        val kinds = arrayOf(Ui.t(a, "💕 Poem", "💕 Kavita"), Ui.t(a, "💌 Love letter", "💌 Love letter"), Ui.t(a, "🌙 Good night message", "🌙 Good night message"),
            Ui.t(a, "🥺 Sorry message", "🥺 Sorry message"))
        AlertDialog.Builder(a).setTitle("✍️  " + Ui.t(a, "Chinu writes for $other", "Chinu $other ke liye likhegi"))
            .setItems(kinds) { _, i ->
                val e = edit(a, Ui.t(a, "About what? e.g. our first meeting, her smile…", "Kis baare mein? jaise pehli mulaqat, unki smile…"))
                AlertDialog.Builder(a).setTitle(kinds[i]).setView(e)
                    .setPositiveButton(Ui.t(a, "Write ✨", "Likho ✨")) { _, _ -> write(a, i, e.text.toString().trim()) }
                    .setNegativeButton(Ui.t(a, "Cancel", "Rehne do"), null).show()
            }.show()
    }

    private fun write(a: Activity, kind: Int, topic: String) {
        val what = listOf("a short romantic poem (6-8 lines)", "a short love letter (5-7 sentences)", "a sweet good night message (3-4 lines)",
            "a heartfelt, cute sorry message (3-5 lines)")[kind]
        val prompt = "Write $what in Hinglish (Hindi written in English letters, simple everyday words) from ${me(a)} to ${other(a)}, " +
            "who are a loving couple." + (if (topic.isNotEmpty()) " Make it about: $topic." else "") +
            " Make it warm, personal, original and a little playful, with 2-3 emojis. Reply with ONLY the text — no title, no explanation."
        toast(a, "Writing… ✨", "Likh rahi hoon… ✨")
        Brain.ask(a, prompt, "hi-IN") { text ->
            if (a.isFinishing) return@ask
            val t = text.trim()
            AlertDialog.Builder(a).setTitle("✍️  " + Ui.t(a, "For ${other(a)}", "${other(a)} ke liye"))
                .setMessage(t)
                .setPositiveButton(Ui.t(a, "💌 Send", "💌 Bhejo")) { _, _ ->
                    CoupleLink.send(a, "note", JSONObject().put("text", "✍️ $t")) { ok ->
                        if (ok) toast(a, "Sent to ${other(a)} 💌", "${other(a)} ko bhej diya 💌") else toast(a, "No internet 😕", "Internet nahi hai 😕")
                    }
                }
                .setNeutralButton(Ui.t(a, "Write again", "Dobara likho")) { _, _ -> write(a, kind, topic) }
                .setNegativeButton(Ui.t(a, "Close", "Band karo"), null).show()
        }
    }

    // =============================================================== 🖼️ today's couple collage
    private fun todayPhotos(ctx: Context): List<File> {
        val t = today()
        val list = ArrayList<File>()
        SelfieStreak.photo(SelfieStreak.mine(ctx, t))?.let { list.add(it) }
        SelfieStreak.photo(SelfieStreak.theirs(ctx, t))?.let { list.add(it) }
        val mine = SelfieStreak.moments(ctx, t, "me")
        val theirs = SelfieStreak.moments(ctx, t, "them")
        // take turns: mine, theirs, mine, theirs…
        for (i in 0 until maxOf(mine.size, theirs.size)) {
            mine.getOrNull(i)?.let { list.add(it) }
            theirs.getOrNull(i)?.let { list.add(it) }
        }
        return list.take(9)
    }

    private fun makeCollage(ctx: Context, files: List<File>): Bitmap? {
        val pics = files.mapNotNull { SelfieStreak.load(it, 700) }
        if (pics.isEmpty()) return null
        val n = pics.size
        val cols = when {
            n == 1 -> 1
            n <= 4 -> 2
            else -> 3
        }
        val rows = (n + cols - 1) / cols
        val size = 1080
        val gap = 12
        val cell = (size - gap * (cols + 1)) / cols
        val footer = 120
        val h = gap + rows * (cell + gap) + footer
        val out = Bitmap.createBitmap(size, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFFFFE4EE.toInt())
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        pics.forEachIndexed { i, b ->
            val r = i / cols
            val col = i % cols
            val left = gap + col * (cell + gap)
            val top = gap + r * (cell + gap)
            // centre-crop to a square
            val s = minOf(b.width, b.height)
            val src = Rect((b.width - s) / 2, (b.height - s) / 2, (b.width - s) / 2 + s, (b.height - s) / 2 + s)
            c.drawBitmap(b, src, Rect(left, top, left + cell, top + cell), paint)
        }
        val p = Profile.load(ctx)
        val text = "${p.partner} ❤ ${p.name}  ·  " + LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
        c.drawText(text, size / 2f, h - footer / 2f + 16f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8A2E5E.toInt()
            textSize = 44f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("cursive", Typeface.BOLD)
        })
        return out
    }

    private fun saveToGallery(ctx: Context, b: Bitmap): Boolean = try {
        val r = ctx.contentResolver
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Chinu_collage_${LocalDate.now()}_${System.currentTimeMillis() % 100000}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Chinu")
        }
        val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
        if (uri == null) false else {
            r.openOutputStream(uri)?.use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            true
        }
    } catch (e: Exception) {
        false
    }

    fun collage(a: Activity) {
        val files = todayPhotos(a)
        if (files.isEmpty()) {
            toast(a, "No photos today yet — click some in 📷 Today's moments", "Aaj abhi koi photo nahi — 📷 Aaj ke pal mein kheencho")
            return
        }
        toast(a, "Making your collage… 🖼️", "Collage bana rahi hoon… 🖼️")
        Thread {
            val b = makeCollage(a, files)
            main.post {
                if (a.isFinishing) return@post
                if (b == null) {
                    toast(a, "Couldn't make it 😕", "Nahi ban paya 😕")
                    return@post
                }
                val img = ImageView(a).apply {
                    adjustViewBounds = true
                    setImageBitmap(b)
                }
                AlertDialog.Builder(a).setTitle("🖼️  " + Ui.t(a, "Today's collage", "Aaj ka collage")).setView(img)
                    .setPositiveButton(Ui.t(a, "💾 Save", "💾 Save karo")) { _, _ ->
                        Thread {
                            val ok = saveToGallery(a, b)
                            main.post { if (ok) toast(a, "Saved in Gallery → Pictures/Chinu 💾", "Gallery → Pictures/Chinu mein save 💾") }
                        }.start()
                    }
                    .setNeutralButton(Ui.t(a, "💌 Send to ${other(a)}", "💌 ${other(a)} ko bhejo")) { _, _ ->
                        Thread {
                            val ok = SelfieStreak.postMoment(a, b, today())
                            main.post { if (ok) toast(a, "Sent 💌", "Bhej diya 💌") }
                        }.start()
                    }
                    .setNegativeButton(Ui.t(a, "Close", "Band karo"), null).show()
            }
        }.start()
    }

    // =============================================================== 🌕 the same moon
    /** 0 = new moon, 0.5 = full moon */
    fun moonPhase(): Double {
        val ref = 947182440000L                       // 6 Jan 2000 18:14 UTC (a new moon)
        val days = (System.currentTimeMillis() - ref) / 86_400_000.0
        val p = (days / 29.530588853) % 1.0
        return if (p < 0) p + 1 else p
    }

    fun moonLine(ctx: Context): String {
        val p = moonPhase()
        val lit = ((1 - Math.cos(2 * Math.PI * p)) / 2 * 100).toInt()
        val (e, en, hi) = when {
            p < 0.03 || p > 0.97 -> Triple("🌑", "New moon", "Amavasya")
            p < 0.22 -> Triple("🌒", "Waxing crescent", "Badhta chaand")
            p < 0.28 -> Triple("🌓", "Half moon", "Aadha chaand")
            p < 0.47 -> Triple("🌔", "Almost full", "Lagbhag poora")
            p < 0.53 -> Triple("🌕", "Full moon!", "Poornima — poora chaand!")
            p < 0.72 -> Triple("🌖", "Waning gibbous", "Ghatta chaand")
            p < 0.78 -> Triple("🌗", "Half moon", "Aadha chaand")
            else -> Triple("🌘", "Waning crescent", "Patla chaand")
        }
        return "$e " + Ui.t(ctx, "Tonight's moon: $en · $lit% lit", "Aaj ka chaand: $hi · $lit% roshan")
    }

    fun lookAtMoon(a: Activity) {
        CoupleLink.send(a, "moon") { ok ->
            if (ok) toast(a, "Told ${other(a)} to look at the moon with you 🌙", "${other(a)} ko bola saath mein chaand dekho 🌙") else toast(a, "No internet 😕", "Internet nahi hai 😕")
        }
    }

    fun onMoon(app: Context, from: String) {
        val text = Ui.t(app, "Go outside and look at the moon — $from is looking at the same moon right now 💫",
            "Bahar jaake chaand dekho — $from bhi abhi wahi chaand dekh rahe hain 💫")
        CoupleLink.notify(app, "🌙  " + Ui.t(app, "$from: look at the moon!", "$from: chaand dekho!"), text + "\n" + moonLine(app), 421,
            Intent(app, CoupleActivity::class.java))
        if (CoupleActivity.current == null) AssistantService.instance?.showLove("🌙", text)
        Voice.say(app, Ui.t(app, "$from says: look at the moon", "$from keh rahe hain: chaand dekho"))
    }

    // =============================================================== 💘 Chinu Cupid — gentle nudges
    fun cupidOn(ctx: Context) = prefs(ctx).getBoolean("cupid", true)

    fun scheduleCupid(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(ctx, 4820, Intent(ctx, PlusReceiver::class.java).setAction(CUPID),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.cancel(pi)
        if (!cupidOn(ctx)) return
        try {
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3 * 3600_000L, 3 * 3600_000L, pi)
        } catch (e: Exception) {
        }
    }

    private val NUDGES = listOf(
        "Aaj %s ko kuch pyaara bheja? Ek jhappi bhej do 🤗",
        "%s ko 'I love you' bole kaafi der ho gayi 🥺 Abhi bol do ❤️",
        "Psst… %s shayad aapko yaad kar rahe hain 💭 Ek message to banta hai",
        "Ek pyaari si selfie %s ko bhejo — din ban jayega 🤳",
        "%s se poocho 'khana khaya?' 🍽️ Chhoti baatein, bada pyaar 💖"
    )

    fun cupidCheck(app: Context) {
        if (!cupidOn(app) || !CoupleLink.paired(app)) return
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour < 9 || hour > 22) return
        val lastMine = LoveJar.all(app).filter { it.mine }.maxOfOrNull { it.at } ?: 0L
        val lastNudge = prefs(app).getLong("lastNudge", 0L)
        val now = System.currentTimeMillis()
        if (now - lastMine < 6 * 3600_000L || now - lastNudge < 5 * 3600_000L) return
        prefs(app).edit().putLong("lastNudge", now).apply()
        val text = String.format(NUDGES.random(), other(app))
        try {
            val nm = app.getSystemService(NotificationManager::class.java)
            val love = PendingIntent.getBroadcast(app, 4803, Intent(app, PlusReceiver::class.java).setAction(PlusReceiver.LOVE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val open = PendingIntent.getActivity(app, 422, Intent(app, CoupleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.createNotificationChannel(android.app.NotificationChannel("couple", "Chiku ❤ Komal", NotificationManager.IMPORTANCE_HIGH))
            nm.notify(422, Notification.Builder(app, "couple")
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("💘  Chinu Cupid")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(null, "❤️ Love you", love).build())
                .setAutoCancel(true)
                .build())
        } catch (e: Exception) {
        }
    }

    // =============================================================== 🗓️ our love-story timeline
    private fun timeline(ctx: Context): MutableList<JSONObject> {
        val a = try {
            JSONArray(prefs(ctx).getString("tl", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.toMutableList()
    }

    private fun mergeTl(ctx: Context, item: JSONObject): Boolean {
        val l = timeline(ctx)
        val i = l.indexOfFirst { it.optString("id") == item.optString("id") }
        if (i >= 0 && l[i].optLong("upd") >= item.optLong("upd")) return false
        if (i >= 0) l[i] = item else l.add(item)
        prefs(ctx).edit().putString("tl", JSONArray(l.takeLast(100)).toString()).apply()
        return true
    }

    private fun putTl(ctx: Context, item: JSONObject) {
        item.put("upd", System.currentTimeMillis())
        mergeTl(ctx, item)
        CoupleLink.send(ctx, "tl_put", item)
    }

    fun onTimeline(app: Context, d: JSONObject, from: String) {
        val known = timeline(app).any { it.optString("id") == d.optString("id") }
        if (!mergeTl(app, d)) return
        if (!known && !d.optBoolean("del")) CoupleLink.notify(app, "🗓️  " + Ui.t(app, "$from added to our timeline", "$from ne hamari timeline mein joda"),
            d.optString("t") + " · " + fmt(d.optLong("day")), 423, Intent(app, CoupleActivity::class.java).putExtra("care", "timeline"))
        CoupleActivity.current?.refresh()
    }

    private fun ago(ctx: Context, day: Long): String {
        val p = Period.between(LocalDate.ofEpochDay(day), LocalDate.now())
        if (p.isNegative) return Ui.t(ctx, "coming up", "aane wala")
        val parts = ArrayList<String>()
        if (p.years > 0) parts.add(Ui.t(ctx, "${p.years} yr", "${p.years} saal"))
        if (p.months > 0) parts.add(Ui.t(ctx, "${p.months} mo", "${p.months} mahine"))
        if (p.years == 0 && p.days > 0) parts.add(Ui.t(ctx, "${p.days} d", "${p.days} din"))
        return if (parts.isEmpty()) Ui.t(ctx, "today", "aaj") else parts.joinToString(" ") + Ui.t(ctx, " ago", " pehle")
    }

    fun showTimeline(a: Activity) {
        val items = timeline(a).filter { !it.optBoolean("del") }.sortedBy { it.optLong("day") }
        val labels = items.map { "💞 ${it.optString("t")}\n     ${fmt(it.optLong("day"))} · ${ago(a, it.optLong("day"))}" }.toTypedArray()
        val b = AlertDialog.Builder(a).setTitle("🗓️  " + Ui.t(a, "Our love story", "Hamari love story"))
        if (items.isEmpty()) b.setMessage(Ui.t(a, "Add your special days: first meeting, first date, first 'I love you'…",
            "Apne khaas din jodo: pehli mulaqat, pehli date, pehla 'I love you'…"))
        else b.setItems(labels) { _, i ->
            val item = items[i]
            AlertDialog.Builder(a).setTitle(item.optString("t")).setMessage(fmt(item.optLong("day")) + " · " + ago(a, item.optLong("day")))
                .setNeutralButton(Ui.t(a, "Delete", "Hatao")) { _, _ ->
                    putTl(a, JSONObject(item.toString()).put("del", true))
                    showTimeline(a)
                }.setPositiveButton("💞", null).show()
        }
        b.setPositiveButton(Ui.t(a, "➕ Add a special day", "➕ Khaas din jodo")) { _, _ ->
            val e = edit(a, Ui.t(a, "e.g. First meeting ☕", "jaise Pehli mulaqat ☕"))
            AlertDialog.Builder(a).setTitle("🗓️  " + Ui.t(a, "What happened?", "Kya hua tha?")).setView(e)
                .setPositiveButton(Ui.t(a, "Pick date", "Date chuno")) { _, _ ->
                    val t = e.text.toString().trim().take(60)
                    if (t.isEmpty()) return@setPositiveButton
                    val c = Calendar.getInstance()
                    DatePickerDialog(a, { _, y, m, d ->
                        putTl(a, JSONObject().put("id", "${System.currentTimeMillis()}").put("t", t).put("day", LocalDate.of(y, m + 1, d).toEpochDay()))
                        CoupleActivity.current?.refresh()
                        showTimeline(a)
                    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                }.setNegativeButton(Ui.t(a, "Cancel", "Rehne do"), null).show()
        }.setNegativeButton(Ui.t(a, "Close", "Band karo"), null).show()
    }

    // =============================================================== 📞 call date (reminder on BOTH phones)
    fun callDate(a: Activity) {
        val other = other(a)
        val now = Calendar.getInstance()
        TimePickerDialog(a, { _, h, m ->
            val c = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
            val at = c.timeInMillis
            try {
                Reminders.add(a, me(a), Ui.t(a, "📞 Call date with $other 💞", "📞 $other ke saath call date 💞"), false, 0, 0, 0, at)
            } catch (e: Exception) {
            }
            prefs(a).edit().putLong("callAt", at).apply()
            CoupleLink.send(a, "calldate", JSONObject().put("at", at)) { ok ->
                if (ok) toast(a, "Call date at ${hm(h, m)} — reminder on both phones 📞", "${hm(h, m)} ka call date — dono phones par reminder 📞")
                else toast(a, "No internet 😕", "Internet nahi hai 😕")
            }
            CoupleActivity.current?.refresh()
        }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), false).show()
    }

    fun onCallDate(app: Context, d: JSONObject, from: String) {
        val at = d.optLong("at")
        if (at < System.currentTimeMillis()) return
        try {
            Reminders.add(app, me(app), Ui.t(app, "📞 Call date with $from 💞", "📞 $from ke saath call date 💞"), false, 0, 0, 0, at)
        } catch (e: Exception) {
        }
        prefs(app).edit().putLong("callAt", at).apply()
        val c = Calendar.getInstance().apply { timeInMillis = at }
        CoupleLink.notify(app, "📞  " + Ui.t(app, "$from fixed a call date!", "$from ne call date fix kiya!"),
            hm(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)) + Ui.t(app, " — reminder is set 💞", " — reminder lag gaya 💞"), 424,
            Intent(app, CoupleActivity::class.java))
        CoupleActivity.current?.refresh()
    }

    private fun callLine(ctx: Context): String? {
        val at = prefs(ctx).getLong("callAt", 0L)
        if (at < System.currentTimeMillis()) return null
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val day = if (c.get(Calendar.DAY_OF_YEAR) == Calendar.getInstance().get(Calendar.DAY_OF_YEAR)) Ui.t(ctx, "today", "aaj") else Ui.t(ctx, "tomorrow", "kal")
        return "📞 " + Ui.t(ctx, "Call date $day at ", "Call date $day ") + hm(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    // =============================================================== actions + 💞 card
    fun action(a: Activity, what: String) {
        when (what) {
            "ai" -> aiWriter(a)
            "collage" -> collage(a)
            "moon" -> lookAtMoon(a)
            "timeline" -> showTimeline(a)
            "call" -> callDate(a)
            else -> Khel.action(a, what)
        }
    }

    fun card(a: Activity, body: LinearLayout) {
        val dp = { v: Float -> Ui.dp(a, v) }
        body.addView(Ui.label(a, Ui.t(a, "🌟 SPECIAL", "🌟 KHAAS"), 12.5f, 0xFF8A4F9E.toInt(), true).apply {
            letterSpacing = 0.1f
            setPadding(dp(4f), dp(26f), 0, dp(8f))
        })
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glass(a, 24f, 0x66)
            setPadding(dp(16f), dp(14f), dp(16f), dp(16f))
        }
        val lines = listOfNotNull(callLine(a), moonLine(a), timeline(a).filter { !it.optBoolean("del") }.minByOrNull { it.optLong("day") }?.let {
            "🗓️ ${it.optString("t")} · ${ago(a, it.optLong("day"))}"
        })
        for (l in lines) card.addView(Ui.label(a, l, 14f, 0xFF3B1F4A.toInt()).apply { setPadding(0, dp(2f), 0, dp(5f)) })
        fun row(l: Pair<String, () -> Unit>, r: Pair<String, () -> Unit>) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Ui.glassButton(a, l.first, l.second).apply { textSize = 14f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Ui.glassButton(a, r.first, r.second).apply { textSize = 14f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8f) })
            card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        }
        row(Ui.t(a, "✍️ AI poem / letter", "✍️ AI kavita / letter") to { aiWriter(a) },
            Ui.t(a, "🖼️ Today's collage", "🖼️ Aaj ka collage") to { collage(a) })
        row(Ui.t(a, "🌕 Look at the moon", "🌕 Saath chaand dekho") to { lookAtMoon(a) },
            Ui.t(a, "🗓️ Our timeline", "🗓️ Hamari timeline") to { showTimeline(a) })
        row(Ui.t(a, "📞 Fix a call date", "📞 Call date fix karo") to { callDate(a) },
            "💘 Cupid: " + (if (cupidOn(a)) "ON" else "OFF") to {
                prefs(a).edit().putBoolean("cupid", !cupidOn(a)).apply()
                scheduleCupid(a)
                toast(a, if (cupidOn(a)) "💘 Cupid will gently remind you to send love" else "💘 Cupid is off",
                    if (cupidOn(a)) "💘 Cupid pyaar se yaad dilayega" else "💘 Cupid band")
                CoupleActivity.current?.refresh()
            })
        body.addView(card)
    }
}
