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
import java.util.Calendar

/**
 * 🎂 Birthday surprise mode:
 * birthday से 2 दिन पहले, 1 दिन पहले, ठीक रात 12 बजे और सुबह 9 बजे notification।
 */
object Birthday {

    private const val CHANNEL = "birthday"

    private val wishesHinglish = listOf(
        "हैप्पी बर्थडे %1\$s! 🎂 भगवान करे आपकी हर ख़्वाहिश पूरी हो, और आपकी मुस्कान हमेशा ऐसे ही चमकती रहे 💚",
        "जन्मदिन मुबारक हो %1\$s! 🎉 आप %2\$s की दुनिया की सबसे ख़ास इंसान हो ✨",
        "हैप्पी बर्थडे %1\$s! 🥳 आज केक भी आपका, दिन भी आपका, और %2\$s का दिल भी आपका 💚",
        "मेनी मेनी हैप्पी रिटर्न्स ऑफ़ द डे, %1\$s! 🎂 ये साल आपके लिए ढेर सारी ख़ुशियाँ और प्यार लाए 🌸",
        "हैप्पी बर्थडे मेरी प्यारी %1\$s! 🎈 %2\$s ने मुझसे कहा है कि आज आपको दुनिया की सबसे ख़ुश लड़की बनाना है 😊💚"
    )
    private val wishesEn = listOf(
        "Happy Birthday %1\$s! 🎂 May all your wishes come true and your smile keep shining forever 💖",
        "Happy Birthday %1\$s! 🎉 You are the most special person in %2\$s's world ✨",
        "Happy Birthday %1\$s! 🥳 Today the cake is yours, the day is yours, and %2\$s's heart is yours too 💖",
        "Many happy returns of the day, %1\$s! 🎂 May this year bring you endless happiness 🌸"
    )

    fun randomWish(p: Profile.Data, hinglish: Boolean): String =
        String.format((if (hinglish) wishesHinglish else wishesEn).random(), p.name, p.partner)

    fun createChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL, "Birthday 🎂", NotificationManager.IMPORTANCE_HIGH)
        ch.description = "Birthday reminders and wishes"
        nm.createNotificationChannel(ch)
    }

    /** सारे birthday notifications सेट करता है (जो समय निकल चुका, उन्हें छोड़कर) */
    fun schedule(ctx: Context) {
        createChannel(ctx)
        val p = Profile.load(ctx)
        val bday = Profile.nextDate(p.birthday) ?: return
        val am = ctx.getSystemService(AlarmManager::class.java)
        // (id, कितने दिन पहले, कितने बजे)
        val plan = listOf(Triple(1, -2, 9), Triple(2, -1, 9), Triple(3, 0, 0), Triple(4, 0, 9))
        for ((id, offset, hour) in plan) {
            val t = (bday.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, hour)
            }
            val pi = PendingIntent.getBroadcast(
                ctx, id,
                Intent(ctx, BirthdayReceiver::class.java).putExtra("kind", id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            if (t.timeInMillis <= System.currentTimeMillis()) {
                am.cancel(pi)
                continue
            }
            try {
                val exactOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
                if (exactOk) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.timeInMillis, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.timeInMillis, pi)
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.timeInMillis, pi)
            }
        }
    }

    /** Phone restart के बाद "Hello Chinu" फिर चालू करने की याद */
    fun remindWake(ctx: Context) {
        val on = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("wake", true)
        if (!on) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        createChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 20, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("🎙️ Wake up Chinu")
            .setContentText("Your phone restarted. Tap once so \"Hey Chinu\" works again 💖")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(99, n)
    }

    fun notify(ctx: Context, kind: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        createChannel(ctx)
        val p = Profile.load(ctx)
        val n = p.name
        Speaker.load(ctx)
        val (title, text) = if (Speaker.owner == true) when (kind) {
            1 -> "🎂 $n's birthday in 2 days!" to "Time to plan something special for her, ${p.partner} 💖"
            2 -> "🎂 Tomorrow is $n's birthday!" to "Be ready to wish her at midnight 🌙"
            3 -> "🎉 It's $n's birthday! 🎂" to "Midnight! Be the first to wish her, ${p.partner} 💖"
            else -> "🎂 Today is $n's birthday!" to "Call her and make her day special 💖"
        } else when (kind) {
            1 -> "🎂 Birthday soon, $n!" to "Just 2 days to go! ${p.partner} and Chinu are super excited 💖"
            2 -> "🎂 Tomorrow is your birthday, $n!" to "One more night... tomorrow is all yours ✨"
            3 -> "🎉 Happy Birthday $n! 🎂" to "It's midnight! Lots of love from ${p.partner} 💖 Open Chinu, a surprise is waiting!"
            else -> "🎂 Happy Birthday $n! 💖" to randomWish(p, false)
        }
        val surprise = kind >= 3 && Bday.isToday() && Speaker.owner != true
        val open = PendingIntent.getActivity(
            ctx, 10 + kind,
            Intent(ctx, if (surprise) BdayActivity::class.java else MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (kind == 3 && Bday.isToday() && Speaker.owner == true)
            Voice.say(ctx, "बारह बज गए! आज कोमल का बर्थडे है। सबसे पहले विश कर दो!")
        val notification = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .apply { if (surprise && kind == 3) setFullScreenIntent(open, true).setCategory(Notification.CATEGORY_ALARM) }
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(100 + kind, notification)
        if (surprise && kind == 3) try {
            ctx.startActivity(Intent(ctx, BdayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
        }
    }
}

/** Alarm आने पर notification दिखाता है; phone restart होने पर फिर से schedule करता है */
class BirthdayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Birthday.schedule(context)
                Birthday.remindWake(context)
            }
            else -> Birthday.notify(context, intent.getIntExtra("kind", 4))
        }
    }
}
