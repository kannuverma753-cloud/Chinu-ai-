package com.meraassistant

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * ⏰ Reminders — spoken aloud + notification (also on the lock screen / screen off).
 * Repeating: every N minutes between fromHour and toHour (e.g. water every hour, 8 AM – 10 PM).
 * One-time: at an exact date & time.
 */
object Reminders {

    const val CHANNEL = "reminders"

    class Rem(
        val id: Int,
        val who: String,
        val text: String,
        val water: Boolean,
        val everyMin: Int,          // 0 = one time
        val fromHour: Int,
        val toHour: Int,
        val atMillis: Long,         // one time only
        val minute: Int = 0,        // minute of the hour for repeating ones (daily 1:30 PM -> 30)
        val full: Boolean = false   // text is a complete sentence (daily routine)
    ) {
        val daily get() = everyMin >= 1440

        fun title(): String = if (water) "💧 Drink water · $who" else "⏰ ${text.ifBlank { "Reminder" }.take(40)} · $who"

        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("who", who).put("text", text).put("water", water)
            .put("every", everyMin).put("from", fromHour).put("to", toHour).put("at", atMillis)
            .put("min", minute).put("full", full)

        companion object {
            fun from(j: JSONObject) = Rem(
                j.optInt("id"), j.optString("who"), j.optString("text"), j.optBoolean("water"),
                j.optInt("every"), j.optInt("from", 8), j.optInt("to", 22), j.optLong("at"),
                j.optInt("min", 0), j.optBoolean("full", false)
            )
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Rem> = try {
        val a = JSONArray(prefs(ctx).getString("list", "[]"))
        (0 until a.length()).map { Rem.from(a.getJSONObject(it)) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveAll(ctx: Context, list: List<Rem>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        prefs(ctx).edit().putString("list", a.toString()).apply()
    }

    fun get(ctx: Context, id: Int) = all(ctx).firstOrNull { it.id == id }

    private fun newId(ctx: Context): Int {
        val p = prefs(ctx)
        val id = p.getInt("nextId", 1)
        p.edit().putInt("nextId", id + 1).apply()
        return id
    }

    fun add(ctx: Context, who: String, text: String, water: Boolean, everyMin: Int, fromHour: Int, toHour: Int, atMillis: Long,
            minute: Int = 0, full: Boolean = false): Rem {
        val r = Rem(newId(ctx), who, text, water, everyMin, fromHour.coerceIn(0, 23), toHour.coerceIn(0, 23), atMillis,
            minute.coerceIn(0, 59), full)
        saveAll(ctx, all(ctx) + r)
        schedule(ctx, r)
        return r
    }

    fun addWater(ctx: Context, who: String, everyMin: Int, fromHour: Int, toHour: Int): Rem {
        // one water reminder per person is enough: replace the old one
        all(ctx).filter { it.water && it.who.equals(who, true) }.forEach { remove(ctx, it.id) }
        return add(ctx, who, "", true, everyMin, fromHour, toHour, 0L)
    }

    /** Every day at h:m */
    fun addDaily(ctx: Context, who: String, text: String, h: Int, m: Int, full: Boolean = false): Rem =
        add(ctx, who, text, false, 1440, h, h, 0L, m, full)

    /** 🗓️ Auto daily routine: water, meals, study, sleep (replaces the old routine of this person) */
    fun addRoutine(ctx: Context, who: String) {
        all(ctx).filter { it.full && it.who.equals(who, true) }.forEach { remove(ctx, it.id) }
        addWater(ctx, who, 60, 8, 22)
        addDaily(ctx, who, "नाश्ते का टाइम हो गया 🍳 कुछ अच्छा सा खा लो, ख़ाली पेट मत रहना।", 9, 0, true)
        addDaily(ctx, who, "दोपहर के खाने का टाइम हो गया 🍽️ आराम से पेट भर के खाना खा लो।", 13, 30, true)
        addDaily(ctx, who, "पढ़ाई का टाइम हो गया 📚 फ़ोन थोड़ा साइड में रखो और फ़ोकस करके पढ़ाई करो, मैं तुम पर गर्व करूँगी!", 17, 0, true)
        addDaily(ctx, who, "रात के खाने का टाइम हो गया 🍲 टाइम से खाना खा लो।", 20, 30, true)
        addDaily(ctx, who, "सोने का टाइम हो गया 😴 फ़ोन रखो और आराम से सो जाओ, गुड नाइट! 🌙", 23, 0, true)
    }

    fun remove(ctx: Context, id: Int) {
        cancel(ctx, id)
        saveAll(ctx, all(ctx).filter { it.id != id })
    }

    fun clear(ctx: Context) {
        all(ctx).forEach { cancel(ctx, it.id) }
        saveAll(ctx, emptyList())
    }

    // ---------------- timing ----------------
    private fun hhmm(h: Int, m: Int = 0): String {
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m) }
        return SimpleDateFormat("h:mm a", Locale.ENGLISH).format(c.time)
    }

    fun describe(r: Rem): String {
        if (r.daily) return "Every day · ${hhmm(r.fromHour, r.minute)}"
        if (r.everyMin > 0) {
            val every = if (r.everyMin % 60 == 0) {
                val h = r.everyMin / 60
                if (h == 1) "Every hour" else "Every $h hours"
            } else "Every ${r.everyMin} min"
            return "$every · ${hhmm(r.fromHour)} – ${hhmm(r.toHour)}"
        }
        return SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH).format(Date(r.atMillis))
    }

    /** Hindi sentence for Chinu to say */
    fun describeHi(r: Rem): String {
        if (r.daily) return "रोज़ ${hiHour(r.fromHour)}" + (if (r.minute != 0) " ${r.minute} मिनट पर" else "")
        if (r.everyMin > 0) {
            val every = if (r.everyMin % 60 == 0) {
                val h = r.everyMin / 60
                if (h == 1) "हर घंटे" else "हर $h घंटे"
            } else "हर ${r.everyMin} मिनट"
            return "$every, ${hiHour(r.fromHour)} से ${hiHour(r.toHour)} तक"
        }
        val c = Calendar.getInstance().apply { timeInMillis = r.atMillis }
        val today = Calendar.getInstance()
        val day = when {
            c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) && c.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> "आज"
            else -> {
                today.add(Calendar.DAY_OF_YEAR, 1)
                if (c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) "कल"
                else SimpleDateFormat("d MMMM", Locale.ENGLISH).format(c.time) + " को"
            }
        }
        val m = c.get(Calendar.MINUTE)
        return "$day ${hiHour(c.get(Calendar.HOUR_OF_DAY))}" + (if (m != 0) " $m मिनट पर" else "")
    }

    private fun hiHour(h: Int): String {
        val part = when (h) {
            in 4..11 -> "सुबह"
            in 12..15 -> "दोपहर"
            in 16..19 -> "शाम"
            else -> "रात"
        }
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$part $h12 बजे"
    }

    /** Next time this reminder should ring (or null if it is over) */
    fun nextTime(r: Rem, now: Long = System.currentTimeMillis()): Long? {
        if (r.everyMin <= 0) return if (r.atMillis > now) r.atMillis else null
        val step = r.everyMin.coerceIn(5, 1440)
        for (dayOffset in 0..1) {
            val start = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, dayOffset)
                set(Calendar.HOUR_OF_DAY, r.fromHour); set(Calendar.MINUTE, r.minute)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val end = (start.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, r.toHour); set(Calendar.MINUTE, r.minute)
                if (r.toHour <= r.fromHour) add(Calendar.DAY_OF_YEAR, 1)   // e.g. 22 -> 2 (past midnight)
            }
            var t = start.timeInMillis
            while (t <= end.timeInMillis) {
                if (t > now + 20_000) return t
                t += step * 60_000L
            }
        }
        return null
    }

    // ---------------- alarms ----------------
    private fun pending(ctx: Context, id: Int, create: Boolean): PendingIntent? {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction("com.meraassistant.REMIND").putExtra("id", id)
        val flags = PendingIntent.FLAG_IMMUTABLE or (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(ctx, 5000 + id, i, flags)
    }

    fun schedule(ctx: Context, r: Rem) {
        val at = nextTime(r)
        if (at == null) {
            if (r.everyMin <= 0) saveAll(ctx, all(ctx).filter { it.id != r.id })
            return
        }
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, r.id, true) ?: return
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun cancel(ctx: Context, id: Int) {
        pending(ctx, id, false)?.let { ctx.getSystemService(AlarmManager::class.java).cancel(it) }
    }

    fun scheduleAll(ctx: Context) {
        all(ctx).forEach { schedule(ctx, it) }
    }

    // ---------------- what Chinu says ----------------
    private val waterLines = listOf(
        "%s, पानी पीने का टाइम हो गया! 💧 एक गिलास पानी पी लो और अपनी बॉडी हाइड्रेट रखो।",
        "%s, चलो एक गिलास पानी पी लो 💧 हाइड्रेटेड रहोगी तो स्किन भी चमकेगी ✨",
        "%s, पानी ब्रेक! 💧 थोड़ा पानी पी लो, मैं वेट कर रही हूँ 😊",
        "%s, याद है ना? पानी पीना है 💧 बॉडी को हाइड्रेट रखना बहुत ज़रूरी है।"
    )

    fun line(ctx: Context, r: Rem): String =
        if (r.water) {
            val l = waterLines.random().format(r.who)
            // the boy (Chiku) gets the masculine form
            if (r.who.equals(Profile.load(ctx).partner, true)) l.replace("रहोगी", "रहोगे") else l
        } else if (r.full) "${r.who}, ${r.text}"
        else "${r.who}, याद दिला रही हूँ ⏰ ${r.text}"

    fun createChannel(ctx: Context) {
        val ch = NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Water and other reminders from Chinu"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(true)
        }
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun notify(ctx: Context, r: Rem, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        createChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 6000 + r.id,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(if (r.water) "💧 Drink water, ${r.who}!" else "⏰ Reminder for ${r.who}")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(7000 + r.id, n)
    }
}

/** Rings a reminder: wakes the screen, shows the notification, speaks it, and sets the next one */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Reminders.scheduleAll(ctx)
            return
        }
        val r = Reminders.get(ctx, intent.getIntExtra("id", -1)) ?: return
        // set the next one first (repeating) / remove it (one time)
        if (r.everyMin > 0) Reminders.schedule(ctx, r) else Reminders.remove(ctx, r.id)

        val text = Reminders.line(ctx, r)
        // light up the screen so it shows on the lock screen too
        try {
            val pm = ctx.getSystemService(PowerManager::class.java)
            @Suppress("DEPRECATION")
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "chinu:reminder"
            ).acquire(10_000)
        } catch (e: Exception) {
        }
        Reminders.notify(ctx, r, text)

        // speak it — but not during a phone call
        val am = ctx.getSystemService(AudioManager::class.java)
        if (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION) return
        val result = goAsync()
        val main = Handler(Looper.getMainLooper())
        var tts: TextToSpeech? = null
        var finished = false
        fun finish() {
            if (finished) return
            finished = true
            try { tts?.shutdown() } catch (e: Exception) {}
            try { result.finish() } catch (e: Exception) {}
        }
        main.postDelayed({ finish() }, 9_000)
        tts = TextToSpeech(ctx, TextToSpeech.OnInitListener { status ->
            val t = tts
            if (status != TextToSpeech.SUCCESS || t == null) {
                finish()
                return@OnInitListener
            }
            LangUtil.applyTts(t, "hi-IN")
            try {
                t.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            } catch (e: Exception) {
            }
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { main.post { finish() } }
                @Deprecated("old api")
                override fun onError(id: String?) { main.post { finish() } }
            })
            t.speak(LangUtil.speakable(text), TextToSpeech.QUEUE_FLUSH, null, "rem")
        })
    }
}
