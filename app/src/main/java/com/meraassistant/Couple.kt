package com.meraassistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 💞 Couple link: Chiku's Chinu ↔ Komal's Chinu.
 * Both phones share a secret pair code. Messages go through the free ntfy.sh relay,
 * END-TO-END ENCRYPTED with a key made from that code — nobody else can read them.
 */
object CoupleLink {
    private const val SERVER = "https://ntfy.sh"
    private const val CHANNEL = "couple"
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false
    private var worker: Thread? = null
    private val seen = LinkedHashSet<String>()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("couple", Context.MODE_PRIVATE)

    fun code(ctx: Context): String = prefs(ctx).getString("code", "").orEmpty()
    fun paired(ctx: Context) = code(ctx).isNotEmpty()

    fun deviceId(ctx: Context): String {
        val p = prefs(ctx)
        var id = p.getString("dev", "").orEmpty()
        if (id.isEmpty()) {
            id = java.util.UUID.randomUUID().toString()
            p.edit().putString("dev", id).apply()
        }
        return id
    }

    fun newCode(): String {
        val abc = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        return (0 until 3).joinToString("-") { (0 until 4).map { abc[r.nextInt(abc.length)] }.joinToString("") }
    }

    fun setCode(ctx: Context, code: String) {
        val c = code.uppercase().filter { it.isLetterOrDigit() }.chunked(4).joinToString("-")
        prefs(ctx).edit().putString("code", c).remove("since").apply()
        restart(ctx)
    }

    fun unpair(ctx: Context) {
        prefs(ctx).edit().remove("code").remove("since").apply()
        stop()
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())

    private fun topic(code: String) = "chinu-" + sha("topic:$code").take(14).joinToString("") { "%02x".format(it) }

    private fun key(code: String) = SecretKeySpec(sha("key:$code"), "AES")

    private fun encrypt(code: String, plain: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(code), GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(code: String, b64: String): String? = try {
        val all = Base64.decode(b64, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(code), GCMParameterSpec(128, all.copyOfRange(0, 12)))
        String(c.doFinal(all.copyOfRange(12, all.size)), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    /** Send an event to the partner's phone. done(true) when delivered to the relay. */
    fun send(ctx: Context, type: String, data: JSONObject = JSONObject(), done: ((Boolean) -> Unit)? = null) {
        val app = ctx.applicationContext
        val code = code(app)
        if (code.isEmpty()) {
            done?.invoke(false)
            return
        }
        Speaker.load(app)
        val p = Profile.load(app)
        when (type) {
            "love" -> LoveJar.add(app, data.optString("k", "love"), "", Speaker.name(p), true)
            "note" -> LoveJar.add(app, "note", data.optString("text"), Speaker.name(p), true)
        }
        val msg = JSONObject().put("t", type).put("d", data).put("dev", deviceId(app))
            .put("from", Speaker.name(p)).put("ts", System.currentTimeMillis())
        Thread {
            var ok = false
            try {
                val c = URL("$SERVER/${topic(code)}").openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 15000
                c.requestMethod = "POST"
                c.doOutput = true
                val body = encrypt(code, msg.toString()).toByteArray()
                // ntfy messages hold max 4 KB — bigger ones (photos, long letters) go as an (encrypted) attachment
                if (body.size > 3800) {
                    c.setRequestProperty("Filename", "chinu.txt")
                    c.readTimeout = if (body.size > 1_000_000) 240000 else 60000
                    c.setFixedLengthStreamingMode(body.size)
                }
                c.outputStream.use { it.write(body) }
                ok = c.responseCode in 200..299
                c.disconnect()
            } catch (e: Exception) {
            }
            main.post { done?.invoke(ok) }
        }.start()
    }

    // ---------------- receiving ----------------
    /** Keeps a live connection open (called from the always-on WakeService and from the app) */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (!paired(app) || running) return
        running = true
        worker = Thread({ loop(app) }, "chinu-couple").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    private fun restart(ctx: Context) {
        stop()
        main.postDelayed({ start(ctx) }, 500)
    }

    private fun loop(app: Context) {
        while (running) {
            val code = code(app)
            if (code.isEmpty()) break
            try {
                val since = prefs(app).getString("since", "").orEmpty().ifEmpty { "2h" }
                val c = URL("$SERVER/${topic(code)}/json?since=$since").openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 90_000          // ntfy sends keepalives every ~45s
                c.inputStream.bufferedReader().use { r ->
                    while (running) {
                        val line = r.readLine() ?: break
                        handleLine(app, code, line)
                    }
                }
                c.disconnect()
            } catch (e: Exception) {
            }
            if (running) try {
                Thread.sleep(5000)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    /** One-off check for missed messages (when the app opens) */
    fun poll(ctx: Context) {
        val app = ctx.applicationContext
        val code = code(app)
        if (code.isEmpty() || running) return
        Thread {
            try {
                val since = prefs(app).getString("since", "").orEmpty().ifEmpty { "2h" }
                val c = URL("$SERVER/${topic(code)}/json?poll=1&since=$since").openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 15000
                c.inputStream.bufferedReader().useLines { lines -> lines.forEach { handleLine(app, code, it) } }
                c.disconnect()
            } catch (e: Exception) {
            }
        }.start()
    }

    private fun handleLine(app: Context, code: String, line: String) {
        val j = try {
            JSONObject(line)
        } catch (e: Exception) {
            return
        }
        if (j.optString("event") != "message") return
        val id = j.optString("id")
        synchronized(seen) {
            if (!seen.add(id)) return
            while (seen.size > 200) seen.remove(seen.first())
        }
        prefs(app).edit().putString("since", id).apply()
        val att = j.optJSONObject("attachment")
        val cipherText = if (att != null && att.optString("url").isNotEmpty()) download(att.optString("url"), att.optLong("size"))
        else j.optString("message")
        val plain = decrypt(code, cipherText ?: return) ?: return
        val m = try {
            JSONObject(plain)
        } catch (e: Exception) {
            return
        }
        if (m.optString("dev") == deviceId(app)) return                 // my own message
        if (System.currentTimeMillis() - m.optLong("ts") > 6 * 3600_000L) return  // too old
        main.post { onEvent(app, m) }
    }

    /** Big messages arrive as an attachment link → fetch the (still encrypted) text */
    private fun download(url: String, size: Long): String? {
        if (size > 15_500_000L || !url.startsWith("https://")) return null
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.readTimeout = 180000
            val t = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            t.trim()
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- what happens when something arrives ----------------
    val KINDS = mapOf(
        "hug" to Triple("🤗", "ne aapko ek pyaari si jhappi bheji hai 🤗", "sent you a warm hug 🤗"),
        "kiss" to Triple("😘", "ne aapko ek meetha sa kiss bheja hai 😘", "sent you a sweet kiss 😘"),
        "miss" to Triple("🥺", "aapko bahut miss kar rahe hain, baat kar lo na 🥺📞", "is missing you a lot — give them a call 🥺📞"),
        "love" to Triple("❤️", "kehte hain: I love you ❤️", "says: I love you ❤️"),
        "think" to Triple("💭", "abhi aapke baare mein soch rahe hain 💭", "is thinking about you 💭")
    )

    private fun onEvent(app: Context, m: JSONObject) {
        val from = m.optString("from").ifBlank { Speaker.other(Profile.load(app)) }
        val d = m.optJSONObject("d") ?: JSONObject()
        when (m.optString("t")) {
            "love" -> {
                val kind = d.optString("k", "love")
                val (emoji, hi, en) = KINDS[kind] ?: KINDS.getValue("love")
                // Komal → feminine forms ("kar rahi hain"), Chiku → masculine
                val girl = from.equals(Profile.load(app).name, true)
                val textHi = "$from " + (if (girl) hi.replace("rahe hain", "rahi hain") else hi)
                LoveJar.add(app, kind, "", from, false)
                if (kind == "miss") missCall(app, from, textHi)
                else notify(app, "$emoji  $from", "$from $en", 300 + kind.hashCode() % 50, Intent(app, CoupleActivity::class.java))
                CoupleActivity.current?.celebrate(emoji, textHi) ?: run {
                    AssistantService.instance?.showLove(emoji, textHi)
                    Voice.say(app, Hindi.devanagariOf(textHi))
                }
                buzz(app, longArrayOf(0, 120, 80, 120))
            }
            "note" -> {
                val text = d.optString("text")
                LoveJar.add(app, "note", text, from, false)
                Plus.noteNotify(app, from, text)
                CoupleActivity.current?.celebrate("💌", "$from: $text") ?: run {
                    AssistantService.instance?.showLove("💌", "$from: $text")
                    Voice.say(app, "$from ka message: $text")
                }
                buzz(app, longArrayOf(0, 80, 60, 80))
            }
            "touch" -> {
                val on = d.optBoolean("on")
                val ca = CoupleActivity.current
                if (ca != null) ca.partnerTouch(on) else if (on) More.heartCall(app, from)
            }
            "tap" -> {
                val ca = CoupleActivity.current
                if (ca != null) ca.partnerTap() else More.heartCall(app, from)
            }
            "note_put" -> {
                val fresh = !d.optBoolean("del") && d.optLong("upd") - d.optLong("at") < 60_000
                if (Diary.merge(app, d)) {
                    DiaryActivity.current?.render()
                    if (fresh && DiaryActivity.current == null) {
                        notify(app, "📝  ${d.optString("author")} wrote in your diary", d.optString("text").take(140), 350,
                            Intent(app, DiaryActivity::class.java))
                    }
                }
            }
            "note_hello" -> Diary.onHello(app, d)
            // 💌 Open-when letters · ❓ daily question · 🌙 bedtime story
            "letter_put" -> Letters.onPut(app, d, from)
            "letter_hello" -> Letters.onHello(app, d)
            "q_ans" -> DailyQ.onAnswer(app, d, from)
            "q_hello" -> DailyQ.onHello(app, d)
            "q_nudge" -> DailyQ.onNudge(app, from)
            "story" -> Bedtime.onStory(app, d, from)
            // 📸 selfie streak
            "selfie_done" -> SelfieStreak.onDone(app, d, from)
            "selfie" -> SelfieStreak.onSelfie(app, d, from)
            "selfie_hello" -> SelfieStreak.onHello(app, d)
            "selfie_nudge" -> SelfieStreak.onNudge(app, from)
            "moment" -> SelfieStreak.onMoment(app, d, from)
            "voice" -> VoiceNote.onVoice(app, d, from)
            "cmsg" -> Plus.onChinuMsg(app, d, from)
            "ping" -> Care.onPing(app, d, from)
            "status_ask" -> Care.onStatusAsk(app)
            "status" -> Care.onStatus(app, d, from)
            "loc" -> Care.onLoc(app, d, from)
            "sos" -> Care.onSos(app, d, from)
            "meet" -> Care.onMeet(app, d, from)
            "list_put" -> Care.onListPut(app, d, from)
            "list_all" -> Care.onListAll(app, d)
            "rem_put" -> Care.onRemPut(app, d, from)
            "photo_ask" -> Plus.onPhotoAsk(app, from)
            "song" -> Plus.onSong(app, d, from)
            "exp_put" -> Plus.onExpPut(app, d, from)
            "exp_all" -> Plus.onExpAll(app, d)
            "secret" -> Fun.onSecret(app, d, from)
            "secret_read" -> Fun.onSecretRead(app, from)
            "poll" -> Fun.onPoll(app, d, from)
            "vote" -> Fun.onVote(app, d, from)
            "sync" -> Fun.onSync(app, d, from)
            "wx_ask" -> Fun.onWeatherAsk(app)
            "wx" -> Fun.onWeather(app, d, from)
            "reason" -> Fun.onReason(app, d, from)
            "bucket_put" -> Fun.onBucket(app, d, from)
            "wish" -> More.onWish(app, d, from)
            "water" -> More.onWater(app, d, from)
            "chal_done" -> More.onChallengeDone(app, d, from)
            "loc_ask" -> More.onLocAsk(app, from)
            "sorry" -> More.onSorry(app, d, from)
            "sorry_reply" -> More.onSorryReply(app, d, from)
            "capsule" -> More.onCapsule(app, d, from)
            "capsule_open" -> More.onCapsuleOpen(app, from)
            "guess_q" -> Xtra.onGuessQ(app, d, from)
            "guess_a" -> Xtra.onGuessA(app, d, from)
            "guess_r" -> Xtra.onGuessR(app, d, from)
            "ourstory" -> Xtra.onStory(app, d, from)
            "pet" -> Xtra.onPet(app, d)
            "oursong" -> Xtra.onOurSong(app, d, from)
            "toss" -> Xtra.onToss(app, d, from)
            "moon" -> Special.onMoon(app, from)
            "tl_put" -> Special.onTimeline(app, d, from)
            "calldate" -> Special.onCallDate(app, d, from)
            "khel" -> Khel.on(app, d, from)
            "ow_put" -> Pyaar.onOwPut(app, d, from)
            "bday" -> Bday.on(app, d, from)
            "ow_open" -> Pyaar.onOwOpen(app, d, from)
            "cp_put" -> Pyaar.onCpPut(app, d, from)
            "cp_use" -> Pyaar.onCpUse(app, d, from)
            "dream" -> Pyaar.onDream(app, d, from)
            "sleep" -> Pyaar.onSleep(app, from)
            "mood" -> {
                val i = d.optInt("i").coerceIn(0, Mood.LIST.size - 1)
                val girl = from.equals(Profile.load(app).name, true)
                val (show, speak) = Mood.line(from, i, girl)
                prefs(app).edit().putInt("pMood", i).putLong("pMoodAt", System.currentTimeMillis()).apply()
                notify(app, "${Mood.LIST[i].first}  $from's mood", show, 340, Intent(app, CoupleActivity::class.java))
                CoupleActivity.current?.let { it.celebrate(Mood.LIST[i].first, show, speak); it.refresh() } ?: run {
                    AssistantService.instance?.showLove(Mood.LIST[i].first, show)
                    Voice.say(app, speak)
                }
            }
            "wheel" -> {
                val i = d.optInt("i").coerceIn(0, LoveWheel.SEGS.size - 1)
                val r = d.optInt("r")
                val w = WheelActivity.current
                if (w != null) w.spin(i, r, from, false)
                else {
                    val seg = LoveWheel.SEGS[i]
                    notify(app, "🎡  $from spun the Love Wheel: ${seg.first} ${seg.second}", "Tap to see 💫", 380,
                        WheelActivity.intentFor(app, i, r, from))
                }
            }
            "ttt" -> when (d.optString("a")) {
                "invite" -> {
                    val i = Intent(app, GameActivity::class.java).putExtra("mode", "online")
                        .putExtra("game", d.optString("g")).putExtra("sym", 2)
                    notify(app, "❌⭕  $from wants to play Zero-Kata!", "Tap to play now 🎮", 370, i)
                    Voice.say(app, "$from aapke saath zero kaata khelna chahte hain! notification pe tap karo.")
                }
                "move" -> GameActivity.current?.remoteMove(d.optString("g"), d.optInt("i", -1))
            }
        }
    }

    /** 🥺 "Miss you" → notification with a "📞 Call now" button */
    private fun missCall(ctx: Context, from: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val p = Profile.load(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Chiku ❤ Komal", NotificationManager.IMPORTANCE_HIGH).apply {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        val open = PendingIntent.getActivity(ctx, 390, Intent(ctx, CoupleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // call the partner: saved number, else open contacts search by name
        val callIntent = if (p.partnerPhone.isNotBlank() && from.equals(p.partner, true))
            Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + p.partnerPhone))
        else Intent(Intent.ACTION_VIEW, android.provider.ContactsContract.Contacts.CONTENT_URI)
        val call = PendingIntent.getActivity(ctx, 391, callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val miss = PendingIntent.getActivity(ctx, 392, Intent(ctx, CoupleActivity::class.java).putExtra("send", "miss")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(393, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("🥺  $from is missing you")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "📞 Call now", call).build())
            .addAction(Notification.Action.Builder(null, "🥺 Miss you too", miss).build())
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build())
    }

    fun buzz(ctx: Context, pattern: LongArray) {
        try {
            ctx.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (e: Exception) {
        }
    }

    fun notify(ctx: Context, title: String, text: String, id: Int, open: Intent) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Chiku ❤ Komal", NotificationManager.IMPORTANCE_HIGH).apply {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        val pi = PendingIntent.getActivity(ctx, id, open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(id, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build())
    }
}

/** Hindi lines written in English letters → speak naturally (simple word map for the couple lines) */
object Hindi {
    private val map = listOf(
        "ne aapko ek pyaari si jhappi bheji hai" to "ने आपको एक प्यारी सी झप्पी भेजी है",
        "ne aapko ek meetha sa kiss bheja hai" to "ने आपको एक मीठा सा किस भेजा है",
        "aapko bahut miss kar rahe hain, baat kar lo na" to "आपको बहुत मिस कर रहे हैं, बात कर लो ना",
        "aapko bahut miss kar rahi hain, baat kar lo na" to "आपको बहुत मिस कर रही हैं, बात कर लो ना",
        "abhi aapke baare mein soch rahi hain" to "अभी आपके बारे में सोच रही हैं",
        "abhi aapke baare mein soch rahe hain" to "अभी आपके बारे में सोच रहे हैं",
        "kehte hain" to "कहते हैं"
    )

    fun devanagariOf(s: String): String {
        var t = s
        for ((a, b) in map) t = t.replace(a, b)
        return t
    }
}

/** One-shot voice (works even when the app is closed) */
object Voice {
    fun say(ctx: Context, text: String) {
        val app = ctx.applicationContext
        var tts: TextToSpeech? = null
        tts = TextToSpeech(app, TextToSpeech.OnInitListener { st ->
            val t = tts ?: return@OnInitListener
            if (st != TextToSpeech.SUCCESS) {
                t.shutdown()
                return@OnInitListener
            }
            LangUtil.applyTts(t, "hi-IN")
            t.speak(LangUtil.speakable(text), TextToSpeech.QUEUE_FLUSH, null, "couple")
            Handler(Looper.getMainLooper()).postDelayed({ t.shutdown() }, 12_000)
        })
    }
}

/** Floating hearts / kisses rising up */
class LoveFx(ctx: Context) : View(ctx) {
    private class P(var x: Float, var y: Float, val vy: Float, val sway: Float, val size: Float, val e: String, val born: Long)

    private val ps = ArrayList<P>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val d = ctx.resources.displayMetrics.density

    init {
        isClickable = false
    }

    fun burst(emoji: String, n: Int = 26) {
        val now = SystemClock.uptimeMillis()
        // kiss → only kisses 😘💋 · hug → only hugs 🤗🫂 · others → hearts
        val set = when (emoji) {
            "😘", "💋" -> listOf("😘", "💋", "😘", "💋", "😙")
            "🤗", "🫂" -> listOf("🤗", "🫂", "🤗")
            else -> listOf(emoji, emoji, "💖", "✨", "💕")
        }
        for (i in 0 until n) {
            ps.add(P((Math.random() * width).toFloat(), height + (Math.random() * 200 * d).toFloat(),
                (2.5f + Math.random().toFloat() * 4f) * d, (Math.random().toFloat() - 0.5f) * 2f,
                (22 + Math.random() * 26).toFloat() * d, set.random(), now))
        }
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val it = ps.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.y -= p.vy
            p.x += kotlin.math.sin((now - p.born) / 300.0).toFloat() * p.sway
            if (p.y < -60 * d) {
                it.remove()
                continue
            }
            paint.textSize = p.size
            paint.alpha = (255 * (p.y / height).coerceIn(0.15f, 1f)).toInt()
            c.drawText(p.e, p.x, p.y, paint)
        }
        if (ps.isNotEmpty()) postInvalidateOnAnimation()
    }
}

/** 💞 The couple screen */
class CoupleActivity : Activity() {
    private lateinit var fx: LoveFx
    private lateinit var body: LinearLayout
    private lateinit var heart: TextView
    private var partnerTouching = false
    private var meTouching = false
    private val h = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Speaker.load(this)
        val root = FrameLayout(this)
        root.addView(GlassBackground(this, GlassBackground.LOVE))
        val scroll = ScrollView(this).apply { isFillViewport = true }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@CoupleActivity, 18f)
            setPadding(p, Ui.dp(this@CoupleActivity, 44f), p, p)
        }
        scroll.addView(body)
        root.addView(scroll)
        fx = LoveFx(this)
        root.addView(fx, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        ScrollFx.attach(scroll, body)
        render()
        intent.getStringExtra("send")?.let { k -> if (CoupleLink.paired(this)) root.post { sendLove(k) } }
        if (intent.getBooleanExtra("voice", false) && CoupleLink.paired(this)) root.post { recordVoice() }
        intent.getStringExtra("care")?.let { c -> if (CoupleLink.paired(this)) root.post { Care.action(this, c) } }
        intent.getStringExtra("playVoice")?.let { p -> java.io.File(p).takeIf { it.exists() }?.let { f -> root.post { VoiceNote.play(this, f) } } }
    }

    override fun onResume() {
        super.onResume()
        current = this
        CoupleLink.start(this)
        VoiceNote.onPlayChange = { runOnUiThread { refresh() } }
        Care.syncList(this)
        Plus.syncExp(this)
    }

    override fun onPause() {
        if (rec != null) {
            recDialog?.dismiss()
            stopRecording(false)
        }
        VoiceNote.onPlayChange = null
        VoiceNote.stop()
        if (meTouching) CoupleLink.send(this, "touch", JSONObject().put("on", false))
        meTouching = false
        if (current === this) current = null
        super.onPause()
    }

    private fun dp(v: Float) = Ui.dp(this, v)

    private fun render() {
        body.removeAllViews()
        val p = Profile.load(this)
        body.addView(Ui.label(this, "💞  ${p.partner} & ${p.name}", 28f, 0xFF3B1F4A.toInt(), true))
        val paired = CoupleLink.paired(this)
        body.addView(Ui.label(this,
            if (paired) Ui.t(this, "Connected · end-to-end encrypted 🔐", "Connected · poori tarah private 🔐")
            else Ui.t(this, "Connect both phones once with a secret code", "Ek baar secret code se dono phones jodo"),
            14f, 0xFF6B4E7A.toInt()).apply { setPadding(0, dp(4f), 0, dp(18f)) })
        if (!paired) {
            renderPairing()
            // 📸 streak · 💌 khat · ❓ sawaal · 🌙 kahani — visible even before the phones are connected
            extrasCard()
            return
        }
        val other = Speaker.other(p)
        togetherCard()

        // 🥺 the big "Miss you" button
        body.addView(Ui.primaryButton(this, Ui.t(this, "🥺  Miss you, $other", "🥺  $other, miss kar raha/rahi hoon")) { sendLove("miss") }.apply {
            textSize = 19f
            setPadding(0, dp(20f), 0, dp(20f))
            if (Speaker.owner == true) text = Ui.t(this@CoupleActivity, "🥺  Miss you, $other", "🥺  $other, miss kar raha hoon")
            else if (Speaker.owner == false) text = Ui.t(this@CoupleActivity, "🥺  Miss you, $other", "🥺  $other, miss kar rahi hoon")
        })

        // 2 x 2 love buttons
        val kinds = listOf("hug" to Ui.t(this, "🤗\nHug", "🤗\nJhappi"), "kiss" to "😘\nKiss",
            "miss" to "🥺\nMiss you", "love" to "❤️\nLove you")
        for (r in 0 until 2) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 2) {
                val (k, label) = kinds[r * 2 + c]
                val b = Ui.glassButton(this, label) { sendLove(k) }.apply { textSize = 18f; setPadding(0, dp(20f), 0, dp(20f)) }
                row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (c == 1) leftMargin = dp(12f)
                })
            }
            body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12f)
            })
        }

        // ✨ just-for-us extras right after the love buttons, so nobody has to scroll for them
        extrasCard()
        // 🛟 status · location · SOS · battery · countdown · list · reminders
        // 🎂 Komal's birthday: countdown → surprise (only until 19 Nov)
        Bday.card(this, body)
        Care.card(this, body)
        // ✨ photo request · song · expenses · auto wishes · app update
        Plus.card(this, body)
        // 🎉 secret · poll · date idea · 3-2-1 · weather · reasons · bucket list · compliments
        Fun.card(this, body)
        // 💝 wishlist · water · challenge · distance · sorry · time capsule
        More.card(this, body)
        // 🎈 guess games · story · pet · stats · love meter · horoscope · memories · song · shayari · toss
        Xtra.card(this, body)
        // 🌟 AI poem · collage · moon · cupid · timeline · call date
        Special.card(this, body)
        // 🎮 games for the three of us (Chiku, Komal & Chinu)
        Khel.card(this, body)
        // 💗 open-when letters · love coupons · dream meanings · sleep together
        Pyaar.card(this, body)

        // Touch together
        body.addView(Ui.label(this, Ui.t(this, "TOUCH TOGETHER", "SAATH MEIN TOUCH KARO"), 12.5f, 0xFF8A4F9E.toInt(), true).apply {
            letterSpacing = 0.1f
            setPadding(dp(4f), dp(26f), 0, dp(8f))
        })
        heart = TextView(this).apply {
            text = "🤍"
            textSize = 64f
            gravity = Gravity.CENTER
            background = Ui.glass(this@CoupleActivity, 32f, 0x55)
            setPadding(0, dp(18f), 0, dp(6f))
        }
        val hint = Ui.label(this, Ui.t(this, "Tap or hold the heart. When $other does it too, both phones beat together 💓",
            "Dil ko tap karo ya dabaye rakho. Jab $other bhi karenge, dono phones saath dhadkenge 💓"), 13f, 0xFF6B4E7A.toInt()).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8f), dp(6f), dp(8f), dp(14f))
        }
        val touchBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glass(this@CoupleActivity, 32f, 0x44)
            addView(heart)
            addView(hint)
        }
        heart.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = SystemClock.uptimeMillis()
                    meTouching = true
                    CoupleLink.send(this, "touch", JSONObject().put("on", true))
                    updateTouch()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (e.action == MotionEvent.ACTION_UP && SystemClock.uptimeMillis() - downAt < 450) myTap()
                    meTouching = false
                    CoupleLink.send(this, "touch", JSONObject().put("on", false))
                    updateTouch()
                    v.performClick()
                }
            }
            true
        }
        body.addView(touchBox)

        // Note + game
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        body.addView(Ui.glassButton(this, Ui.t(this, "📝  Our Diary", "📝  Hamari Diary")) {
            startActivity(Intent(this, DiaryActivity::class.java))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18f) })
        row2.addView(Ui.glassButton(this, Ui.t(this, "💌  Love note", "💌  Love note")) { writeNote() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(Ui.glassButton(this, Ui.t(this, "❌⭕  Play together", "❌⭕  Saath khelo")) {
            startActivity(Intent(this, GameActivity::class.java).putExtra("mode", "online"))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12f) })
        body.addView(row2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(18f)
        })
        voiceCard(other)
        moodCard()
        jarCard()
        body.addView(Ui.label(this, Ui.t(this, "Tip: say “Hey Chinu, $other ko kiss bhejo”", "Tip: bolo “Hey Chinu, $other ko kiss bhejo”"),
            13f, 0xFF6B4E7A.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, dp(18f), 0, dp(6f)) })
        body.addView(TextView(this).apply {
            text = Ui.t(this@CoupleActivity, "Disconnect", "Connection hatao")
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFFB0476E.toInt())
            setPadding(0, dp(10f), 0, dp(20f))
            setOnClickListener {
                AlertDialog.Builder(this@CoupleActivity).setTitle("Disconnect?")
                    .setPositiveButton("Disconnect") { _, _ -> CoupleLink.unpair(this@CoupleActivity); render() }
                    .setNegativeButton("Cancel", null).show()
            }
        })
    }

    private fun renderPairing() {
        val p = Profile.load(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glass(this@CoupleActivity, 28f, 0x66)
            setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
        }
        card.addView(Ui.label(this, Ui.t(this, "1. On ${p.partner}'s phone tap “Create code”.\n2. On ${p.name}'s phone tap “Enter code” and type it.",
            "1. ${p.partner} ke phone mein “Create code” dabao.\n2. ${p.name} ke phone mein “Enter code” dabake wahi code likho."),
            15f, 0xFF3B1F4A.toInt()).apply { setLineSpacing(dp(4f).toFloat(), 1f) })
        card.addView(Ui.primaryButton(this, Ui.t(this, "✨  Create code", "✨  Code banao")) {
            val c = CoupleLink.newCode()
            CoupleLink.setCode(this, c)
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("code", c))
            AlertDialog.Builder(this).setTitle(Ui.t(this, "Your secret code", "Aapka secret code"))
                .setMessage("\n$c\n\n" + Ui.t(this, "Copied. Type this on the other phone (only share it with each other).",
                    "Copy ho gaya. Ise dusre phone mein daalo (sirf aapas mein share karna)."))
                .setPositiveButton("OK") { _, _ -> render() }.show()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18f) })
        card.addView(Ui.glassButton(this, Ui.t(this, "⌨️  Enter code", "⌨️  Code daalo")) {
            val input = EditText(this).apply {
                hint = "ABCD-EFGH-JKLM"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            }
            AlertDialog.Builder(this).setTitle(Ui.t(this, "Enter the code", "Code daalo")).setView(input)
                .setPositiveButton("Connect") { _, _ ->
                    val c = input.text.toString().filter { it.isLetterOrDigit() }
                    if (c.length != 12) {
                        Toast.makeText(this, Ui.t(this, "The code has 12 letters", "Code mein 12 akshar hote hain"), Toast.LENGTH_LONG).show()
                    } else {
                        CoupleLink.setCode(this, c)
                        CoupleLink.send(this, "love", JSONObject().put("k", "love"))
                        render()
                        fx.burst("💞")
                    }
                }
                .setNegativeButton("Cancel", null).show()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })
        body.addView(card)
    }

    private fun sendLove(kind: String) {
        val emoji = CoupleLink.KINDS[kind]?.first ?: "❤️"
        fx.burst(emoji, 14)
        CoupleLink.send(this, "love", JSONObject().put("k", kind)) { ok ->
            Toast.makeText(this, if (ok) Ui.t(this, "Sent $emoji", "Bhej diya $emoji") else Ui.t(this, "No internet 😕", "Internet nahi hai 😕"),
                Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- 🎤 voice messages ----------
    private var rec: android.media.MediaRecorder? = null
    private var recFile: java.io.File? = null
    private var recStart = 0L
    private var recDialog: AlertDialog? = null
    private val reqMic = 9120

    private fun voiceCard(other: String) {
        sectionTitle(Ui.t(this, "🎤 VOICE MESSAGES", "🎤 VOICE MESSAGES"))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glass(this@CoupleActivity, 24f, 0x66)
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        }
        card.addView(Ui.primaryButton(this, Ui.t(this, "🎙️  Record for $other", "🎙️  $other ke liye bolo")) { recordVoice() })
        card.addView(Ui.glassButton(this, Ui.t(this, "💬  Type a message", "💬  Message likho")) { writeNote() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        val list = VoiceNote.all(this).take(8)
        if (list.isEmpty()) {
            card.addView(Ui.label(this, Ui.t(this, "No voice messages yet — send the first one 💞", "Abhi koi voice message nahi — pehla aap bhejo 💞"),
                13f, 0xFF8E7A99.toInt()).apply { setPadding(0, dp(10f), 0, 0) })
        }
        val fmt = java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale.ENGLISH)
        for (v in list) {
            val now = VoiceNote.playing == v.file.path
            val who = if (v.mine) Ui.t(this, "You", "Aap") else other
            card.addView(Ui.glassButton(this, (if (now) "⏹️  " else "▶️  ") + "$who · ${VoiceNote.len(v.sec)} · ${fmt.format(java.util.Date(v.at))}") {
                VoiceNote.play(this, v.file)
            }.apply { textSize = 14.5f }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8f)
            })
        }
        body.addView(card)
    }

    private fun recordVoice() {
        if (!CoupleLink.paired(this) || rec != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), reqMic)
            return
        }
        val other = Speaker.other(Profile.load(this))
        VoiceNote.stop()
        VoiceNote.recording = true
        WakeService.pause()
        val f = java.io.File(cacheDir, "voice_rec.m4a").apply { delete() }
        val time = Ui.label(this, "🔴 0:00", 30f, 0xFFB0276E.toInt(), true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(18f), 0, dp(6f))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(time)
            addView(Ui.label(this@CoupleActivity, Ui.t(this@CoupleActivity, "Speak now… (max 2 min)", "Ab bolo… (max 2 minute)"), 13f, 0xFF6B4E7A.toInt()).apply {
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(10f))
            })
        }
        recDialog = AlertDialog.Builder(this).setTitle("🎤  " + Ui.t(this, "Voice message for $other", "$other ke liye voice message"))
            .setView(box).setCancelable(false)
            .setPositiveButton(Ui.t(this, "💌 Send", "💌 Bhejo")) { _, _ -> stopRecording(true) }
            .setNegativeButton(Ui.t(this, "🗑️ Cancel", "🗑️ Rehne do")) { _, _ -> stopRecording(false) }
            .show()
        h.postDelayed({ startRecorder(f, time) }, 350)
    }

    private fun startRecorder(f: java.io.File, time: TextView) {
        if (!VoiceNote.recording || rec != null) return
        try {
            val r = if (Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(this)
            else android.media.MediaRecorder()
            r.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(22050)
            r.setAudioEncodingBitRate(32000)
            r.setMaxDuration(120_000)
            r.setOutputFile(f.path)
            r.setOnInfoListener { _, what, _ ->
                if (what == android.media.MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) runOnUiThread {
                    recDialog?.dismiss()
                    stopRecording(true)
                }
            }
            r.prepare()
            r.start()
            rec = r
            recFile = f
            recStart = SystemClock.elapsedRealtime()
            val tick = object : Runnable {
                override fun run() {
                    if (rec == null) return
                    time.text = "🔴 " + VoiceNote.len(((SystemClock.elapsedRealtime() - recStart) / 1000).toInt())
                    h.postDelayed(this, 500)
                }
            }
            h.post(tick)
        } catch (e: Exception) {
            recDialog?.dismiss()
            recDialog = null
            VoiceNote.recording = false
            Toast.makeText(this, Ui.t(this, "Couldn't open the mic 😕", "Mic nahi khula 😕"), Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecording(send: Boolean) {
        val r = rec
        val f = recFile
        rec = null
        recFile = null
        recDialog = null
        val sec = ((SystemClock.elapsedRealtime() - recStart) / 1000).toInt()
        var ok = r != null
        try {
            r?.stop()
        } catch (e: Exception) {
            ok = false
        }
        try {
            r?.release()
        } catch (e: Exception) {
        }
        VoiceNote.recording = false
        if (!send || !ok || f == null || !f.exists() || f.length() < 800 || sec < 1) {
            if (send) Toast.makeText(this, Ui.t(this, "Too short — try again 🎤", "Bahut chhota tha, phir se bolo 🎤"), Toast.LENGTH_SHORT).show()
            f?.delete()
            return
        }
        val other = Speaker.other(Profile.load(this))
        val saved = VoiceNote.keepMine(this, f, sec)
        fx.burst("🎤", 12)
        Toast.makeText(this, Ui.t(this, "Sending… 📤", "Bhej rahe hain… 📤"), Toast.LENGTH_SHORT).show()
        VoiceNote.send(this, saved, sec) { sent ->
            Toast.makeText(this, if (sent) Ui.t(this, "Voice message sent to $other 💞", "$other ko voice message chala gaya 💞")
            else Ui.t(this, "No internet 😕", "Internet nahi hai 😕"), Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == reqMic && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) recordVoice()
        Care.onPermission(this, requestCode, grantResults)
    }

    private fun writeNote() {
        val input = EditText(this).apply { hint = Ui.t(this@CoupleActivity, "Write something sweet…", "Kuch pyaara sa likho…") }
        AlertDialog.Builder(this).setTitle("💬  " + Ui.t(this, "Message for ${Speaker.other(Profile.load(this))}",
            "${Speaker.other(Profile.load(this))} ke liye message")).setView(input)
            .setPositiveButton(Ui.t(this, "Send", "Bhejo")) { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) {
                    fx.burst("💌", 12)
                    CoupleLink.send(this, "note", JSONObject().put("text", t))
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    fun celebrate(emoji: String, text: String) = celebrate(emoji, text, Hindi.devanagariOf(text))

    fun celebrate(emoji: String, text: String, spoken: String) {
        fx.burst(emoji)
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        Voice.say(this, spoken)
        refresh()
    }

    fun refresh() {
        if (!meTouching) {
            render()
            updateTouch()
        }
    }

    // ---------- 💞 days together ----------
    private fun togetherCard() {
        val days = Together.days(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Ui.glass(this@CoupleActivity, 28f, 0x77)
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            setOnClickListener { pickSince() }
        }
        if (days < 0) {
            card.addView(Ui.label(this, Ui.t(this, "💞 When did your story begin?", "💞 Aapki kahani kab shuru hui?"), 17f, 0xFF3B1F4A.toInt(), true))
            card.addView(Ui.label(this, Ui.t(this, "Tap to pick the date", "Date chunne ke liye tap karo"), 13f, 0xFF6B4E7A.toInt()))
        } else {
            card.addView(Ui.label(this, "$days", 44f, 0xFFE0115F.toInt(), true).apply { gravity = Gravity.CENTER })
            val extra = Together.pretty(this).let { if (it.isNotEmpty()) " · $it" else "" }
            card.addView(Ui.label(this, Ui.t(this, "days together 💞", "din saath mein 💞") + extra, 15f, 0xFF3B1F4A.toInt(), true).apply {
                gravity = Gravity.CENTER
            })
        }
        body.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14f)
        })
    }

    private fun pickSince() {
        val c = java.util.Calendar.getInstance()
        if (Together.since(this) > 0) c.timeInMillis = Together.since(this)
        android.app.DatePickerDialog(this, { _, y, m, d ->
            Together.setSince(this, y, m, d)
            fx.burst("💞")
            render()
        }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)).apply {
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    // ---------- 😊 mood ----------
    private fun moodCard() {
        val p = Profile.load(this)
        val other = Speaker.other(p)
        sectionTitle(Ui.t(this, "MY MOOD", "MERA MOOD"))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.glass(this@CoupleActivity, 24f, 0x55)
            setPadding(dp(6f), dp(8f), dp(6f), dp(8f))
        }
        val mine = getSharedPreferences("couple", MODE_PRIVATE).getInt("myMood", -1)
        Mood.LIST.forEachIndexed { i, (e, _, _) ->
            row.addView(TextView(this).apply {
                text = e
                textSize = 26f
                gravity = Gravity.CENTER
                setPadding(0, dp(6f), 0, dp(6f))
                if (i == mine) background = Ui.glass(this@CoupleActivity, 18f, 0xDD)
                setOnClickListener {
                    getSharedPreferences("couple", MODE_PRIVATE).edit().putInt("myMood", i).apply()
                    fx.burst(e, 10)
                    CoupleLink.send(this@CoupleActivity, "mood", JSONObject().put("i", i)) { ok ->
                        Toast.makeText(this@CoupleActivity, if (ok) Ui.t(this@CoupleActivity, "$other will know $e", "$other ko pata chal jayega $e")
                            else Ui.t(this@CoupleActivity, "No internet 😕", "Internet nahi hai 😕"), Toast.LENGTH_SHORT).show()
                    }
                    render()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        body.addView(row)
        val cp = getSharedPreferences("couple", MODE_PRIVATE)
        val pm = cp.getInt("pMood", -1)
        if (pm in Mood.LIST.indices) {
            val ago = (System.currentTimeMillis() - cp.getLong("pMoodAt", 0)) / 60_000
            val whenTxt = when {
                ago < 1 -> Ui.t(this, "just now", "abhi")
                ago < 60 -> "$ago min"
                ago < 1440 -> "${ago / 60} h"
                else -> "${ago / 1440} d"
            }
            body.addView(Ui.label(this, "$other: ${Mood.LIST[pm].first} ${if (Ui.hinglish(this)) Mood.LIST[pm].third else Mood.LIST[pm].second} · $whenTxt",
                14f, 0xFF6B4E7A.toInt(), true).apply { setPadding(dp(6f), dp(8f), 0, 0) })
        }
    }

    // ---------- 💝 love jar ----------
    private fun jarCard() {
        val p = Profile.load(this)
        val other = Speaker.other(p)
        sectionTitle(Ui.t(this, "💝 LOVE JAR · THIS WEEK", "💝 LOVE JAR · IS HAFTE"))
        val wk = LoveJar.week(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glass(this@CoupleActivity, 24f, 0x66)
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        }
        val order = listOf("kiss" to "😘", "hug" to "🤗", "miss" to "🥺", "love" to "❤️", "note" to "💌")
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((k, e) in order) {
            val (got, sent) = wk[k] ?: (0 to 0)
            stats.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(Ui.label(this@CoupleActivity, e, 22f, 0xFF3B1F4A.toInt()).apply { gravity = Gravity.CENTER })
                addView(Ui.label(this@CoupleActivity, "↓$got  ↑$sent", 12f, 0xFF3B1F4A.toInt(), true).apply { gravity = Gravity.CENTER })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        card.addView(stats)
        card.addView(Ui.label(this, Ui.t(this, "↓ from $other   ↑ from you", "↓ $other se   ↑ aapki taraf se"), 11.5f, 0xFF8E7A99.toInt()).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(6f), 0, 0)
        })
        val notes = LoveJar.all(this).filter { it.kind == "note" && it.text.isNotBlank() }.takeLast(6).reversed()
        if (notes.isNotEmpty()) {
            card.addView(Ui.label(this, Ui.t(this, "Love notes", "Love notes"), 13f, 0xFF8A4F9E.toInt(), true).apply { setPadding(0, dp(12f), 0, dp(4f)) })
            val fmt = java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale.ENGLISH)
            for (n in notes) {
                card.addView(Ui.label(this, "💌 ${n.text}", 14.5f, 0xFF3B1F4A.toInt()).apply { setPadding(0, dp(6f), 0, 0) })
                card.addView(Ui.label(this, "${if (n.mine) Ui.t(this, "You", "Aap") else n.from} · ${fmt.format(java.util.Date(n.at))}",
                    11.5f, 0xFF8E7A99.toInt()))
            }
        }
        body.addView(card)
    }

    // ---------- 💌 letters · ❓ daily question · 🌙 bedtime story ----------
    private fun extrasCard() {
        val other = Speaker.other(Profile.load(this))
        sectionTitle(Ui.t(this, "✨ JUST FOR US", "✨ SIRF HUMARE LIYE"))
        selfieCard(other)
        // today's question, with a teaser when the partner already answered
        val mine = DailyQ.mine(this)
        val theirs = DailyQ.theirs(this)
        val streak = DailyQ.streak(this)
        val qStatus = when {
            mine.isEmpty() && theirs.isNotEmpty() -> Ui.t(this, "👀 $other answered — yours unlocks it!", "👀 $other ne jawab de diya — apna likho!")
            mine.isEmpty() -> Ui.t(this, "Tap to answer ✍️", "Jawab dene ke liye tap karo ✍️")
            theirs.isEmpty() -> Ui.t(this, "⏳ Waiting for $other", "⏳ $other ka intezaar")
            else -> Ui.t(this, "🎉 Both answered — see it!", "🎉 Dono ne jawab de diya — dekho!")
        }
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glass(this@CoupleActivity, 24f, if (mine.isEmpty() && theirs.isNotEmpty()) 0xAA else 0x66)
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            addView(Ui.label(this@CoupleActivity, "❓ " + Ui.t(this@CoupleActivity, "Today's question", "Aaj ka sawaal") +
                (if (streak > 0) "   🔥 $streak" else ""), 13f, 0xFF8A4F9E.toInt(), true))
            addView(Ui.label(this@CoupleActivity, DailyQ.question(this@CoupleActivity), 16.5f, 0xFF2E1A38.toInt(), true).apply {
                setPadding(0, dp(6f), 0, dp(6f))
            })
            addView(Ui.label(this@CoupleActivity, qStatus, 13f, 0xFF6B4E7A.toInt()))
            setOnClickListener { startActivity(Intent(this@CoupleActivity, QuestionActivity::class.java)) }
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val sealed = Letters.sealedCount(this)
        row.addView(Ui.glassButton(this, Ui.t(this, "💌 Open-when letters", "💌 Open-when khat") + if (sealed > 0) "  ($sealed)" else "") {
            startActivity(Intent(this, LettersActivity::class.java))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.glassButton(this, Ui.t(this, "🌙 Bedtime story", "🌙 Raat ki kahani")) {
            startActivity(Intent(this, StoryActivity::class.java))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12f) })
        body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12f)
        })
    }

    // ---------- 📸🔥 selfie streak teaser ----------
    private fun selfieCard(other: String) {
        val s = SelfieStreak.streak(this)
        val meDone = SelfieStreak.posted(SelfieStreak.mine(this))
        val themDone = SelfieStreak.posted(SelfieStreak.theirs(this))
        val rescue = SelfieStreak.canRescue(this) && !SelfieStreak.rescued(this)
        val line = when {
            rescue -> Ui.t(this, "💔 Streak broke! Save 🔥 ${SelfieStreak.brokenStreak(this)} with a dare 🛟", "💔 Streak tooti! Dare karke 🔥 ${SelfieStreak.brokenStreak(this)} bachao 🛟")
            meDone && themDone -> Ui.t(this, "Today is locked in 💞", "Aaj ki streak pakki 💞")
            themDone -> Ui.t(this, "👀 $other posted — your turn!", "👀 $other ne bhej di — ab aapki baari!")
            meDone -> Ui.t(this, "⏳ Waiting for $other", "⏳ $other ka intezaar")
            else -> Ui.t(this, "📸 Take today's selfie", "📸 Aaj ki selfie lo")
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                if (rescue) intArrayOf(0xFFFF5F7E.toInt(), 0xFFB06BFF.toInt()) else intArrayOf(0xFFFFB37A.toInt(), 0xFFFF6FB1.toInt())
            ).apply { cornerRadius = dp(24f).toFloat() }
            elevation = dp(4f).toFloat()
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            setOnClickListener { startActivity(Intent(this@CoupleActivity, SelfieActivity::class.java)) }
        }
        val flame = TextView(this).apply { text = if (rescue) "💔" else if (s > 0) "🔥" else "📸"; textSize = 36f }
        card.addView(flame)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), 0, 0, 0) }
        col.addView(Ui.label(this, Ui.t(this, "Selfie streak", "Selfie streak") + if (s > 0) "  ·  $s" else "", 17f, 0xFFFFFFFF.toInt(), true))
        col.addView(Ui.label(this, line, 13.5f, 0xEEFFFFFF.toInt()))
        card.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12f)
        })
        if (s > 0 || rescue) android.animation.ObjectAnimator.ofFloat(flame, "scaleY", 1f, 1.15f).apply {
            duration = 600; repeatCount = android.animation.ValueAnimator.INFINITE; repeatMode = android.animation.ValueAnimator.REVERSE; start()
        }
    }

    private fun sectionTitle(t: String) {
        body.addView(Ui.label(this, t, 12.5f, 0xFF8A4F9E.toInt(), true).apply {
            letterSpacing = 0.1f
            setPadding(dp(4f), dp(22f), 0, dp(8f))
        })
    }

    fun partnerTouch(on: Boolean) {
        // a soft beat the moment they start touching, so you know to touch too
        if (on && !partnerTouching && !meTouching) vibe(longArrayOf(0, 40, 60, 40), intArrayOf(0, 140, 0, 120))
        partnerTouching = on
        updateTouch()
    }

    // ---------- 💓 tap together ----------
    private var downAt = 0L
    private var lastMyTap = 0L
    private var lastPartnerTap = 0L

    private fun vibe(t: LongArray, amp: IntArray) {
        try {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(t, amp, -1))
        } catch (e: Exception) {
        }
    }

    private fun myTap() {
        lastMyTap = SystemClock.uptimeMillis()
        CoupleLink.send(this, "tap")
        vibe(longArrayOf(0, 30), intArrayOf(0, 120))
        if (lastMyTap - lastPartnerTap < 4000) bothTapped()
    }

    fun partnerTap() {
        lastPartnerTap = SystemClock.uptimeMillis()
        if (lastPartnerTap - lastMyTap < 4000) {
            bothTapped()
            return
        }
        vibe(longArrayOf(0, 40, 60, 40), intArrayOf(0, 160, 0, 140))
        if (this::heart.isInitialized) {
            heart.text = "💓"
            heart.animate().scaleX(1.15f).scaleY(1.15f).setDuration(150).withEndAction {
                heart.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                h.postDelayed({ updateTouch() }, 2500)
            }.start()
        }
        Toast.makeText(this, Ui.t(this, "${Speaker.other(Profile.load(this))} tapped the heart — tap it too! 💓",
            "${Speaker.other(Profile.load(this))} ne dil tap kiya — aap bhi tap karo! 💓"), Toast.LENGTH_SHORT).show()
    }

    /** 💞 both tapped within a few seconds → three strong heartbeats on both phones */
    private fun bothTapped() {
        lastMyTap = 0L
        lastPartnerTap = 0L
        vibe(longArrayOf(0, 90, 120, 140, 450, 90, 120, 140, 450, 90, 120, 140), intArrayOf(0, 255, 0, 200, 0, 255, 0, 200, 0, 255, 0, 200))
        fx.burst("💞", 30)
        if (this::heart.isInitialized) {
            heart.text = "💞"
            heart.animate().scaleX(1.3f).scaleY(1.3f).setDuration(220).setInterpolator(android.view.animation.OvershootInterpolator()).withEndAction {
                heart.animate().scaleX(1f).scaleY(1f).setDuration(300).start()
                h.postDelayed({ updateTouch() }, 3000)
            }.start()
        }
        Toast.makeText(this, Ui.t(this, "💞 You touched together!", "💞 Dono ne saath mein choo liya!"), Toast.LENGTH_SHORT).show()
    }

    private val pulse = object : Runnable {
        override fun run() {
            if (!(meTouching && partnerTouching)) return
            try {
                // strong "lub-dub" heartbeat
                getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 90, 120, 140), intArrayOf(0, 255, 0, 200), -1))
            } catch (e: Exception) {
            }
            fx.burst("💓", 3)
            h.postDelayed(this, 900)
        }
    }

    private fun updateTouch() {
        if (!this::heart.isInitialized) return
        heart.text = when {
            meTouching && partnerTouching -> "💗"
            partnerTouching -> "💓"
            meTouching -> "🤍"
            else -> "🤍"
        }
        heart.animate().scaleX(if (meTouching) 1.08f else 1f).scaleY(if (meTouching) 1.08f else 1f).setDuration(150).start()
        h.removeCallbacks(pulse)
        if (meTouching && partnerTouching) pulse.run()
    }

    companion object {
        @Volatile
        var current: CoupleActivity? = null
    }
}
