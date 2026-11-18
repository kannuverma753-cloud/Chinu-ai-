package com.meraassistant

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import kotlin.math.sqrt

/**
 * 🎙️ "Hi Chinu" wake word — OFFLINE, SILENT.
 *
 * A tiny speech engine (Vosk) runs inside the phone and listens ONLY for
 * "Hi / Hey / Hello Chinu". No beeps, nothing is sent to the internet,
 * nothing else is recognised. When the name is heard, Chinu opens.
 */
class WakeService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var lastWake = 0L

    @Volatile
    private var running = false
    private var worker: Thread? = null

    private val poll = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 400)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Speaker.load(this)
        if (!startInForeground()) return
        CoupleLink.start(this)          // hugs & kisses arrive even when the app is closed
        // Model ko assets se phone mein ek baar unpack karo
        StorageService.unpack(
            this, "model-en-in", "model",
            { m: Model ->
                model = m
                handler.post(poll)
            },
            { _: java.io.IOException -> stopSelf() }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("wake", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        stopEngine()
        try {
            worker?.join(400)
        } catch (e: Exception) {
        }
        try {
            model?.close()
        } catch (e: Exception) {
        }
        model = null
        super.onDestroy()
    }

    // ---------------- Notification (Android ka niyam) ----------------
    private fun startInForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Hey Chinu", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(
            this, 50, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 51, Intent(this, WakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Chinu is ready")
            .setContentText("Say \"Hi Chinu\" · works offline, only listens for her name")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_mic), "Turn off", stop).build())
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIF_ID, n)
            true
        } catch (e: Exception) {
            stopSelf()
            false
        }
    }

    // ---------------- Engine on/off ----------------
    private fun shouldListen(): Boolean {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val ttsBusy = MainActivity.speaking && System.currentTimeMillis() - MainActivity.speakingSince < 30_000
        return !(MainActivity.visible || ChinuOverlay.active || ttsBusy || VoiceNote.recording || BdayActivity.active ||
            am.mode == AudioManager.MODE_IN_CALL ||
            am.mode == AudioManager.MODE_IN_COMMUNICATION ||
            am.mode == AudioManager.MODE_RINGTONE)
    }

    private var lastAccAlert = 0L

    @Volatile
    private var usingBt = false

    /** Listen for "Hey Chinu" through the earbuds too (Settings → Voice) */
    private fun wantBt(): Boolean =
        getSharedPreferences("settings", MODE_PRIVATE).getBoolean("budsWake", false) && BtMic.headset(this) != null

    private fun update() {
        // buds connected / disconnected → restart the engine on the right mic
        if (running && wantBt() != usingBt) stopEngine()
        if (shouldListen()) startEngine() else stopEngine()
        watchAccessibility()
    }

    /** Phone ne Accessibility band kar di ho to ek notification se turant wapas ON karne ko bolo */
    private fun watchAccessibility() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (AssistantService.instance != null || !prefs.getBoolean("accWasOn", false)) return
        val now = System.currentTimeMillis()
        if (now - lastAccAlert < 30 * 60_000L) return
        lastAccAlert = now
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Chinu replies", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(
            this, 53, Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("⚠️ Chinu's Accessibility turned off")
            .setContentText("Your phone switched it off. Tap → Chinu → ON (needed for screenshots, WhatsApp & the orb)")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(NOTIF_ID + 2, n)
        } catch (e: Exception) {
        }
    }

    private fun startEngine() {
        val m = model ?: return
        if (running || worker?.isAlive == true) return
        running = true
        worker = Thread({ listenLoop(m) }, "chinu-wake").also { it.start() }
    }

    private fun stopEngine() {
        running = false
    }

    /**
     * Reads the mic ourselves (no beeps), feeds the offline recogniser,
     * and measures voice pitch so we know if a boy or a girl said "Hi Chinu".
     */
    private fun listenLoop(m: Model) {
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 8192))
        } catch (e: Exception) {
            running = false
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            running = false
            return
        }
        usingBt = wantBt()
        if (usingBt) {
            BtMic.use(this)
            try {
                Thread.sleep(700)
            } catch (e: InterruptedException) {
            }
            BtMic.input(this)?.let { rec.preferredDevice = it }
        }
        val recognizer = try {
            Recognizer(m, 16000.0f, grammar())
        } catch (e: Exception) {
            rec.release()
            running = false
            return
        }
        try {
            recognizer.setWords(true)              // per-word confidence
        } catch (e: Exception) {
        }
        val buf = ShortArray(1600)                 // 100 ms
        val pitches = ArrayDeque<Pair<Long, Float>>()
        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val now = System.currentTimeMillis()
                pitchOf(buf, n)?.let { pitches.addLast(now to it) }
                while (pitches.isNotEmpty() && now - pitches.first().first > 2500) pitches.removeFirst()

                val isFinal = recognizer.acceptWaveForm(buf, n)
                var hit = false
                if (isFinal) {
                    val json = try {
                        JSONObject(recognizer.result)
                    } catch (e: Exception) {
                        null
                    }
                    hit = json != null && isWake(json)
                    lastPartial = ""
                } else {
                    // Fast path: name heard in the live (partial) result twice in a row → wake right away,
                    // no need to wait for the end of the sentence
                    val p = try {
                        JSONObject(recognizer.partialResult).optString("partial")
                    } catch (e: Exception) {
                        ""
                    }
                    if (p.isNotEmpty() && partialWake(p)) {
                        hit = p == lastPartial || ++partialHits >= 2
                        lastPartial = p
                    } else {
                        partialHits = 0
                        lastPartial = p
                    }
                }
                if (hit) {
                    partialHits = 0
                    lastPartial = ""
                    val male = isMaleVoice(pitches.map { it.second })
                    recognizer.reset()
                    pitches.clear()
                    running = false
                    handler.post { onWakeWord(male) }
                }
            }
        } catch (e: Exception) {
        } finally {
            try {
                rec.stop()
            } catch (e: Exception) {
            }
            rec.release()
            recognizer.close()
            if (usingBt) {
                BtMic.release(this)
                usingBt = false
            }
            running = false
        }
    }

    /** Pitch (Hz) of a 100 ms frame using autocorrelation; null if silent/unvoiced */
    private fun pitchOf(b: ShortArray, n: Int): Float? {
        var energy = 0.0
        for (i in 0 until n) energy += b[i].toDouble() * b[i]
        if (sqrt(energy / n) < 400) return null
        val minLag = 16000 / 350               // 350 Hz
        val maxLag = 16000 / 70                // 70 Hz
        val len = minOf(n - maxLag - 1, 1024)
        if (len <= 200) return null
        val r = DoubleArray(maxLag + 1)
        var best = 0.0
        for (lag in minLag..maxLag) {
            var s = 0.0
            var e1 = 0.0
            var e2 = 0.0
            for (i in 0 until len) {
                val x = b[i].toDouble()
                val y = b[i + lag].toDouble()
                s += x * y
                e1 += x * x
                e2 += y * y
            }
            r[lag] = s / (sqrt(e1 * e2) + 1e-9)
            if (r[lag] > best) best = r[lag]
        }
        if (best < 0.5) return null
        // smallest lag close to the best peak = true pitch (avoids octave errors)
        for (lag in minLag..maxLag) {
            if (r[lag] >= best * 0.88 && (lag == minLag || r[lag] >= r[lag - 1]) && (lag == maxLag || r[lag] >= r[lag + 1])) {
                return 16000f / lag
            }
        }
        return null
    }

    /** Typical men ~85-160 Hz, women ~165-260 Hz. null = not sure */
    private fun isMaleVoice(p: List<Float>): Boolean? {
        if (p.size < 3) return null
        val sorted = p.sorted()
        val median = sorted[sorted.size / 2]
        return median < 165f
    }

    /** Words the offline model knows (so the grammar never contains unknown words) */
    private val known: Set<String>? by lazy {
        try {
            assets.open("model-en-in/graph/words.txt").bufferedReader().useLines { lines ->
                lines.map { it.substringBefore(' ').trim() }.filter { it.isNotEmpty() }.toHashSet()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun grammar(): String {
        val k = known
        val names = NAME_WORDS.filter { k == null || it.split(" ").all { w -> w in k } }
        val greets = GREETINGS.filter { g -> g.isEmpty() || k == null || g.trim() in k }
        val phrases = LinkedHashSet<String>()
        for (n in names) for (g in greets) phrases.add((g + n).trim())
        return "[" + phrases.joinToString(", ") { "\"$it\"" } + ", \"[unk]\"]"
    }

    /** Another app (YouTube, music, a video) is playing sound right now */
    private fun mediaPlaying(): Boolean {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        return try {
            am.activePlaybackConfigurations.any {
                val u = it.audioAttributes.usage
                u == android.media.AudioAttributes.USAGE_MEDIA || u == android.media.AudioAttributes.USAGE_GAME ||
                    u == android.media.AudioAttributes.USAGE_UNKNOWN
            }
        } catch (e: Exception) {
            am.isMusicActive
        }
    }

    /**
     * Strict wake check (like a real assistant):
     *  - the phrase must START with (hi/hey/ok) + Chinu — not be somewhere in the middle of other talk
     *  - the name must be heard with high confidence
     *  - while a video/song is playing, only "Hey/Hi/OK Chinu" said on its own counts
     */
    private fun isWake(j: JSONObject): Boolean {
        val words = j.optJSONArray("result") ?: return false
        val toks = ArrayList<Pair<String, Double>>()
        for (i in 0 until words.length()) {
            val w = words.optJSONObject(i) ?: continue
            toks.add(w.optString("word").lowercase() to w.optDouble("conf", 0.0))
        }
        while (toks.isNotEmpty() && toks[0].first == "[unk]") toks.removeAt(0)
        if (toks.isEmpty() || toks.size > 8) return false
        val sense = getSharedPreferences("settings", MODE_PRIVATE).getInt("wakeSense", 1)
        val media = mediaPlaying()
        var minConf = when (sense) {
            0 -> 0.80
            2 -> 0.45
            else -> 0.60
        }
        if (media) minConf = maxOf(minConf, 0.85)

        var i = 0
        val greeted = toks[0].first in GREET_WORDS
        if (greeted) i = 1
        if (media && !greeted) return false
        if (media && toks.size > 3) return false
        for (name in NAME_WORDS) {
            val parts = name.split(" ")
            if (i + parts.size > toks.size) continue
            var ok = true
            var conf = 0.0
            for ((k, part) in parts.withIndex()) {
                if (toks[i + k].first != part) { ok = false; break }
                conf += toks[i + k].second
            }
            if (!ok) continue
            conf /= parts.size
            if (conf < minConf) return false
            // "chinu chinu chinu" or a short command after the name is fine
            return toks.size - (i + parts.size) <= 6
        }
        return false
    }

    private var lastPartial = ""
    private var partialHits = 0

    /** Live check: "chinu" (or a variant) among the first words, nothing playing in the background */
    private fun partialWake(p: String): Boolean {
        if (mediaPlaying()) return false
        val toks = p.lowercase().split(" ").filter { it.isNotBlank() && it != "[unk]" }
        if (toks.isEmpty() || toks.size > 6) return false
        var i = 0
        if (toks[0] in GREET_WORDS) i = 1
        val joined = toks.drop(i).take(2).joinToString(" ")
        return NAME_WORDS.any { joined == it || joined.startsWith("$it ") }
    }

    private fun onWakeWord(male: Boolean?) {
        val now = System.currentTimeMillis()
        if (now - lastWake < 1200) return
        lastWake = now
        // Halka sa vibrate: "suna, main aa rahi hoon"
        try {
            val v = getSystemService(android.os.Vibrator::class.java)
            v?.vibrate(android.os.VibrationEffect.createOneShot(45, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
        }
        if (male != null) Speaker.set(male)
        wake()
    }

    // ---------------- Wake up! ----------------
    private fun wake() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val acc = AssistantService.instance
        // Screen ON + unlocked: Siri jaisa orb, app khole bina
        if (pm.isInteractive && !km.isKeyguardLocked && acc != null) {
            acc.showOverlay("")
            return
        }
        // Screen OFF / locked: Chinu lock screen ke upar khulti hai
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(EXTRA_WAKE, true)
            .putExtra(EXTRA_COMMAND, "")
        try {
            startActivity(i)
        } catch (e: Exception) {
        }
        handler.postDelayed({ if (!MainActivity.visible) showWakeNotification(i) }, 1500)
    }

    private fun showWakeNotification(i: Intent) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Chinu replies", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(this, 52, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("💖 Haan, bolo?")
            .setContentText("Tap to talk to Chinu")
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_CALL)
            .build()
        try {
            nm.notify(NOTIF_ID + 1, n)
        } catch (e: Exception) {
        }
    }

    /** Called when Chinu's own screens open: free the mic immediately */
    fun pauseNow() {
        stopEngine()
    }

    companion object {
        private const val CHANNEL = "wake_quiet"
        private const val ALERT_CHANNEL = "wake_alert"
        private const val NOTIF_ID = 700
        const val ACTION_STOP = "com.meraassistant.STOP_WAKE"
        const val EXTRA_WAKE = "wake"
        const val EXTRA_COMMAND = "command"

        /** Every way the engine might hear "Chinu" (only words the model knows are used) */
        private val NAME_WORDS = listOf(
            "chinu", "cheenu", "chinnu", "chinoo", "chino", "cheeno", "chenu", "chinni", "chinna",
            "chin new", "chin you", "chin u", "china"
        )
        private val GREETINGS = listOf("", "hi ", "hey ", "hello ", "ok ", "okay ", "hai ")
        private val GREET_WORDS = setOf("hi", "hey", "hello", "ok", "okay", "hai")

        @Volatile
        var instance: WakeService? = null
            private set

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, WakeService::class.java))
            } catch (e: Exception) {
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WakeService::class.java))
        }

        fun pause() {
            instance?.pauseNow()
        }
    }
}
