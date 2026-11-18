package com.meraassistant

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 🎂 Komal's birthday surprise — lives ONLY on 19 Nov 2026.
 * Before: a countdown. 12:00 AM: the surprise opens (cake, candles to blow, song, gifts). 20 Nov: everything disappears.
 */
object Bday {
    val DAY: LocalDate = LocalDate.of(2026, 11, 19)
    private val main = Handler(Looper.getMainLooper())

    fun prefs(ctx: Context) = ctx.getSharedPreferences("bday", Context.MODE_PRIVATE)
    fun isToday() = LocalDate.now() == DAY
    fun upcoming() = LocalDate.now().isBefore(DAY)
    fun chiku(ctx: Context): Boolean {
        Speaker.load(ctx)
        return Speaker.owner == true
    }

    fun her(ctx: Context) = Profile.load(ctx).name.ifBlank { "Komal" }
    fun him(ctx: Context) = Profile.load(ctx).partner.ifBlank { "Chiku" }
    fun startMillis() = DAY.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Chinu's wake greeting on her birthday */
    fun greet(ctx: Context): String? {
        if (!isToday() || chiku(ctx)) return null
        val n = her(ctx)
        return "हैप्पी बर्थडे ${if (n.equals("Komal", true)) "कोमल" else n}! 🎂 बोलो?"
    }

    /** First time she opens Chinu on the 19th → the surprise opens by itself */
    fun maybeAutoOpen(a: Activity) {
        cleanup(a)
        if (!isToday() || chiku(a) || prefs(a).getBoolean("seen", false)) return
        a.startActivity(Intent(a, BdayActivity::class.java))
    }

    fun open(a: Activity, preview: Boolean = false) =
        a.startActivity(Intent(a, BdayActivity::class.java).putExtra("preview", preview))

    // ---------------------------------------------------------------- 💌 Chiku's secret message (opens at midnight)
    private fun defaultMsg(ctx: Context): String {
        val h = her(ctx)
        val m = Profile.load(ctx).message
        return "Happy Birthday $h 💖\n\n" +
            "Aaj ka din sirf tumhara hai. Tum meri life ki sabse pyaari cheez ho — tumhari ek smile se mera poora din ban jaata hai. " +
            "Tumhare saath har chhoti baat bhi special lagti hai, aur main har din bhagwan ko thank you bolta hoon ki tum meri ho. 🥹\n\n" +
            "Bas aise hi hamesha hansti raho, khush raho, aur mujhe pareshan karti raho 😄. " +
            "Is saal tumhari har wish poori ho — aur jo nahi hogi, wo main poori karunga. Promise. 🤞\n\n" +
            (if (m.isNotBlank()) "$m\n\n" else "") +
            "Love you forever,\n— ${him(ctx)} 💚"
    }

    fun message(ctx: Context): String = prefs(ctx).getString("msg", null)?.takeIf { it.isNotBlank() } ?: defaultMsg(ctx)

    fun writeMessage(a: Activity) {
        val e = EditText(a).apply {
            hint = "Dil se likho… ye 19 ki raat 12 baje khulega 🔒"
            minLines = 5
            setText(prefs(a).getString("myMsg", ""))
        }
        AlertDialog.Builder(a).setTitle("✍️  ${her(a)} ke liye birthday message")
            .setView(e)
            .setPositiveButton("Seal karke bhejo 💌") { _, _ ->
                val t = e.text.toString().trim()
                if (t.isEmpty()) return@setPositiveButton
                prefs(a).edit().putString("myMsg", t).apply()
                CoupleLink.send(a, "bday", JSONObject().put("k", "msg").put("text", t)) { ok ->
                    main.post {
                        Toast.makeText(a, if (ok) "🔒 Sealed! ${her(a)} ko 12 baje dikhega 💌" else "Internet nahi hai 😕 dobara try karo",
                            Toast.LENGTH_LONG).show()
                    }
                }
            }.setNegativeButton("Rehne do", null).show()
    }

    // ---------------------------------------------------------------- 📸🎵 Chiku's photos / videos / song → Komal's phone (encrypted, hidden till 12)
    private val IMG = Regex("(?i).*\\.(jpe?g|png|webp)$")
    private val VID = Regex("(?i).*\\.(mp4|3gp|webm|mkv|mov)$")
    private val AUD = Regex("(?i).*\\.(mp3|m4a|aac|ogg|wav|opus)$")

    fun dir(ctx: Context) = File(ctx.filesDir, "bday").apply { mkdirs() }

    /** Photos + videos for the slideshow, in order */
    fun media(ctx: Context): List<File> =
        (dir(ctx).listFiles() ?: emptyArray()).filter { (IMG.matches(it.name) || VID.matches(it.name)) && it.length() > 0 }.sortedBy { it.name }

    fun isVideo(f: File) = VID.matches(f.name)
    fun song(ctx: Context): File? = (dir(ctx).listFiles() ?: emptyArray()).filter { it.name.startsWith("song.") && it.length() > 0 }
        .maxByOrNull { it.lastModified() }

    /** Files from the repo's bday/ folder (if any) → same place */
    fun copyAssets(ctx: Context) {
        val names = try {
            ctx.assets.list("bday") ?: emptyArray()
        } catch (e: Exception) {
            emptyArray()
        }
        for (n in names) {
            val out = when {
                IMG.matches(n) || VID.matches(n) -> File(dir(ctx), "a_$n")
                AUD.matches(n) && song(ctx) == null -> File(dir(ctx), "song." + n.substringAfterLast('.'))
                else -> null
            } ?: continue
            if (out.exists()) continue
            try {
                ctx.assets.open("bday/$n").use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            } catch (e: Exception) {
            }
        }
    }

    /** After the 19th everything goes away */
    fun cleanup(ctx: Context) {
        if (upcoming() || isToday()) return
        try {
            File(ctx.filesDir, "bday").deleteRecursively()
        } catch (e: Exception) {
        }
    }

    private val outbox = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun sendBlocking(app: Context, d: JSONObject): Boolean {
        repeat(3) { attempt ->
            val latch = java.util.concurrent.CountDownLatch(1)
            var ok = false
            CoupleLink.send(app, "bday", d) { r ->
                ok = r
                latch.countDown()
            }
            try {
                latch.await(150, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
            }
            if (ok) return true
            try {
                Thread.sleep(4000L * (attempt + 1))
            } catch (e: InterruptedException) {
            }
        }
        return false
    }

    /** Photo → max 1400px JPEG, turned the right way up */
    private fun photoBytes(app: Context, u: android.net.Uri): ByteArray? = try {
        val r = app.contentResolver
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        r.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 1400) sample *= 2
        var b = r.openInputStream(u)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        }
        val rot = try {
            r.openInputStream(u)?.use {
                when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> 90f
                    3 -> 180f
                    8 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) {
            0f
        }
        if (b != null && rot != 0f) b = android.graphics.Bitmap.createBitmap(b, 0, 0, b.width, b.height,
            android.graphics.Matrix().apply { postRotate(rot) }, true)
        b?.let { bm -> java.io.ByteArrayOutputStream().also { bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray() }
    } catch (e: Exception) {
        null
    }

    private fun rawBytes(app: Context, u: android.net.Uri, max: Long): ByteArray? = try {
        app.contentResolver.openInputStream(u)?.use { i ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                total += n
                if (total > max) return@use null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    } catch (e: Exception) {
        null
    }

    private fun toast(ctx: Context, t: String) = main.post { Toast.makeText(ctx, t, Toast.LENGTH_LONG).show() }

    /** Chiku picked photos/videos (or the song) → copy here for preview + send to Komal one by one */
    fun sendMedia(ctx: Context, uris: List<android.net.Uri>, isSong: Boolean) {
        val app = ctx.applicationContext
        val her = her(app)
        outbox.execute {
            var sent = 0
            var skipped = 0
            for ((i, u) in uris.withIndex()) {
                val mime = app.contentResolver.getType(u).orEmpty()
                val (kind, bytes) = when {
                    isSong || mime.startsWith("audio") -> "song" to rawBytes(app, u, 7_000_000L)
                    mime.startsWith("video") -> "vid" to rawBytes(app, u, 7_500_000L)
                    else -> "img" to photoBytes(app, u)
                }
                if (bytes == null) {
                    skipped++
                    continue
                }
                val ext = when (kind) {
                    "img" -> "jpg"
                    "vid" -> "mp4"
                    else -> android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "mp3"
                }
                val name = if (kind == "song") "song.$ext" else "p_${System.currentTimeMillis()}_${100 + i}.$ext"
                if (kind == "song") (dir(app).listFiles() ?: emptyArray()).filter { it.name.startsWith("song.") }.forEach { it.delete() }
                try {
                    File(dir(app), name).writeBytes(bytes)          // so the preview shows it too
                } catch (e: Exception) {
                }
                toast(app, "📤 ${i + 1}/${uris.size} — $her ko bhej raha hoon…")
                val d = JSONObject().put("k", "media").put("name", name)
                    .put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                if (sendBlocking(app, d)) {
                    sent++
                    prefs(app).edit().putInt("sent", prefs(app).getInt("sent", 0) + 1).apply()
                } else skipped++
            }
            toast(app, if (skipped == 0) "✅ $sent bhej diye! $her ko 12 baje hi dikhenge 🔒" else
                "✅ $sent bheje · ⚠️ $skipped nahi gaye (bahut badi file ya internet) — dobara try karo")
            main.post { CoupleActivity.current?.refresh() }
        }
    }

    fun pick(a: Activity, song: Boolean) = a.startActivity(Intent(a, BdayPickActivity::class.java).putExtra("song", song))

    fun on(app: Context, d: JSONObject, from: String) {
        val couple = CoupleActivity::class.java
        when (d.optString("k")) {
            "media" -> {
                // secret: saved quietly, shown only in the surprise
                val name = File(d.optString("name")).name
                if (name.isEmpty() || !(IMG.matches(name) || VID.matches(name) || AUD.matches(name))) return
                try {
                    if (name.startsWith("song.")) (dir(app).listFiles() ?: emptyArray()).filter { it.name.startsWith("song.") }.forEach { it.delete() }
                    File(dir(app), name).writeBytes(android.util.Base64.decode(d.optString("data"), android.util.Base64.NO_WRAP))
                    CoupleLink.send(app, "bday", JSONObject().put("k", "got"))
                } catch (e: Exception) {
                }
            }
            "got" -> {
                prefs(app).edit().putInt("got", prefs(app).getInt("got", 0) + 1).apply()
                main.post { CoupleActivity.current?.refresh() }
            }
            "msg" -> {
                prefs(app).edit().putString("msg", d.optString("text")).apply()
                // secret until her birthday: no notification before midnight
                if (isToday()) CoupleLink.notify(app, "💌  $from ka birthday message aaya!", "Tap karke surprise kholo 🎂", 496,
                    Intent(app, BdayActivity::class.java))
            }
            "seen" -> {
                CoupleLink.buzz(app, longArrayOf(0, 150, 100, 150))
                CoupleLink.notify(app, "🎂  $from ne surprise khol liya! 🥹", "Abhi call karke wish kar do 📞💖", 497, Intent(app, couple))
            }
            "blow" -> CoupleLink.notify(app, "🕯️  $from ne candles bujha di!", "Aur aankhein band karke ek wish maangi ✨", 498, Intent(app, couple))
            "thanks" -> {
                CoupleLink.buzz(app, longArrayOf(0, 300, 120, 300))
                CoupleLink.notify(app, "💖  $from ka thank you", d.optString("text"), 499, Intent(app, couple))
                Voice.say(app, "$from ने आपको थैंक यू भेजा है")
            }
        }
    }

    // ---------------------------------------------------------------- 🎵 Birthday tune (music box, made in code)
    @Volatile
    var tunePlaying = false

    fun playTune(onDone: () -> Unit) {
        if (tunePlaying) return
        tunePlaying = true
        Thread({
            val rate = 22050
            val g4 = 392.0; val a4 = 440.0; val b4 = 493.88; val c5 = 523.25; val d5 = 587.33
            val e5 = 659.25; val f5 = 698.46; val g5 = 783.99
            val notes = listOf(
                g4 to .75, g4 to .25, a4 to 1.0, g4 to 1.0, c5 to 1.0, b4 to 2.0,
                g4 to .75, g4 to .25, a4 to 1.0, g4 to 1.0, d5 to 1.0, c5 to 2.0,
                g4 to .75, g4 to .25, g5 to 1.0, e5 to 1.0, c5 to 1.0, b4 to 1.0, a4 to 2.0,
                f5 to .75, f5 to .25, e5 to 1.0, c5 to 1.0, d5 to 1.0, c5 to 3.0
            )
            val beat = 0.42
            val tail = (rate * 0.6).toInt()
            val total = (notes.sumOf { it.second } * beat * rate).toInt() + tail
            val pcm = FloatArray(total)
            var pos = 0
            for ((f, d) in notes) {
                val len = (d * beat * rate).toInt()
                val ring = len + tail
                for (i in 0 until ring) {
                    if (pos + i >= total) break
                    val t = i.toDouble() / rate
                    val env = minOf(1.0, i / (rate * 0.008)) * exp(-t * 2.6)
                    val v = sin(2 * PI * f * t) * 0.55 + sin(4 * PI * f * t) * 0.22 + sin(6 * PI * f * t) * 0.08 +
                        sin(2 * PI * f / 2 * t) * 0.12
                    pcm[pos + i] += (v * env).toFloat()
                }
                pos += len
            }
            val out = ShortArray(total) { (pcm[it].coerceIn(-1f, 1f) * 12000).toInt().toShort() }
            try {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(out.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
                track.write(out, 0, out.size)
                track.play()
                Thread.sleep(total * 1000L / rate + 200)
                track.stop()
                track.release()
            } catch (e: Exception) {
            }
            tunePlaying = false
            main.post(onDone)
        }, "chinu-bday-tune").start()
    }

    private fun mediaButtons(a: Activity, card: LinearLayout, btn: (String, () -> Unit) -> Unit) {
        val sent = prefs(a).getInt("sent", 0)
        val got = prefs(a).getInt("got", 0)
        val songName = song(a)?.let { "✅" } ?: "—"
        card.addView(Ui.label(a, "📸 Bheje: $sent · ✅ ${her(a)} ke phone pe pahunche: $got · 🎵 Gaana: $songName", 13f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(a, 8f), 0, 0)
        })
        btn("📸 Photos / videos chuno (12 baje dikhenge)") { pick(a, false) }
        btn("🎵 Gaana chuno (mombatti bujhte hi bajega)") { pick(a, true) }
    }

    // ---------------------------------------------------------------- 🎁 countdown / surprise card (Couple screen)
    private fun two(n: Long) = if (n < 10) "0$n" else "$n"

    fun card(a: Activity, body: LinearLayout) {
        if (!upcoming() && !isToday()) return
        val dp = { v: Float -> Ui.dp(a, v) }
        val isChiku = chiku(a)
        val h = her(a)
        body.addView(Ui.label(a, if (isToday()) "🎂 BIRTHDAY" else "🎁 SURPRISE", 12.5f, 0xFF8A4F9E.toInt(), true).apply {
            letterSpacing = 0.1f
            setPadding(dp(4f), dp(22f), 0, dp(8f))
        })
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFFF7EB3.toInt(), 0xFFB06AB3.toInt(), 0xFF6A5ACD.toInt()))
                .apply { cornerRadius = dp(24f).toFloat() }
            setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
        }
        val title = TextView(a).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val timer = TextView(a).apply {
            setTextColor(Color.WHITE)
            textSize = 34f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            setPadding(0, dp(6f), 0, dp(6f))
        }
        card.addView(title)
        card.addView(timer)
        val btn: (String, () -> Unit) -> Unit = { text, click ->
            card.addView(Ui.glassButton(a, text, click).apply { textSize = 15f },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        }
        if (isToday()) {
            title.text = if (isChiku) "🎉 Aaj $h ka birthday hai!" else "🎉 Happy Birthday $h! 🎂"
            timer.text = "💖 19 · 11 💖"
            btn(if (isChiku) "🎁 Surprise dekho" else "🎁 Apna surprise kholo") { open(a, isChiku) }
            if (isChiku) {
                btn("✍️ Birthday message likho / badlo") { writeMessage(a) }
                mediaButtons(a, card, btn)
            }
        } else {
            title.text = if (isChiku) "🎂 $h ka birthday — 12 baje surprise khulega" else "🎁 Kuch bahut special aa raha hai…"
            val tick = object : Runnable {
                override fun run() {
                    val left = startMillis() - System.currentTimeMillis()
                    if (left <= 0) {
                        timer.text = "🎉 00:00:00"
                        CoupleActivity.current?.refresh()
                        return
                    }
                    val s = left / 1000
                    timer.text = "⏳ ${two(s / 3600)}:${two(s / 60 % 60)}:${two(s % 60)}"
                    timer.postDelayed(this, 1000)   // stops by itself once the card is gone
                }
            }
            tick.run()
            if (isChiku) {
                btn("✍️ Birthday message likho (12 baje khulega)") { writeMessage(a) }
                mediaButtons(a, card, btn)
                btn("👀 Preview — $h ko kya dikhega") { open(a, true) }
            } else btn("🔒 Locked hai… 12 baje tak intezaar 😉") {
                Toast.makeText(a, listOf("Itni jaldi kya hai? 😄", "Nahi bataungi 🤐", "Thoda sabar… 🎁", "Bas kuch ghante aur 💖").random(),
                    Toast.LENGTH_SHORT).show()
            }
        }
        body.addView(card)
    }
}

// ==================================================================== 🎉 Confetti + balloons
class BdayConfetti(ctx: Context) : View(ctx) {
    private class P(var x: Float, var y: Float, var vx: Float, var vy: Float, var rot: Float, var vr: Float,
                    val color: Int, val kind: Int, val size: Float, var life: Float)

    private val ps = ArrayList<P>()
    private val rnd = Random()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val colors = intArrayOf(0xFFFF4F8B.toInt(), 0xFFFFD166.toInt(), 0xFF06D6A0.toInt(), 0xFF4CC9F0.toInt(),
        0xFFB388FF.toInt(), 0xFFFF9F1C.toInt(), Color.WHITE)
    private var last = 0L
    private var gentle = true

    init {
        isClickable = false
    }

    fun burst(n: Int = 160) {
        val w = width.toFloat().coerceAtLeast(1f)
        for (i in 0 until n) {
            val ang = rnd.nextFloat() * PI.toFloat() * 2
            val sp = 6f + rnd.nextFloat() * 18f
            ps.add(P(w / 2, height * 0.35f, kotlin.math.cos(ang) * sp, kotlin.math.sin(ang) * sp - 8f, rnd.nextFloat() * 360,
                rnd.nextFloat() * 20 - 10, colors[rnd.nextInt(colors.size)], if (rnd.nextInt(6) == 0) 2 else 0,
                10f + rnd.nextFloat() * 14f, 1f))
        }
        invalidate()
    }

    private fun spawn() {
        val w = width.toFloat()
        if (w <= 0) return
        if (rnd.nextInt(4) == 0) ps.add(P(rnd.nextFloat() * w, -20f, rnd.nextFloat() * 2 - 1, 2f + rnd.nextFloat() * 3, rnd.nextFloat() * 360,
            rnd.nextFloat() * 10 - 5, colors[rnd.nextInt(colors.size)], 0, 9f + rnd.nextFloat() * 10, 1f))
        if (rnd.nextInt(45) == 0) ps.add(P(rnd.nextFloat() * w, height + 80f, 0f, -(2f + rnd.nextFloat() * 2), 0f, 0f,
            colors[rnd.nextInt(colors.size - 1)], 1, 34f + rnd.nextFloat() * 22, 1f))
        if (rnd.nextInt(60) == 0) ps.add(P(rnd.nextFloat() * w, height + 40f, 0f, -(1.5f + rnd.nextFloat() * 2), 0f, 0f,
            Color.WHITE, 2, 30f + rnd.nextFloat() * 20, 1f))
    }

    override fun onDraw(c: Canvas) {
        val now = System.currentTimeMillis()
        if (now - last > 16) {
            last = now
            if (gentle) spawn()
            val it = ps.iterator()
            while (it.hasNext()) {
                val p = it.next()
                when (p.kind) {
                    0 -> {
                        p.vy += 0.35f
                        p.vx *= 0.985f
                        if (p.vy > 6f) p.vy = 6f
                    }
                    else -> p.vx = sin((now + p.size * 100) / 700.0).toFloat() * 0.8f
                }
                p.x += p.vx
                p.y += p.vy
                p.rot += p.vr
                if (p.y > height + 120 || p.y < -200 || p.x < -100 || p.x > width + 100) it.remove()
            }
        }
        for (p in ps) {
            when (p.kind) {
                0 -> {
                    paint.color = p.color
                    c.save()
                    c.rotate(p.rot, p.x, p.y)
                    c.drawRect(p.x - p.size / 2, p.y - p.size / 4, p.x + p.size / 2, p.y + p.size / 4, paint)
                    c.restore()
                }
                1 -> {
                    paint.color = p.color
                    paint.alpha = 225
                    c.drawOval(p.x - p.size * 0.8f, p.y - p.size, p.x + p.size * 0.8f, p.y + p.size, paint)
                    paint.alpha = 255
                    paint.color = 0x88FFFFFF.toInt()
                    paint.strokeWidth = 2f
                    c.drawLine(p.x, p.y + p.size, p.x + sin(p.y / 30.0).toFloat() * 8, p.y + p.size * 2.6f, paint)
                    paint.color = 0x55FFFFFF
                    c.drawOval(p.x - p.size * 0.45f, p.y - p.size * 0.7f, p.x - p.size * 0.1f, p.y - p.size * 0.2f, paint)
                }
                else -> {
                    text.textSize = p.size
                    c.drawText("💖", p.x, p.y, text)
                }
            }
        }
        postInvalidateOnAnimation()
    }
}

// ==================================================================== 🎂 Cake with candles
class CakeView(ctx: Context, private val name: String) : View(ctx) {
    var blown = false
        private set
    var onBlow: (() -> Unit)? = null
    private var blownAt = 0L
    private val rnd = Random()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sprinkles = List(40) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), rnd.nextInt(5).toFloat()) }
    private val sprinkleColors = intArrayOf(0xFFFF4F8B.toInt(), 0xFFFFD166.toInt(), 0xFF06D6A0.toInt(), 0xFF4CC9F0.toInt(), 0xFFB388FF.toInt())

    fun blow() {
        if (blown) return
        blown = true
        blownAt = System.currentTimeMillis()
        onBlow?.invoke()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) blow()
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        // plate
        p.shader = null
        p.color = 0x55FFFFFF
        c.drawOval(cx - w * 0.42f, h * 0.86f, cx + w * 0.42f, h * 0.98f, p)
        // bottom tier
        val bw = w * 0.36f
        val bTop = h * 0.56f
        val bBot = h * 0.92f
        p.shader = LinearGradient(0f, bTop, 0f, bBot, 0xFFFFB3C6.toInt(), 0xFFFF7EA8.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(cx - bw, bTop, cx + bw, bBot), 18f, 18f, p)
        // top tier
        val tw = w * 0.25f
        val tTop = h * 0.33f
        p.shader = LinearGradient(0f, tTop, 0f, bTop, 0xFFFFF1E6.toInt(), 0xFFFFD6E0.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(cx - tw, tTop, cx + tw, bTop + 6f), 16f, 16f, p)
        p.shader = null
        // icing drips
        p.color = Color.WHITE
        c.drawRect(cx - bw, bTop, cx + bw, bTop + h * 0.04f, p)
        var x = cx - bw + 14f
        var k = 0
        while (x < cx + bw - 10f) {
            c.drawCircle(x, bTop + h * 0.04f, 9f + (k % 3) * 4f, p)
            x += 26f
            k++
        }
        p.color = 0xFFFF4F8B.toInt()
        c.drawRect(cx - tw, tTop, cx + tw, tTop + h * 0.03f, p)
        // sprinkles on the bottom tier
        for (s in sprinkles) {
            p.color = sprinkleColors[s[2].toInt()]
            val sx = cx - bw + 16f + s[0] * (bw * 2 - 32f)
            val sy = bTop + h * 0.09f + s[1] * (bBot - bTop - h * 0.12f)
            c.drawRoundRect(RectF(sx, sy, sx + 10f, sy + 4f), 2f, 2f, p)
        }
        // name on the cake
        p.color = 0xFF8A1E4E.toInt()
        p.textAlign = Paint.Align.CENTER
        p.textSize = h * 0.09f
        p.typeface = Typeface.create("cursive", Typeface.BOLD)
        c.drawText(name, cx, (bTop + bBot) / 2 + h * 0.05f, p)
        // candles
        val n = 5
        val now = System.currentTimeMillis()
        for (i in 0 until n) {
            val x0 = cx - tw * 0.7f + i * (tw * 1.4f / (n - 1))
            val cTop = tTop - h * 0.15f
            p.color = if (i % 2 == 0) 0xFF4CC9F0.toInt() else 0xFFFFD166.toInt()
            c.drawRoundRect(RectF(x0 - 6f, cTop, x0 + 6f, tTop + 2f), 4f, 4f, p)
            p.color = 0x88FFFFFF.toInt()
            for (sY in 0..3) c.drawRect(x0 - 6f, cTop + 8f + sY * (h * 0.035f), x0 + 6f, cTop + 12f + sY * (h * 0.035f), p)
            if (!blown) {
                val fl = 1f + (rnd.nextFloat() - 0.5f) * 0.25f
                val fy = cTop - 4f
                p.shader = RadialGradient(x0, fy - 14f, 46f, 0x66FFD166, 0x00FFD166, Shader.TileMode.CLAMP)
                c.drawCircle(x0, fy - 14f, 46f, p)
                p.shader = null
                val path = Path().apply {
                    moveTo(x0, fy - 30f * fl)
                    quadTo(x0 + 11f, fy - 8f, x0, fy)
                    quadTo(x0 - 11f, fy - 8f, x0, fy - 30f * fl)
                }
                p.color = 0xFFFF9F1C.toInt()
                c.drawPath(path, p)
                p.color = 0xFFFFF3B0.toInt()
                c.drawOval(x0 - 4f, fy - 14f, x0 + 4f, fy - 2f, p)
            } else {
                val t = (now - blownAt) / 1000f
                if (t < 3f) for (j in 0..3) {
                    val tt = t - j * 0.25f
                    if (tt <= 0f) continue
                    p.color = Color.argb(((1f - tt / 3f).coerceIn(0f, 1f) * 140).toInt(), 200, 200, 210)
                    c.drawCircle(x0 + sin((tt * 4 + i).toDouble()).toFloat() * 8f, cTop - 10f - tt * 50f, 6f + tt * 6f, p)
                }
            }
        }
        postInvalidateOnAnimation()
    }
}

// ==================================================================== 🎂 The surprise screen
class BdayActivity : Activity() {
    companion object {
        @Volatile
        var active = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var confetti: BdayConfetti
    private lateinit var cake: CakeView
    private lateinit var hint: TextView
    private lateinit var gifts: LinearLayout
    private var preview = false
    @Volatile
    private var listening = false
    private var panel: View? = null
    private fun dp(v: Float) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = intent.getBooleanExtra("preview", false)
        if (!preview && !Bday.isToday()) {
            finish()
            return
        }
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = 0xFFFF7EB3.toInt()
        val her = Bday.her(this)
        val him = Bday.him(this)
        Bday.copyAssets(this)

        root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFFFF7EB3.toInt(), 0xFFB06AB3.toInt(), 0xFF3A1C71.toInt()))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20f), dp(36f), dp(20f), dp(30f))
        }
        fun tv(t: String, size: Float, bold: Boolean = false, cursive: Boolean = false) = TextView(this).apply {
            text = t
            textSize = size
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = when {
                cursive -> Typeface.create("cursive", Typeface.BOLD)
                bold -> Typeface.DEFAULT_BOLD
                else -> Typeface.DEFAULT
            }
            setShadowLayer(12f, 0f, 2f, 0x66000000)
        }
        col.addView(tv(if (preview) "👀 Preview (${her} ko aisa dikhega)" else "19 November 💫", 14f))
        col.addView(tv("Happy Birthday", 40f, cursive = true).apply { setPadding(0, dp(6f), 0, 0) })
        col.addView(tv("$her 💖", 46f, bold = true))
        col.addView(tv("$him aur Chinu ki taraf se 🎁", 15f).apply { setPadding(0, dp(4f), 0, dp(10f)) })
        cake = CakeView(this, her)
        col.addView(cake, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(270f)))
        hint = tv("🎵 Chinu gaana gaa rahi hai…", 16f, bold = true).apply { setPadding(0, dp(10f), 0, dp(6f)) }
        col.addView(hint)
        gifts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            alpha = 0f
        }
        col.addView(gifts, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        buildGifts()
        scroll.addView(col)
        root.addView(scroll)
        confetti = BdayConfetti(this)
        root.addView(confetti, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val close = TextView(this).apply {
            text = "✕"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            setOnClickListener { finish() }
        }
        root.addView(close, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        setContentView(root)

        cake.onBlow = { blownOut() }
        if (!preview && !Bday.prefs(this).getBoolean("seen", false)) {
            Bday.prefs(this).edit().putBoolean("seen", true).apply()
            CoupleLink.send(this, "bday", JSONObject().put("k", "seen"))
        }
        handler.postDelayed({
            confetti.burst(220)
            buzz(longArrayOf(0, 80, 60, 80, 60, 250))
            Bday.playTune {
                if (isFinishing) return@playTune
                Voice.say(this, "हैप्पी बर्थडे ${if (her == "Komal") "कोमल" else her}! आज का दिन सिर्फ़ आपका है। अब मोमबत्तियाँ बुझाओ, फूँक मारो!")
                hint.text = "🌬️ Phone ke mic pe phoonk maaro… ya candles ko tap karo 🕯️"
                pulse(hint)
                handler.postDelayed({ startBlowListener() }, 4500)
            }
        }, 700)
    }

    override fun onResume() {
        super.onResume()
        active = true
        WakeService.pause()
    }

    override fun onPause() {
        super.onPause()
        active = false
        listening = false
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSong()
        active = false
        listening = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun buzz(p: LongArray) = try {
        getSystemService(android.os.Vibrator::class.java)?.vibrate(android.os.VibrationEffect.createWaveform(p, -1))
    } catch (e: Exception) {
    }

    private fun pulse(v: View) {
        ObjectAnimator.ofFloat(v, "scaleX", 1f, 1.08f, 1f).apply { duration = 900; repeatCount = 3 }.start()
        ObjectAnimator.ofFloat(v, "scaleY", 1f, 1.08f, 1f).apply { duration = 900; repeatCount = 3 }.start()
    }

    /** Blow into the mic → candles go out */
    private fun startBlowListener() {
        if (cake.blown || listening || isFinishing) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        listening = true
        Thread({
            val rate = 16000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096))
            } catch (e: Exception) {
                listening = false
                return@Thread
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                listening = false
                return@Thread
            }
            val buf = ShortArray(800)
            var loud = 0
            try {
                rec.startRecording()
                while (listening && !cake.blown) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var s = 0.0
                    for (i in 0 until n) s += buf[i].toDouble() * buf[i]
                    val rms = sqrt(s / n)
                    loud = if (rms > 3000) loud + 1 else maxOf(0, loud - 1)
                    if (loud >= 5) {
                        handler.post { cake.blow() }
                        break
                    }
                }
            } catch (e: Exception) {
            } finally {
                try {
                    rec.stop()
                } catch (e: Exception) {
                }
                rec.release()
                listening = false
            }
        }, "chinu-blow").start()
    }

    private fun blownOut() {
        listening = false
        confetti.burst(300)
        handler.postDelayed({ confetti.burst(200) }, 600)
        buzz(longArrayOf(0, 200, 100, 200, 100, 500))
        hint.text = "✨ Yay! Ab aankhein band karo aur ek wish maango… ✨\n\n👇 Tumhare gifts 👇"
        if (songFile() != null) {
            // Chiku's song + photos start together
            handler.postDelayed({
                if (isFinishing) return@postDelayed
                playSong()
                giftMemories()
            }, 1600)
        } else Voice.say(this, "वाह! अब आँखें बंद करके एक विश माँगो। और नीचे आपके गिफ्ट्स हैं!")
        if (!preview) CoupleLink.send(this, "bday", JSONObject().put("k", "blow"))
        gifts.visibility = View.VISIBLE
        gifts.animate().alpha(1f).setDuration(900).start()
    }

    // ---------------------------------------------------------------- 🎁 gifts
    private fun buildGifts() {
        val him = Bday.him(this)
        fun giftRow(l: Pair<String, () -> Unit>, r: Pair<String, () -> Unit>) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun box(t: String, click: () -> Unit) = TextView(this).apply {
                text = t
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(8f), dp(18f), dp(8f), dp(18f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(20f).toFloat()
                    setColor(0x33FFFFFF)
                    setStroke(dp(1.5f), 0x88FFFFFF.toInt())
                }
                setOnClickListener {
                    if (!text.startsWith("✅")) text = "✅ " + text.toString().removePrefix("🎁 ")
                    click()
                }
            }
            row.addView(box(l.first, l.second), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(box(r.first, r.second), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10f) })
            gifts.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })
        }
        giftRow("🎁 $him ka\nmessage 💌" to { giftMessage() }, "🎁 Chinu ki\nkavita ✍️" to { giftPoem() })
        giftRow("🎁 Birthday\ncoupons 🎟️" to { giftCoupons() }, "🎁 Hamari\nyaadein 📸" to { giftMemories() })
        val again = TextView(this).apply {
            text = "🎵 Gaana phir se   ·   🎉 Aur confetti"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(18f), 0, dp(6f))
            setOnClickListener {
                confetti.burst(200)
                Bday.playTune {}
            }
        }
        gifts.addView(again)
        if (songFile() != null) {
            val sb = TextView(this).apply {
                text = "🎵 Gaana chalao"
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(8f), 0, dp(6f))
                setOnClickListener { if (player != null) stopSong() else playSong() }
            }
            songBtn = sb
            gifts.addView(sb)
        }
        if (!preview) gifts.addView(Ui.primaryButton(this, "💖 $him ko thank you bhejo") { thanks() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })
    }

    /** A pretty card that pops up over the screen */
    private fun showPanel(title: String, content: View) {
        gen++
        panel?.let { root.removeView(it) }
        val shade = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { closePanel() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(26f).toFloat()
                setColor(0xFFFFF5F8.toInt())
            }
            setPadding(dp(20f), dp(20f), dp(20f), dp(16f))
            isClickable = true
        }
        card.addView(TextView(this).apply {
            text = title
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF8A1E4E.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12f))
        })
        card.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        card.addView(TextView(this).apply {
            text = "❤️ Band karo"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFFB0306A.toInt())
            setPadding(0, dp(14f), 0, dp(4f))
            setOnClickListener { closePanel() }
        })
        shade.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.72f).toInt(),
            Gravity.CENTER).apply { setMargins(dp(18f), 0, dp(18f), 0) })
        root.addView(shade, root.childCount - 1)
        card.scaleX = 0.6f
        card.scaleY = 0.6f
        card.alpha = 0f
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(380).start()
        confetti.burst(90)
        panel = shade
    }

    private fun closePanel() {
        gen++
        panel?.let { root.removeView(it) }
        panel = null
    }

    private fun textBody(t: String) = ScrollView(this).apply {
        addView(TextView(this@BdayActivity).apply {
            text = t
            textSize = 17f
            setTextColor(0xFF3B1F4A.toInt())
            setLineSpacing(0f, 1.25f)
        })
    }

    private fun giftMessage() {
        showPanel("💌 ${Bday.him(this)} ka message", textBody(Bday.message(this)))
    }

    private fun giftPoem() {
        val her = Bday.her(this)
        val him = Bday.him(this)
        val fallback = "Aaj chaand bhi thoda zyada chamka hai,\nkyunki aaj mere $her ka din aaya hai 🌙\n\n" +
            "Hawaon ne bhi gungunaya hai,\nphoolon ne bhi muskuraya hai 🌸\n\n" +
            "Har khushi tumhare kadmon mein ho,\nhar sapna tumhari aankhon mein sach ho ✨\n\n" +
            "$him ki duniya tum ho,\naur Chinu ki sabse pyaari dost bhi tum ho 💖\n\nHappy Birthday $her! 🎂"
        val tv = TextView(this).apply {
            text = "✍️ Chinu likh rahi hai…"
            textSize = 17f
            setTextColor(0xFF3B1F4A.toInt())
            setLineSpacing(0f, 1.25f)
        }
        showPanel("✍️ Chinu ki kavita", ScrollView(this).apply { addView(tv) })
        var done = false
        handler.postDelayed({
            if (!done) {
                done = true
                tv.text = fallback
            }
        }, 9000)
        val prompt = "Write a beautiful, original birthday poem (8-10 short lines) in Hinglish (Hindi in English letters, simple words) for $her " +
            "on her birthday, from Chinu (a sweet AI friend) on behalf of her boyfriend $him who loves her a lot. Warm, magical, a bit playful, " +
            "3-4 emojis. End with 'Happy Birthday $her! 🎂'. Reply with ONLY the poem."
        try {
            Brain.ask(this, prompt, "hi-IN") { t ->
                handler.post {
                    if (done) return@post
                    done = true
                    val clean = t.trim()
                    tv.text = if (clean.length > 40) clean else fallback
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun giftCoupons() {
        val him = Bday.him(this)
        val list = listOf("👑 Aaj ka din: jo tum bolo wahi hoga", "🍫 Unlimited chocolates for a week", "🎬 Date night — tum plan karo, main pay karunga",
            "🤗 100 jhappiyaan (kabhi bhi redeem karo)", "🙊 Ek baar ka 'tum sahi ho' — bina behas ke")
        if (!preview && !Bday.prefs(this).getBoolean("coupons", false)) {
            Bday.prefs(this).edit().putBoolean("coupons", true).apply()
            for (c in list) Pyaar.addCoupon(this, "🎂 $c", him)
        }
        showPanel("🎟️ Birthday coupons", textBody(list.joinToString("\n\n") { "🎟️ $it" } +
            "\n\n👛 Ye sab tumhare wallet mein daal diye (Couple → 💗 Dil Se → Mere coupons). Jab chaho use karo — $him ko nibhana padega 😄"))
    }

    @Volatile
    private var gen = 0

    // ---------------------------------------------------------------- 🎵 Chiku's song + 📸 his photos / videos
    private var player: android.media.MediaPlayer? = null
    private var songBtn: TextView? = null

    private fun songFile(): File? = Bday.song(this)

    private fun playSong() {
        val f = songFile() ?: return
        stopSong()
        try {
            player = android.media.MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(f.absolutePath)
                isLooping = true
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
            songBtn?.text = "⏸️ Gaana roko"
        } catch (e: Exception) {
            stopSong()
        }
    }

    private fun stopSong() {
        try {
            player?.stop()
        } catch (e: Exception) {
        }
        try {
            player?.release()
        } catch (e: Exception) {
        }
        player = null
        songBtn?.text = "🎵 Gaana chalao"
    }

    private val captions = listOf("💖", "🥹", "✨", "💞", "🌸", "😘", "🎂", "💫")

    private fun giftMemories() {
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val video = android.widget.VideoView(this).apply { visibility = View.GONE }
        val frame = FrameLayout(this).apply {
            clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = dp(18f).toFloat(); setColor(0xFF2A1238.toInt()) }
        }
        frame.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        val cap = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xFF8A1E4E.toInt())
            setPadding(0, dp(8f), 0, 0)
            text = "📸 Yaadein dhoondh rahi hoon…"
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        box.addView(cap)
        showPanel("📸 Hamari yaadein", box)
        val my = gen
        Thread({
            val items = ArrayList<File>(Bday.media(this))
            val app = ArrayList<File>()
            val today = LocalDate.now().toEpochDay()
            for (d in today downTo today - 120) {
                try {
                    SelfieStreak.photo(SelfieStreak.mine(this, d))?.let { app.add(it) }
                    SelfieStreak.photo(SelfieStreak.theirs(this, d))?.let { app.add(it) }
                    app.addAll(SelfieStreak.moments(this, d, "me"))
                    app.addAll(SelfieStreak.moments(this, d, "them"))
                } catch (e: Exception) {
                }
                if (app.size >= 30) break
            }
            app.reverse()
            items.addAll(app)
            handler.post {
                if (items.isEmpty()) cap.text = "Abhi photos nahi hain… aaj se bahut saari yaadein banayenge 📸💖"
                else showSlide(items, 0, my, img, video, cap)
            }
        }, "chinu-bday-slides").start()
    }

    /** One slide: photo (3.2 s, slow zoom) or video (plays to the end, muted so the song keeps going) */
    private fun showSlide(items: List<File>, i: Int, my: Int, img: ImageView, video: android.widget.VideoView, cap: TextView) {
        if (gen != my || isFinishing) {
            try {
                video.stopPlayback()
            } catch (e: Exception) {
            }
            return
        }
        val k = i % items.size
        val f = items[k]
        cap.text = "${captions[k % captions.size]}  ${k + 1} / ${items.size}"
        val next = { showSlide(items, i + 1, my, img, video, cap) }
        if (Bday.isVideo(f)) {
            img.visibility = View.GONE
            video.visibility = View.VISIBLE
            var moved = false
            val go = {
                if (!moved) {
                    moved = true
                    next()
                }
            }
            video.setOnPreparedListener { mp ->
                try {
                    mp.setVolume(0f, 0f)
                } catch (e: Exception) {
                }
                video.start()
                handler.postDelayed({ go() }, minOf(maxOf(mp.duration.toLong(), 2000L), 20_000L) + 300)
            }
            video.setOnCompletionListener { go() }
            video.setOnErrorListener { _, _, _ -> go(); true }
            video.setVideoPath(f.absolutePath)
            return
        }
        try {
            video.stopPlayback()
        } catch (e: Exception) {
        }
        video.visibility = View.GONE
        img.visibility = View.VISIBLE
        Thread({
            val b = try {
                SelfieStreak.load(f, 1100)
            } catch (e: Exception) {
                null
            }
            handler.post {
                if (gen != my || isFinishing) return@post
                if (b != null) {
                    img.alpha = 0f
                    img.scaleX = 1f
                    img.scaleY = 1f
                    img.setImageBitmap(b)
                    img.animate().alpha(1f).setDuration(700).start()
                    img.animate().scaleX(1.08f).scaleY(1.08f).setDuration(3300).start()
                    handler.postDelayed({ next() }, 3300)
                } else next()
            }
        }, "chinu-bday-img").start()
    }

    private fun thanks() {
        val him = Bday.him(this)
        val e = EditText(this).apply {
            hint = "Thank you $him… 💖"
            setText("Thank you $him, ye sabse pyaara surprise hai 🥹💖")
        }
        AlertDialog.Builder(this).setTitle("💖 $him ko thank you").setView(e)
            .setPositiveButton("Bhejo 💌") { _, _ ->
                val t = e.text.toString().trim()
                if (t.isEmpty()) return@setPositiveButton
                CoupleLink.send(this, "bday", JSONObject().put("k", "thanks").put("text", t)) { ok ->
                    handler.post {
                        Toast.makeText(this, if (ok) "Bhej diya 💖" else "Internet nahi hai 😕", Toast.LENGTH_SHORT).show()
                        if (ok) confetti.burst(150)
                    }
                }
            }.setNegativeButton("Rehne do", null).show()
    }
}

// ==================================================================== 📸 Chiku picks photos / videos / the song
class BdayPickActivity : Activity() {
    private var song = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        song = intent.getBooleanExtra("song", false)
        if (savedInstanceState != null) return
        val i = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
        if (song) i.type = "audio/*"
        else {
            i.type = "*/*"
            i.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(Intent.createChooser(i, if (song) "🎵 Gaana chuno" else "📸 Photos / videos chuno"), 7)
        } catch (e: Exception) {
            finish()
        }
    }

    @Deprecated("old API, still works")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uris = ArrayList<android.net.Uri>()
        if (resultCode == RESULT_OK && data != null) {
            val clip = data.clipData
            if (clip != null) for (k in 0 until clip.itemCount) clip.getItemAt(k).uri?.let { uris.add(it) }
            else data.data?.let { uris.add(it) }
        }
        if (uris.isNotEmpty()) {
            Toast.makeText(this, if (song) "🎵 Gaana bhej raha hoon…" else "📤 ${uris.size} bhej raha hoon… (app band mat karna)", Toast.LENGTH_LONG).show()
            Bday.sendMedia(this, if (song) uris.take(1) else uris, song)
        }
        finish()
    }
}
