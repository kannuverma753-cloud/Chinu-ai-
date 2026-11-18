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
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** 💞 "Days together" + monthiversary / anniversary */
object Together {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("couple", Context.MODE_PRIVATE)

    /** start date as millis (0 = not set) */
    fun since(ctx: Context): Long = prefs(ctx).getLong("since_date", 0L).takeIf { it != 0L } ?: FIRST_MET

    /** Chiku & Komal met on 26 March 2025 💞 */
    private val FIRST_MET: Long = Calendar.getInstance().apply { clear(); set(2025, Calendar.MARCH, 26) }.timeInMillis

    fun setSince(ctx: Context, y: Int, m: Int, d: Int) {
        val c = Calendar.getInstance().apply { clear(); set(y, m, d) }
        prefs(ctx).edit().putLong("since_date", c.timeInMillis).apply()
    }

    fun days(ctx: Context): Long {
        val s = since(ctx)
        if (s == 0L) return -1
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return TimeUnit.MILLISECONDS.toDays(today.timeInMillis - s + 3_600_000L)
    }

    /** "1 year 2 months" style */
    fun pretty(ctx: Context): String {
        val s = since(ctx)
        if (s == 0L) return ""
        val a = Calendar.getInstance().apply { timeInMillis = s }
        val b = Calendar.getInstance()
        var months = (b.get(Calendar.YEAR) - a.get(Calendar.YEAR)) * 12 + (b.get(Calendar.MONTH) - a.get(Calendar.MONTH))
        if (b.get(Calendar.DAY_OF_MONTH) < a.get(Calendar.DAY_OF_MONTH)) months--
        val y = months / 12
        val m = months % 12
        return when {
            y > 0 && m > 0 -> "$y yr $m mo"
            y > 0 -> "$y yr"
            m > 0 -> "$m mo"
            else -> ""
        }
    }

    /** Today is a monthiversary (same day of month) / anniversary (same date) */
    fun special(ctx: Context): Int {
        val s = since(ctx)
        if (s == 0L) return 0
        val a = Calendar.getInstance().apply { timeInMillis = s }
        val b = Calendar.getInstance()
        if (days(ctx) <= 0) return 0
        if (a.get(Calendar.DAY_OF_MONTH) != b.get(Calendar.DAY_OF_MONTH)) return 0
        return if (a.get(Calendar.MONTH) == b.get(Calendar.MONTH)) 2 else 1
    }

    /** What Chinu says on such a day (or null) */
    fun wish(ctx: Context): String? {
        val p = Profile.load(ctx)
        val years = Calendar.getInstance().get(Calendar.YEAR) - Calendar.getInstance().apply { timeInMillis = since(ctx) }.get(Calendar.YEAR)
        return when (special(ctx)) {
            2 -> "हैप्पी एनिवर्सरी ${p.partner} और ${p.name}! 💞🎉 आज आपके साथ के पूरे $years साल हो गए। ऐसे ही हमेशा साथ रहना!"
            1 -> "हैप्पी मंथ-एनिवर्सरी! 💞 आज आप दोनों को साथ ${days(ctx)} दिन हो गए। ${p.partner} और ${p.name}, आप दोनों की जोड़ी सबसे प्यारी है!"
            else -> null
        }
    }
}

/** 💝 Love Jar: everything sent and received between the two phones */
object LoveJar {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("couple", Context.MODE_PRIVATE)

    class Entry(val kind: String, val text: String, val from: String, val mine: Boolean, val at: Long)

    fun add(ctx: Context, kind: String, text: String, from: String, mine: Boolean) {
        val a = try {
            JSONArray(prefs(ctx).getString("jar", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        a.put(JSONObject().put("k", kind).put("t", text).put("f", from).put("m", mine).put("at", System.currentTimeMillis()))
        val keep = JSONArray()
        for (i in maxOf(0, a.length() - 400) until a.length()) keep.put(a.get(i))
        prefs(ctx).edit().putString("jar", keep.toString()).apply()
    }

    fun all(ctx: Context): List<Entry> = try {
        val a = JSONArray(prefs(ctx).getString("jar", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Entry(o.optString("k"), o.optString("t"), o.optString("f"), o.optBoolean("m"), o.optLong("at"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** counts in the last 7 days: kind -> (received, sent) */
    fun week(ctx: Context): Map<String, Pair<Int, Int>> {
        val since = System.currentTimeMillis() - 7 * 86_400_000L
        return all(ctx).filter { it.at >= since }.groupBy { it.kind }
            .mapValues { (_, l) -> l.count { !it.mine } to l.count { it.mine } }
    }
}

/** 😊 Moods */
object Mood {
    val LIST = listOf(
        Triple("😊", "happy", "khush"), Triple("😍", "in love", "pyaar mein"), Triple("😔", "sad", "udaas"),
        Triple("😠", "angry", "gusse mein"), Triple("😴", "sleepy", "neend mein"), Triple("🤒", "unwell", "beemar")
    )

    /** (text on screen, what Chinu says) */
    fun line(from: String, i: Int, girl: Boolean): Pair<String, String> {
        val gyi = if (girl) "गई" else "गए"
        return when (i) {
            0 -> "$from is very happy today 😊" to "$from आज बहुत खुश हैं 😊"
            1 -> "$from is feeling the love 😍" to "$from आपके प्यार में खो $gyi हैं 😍"
            2 -> "$from is feeling sad 🥺 Give them a call" to "$from का मूड थोड़ा उदास है 🥺 उन्हें कॉल कर लो, अच्छा लगेगा"
            3 -> "$from is a bit angry 😠 Talk sweetly" to "$from थोड़ा गुस्से में हैं 😠 प्यार से बात करना, सब ठीक हो जाएगा"
            4 -> "$from is sleepy 😴 Say good night" to "$from को नींद आ रही है 😴 गुड नाइट बोल दो"
            else -> "$from is unwell 🤒 Check on them" to "$from की तबियत ठीक नहीं है 🤒 उनका हाल पूछ लो"
        }
    }
}

/** ☀️ Good-morning love note every day at 8:30 */
object DailyLove {
    private const val CHANNEL = "daily_love"

    private val FOR_HER = listOf(
        "Good morning %s ☀️ Aaj tum aur bhi pyaari lag rahi ho, mujhe pata hai 😊",
        "Good morning %s 🌸 Ek gilaas paani piyo, smile karo, aur aaj ka din rock karo!",
        "Good morning %s 💖 Kisi ne aaj subah sabse pehle tumhare baare mein socha hai… guess karo kaun? 😉",
        "Good morning %s ☀️ Tumhari muskaan kisi ka poora din bana deti hai 💕",
        "Good morning %s 🌼 Naya din, nayi khushiyaan. Aaj bas khush rehna!",
        "Good morning %s ✨ Tum strong ho, smart ho, aur bahut special ho 💪💖",
        "Good morning %s 🌷 Breakfast skip mat karna, warna Chinu naraz ho jayegi 😤😄"
    )
    private val FOR_HIM = listOf(
        "Good morning %s ☀️ Aaj %s ko ek pyaara sa good morning bhejo 💌",
        "Good morning %s 🌸 %s ko bata do ki wo aaj bhi tumhari favourite hai 💖",
        "Good morning %s ☀️ Ek chhota sa ‘miss you’ %s ka poora din bana dega 🥺💕",
        "Good morning %s 💌 %s ke liye aaj kuch sweet plan karo?"
    )

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(ctx, 8830, Intent(ctx, DailyLoveReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 30); set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.timeInMillis, pi)
        } catch (e: Exception) {
        }
    }

    fun fire(ctx: Context) {
        schedule(ctx)    // tomorrow
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        Speaker.load(ctx)
        val p = Profile.load(ctx)
        val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val boy = Speaker.owner == true
        var text = if (boy) FOR_HIM[day % FOR_HIM.size].format(p.partner, p.name)
        else FOR_HER[day % FOR_HER.size].format(p.name)
        Together.wish(ctx)?.let { text = it + "\n" + text }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Good morning", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 8831,
            Intent(ctx, if (boy) CoupleActivity::class.java else MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("☀️ Good morning, ${if (boy) p.partner else p.name}")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
        if (CoupleLink.paired(ctx)) {
            val send = PendingIntent.getActivity(ctx, 8832,
                Intent(ctx, CoupleActivity::class.java).putExtra("send", "love").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(Notification.Action.Builder(null, "💌 Send love", send).build())
        }
        nm.notify(8833, b.build())
    }
}

class DailyLoveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> DailyLove.schedule(context)
            else -> DailyLove.fire(context)
        }
    }
}
