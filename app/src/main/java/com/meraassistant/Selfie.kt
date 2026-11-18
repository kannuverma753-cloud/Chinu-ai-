package com.meraassistant

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 📸🔥 Selfie Streak — like Snapchat streaks, but just for the two of you.
 *  · Both post one selfie every day → the streak grows.
 *  · Evening + late-night warnings if today's selfie is missing.
 *  · Miss a day and the streak breaks… BUT you get ONE rescue chance the next day:
 *    each of you gets a small photo dare (truth-or-dare style). Both complete it before
 *    midnight and the streak comes back to life. 🛟
 *  · Your partner's selfie stays blurred until you post yours. 🤫
 */
object SelfieStreak {
    /** Photo dares for the rescue (English, Hinglish). %s = partner's name */
    val DARES = listOf(
        "Take a selfie with your funniest face 🤪" to "Sabse funny face banake selfie lo 🤪",
        "Make a heart with your hands and click it 🫶" to "Haathon se dil banake photo lo 🫶",
        "Write “%s ❤️” on paper and click it ✍️" to "Kaagaz pe “%s ❤️” likh ke photo lo ✍️",
        "Click something that reminds you of %s 💭" to "Aisi cheez ki photo lo jo %s ki yaad dilaye 💭",
        "Selfie blowing a flying kiss to %s 😘" to "%s ko flying kiss dete hue selfie lo 😘",
        "Click the sky right now ☁️" to "Abhi ke aasmaan ki photo lo ☁️",
        "Selfie with eyes closed, making a wish for us 🌠" to "Aankhein band karke hum dono ke liye wish maangte hue selfie 🌠",
        "Selfie wearing something %s likes 👕" to "%s ki pasand ka kuch pehen ke selfie lo 👕",
        "Draw a tiny doodle of both of you and click it 🎨" to "Hum dono ka chhota sa doodle bana ke photo lo 🎨",
        "Selfie with your cutest ‘sorry’ face 🥺" to "Sabse cute ‘sorry’ wala face banake selfie lo 🥺",
        "Click your favourite snack right now 🍫" to "Abhi apne favourite snack ki photo lo 🍫",
        "Hug a pillow pretending it's %s, and take a selfie 🤗" to "Takiye ko %s samajh ke jhappi do aur selfie lo 🤗"
    )

    fun today(): Long = LocalDate.now().toEpochDay()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("selfie", Context.MODE_PRIVATE)
    private fun dir(ctx: Context) = File(ctx.filesDir, "selfies").apply { mkdirs() }

    // kind: "d" = daily selfie, "x" = rescue dare photo · who: "me" / "them"
    // value = local file path, or "posted" when we know they posted but the photo isn't here yet
    private fun get(ctx: Context, kind: String, who: String, day: Long) = prefs(ctx).getString("${kind}_${who}_$day", "").orEmpty()
    private fun put(ctx: Context, kind: String, who: String, day: Long, v: String) =
        prefs(ctx).edit().putString("${kind}_${who}_$day", v).apply()

    fun mine(ctx: Context, day: Long = today()) = get(ctx, "d", "me", day)
    fun theirs(ctx: Context, day: Long = today()) = get(ctx, "d", "them", day)
    fun dareMine(ctx: Context, day: Long) = get(ctx, "x", "me", day)
    fun dareTheirs(ctx: Context, day: Long) = get(ctx, "x", "them", day)

    fun posted(v: String) = v.isNotEmpty()
    fun photo(v: String): File? = if (v.isEmpty() || v == "posted") null else File(v).takeIf { it.exists() }

    /** a day counts when both posted a selfie — or both finished the rescue dare for it */
    fun complete(ctx: Context, day: Long) =
        (posted(mine(ctx, day)) && posted(theirs(ctx, day))) || (posted(dareMine(ctx, day)) && posted(dareTheirs(ctx, day)))

    fun runEndingAt(ctx: Context, day: Long): Int {
        var d = day
        var n = 0
        while (n < 5000 && complete(ctx, d)) {
            n++
            d--
        }
        return n
    }

    /** Current streak (today counts once both selfies are in) */
    fun streak(ctx: Context): Int {
        val t = today()
        return if (complete(ctx, t)) runEndingAt(ctx, t) else runEndingAt(ctx, t - 1)
    }

    fun best(ctx: Context): Int {
        val b = maxOf(prefs(ctx).getInt("best", 0), streak(ctx))
        prefs(ctx).edit().putInt("best", b).apply()
        return b
    }

    /** Yesterday was missed but the streak before it can still be saved today */
    fun rescueDay(): Long = today() - 1
    fun canRescue(ctx: Context): Boolean {
        val y = rescueDay()
        return !(posted(mine(ctx, y)) && posted(theirs(ctx, y))) && runEndingAt(ctx, y - 1) > 0
    }

    fun rescued(ctx: Context) = posted(dareMine(ctx, rescueDay())) && posted(dareTheirs(ctx, rescueDay()))

    /** How long the broken streak was (what you win back) */
    fun brokenStreak(ctx: Context) = runEndingAt(ctx, rescueDay() - 1)

    fun isBoy(ctx: Context): Boolean {
        Speaker.load(ctx)
        return Speaker.isPartner()
    }

    /** Same dares on both phones: one for Chiku, a different one for Komal */
    fun dareFor(ctx: Context, boy: Boolean, day: Long): String {
        val p = Profile.load(ctx)
        val i = Math.floorMod(day * 5 + (if (boy) 0 else 6), DARES.size.toLong()).toInt()
        val (en, hi) = DARES[i]
        val target = if (boy) p.name else p.partner
        return (if (Ui.hinglish(ctx)) hi else en).replace("%s", target)
    }

    fun millisToMidnight(): Long =
        LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - System.currentTimeMillis()

    // ---------- 📷 images ----------
    /** Load a photo (fixing camera rotation) scaled down to maxPx */
    fun load(f: File, maxPx: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxPx) s *= 2
        val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
        val deg = when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (b == null || deg == 0f) b
        else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
    } catch (e: Exception) {
        null
    }

    private fun scaled(b: Bitmap, maxPx: Int): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= maxPx) return b
        val k = maxPx.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
    }

    /** The photo the camera just took → save it, then send it to the partner */
    fun postFromCamera(ctx: Context, raw: File, kind: String, day: Long): Boolean {
        val b = load(raw, 1440) ?: return false
        val small = scaled(b, 720)
        val bytes = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 74, it) }.toByteArray()
        val f = File(dir(ctx), "${kind}_me_$day.jpg")
        f.writeBytes(bytes)
        put(ctx, kind, "me", day, f.path)
        // 💾 a full-quality copy goes to the phone's gallery (Pictures/Chinu)
        if (galleryOn(ctx)) {
            val big = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
            saveToGallery(ctx, big, kind, "me", day)
        }
        raw.delete()
        cleanup(ctx)
        sendPhoto(ctx, kind, day, bytes)
        return true
    }

    private fun sendPhoto(ctx: Context, kind: String, day: Long, bytes: ByteArray, done: ((Boolean) -> Unit)? = null) {
        // a tiny "I posted" ping first (always arrives), then the photo itself
        CoupleLink.send(ctx, "selfie_done", JSONObject().put("day", day).put("k", kind))
        CoupleLink.send(ctx, "selfie", JSONObject().put("day", day).put("k", kind)
            .put("img", Base64.encodeToString(bytes, Base64.NO_WRAP)), done)
    }

    fun onDone(app: Context, d: JSONObject, from: String) {
        val day = d.optLong("day", -1L)
        val kind = if (d.optString("k") == "x") "x" else "d"
        if (day < 0) return
        val was = get(app, kind, "them", day)
        if (was.isEmpty()) {
            put(app, kind, "them", day, "posted")
            announce(app, kind, day, from)
        }
        SelfieActivity.current?.render()
        CoupleActivity.current?.refresh()
    }

    fun onSelfie(app: Context, d: JSONObject, from: String) {
        val day = d.optLong("day", -1L)
        val kind = if (d.optString("k") == "x") "x" else "d"
        val img = d.optString("img")
        if (day < 0 || img.isEmpty()) return
        val was = get(app, kind, "them", day)
        try {
            val f = File(dir(app), "${kind}_them_$day.jpg")
            val bytes = Base64.decode(img, Base64.NO_WRAP)
            f.writeBytes(bytes)
            put(app, kind, "them", day, f.path)
            if (galleryOn(app) && gal(app, kind, "them", day).isEmpty()) Thread { saveToGallery(app, bytes, kind, "them", day) }.start()
        } catch (e: Exception) {
            if (was.isEmpty()) put(app, kind, "them", day, "posted")
        }
        if (was.isEmpty()) announce(app, kind, day, from)
        SelfieActivity.current?.render()
        CoupleActivity.current?.refresh()
    }

    private fun announce(app: Context, kind: String, day: Long, from: String) {
        if (SelfieActivity.current != null) return
        val open = Intent(app, SelfieActivity::class.java)
        if (kind == "x") {
            val txt = if (complete(app, day)) Ui.t(app, "Streak saved! 🔥 ${streak(app)} is back 🛟💞", "Streak bach gayi! 🔥 ${streak(app)} wapas aa gayi 🛟💞")
            else Ui.t(app, "Now finish your dare before midnight 🛟", "Ab aap apna dare raat 12 se pehle poora karo 🛟")
            CoupleLink.notify(app, "🛟  " + Ui.t(app, "$from finished the rescue dare!", "$from ne rescue dare poora kar diya!"), txt, 472, open)
        } else if (day == today()) {
            val txt = if (posted(mine(app))) Ui.t(app, "Today's done! 🔥 ${streak(app)} — tap to see 💖", "Aaj ki streak pakki! 🔥 ${streak(app)} — dekho 💖")
            else Ui.t(app, "Your turn! Their selfie unlocks when you send yours 🤫", "Ab aapki baari! Aapki selfie bhejte hi unki dikhegi 🤫")
            CoupleLink.notify(app, "📸  " + Ui.t(app, "$from sent today's selfie", "$from ne aaj ki selfie bhej di"), txt, 471, open)
        }
        CoupleLink.buzz(app, longArrayOf(0, 70, 60, 70))
    }

    fun onNudge(app: Context, from: String) {
        if (posted(mine(app))) return
        CoupleLink.notify(app, "📸🔥  " + Ui.t(app, "$from is waiting for your selfie!", "$from aapki selfie ka intezaar kar rahe hain!"),
            Ui.t(app, "Don't let the 🔥 ${streak(app)} streak die 😱", "🔥 ${streak(app)} wali streak mat todna 😱"), 473,
            Intent(app, SelfieActivity::class.java))
        CoupleLink.buzz(app, longArrayOf(0, 60, 50, 60, 50, 60))
    }

    // ---------- catching up on photos a phone missed (relay keeps files ~3 h) ----------
    fun hello(ctx: Context, reply: Boolean = false) {
        val have = JSONArray()
        val t = today()
        for (day in t - 2..t) for (k in listOf("d", "x")) if (photo(get(ctx, k, "them", day)) != null) have.put("${k}_$day")
        for (day in t - 2..t) for (f in moments(ctx, day, "them")) have.put("m_${day}_${momentId(f)}")
        CoupleLink.send(ctx, "selfie_hello", JSONObject().put("have", have).put("reply", reply).put("mv", 1))
    }

    fun onHello(ctx: Context, d: JSONObject) {
        val have = HashSet<String>()
        val a = d.optJSONArray("have") ?: JSONArray()
        for (i in 0 until a.length()) have.add(a.optString(i))
        val t = today()
        for (day in t - 2..t) for (k in listOf("d", "x")) {
            val f = photo(get(ctx, k, "me", day)) ?: continue
            if ("${k}_$day" in have) continue
            try {
                CoupleLink.send(ctx, "selfie", JSONObject().put("day", day).put("k", k)
                    .put("img", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)))
            } catch (e: Exception) {
            }
        }
        // 📷 extra photos the partner's phone missed (only if their app knows about them)
        if (d.has("mv")) for (day in t - 2..t) for (f in moments(ctx, day, "me")) {
            val id = momentId(f)
            if ("m_${day}_$id" in have) continue
            try {
                sendMoment(ctx, day, id, f.readBytes())
            } catch (e: Exception) {
            }
        }
        if (!d.optBoolean("reply")) hello(ctx, reply = true)
    }

    /**
     * Keep Chinu light: inside the app only the last 7 days stay (needed for syncing);
     * older photos are read back from the phone's gallery. Without a gallery copy we keep ~2 months.
     */
    private fun cleanup(ctx: Context) {
        val t = today()
        dir(ctx).listFiles()?.forEach { f ->
            val parts = f.nameWithoutExtension.split('_')        // d_me_20410
            val day = parts.lastOrNull()?.toLongOrNull() ?: return@forEach
            val inGallery = parts.size == 3 && gal(ctx, parts[0], parts[1], day).isNotEmpty()
            if (day < t - 60 || (day < t - 7 && inGallery)) f.delete()
        }
    }

    // ---------- 💾 phone gallery ----------
    fun galleryOn(ctx: Context) = prefs(ctx).getBoolean("toGallery", true)
    fun setGallery(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("toGallery", on).apply()
    private fun gal(ctx: Context, kind: String, who: String, day: Long) = prefs(ctx).getString("g_${kind}_${who}_$day", "").orEmpty()

    /** Saves into Pictures/Chinu (Android 10+ needs no storage permission for this) */
    private fun saveToGallery(ctx: Context, bytes: ByteArray, kind: String, who: String, day: Long) {
        if (gal(ctx, kind, who, day).isNotEmpty()) return
        try {
            Speaker.load(ctx)
            val p = Profile.load(ctx)
            val name = if (who == "me") Speaker.name(p) else Speaker.other(p)
            val r = ctx.contentResolver
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Chinu_${LocalDate.ofEpochDay(day)}_${if (kind == "x") "dare_" else ""}$name.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Chinu")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return
            r.openOutputStream(uri)?.use { it.write(bytes) }
            v.clear()
            v.put(MediaStore.Images.Media.IS_PENDING, 0)
            r.update(uri, v, null, null)
            prefs(ctx).edit().putString("g_${kind}_${who}_$day", uri.toString()).apply()
        } catch (e: Exception) {
        }
    }

    /** Is there a photo for this slot (in the app or in the gallery)? */
    fun hasImage(ctx: Context, kind: String, who: String, day: Long) =
        photo(get(ctx, kind, who, day)) != null || gal(ctx, kind, who, day).isNotEmpty()

    /** The photo for this slot — from the app if it's recent, otherwise from the gallery */
    fun image(ctx: Context, kind: String, who: String, day: Long, maxPx: Int): Bitmap? {
        photo(get(ctx, kind, who, day))?.let { return load(it, maxPx) }
        val g = gal(ctx, kind, who, day)
        if (g.isEmpty()) return null
        return try {
            val uri = Uri.parse(g)
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var sm = 1
            while (maxOf(o.outWidth, o.outHeight) / (sm * 2) >= maxPx) sm *= 2
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sm }) }
        } catch (e: Exception) {
            null
        }
    }

    /** For a day: the pair of photos to show (the rescue-dare photos when the day was saved by a dare) */
    fun dayKind(ctx: Context, day: Long): String =
        if (!(posted(mine(ctx, day)) && posted(theirs(ctx, day))) && posted(dareMine(ctx, day)) && posted(dareTheirs(ctx, day))) "x" else "d"

    /** Days (oldest first) that have at least one photo, within the last [back] days */
    fun reelDays(ctx: Context, back: Int): List<Long> {
        val t = today()
        return (back.toLong() downTo 0L).map { t - it }.filter { d ->
            listOf("d", "x").any { k -> hasImage(ctx, k, "me", d) || hasImage(ctx, k, "them", d) }
        }
    }

    // ---------- 📷 Aaj ke pal: many photos a day (not just the one streak selfie) ----------
    const val MAX_MOMENTS = 50

    private fun momentRoot(ctx: Context) = File(dir(ctx), "moments")
    private fun momentDir(ctx: Context, day: Long) = File(momentRoot(ctx), day.toString())

    /** A day's extra photos for "me" / "them", oldest first */
    fun moments(ctx: Context, day: Long, who: String): List<File> =
        momentDir(ctx, day).listFiles()?.filter { it.name.startsWith("${who}_") && it.name.endsWith(".jpg") }
            ?.sortedBy { it.name }.orEmpty()

    fun momentCount(ctx: Context, day: Long, who: String) = moments(ctx, day, who).size

    fun hasMoments(ctx: Context, day: Long) = momentDir(ctx, day).listFiles()?.any { it.name.endsWith(".jpg") } == true

    fun momentId(f: File) = f.nameWithoutExtension.substringAfter('_')

    private var momentSeq = 0

    /** Load a picked photo (gallery / photo picker) with its rotation fixed */
    fun loadUri(ctx: Context, uri: Uri, maxPx: Int): Bitmap? = try {
        val r = ctx.contentResolver
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var sm = 1
        while (maxOf(o.outWidth, o.outHeight) / (sm * 2) >= maxPx) sm *= 2
        val b = r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sm }) }
        val deg = try {
            r.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) {
            0f
        }
        if (b == null || deg == 0f) b
        else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
    } catch (e: Throwable) {
        null
    }

    /** Save one extra photo for [day] and send it to the partner */
    fun postMoment(ctx: Context, b: Bitmap, day: Long = today()): Boolean {
        if (momentCount(ctx, day, "me") >= MAX_MOMENTS) return false
        return try {
            val small = scaled(b, 720)
            val bytes = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 74, it) }.toByteArray()
            val id = System.currentTimeMillis().toString() + String.format(Locale.ENGLISH, "%03d", (momentSeq++) % 1000)
            File(momentDir(ctx, day).apply { mkdirs() }, "me_$id.jpg").writeBytes(bytes)
            if (galleryOn(ctx)) {
                val big = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
                saveMomentToGallery(ctx, big, "me", day, id)
            }
            cleanupMoments(ctx)
            sendMoment(ctx, day, id, bytes)
            true
        } catch (e: Throwable) {
            false
        }
    }

    /** Photos go out one by one (with a small gap) so the free relay never gets flooded */
    private val outbox = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun sendMoment(ctx: Context, day: Long, id: String, bytes: ByteArray) {
        val app = ctx.applicationContext
        outbox.execute {
            val img = Base64.encodeToString(bytes, Base64.NO_WRAP)
            for (attempt in 0..2) {
                val latch = java.util.concurrent.CountDownLatch(1)
                var ok = false
                CoupleLink.send(app, "moment", JSONObject().put("day", day).put("id", id).put("img", img)) {
                    ok = it
                    latch.countDown()
                }
                try {
                    latch.await(90, java.util.concurrent.TimeUnit.SECONDS)
                    Thread.sleep(if (ok) 1500L else 8000L)
                } catch (e: InterruptedException) {
                }
                if (ok) break
            }
        }
    }

    private var lastMomentBuzz = 0L

    fun onMoment(app: Context, d: JSONObject, from: String) {
        val day = d.optLong("day", -1L)
        val id = d.optString("id").filter { it.isLetterOrDigit() }
        val img = d.optString("img")
        if (day < 0 || id.isEmpty() || img.isEmpty()) return
        val f = File(momentDir(app, day).apply { mkdirs() }, "them_$id.jpg")
        if (f.exists()) return
        try {
            val bytes = Base64.decode(img, Base64.NO_WRAP)
            f.writeBytes(bytes)
            if (galleryOn(app)) Thread { saveMomentToGallery(app, bytes, "them", day, id) }.start()
        } catch (e: Exception) {
            return
        }
        if (SelfieActivity.current == null && day == today()) {
            val n = momentCount(app, day, "them")
            CoupleLink.notify(app, "📷  " + Ui.t(app, "$from shared a new photo", "$from ne nayi photo bheji"),
                Ui.t(app, "$n photos from $from today — tap to see 💖", "Aaj $from ki $n photos aa gayi — dekho 💖"), 478,
                Intent(app, SelfieActivity::class.java))
            if (System.currentTimeMillis() - lastMomentBuzz > 60_000L) {
                lastMomentBuzz = System.currentTimeMillis()
                CoupleLink.buzz(app, longArrayOf(0, 60, 50, 60))
            }
        }
        SelfieActivity.current?.render()
    }

    /** Extra photos stay inside the app for 30 days (the gallery copy stays forever) */
    private fun cleanupMoments(ctx: Context) {
        val t = today()
        momentRoot(ctx).listFiles()?.forEach { d ->
            val day = d.name.toLongOrNull() ?: return@forEach
            if (day < t - 30) d.deleteRecursively()
        }
    }

    private fun saveMomentToGallery(ctx: Context, bytes: ByteArray, who: String, day: Long, id: String) {
        try {
            Speaker.load(ctx)
            val p = Profile.load(ctx)
            val name = if (who == "me") Speaker.name(p) else Speaker.other(p)
            val r = ctx.contentResolver
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Chinu_${LocalDate.ofEpochDay(day)}_pal_${name}_${id.takeLast(7)}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Chinu")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return
            r.openOutputStream(uri)?.use { it.write(bytes) }
            v.clear()
            v.put(MediaStore.Images.Media.IS_PENDING, 0)
            r.update(uri, v, null, null)
        } catch (e: Exception) {
        }
    }

    // ---------- ⏰ 9 AM rescue alert · 8 PM warning · 11 PM last call ----------
    private val SLOTS = listOf(9, 20, 23)

    fun schedule(ctx: Context) {
        val now = java.util.Calendar.getInstance()
        var at: java.util.Calendar? = null
        var slot = SLOTS[0]
        for (dayAdd in 0..1) for (h in SLOTS) {
            if (at != null) break
            val c = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_YEAR, dayAdd)
                set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
            }
            if (c.after(now)) {
                at = c
                slot = h
            }
        }
        val whenAt = at ?: return
        val pi = PendingIntent.getBroadcast(ctx, 4700,
            Intent(ctx, LoveExtrasReceiver::class.java).setAction(LoveExtrasReceiver.SELFIE).putExtra("slot", slot),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            ctx.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenAt.timeInMillis, pi)
        } catch (e: Exception) {
        }
    }

    fun fire(ctx: Context, slot: Int) {
        schedule(ctx)
        if (!CoupleLink.paired(ctx)) return
        Speaker.load(ctx)
        val other = Speaker.other(Profile.load(ctx))
        val open = Intent(ctx, SelfieActivity::class.java)
        val s = streak(ctx)
        // 🛟 the rescue chance
        if (canRescue(ctx) && !rescued(ctx) && slot != 20) {
            val b = brokenStreak(ctx)
            if (slot == 9) CoupleLink.notify(ctx, "💔  " + Ui.t(ctx, "Your 🔥 $b streak broke…", "Aapki 🔥 $b ki streak toot gayi…"),
                Ui.t(ctx, "…but you can save it! Both finish a tiny dare today 🛟", "…par abhi bacha sakte ho! Aaj dono ek chhota sa dare poora karo 🛟"), 474, open)
            else CoupleLink.notify(ctx, "🛟⏳  " + Ui.t(ctx, "Last hour to save 🔥 $b!", "🔥 $b bachane ka aakhri ghanta!"),
                Ui.t(ctx, "Finish the rescue dare before midnight", "Raat 12 se pehle rescue dare poora karo"), 474, open)
            return
        }
        // 🎬 Sunday morning: this week's reel
        if (slot == 9 && java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.SUNDAY &&
            reelDays(ctx, 6).size >= 3
        ) {
            CoupleLink.notify(ctx, "🎬  " + Ui.t(ctx, "This week's selfie reel is ready!", "Is hafte ki selfie reel taiyaar hai!"),
                Ui.t(ctx, "Watch it with $other 💞", "$other ke saath dekho 💞"), 477,
                Intent(ctx, ReelActivity::class.java).putExtra("back", 6))
        }
        if (slot == 9) return
        val meDone = posted(mine(ctx))
        val themDone = posted(theirs(ctx))
        when {
            !meDone && (s > 0 || themDone) -> CoupleLink.notify(ctx,
                if (slot == 23) "⚠️🔥  " + Ui.t(ctx, "1 hour left! Your $s streak can break", "Sirf 1 ghanta! $s ki streak toot sakti hai")
                else "⏳🔥  " + Ui.t(ctx, "Your $s streak is in danger!", "Aapki $s ki streak khatre mein hai!"),
                if (themDone) Ui.t(ctx, "$other already sent theirs 👀 Send yours!", "$other ne apni bhej di hai 👀 Aap bhi bhejo!")
                else Ui.t(ctx, "Today's selfie is still missing 📸", "Aaj ki selfie abhi tak nahi aayi 📸"), 475, open)
            meDone && !themDone && slot == 23 -> CoupleLink.notify(ctx,
                "⏳  " + Ui.t(ctx, "$other hasn't sent today's selfie", "$other ne aaj ki selfie nahi bheji"),
                Ui.t(ctx, "Tap to nudge them before midnight 👉", "Raat 12 se pehle yaad dilao 👉"), 476, open)
        }
    }
}

/** Lets the camera app save the photo straight into Chinu (no gallery clutter, no storage permission) */
class SelfieProvider : ContentProvider() {
    companion object {
        const val AUTH = "com.meraassistant.selfies"
        fun file(ctx: Context) = File(ctx.cacheDir, "selfie_cam.jpg")
        fun uri(): Uri = Uri.parse("content://$AUTH/selfie_cam.jpg")
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        return ParcelFileDescriptor.open(file(ctx), ParcelFileDescriptor.parseMode(mode))
    }

    override fun getType(uri: Uri) = "image/jpeg"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val ctx = context
        val c = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        c.addRow(arrayOf<Any>("selfie_cam.jpg", if (ctx != null) file(ctx).length() else 0L))
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

/** ✨ Scroll effects: cards fade + shrink as they leave the top, and slide/fade in from the bottom */
object ScrollFx {
    fun attach(scroll: ScrollView, content: ViewGroup) {
        val apply = { run(scroll, content) }
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> apply() }
        content.viewTreeObserver.addOnGlobalLayoutListener { apply() }
    }

    private fun run(scroll: ScrollView, content: ViewGroup) {
        val h = scroll.height.toFloat()
        if (h <= 0f) return
        val zone = h * 0.16f
        val y0 = scroll.scrollY
        val d = scroll.resources.displayMetrics.density
        for (i in 0 until content.childCount) {
            val v = content.getChildAt(i)
            val top = (v.top + content.top - y0).toFloat()
            val bottom = top + v.height
            val leaveTop = (bottom / zone).coerceIn(0f, 1f)           // 1 = fully on screen at the top edge
            val enterBottom = ((h - top) / zone).coerceIn(0f, 1f)     // 1 = fully risen from the bottom
            val a = minOf(leaveTop, enterBottom)
            v.alpha = 0.08f + 0.92f * a
            val s = 0.9f + 0.1f * a
            v.scaleX = s
            v.scaleY = s
            v.translationY = (1f - enterBottom) * 34f * d
        }
    }
}

/** 📸🔥 The selfie streak screen */
class SelfieActivity : Activity() {
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var fx: LoveFx
    private val h = Handler(Looper.getMainLooper())
    private val countdowns = ArrayList<TextView>()
    private val anims = ArrayList<ValueAnimator>()
    private var justPosted = false
    private var firstRender = true
    private val hand: Typeface by lazy { Typeface.create("casual", Typeface.NORMAL) }
    private val script: Typeface by lazy { Typeface.create("cursive", Typeface.BOLD) }
    private val thumbCache = object : android.util.LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val thumbPool = java.util.concurrent.Executors.newFixedThreadPool(2)

    private val tick = object : Runnable {
        override fun run() {
            val left = hms(SelfieStreak.millisToMidnight())
            countdowns.forEach { it.text = left }
            h.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Speaker.load(this)
        val root = FrameLayout(this)
        root.addView(GlassBackground(this, intArrayOf(0xFFFFB37A.toInt(), 0xFFFF8FB8.toInt(), 0xFFFFD58A.toInt(), 0xFFE0A8FF.toInt())))
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(dp(18f), dp(44f), dp(18f), dp(60f))
        }
        scroll.addView(body)
        root.addView(scroll)
        fx = LoveFx(this)
        root.addView(fx, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        ScrollFx.attach(scroll, body)
        if (intent.getBooleanExtra("snap", false)) root.post { addMomentCamera() }
    }

    override fun onResume() {
        super.onResume()
        current = this
        CoupleLink.start(this)
        val sp = getSharedPreferences("selfie", MODE_PRIVATE)
        if (CoupleLink.paired(this) && System.currentTimeMillis() - sp.getLong("lastHello", 0) > 10 * 60_000L) {
            sp.edit().putLong("lastHello", System.currentTimeMillis()).apply()
            SelfieStreak.hello(this)
        }
        render()
        h.post(tick)
    }

    override fun onPause() {
        h.removeCallbacks(tick)
        if (current === this) current = null
        super.onPause()
    }

    override fun onDestroy() {
        anims.forEach { it.cancel() }
        thumbPool.shutdownNow()
        super.onDestroy()
    }

    private fun dp(v: Float) = Ui.dp(this, v)

    private fun hms(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.ENGLISH, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }

    fun render() {
        anims.forEach { it.cancel() }
        anims.clear()
        body.removeAllViews()
        countdowns.clear()
        val p = Profile.load(this)
        val me = Speaker.name(p)
        val other = Speaker.other(p)
        val streak = SelfieStreak.streak(this)
        val best = SelfieStreak.best(this)
        val mine = SelfieStreak.mine(this)
        val theirs = SelfieStreak.theirs(this)
        val meDone = SelfieStreak.posted(mine)
        val themDone = SelfieStreak.posted(theirs)

        body.addView(Ui.label(this, "📸 " + Ui.t(this, "Selfie Streak", "Selfie Streak"), 28f, 0xFF3B1F4A.toInt(), true))
        body.addView(Ui.label(this, Ui.t(this, "One selfie each, every day. Don't let the fire go out!", "Roz dono ki ek selfie. Aag bujhne mat dena!"),
            14f, 0xFF6B4E7A.toInt()).apply { setPadding(dp(2f), 0, 0, dp(14f)) })

        // ---------- 🔥 the flame ----------
        val flameCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Ui.glass(this@SelfieActivity, 32f, 0x88)
            setPadding(dp(16f), dp(18f), dp(16f), dp(18f))
        }
        val flame = TextView(this).apply {
            text = if (streak > 0) "🔥" else "🪵"
            textSize = 70f
            gravity = Gravity.CENTER
        }
        flameCard.addView(flame)
        val num = Ui.label(this, "0", 46f, 0xFFE8590C.toInt(), true).apply { gravity = Gravity.CENTER }
        flameCard.addView(num)
        flameCard.addView(Ui.label(this, Ui.t(this, "day streak", "din ki streak") + if (best > streak && best > 0) "  ·  🏆 $best" else "",
            15f, 0xFF3B1F4A.toInt(), true).apply { gravity = Gravity.CENTER })
        val status = when {
            meDone && themDone -> Ui.t(this, "Today is locked in! 💞", "Aaj ki streak pakki! 💞")
            meDone -> Ui.t(this, "Waiting for $other's selfie ⏳", "$other ki selfie ka intezaar ⏳")
            themDone -> Ui.t(this, "$other posted! Your turn 👀", "$other ne bhej di! Ab aapki baari 👀")
            else -> Ui.t(this, "Today's selfies are still missing", "Aaj ki selfies abhi baaki hain")
        }
        flameCard.addView(Ui.label(this, status, 14f, 0xFF6B4E7A.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, dp(6f), 0, 0) })
        if (!(meDone && themDone)) {
            val cd = Ui.label(this, hms(SelfieStreak.millisToMidnight()), 13f, 0xFFB0476E.toInt(), true).apply { gravity = Gravity.CENTER }
            flameCard.addView(Ui.label(this, Ui.t(this, "time left today", "aaj ka bacha hua time"), 11.5f, 0xFF8E7A99.toInt()).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(8f), 0, 0)
            })
            flameCard.addView(cd)
            countdowns.add(cd)
        }
        body.addView(flameCard)
        // flame flickers, number counts up
        if (streak > 0) {
            listOf(ObjectAnimator.ofFloat(flame, "scaleX", 1f, 1.14f), ObjectAnimator.ofFloat(flame, "scaleY", 1f, 1.18f),
                ObjectAnimator.ofFloat(flame, "rotation", -4f, 4f)).forEachIndexed { i, a ->
                a.duration = 620L + i * 90L
                a.repeatCount = ValueAnimator.INFINITE
                a.repeatMode = ValueAnimator.REVERSE
                a.start()
                anims.add(a)
            }
        }
        ValueAnimator.ofInt(if (firstRender) 0 else streak, streak).apply {
            duration = 900
            interpolator = DecelerateInterpolator()
            addUpdateListener { num.text = (it.animatedValue as Int).toString() }
            start()
            anims.add(this)
        }

        // ---------- 🛟 rescue ----------
        if (SelfieStreak.canRescue(this)) rescueCard(other)

        // ---------- today's polaroids ----------
        section(Ui.t(this, "TODAY", "AAJ"))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            gravity = Gravity.CENTER
        }
        val left = polaroid(me, mine, false, -3f)
        val right = polaroid(other, theirs, themDone && !meDone, 3.5f)
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(14f) })
        body.addView(row)
        if (firstRender) {
            pop(left, 150)
            pop(right, 300)
        }
        if (justPosted) {
            justPosted = false
            left.rotationY = 90f
            left.animate().rotationY(0f).setDuration(450).setInterpolator(DecelerateInterpolator()).start()
            fx.burst(if (themDone) "🔥" else "📸", if (themDone) 30 else 14)
        }

        if (!meDone) {
            body.addView(Ui.primaryButton(this, Ui.t(this, "📸  Take today's selfie  🐶🐱👑", "📸  Aaj ki selfie lo  🐶🐱👑")) { takePhoto("d") }.apply {
                textSize = 18f
                setPadding(0, dp(18f), 0, dp(18f))
                val pulse = ObjectAnimator.ofFloat(this, "scaleX", 1f, 1.04f).apply {
                    duration = 700; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE; start()
                }
                anims.add(pulse)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18f) })
        } else if (!themDone && CoupleLink.paired(this)) {
            body.addView(Ui.glassButton(this, Ui.t(this, "👉  Nudge $other", "👉  $other ko yaad dilao")) {
                CoupleLink.send(this, "selfie_nudge") { ok ->
                    Toast.makeText(this, if (ok) Ui.t(this, "Nudged 👀", "Yaad dila diya 👀") else Ui.t(this, "No internet 😕", "Internet nahi hai 😕"),
                        Toast.LENGTH_SHORT).show()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18f) })
        }

        momentsCard(me, other)
        gallery(me, other)
        if (firstRender && meDone && themDone) fx.burst("🔥", 18)
        firstRender = false
    }

    private fun section(t: String) {
        body.addView(Ui.label(this, t, 12.5f, 0xFF8A4F9E.toInt(), true).apply {
            letterSpacing = 0.1f
            setPadding(dp(4f), dp(24f), 0, dp(10f))
        })
    }

    /** A tilted white polaroid with the photo (or a blurred/locked one) and a handwritten name */
    private fun polaroid(name: String, value: String, locked: Boolean, tilt: Float): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = dp(6f).toFloat() }
            elevation = dp(6f).toFloat()
            setPadding(dp(8f), dp(8f), dp(8f), dp(4f))
            rotation = tilt
        }
        val frame = FrameLayout(this).apply { setBackgroundColor(0xFFF1E6EE.toInt()) }
        val size = (resources.displayMetrics.widthPixels - dp(36f) - dp(14f)) / 2 - dp(16f)
        val f = SelfieStreak.photo(value)
        if (f != null) {
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                // locked → tiny version stretched up = dreamy blur 🤫
                setImageBitmap(SelfieStreak.load(f, if (locked) 18 else 600))
            }
            frame.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (!locked) card.setOnClickListener { zoom({ SelfieStreak.load(f, 1200) }, name) }
        }
        val overlay = when {
            locked -> "🔒\n" + Ui.t(this, "Send yours\nto unlock", "Apni bhejo\nto khulegi")
            f == null && value.isNotEmpty() -> "📸✓\n" + Ui.t(this, "on its way…", "aa rahi hai…")
            f == null -> "❔\n" + Ui.t(this, "not yet", "abhi nahi")
            else -> ""
        }
        if (overlay.isNotEmpty()) frame.addView(TextView(this).apply {
            text = overlay
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(if (locked) 0xFFFFFFFF.toInt() else 0xFF8E7A99.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            if (locked) setShadowLayer(8f, 0f, 0f, 0xAA000000.toInt())
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        card.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, size))
        card.addView(TextView(this).apply {
            text = name
            textSize = 22f
            typeface = script
            gravity = Gravity.CENTER
            setTextColor(0xFF3B1F4A.toInt())
        })
        return card
    }

    private fun pop(v: View, delay: Long) {
        val r = v.rotation
        v.alpha = 0f
        v.translationY = dp(70f).toFloat()
        v.rotation = r * 4
        v.animate().alpha(1f).translationY(0f).rotation(r).setStartDelay(delay).setDuration(650)
            .setInterpolator(OvershootInterpolator(1.4f)).start()
    }

    private fun zoom(bitmap: () -> Bitmap?, name: String) {
        val img = ImageView(this).apply {
            adjustViewBounds = true
            setImageBitmap(bitmap())
        }
        android.app.AlertDialog.Builder(this).setTitle("📸  $name").setView(img).setPositiveButton("💖", null).show()
        img.scaleX = 0.6f
        img.scaleY = 0.6f
        img.animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(OvershootInterpolator()).start()
    }

    // ---------- 🛟 the rescue card ----------
    private fun rescueCard(other: String) {
        val day = SelfieStreak.rescueDay()
        val b = SelfieStreak.brokenStreak(this)
        val boy = SelfieStreak.isBoy(this)
        val myDare = SelfieStreak.dareFor(this, boy, day)
        val theirDare = SelfieStreak.dareFor(this, !boy, day)
        val iDid = SelfieStreak.posted(SelfieStreak.dareMine(this, day))
        val theyDid = SelfieStreak.posted(SelfieStreak.dareTheirs(this, day))
        val saved = iDid && theyDid
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                if (saved) intArrayOf(0xFF7BE0B0.toInt(), 0xFF5BC0EB.toInt()) else intArrayOf(0xFFFF5F7E.toInt(), 0xFFB06BFF.toInt())
            ).apply { cornerRadius = dp(28f).toFloat() }
            elevation = dp(6f).toFloat()
            setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
        }
        val white = 0xFFFFFFFF.toInt()
        if (saved) {
            card.addView(Ui.label(this, "🛟  " + Ui.t(this, "Streak saved! 🔥 $b is back", "Streak bach gayi! 🔥 $b wapas aa gayi"), 19f, white, true))
            card.addView(Ui.label(this, Ui.t(this, "You both did it 💞 Now keep it going today!", "Dono ne kar dikhaya 💞 Ab aaj ki selfie bhi bhejo!"),
                14f, white).apply { setPadding(0, dp(6f), 0, 0) })
        } else {
            val heart = Ui.label(this, "💔", 40f, white).apply { gravity = Gravity.CENTER }
            card.addView(heart)
            ObjectAnimator.ofFloat(heart, "scaleX", 1f, 1.2f).apply {
                duration = 480; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE; start(); anims.add(this)
            }
            ObjectAnimator.ofFloat(heart, "scaleY", 1f, 1.2f).apply {
                duration = 480; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE; start(); anims.add(this)
            }
            card.addView(Ui.label(this, Ui.t(this, "Yesterday was missed… the 🔥 $b streak broke!", "Kal ki selfie chhoot gayi… 🔥 $b ki streak toot gayi!"),
                18f, white, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(4f), 0, dp(4f)) })
            card.addView(Ui.label(this, Ui.t(this, "But you get ONE chance 🛟 Both finish your dare before midnight to bring it back:",
                "Par ek mauka hai 🛟 Raat 12 se pehle dono apna dare poora karo, streak wapas aa jayegi:"), 14f, white).apply {
                gravity = Gravity.CENTER
            })
            val cd = Ui.label(this, hms(SelfieStreak.millisToMidnight()), 26f, white, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8f), 0, dp(6f)) }
            card.addView(cd)
            countdowns.add(cd)
        }
        fun dareRow(who: String, dare: String, done: Boolean) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glass(this@SelfieActivity, 18f, 0x55)
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            addView(Ui.label(this@SelfieActivity, (if (done) "✅ " else "🎯 ") + who, 13f, white, true))
            addView(Ui.label(this@SelfieActivity, dare, 15.5f, white).apply { typeface = hand; setPadding(0, dp(4f), 0, 0) })
        }
        card.addView(dareRow(Ui.t(this, "Your dare", "Aapka dare"), myDare, iDid),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12f) })
        card.addView(dareRow(Ui.t(this, "$other's dare", "$other ka dare"), theirDare, theyDid),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        if (!iDid) {
            card.addView(TextView(this).apply {
                text = Ui.t(this@SelfieActivity, "📸  Do my dare", "📸  Mera dare poora karo")
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(0xFFB0276E.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = GradientDrawable().apply { setColor(white); cornerRadius = dp(24f).toFloat() }
                setPadding(0, dp(14f), 0, dp(14f))
                setOnClickListener { takePhoto("x") }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14f) })
        }
        body.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16f) })
        if (firstRender && !saved) {
            // a little "shake" to grab attention
            card.translationX = 0f
            ObjectAnimator.ofFloat(card, "translationX", 0f, -dp(10f).toFloat(), dp(10f).toFloat(), -dp(6f).toFloat(), dp(6f).toFloat(), 0f).apply {
                duration = 600; startDelay = 500; start()
            }
        }
    }

    // ---------- 📖 memories ----------
    private fun gallery(me: String, other: String) {
        val t = SelfieStreak.today()
        // 🎬 the reel
        val all = SelfieStreak.reelDays(this, 60)
        if (all.isNotEmpty()) {
            section(Ui.t(this, "🎬 OUR SELFIE REEL", "🎬 HAMARI SELFIE REEL"))
            val reel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF2A1B4D.toInt(), 0xFF7A2E6E.toInt()))
                    .apply { cornerRadius = dp(26f).toFloat() }
                elevation = dp(5f).toFloat()
                setPadding(dp(18f), dp(16f), dp(18f), dp(16f))
            }
            reel.addView(Ui.label(this, "▶️  " + Ui.t(this, "Watch our story", "Hamari kahani dekho"), 19f, 0xFFFFFFFF.toInt(), true))
            reel.addView(Ui.label(this, Ui.t(this, "${all.size} days of selfies as a slideshow ✨", "${all.size} din ki selfies, ek slideshow mein ✨"),
                13.5f, 0xDDFFFFFF.toInt()).apply { setPadding(0, dp(4f), 0, dp(10f)) })
            val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun reelBtn(label: String, back: Int) = TextView(this).apply {
                text = label
                textSize = 14.5f
                gravity = Gravity.CENTER
                setTextColor(0xFF2A1B4D.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = dp(20f).toFloat() }
                setPadding(0, dp(11f), 0, dp(11f))
                setOnClickListener { startActivity(Intent(this@SelfieActivity, ReelActivity::class.java).putExtra("back", back)) }
            }
            btns.addView(reelBtn(Ui.t(this, "This week", "Is hafte"), 6), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            btns.addView(reelBtn(Ui.t(this, "Full reel", "Poori reel"), 60),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10f) })
            reel.addView(btns)
            body.addView(reel)
        }
        // 💾 gallery switch
        body.addView(TextView(this).apply {
            fun label() = "💾  " + Ui.t(this@SelfieActivity, "Save photos to phone gallery: ", "Photos phone ki gallery mein save: ") +
                if (SelfieStreak.galleryOn(this@SelfieActivity)) "ON ✅" else "OFF"
            text = label()
            textSize = 13.5f
            setTextColor(0xFF6B4E7A.toInt())
            setPadding(dp(6f), dp(12f), dp(6f), 0)
            setOnClickListener {
                SelfieStreak.setGallery(this@SelfieActivity, !SelfieStreak.galleryOn(this@SelfieActivity))
                text = label()
            }
        })
        val days = (1L..30L).map { t - it }.filter { d ->
            listOf("d", "x").any { k -> SelfieStreak.hasImage(this, k, "me", d) || SelfieStreak.hasImage(this, k, "them", d) } ||
                SelfieStreak.hasMoments(this, d)
        }
        if (days.isEmpty()) return
        section(Ui.t(this, "📖 OUR SELFIE MEMORIES", "📖 HAMARI SELFIE YAADEIN"))
        val fmt = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)
        for (day in days) {
            val rescued = !(SelfieStreak.posted(SelfieStreak.mine(this, day)) && SelfieStreak.posted(SelfieStreak.theirs(this, day))) &&
                SelfieStreak.complete(this, day)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.glass(this@SelfieActivity, 22f, 0x55)
                setPadding(dp(12f), dp(10f), dp(12f), dp(12f))
            }
            val hasSelfies = listOf("d", "x").any { k -> SelfieStreak.hasImage(this, k, "me", day) || SelfieStreak.hasImage(this, k, "them", day) }
            val badge = when {
                !hasSelfies -> ""
                rescued -> "  🛟 " + Ui.t(this, "rescued", "bachayi")
                SelfieStreak.complete(this, day) -> "  🔥"
                else -> "  💔"
            }
            row.addView(Ui.label(this, LocalDate.ofEpochDay(day).format(fmt) + badge, 13f, 0xFF8A4F9E.toInt(), true))
            val extra = momentItems(day, me, other)
            if (extra.isNotEmpty()) {
                row.addView(Ui.label(this, "📷 " + Ui.t(this, "${extra.size} more photos", "${extra.size} aur photos"), 12f, 0xFF6B4E7A.toInt()).apply {
                    setPadding(0, dp(4f), 0, 0)
                })
            }
            val pics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8f), 0, 0) }
            val k = if (rescued) "x" else "d"
            for ((i, pair) in listOf(me to "me", other to "them").withIndex()) {
                val bmp = if (SelfieStreak.hasImage(this, k, pair.second, day)) SelfieStreak.image(this, k, pair.second, day, 260) else null
                val th = FrameLayout(this).apply {
                    background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = dp(14f).toFloat() }
                    clipToOutline = true
                }
                if (bmp != null) {
                    th.addView(ImageView(this).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageBitmap(bmp)
                    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    th.setOnClickListener { zoom({ SelfieStreak.image(this, k, pair.second, day, 1200) }, pair.first) }
                } else th.addView(Ui.label(this, "—", 20f, 0xFF8E7A99.toInt()).apply { gravity = Gravity.CENTER },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                th.addView(Ui.label(this, pair.first, 11f, 0xFFFFFFFF.toInt(), true).apply {
                    setShadowLayer(6f, 0f, 0f, 0xCC000000.toInt())
                    setPadding(dp(8f), dp(4f), 0, 0)
                })
                pics.addView(th, LinearLayout.LayoutParams(0, dp(130f), 1f).apply { if (i == 1) leftMargin = dp(8f) })
            }
            if (hasSelfies) row.addView(pics)
            if (extra.isNotEmpty()) row.addView(momentGrid(extra, 4, dp(24f)))
            body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })
        }
    }

    // ---------- 📷 aaj ke pal (many photos a day) ----------
    private fun momentItems(day: Long, me: String, other: String): List<Pair<File, String>> =
        (SelfieStreak.moments(this, day, "me").map { it to me } + SelfieStreak.moments(this, day, "them").map { it to other })
            .sortedBy { SelfieStreak.momentId(it.first) }

    private fun momentsCard(me: String, other: String) {
        val t = SelfieStreak.today()
        val mineN = SelfieStreak.momentCount(this, t, "me")
        val theirN = SelfieStreak.momentCount(this, t, "them")
        section(Ui.t(this, "📷 TODAY'S MOMENTS", "📷 AAJ KE PAL"))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glass(this@SelfieActivity, 26f, 0x77)
            setPadding(dp(16f), dp(14f), dp(16f), dp(16f))
        }
        card.addView(Ui.label(this, Ui.t(this, "Share as many photos as you like today — 10, 15 or even more! 💞",
            "Aaj jitni chahe utni photos bhejo — 10, 15 ya usse bhi zyada! 💞"), 14.5f, 0xFF3B1F4A.toInt(), true))
        card.addView(Ui.label(this, "$me: $mineN/${SelfieStreak.MAX_MOMENTS}   ·   $other: $theirN", 12.5f, 0xFF8A4F9E.toInt()).apply {
            setPadding(0, dp(4f), 0, dp(10f))
        })
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(Ui.glassButton(this, Ui.t(this, "📷  Click & send", "📷  Kheencho & bhejo")) { addMomentCamera() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btns.addView(Ui.glassButton(this, "🖼️  " + Ui.t(this, "Gallery (many)", "Gallery (kai saari)")) { pickMoments() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10f) })
        card.addView(btns)
        val items = momentItems(t, me, other)
        if (items.isNotEmpty()) card.addView(momentGrid(items, 3, dp(32f)))
        body.addView(card)
    }

    /** Square thumbnails in rows of [cols]; [sidePad] = horizontal padding of the box it sits in */
    private fun momentGrid(items: List<Pair<File, String>>, cols: Int, sidePad: Int): View {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12f), 0, 0) }
        val gap = dp(6f)
        val cell = (resources.displayMetrics.widthPixels - dp(36f) - sidePad - gap * (cols - 1)) / cols
        items.chunked(cols).forEachIndexed { r, chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until cols) {
                val box = FrameLayout(this)
                val item = chunk.getOrNull(c)
                if (item != null) {
                    box.background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = dp(12f).toFloat() }
                    box.clipToOutline = true
                    val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                    box.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    thumbInto(iv, item.first)
                    box.addView(Ui.label(this, item.second, 10f, 0xFFFFFFFF.toInt(), true).apply {
                        setShadowLayer(6f, 0f, 0f, 0xCC000000.toInt())
                        setPadding(dp(6f), dp(3f), 0, 0)
                    })
                    val f = item.first
                    box.setOnClickListener { zoom({ SelfieStreak.load(f, 1200) }, item.second) }
                }
                row.addView(box, LinearLayout.LayoutParams(0, cell, 1f).apply { if (c > 0) leftMargin = gap })
            }
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (r > 0) topMargin = gap
            })
        }
        return grid
    }

    /** Thumbnails load in the background so 50+ photos don't freeze the screen */
    private fun thumbInto(iv: ImageView, f: File) {
        val key = f.path
        iv.tag = key
        thumbCache.get(key)?.let {
            iv.setImageBitmap(it)
            return
        }
        try {
            thumbPool.execute {
                val b = SelfieStreak.load(f, 260) ?: return@execute
                thumbCache.put(key, b)
                runOnUiThread { if (iv.tag == key) iv.setImageBitmap(b) }
            }
        } catch (e: Exception) {
        }
    }

    private fun canAddMoment(): Boolean {
        if (!CoupleLink.paired(this)) {
            Toast.makeText(this, Ui.t(this, "Connect both phones first in 💞", "Pehle 💞 mein dono phones jodo"), Toast.LENGTH_LONG).show()
            return false
        }
        if (SelfieStreak.momentCount(this, SelfieStreak.today(), "me") >= SelfieStreak.MAX_MOMENTS) {
            Toast.makeText(this, Ui.t(this, "That's ${SelfieStreak.MAX_MOMENTS} photos today 😅 More tomorrow!",
                "Aaj ki ${SelfieStreak.MAX_MOMENTS} photos ho gayi 😅 Baaki kal!"), Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    private fun addMomentCamera() {
        if (!canAddMoment()) return
        getSharedPreferences("selfie", MODE_PRIVATE).edit().putString("pendKind", "m").putLong("pendDay", SelfieStreak.today()).apply()
        SelfieProvider.file(this).delete()
        val left = SelfieStreak.MAX_MOMENTS - SelfieStreak.momentCount(this, SelfieStreak.today(), "me")
        startActivityForResult(Intent(this, FilterCameraActivity::class.java).putExtra("front", false)
            .putExtra("multi", true).putExtra("max", left), REQ)
    }

    private fun pickMoments() {
        if (!canAddMoment()) return
        val left = SelfieStreak.MAX_MOMENTS - SelfieStreak.momentCount(this, SelfieStreak.today(), "me")
        val fallback = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val i = Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
                val max = minOf(left, MediaStore.getPickImagesMaxLimit())
                if (max > 1) i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, max)
                startActivityForResult(i, REQ_PICK)
            } else startActivityForResult(fallback, REQ_PICK)
        } catch (e: Exception) {
            try {
                startActivityForResult(fallback, REQ_PICK)
            } catch (e2: Exception) {
                Toast.makeText(this, Ui.t(this, "No gallery app found 😕", "Gallery app nahi mila 😕"), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onPicked(data: Intent?) {
        val uris = ArrayList<Uri>()
        data?.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let { uris.add(it) } }
        if (uris.isEmpty()) data?.data?.let { uris.add(it) }
        if (uris.isEmpty()) return
        val day = SelfieStreak.today()
        val left = (SelfieStreak.MAX_MOMENTS - SelfieStreak.momentCount(this, day, "me")).coerceAtLeast(0)
        val take = uris.take(left)
        val skipped = uris.size - take.size
        if (take.isEmpty()) {
            canAddMoment()
            return
        }
        Toast.makeText(this, Ui.t(this, "Sending ${take.size} photos… 📤", "${take.size} photos bhej rahe hain… 📤"), Toast.LENGTH_SHORT).show()
        Thread {
            var ok = 0
            var bad = 0
            for (u in take) {
                val b = SelfieStreak.loadUri(this, u, 1440)
                if (b != null && SelfieStreak.postMoment(this, b, day)) ok++ else bad++
            }
            runOnUiThread { momentResult(ok, bad, skipped) }
        }.start()
    }

    private fun momentResult(ok: Int, bad: Int, skipped: Int = 0) {
        if (isFinishing) return
        val other = Speaker.other(Profile.load(this))
        if (ok > 0) {
            render()
            fx.burst("📷", minOf(10 + ok * 2, 36))
        }
        val msg = StringBuilder()
        if (ok > 0) msg.append(Ui.t(this, "$ok photo(s) on the way to $other 💞", "$ok photo $other ke paas ja rahi hain 💞"))
        if (bad > 0) msg.append(if (msg.isEmpty()) "" else "\n").append(Ui.t(this, "$bad couldn't be read 😕", "$bad photo padh nahi paye 😕"))
        if (skipped > 0) msg.append(if (msg.isEmpty()) "" else "\n").append(Ui.t(this,
            "$skipped skipped — max ${SelfieStreak.MAX_MOMENTS} a day", "$skipped chhod di — din mein max ${SelfieStreak.MAX_MOMENTS}"))
        if (msg.isNotEmpty()) Toast.makeText(this, msg.toString(), Toast.LENGTH_LONG).show()
    }

    // ---------- 📷 camera ----------
    private fun takePhoto(kind: String) {
        if (!CoupleLink.paired(this)) {
            Toast.makeText(this, Ui.t(this, "Connect both phones first in 💞", "Pehle 💞 mein dono phones jodo"), Toast.LENGTH_LONG).show()
            return
        }
        val day = if (kind == "x") SelfieStreak.rescueDay() else SelfieStreak.today()
        getSharedPreferences("selfie", MODE_PRIVATE).edit().putString("pendKind", kind).putLong("pendDay", day).apply()
        SelfieProvider.file(this).delete()
        // 🐶 Chinu's own camera with face filters
        startActivityForResult(Intent(this, FilterCameraActivity::class.java).putExtra("front", true), REQ)
    }

    /** Fallback: the phone's normal camera app */
    private fun systemCamera(kind: String) {
        SelfieProvider.file(this).delete()
        val uri = SelfieProvider.uri()
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (kind == "d") {
            // ask for the front camera (each camera app understands a different hint)
            i.putExtra("android.intent.extras.CAMERA_FACING", 1)
                .putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                .putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        }
        i.clipData = ClipData.newRawUri("selfie", uri)
        try {
            startActivityForResult(i, REQ_SYS)
        } catch (e: Exception) {
            Toast.makeText(this, Ui.t(this, "No camera app found 😕", "Camera app nahi mila 😕"), Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK) {
            if (resultCode == RESULT_OK) onPicked(data)
            return
        }
        if (requestCode == REQ && resultCode == RESULT_FIRST_USER) {
            systemCamera(getSharedPreferences("selfie", MODE_PRIVATE).getString("pendKind", "d") ?: "d")
            return
        }
        if (requestCode != REQ && requestCode != REQ_SYS) return
        val sentFromCam = data?.getIntExtra("sent", 0) ?: 0
        if (requestCode == REQ && resultCode == RESULT_OK && sentFromCam > 0) {
            momentResult(sentFromCam, 0)
            return
        }
        // 📷📷📷 many photos clicked in one go
        val burst = FilterCameraActivity.burstDir(this).listFiles()?.filter { it.name.endsWith(".jpg") }?.sortedBy { it.name }.orEmpty()
        if (requestCode == REQ && resultCode == RESULT_OK && burst.isNotEmpty() &&
            getSharedPreferences("selfie", MODE_PRIVATE).getString("pendKind", "d") == "m"
        ) {
            Toast.makeText(this, Ui.t(this, "Sending ${burst.size} photos… 📤", "${burst.size} photos bhej rahe hain… 📤"), Toast.LENGTH_SHORT).show()
            Thread {
                var ok = 0
                var bad = 0
                val day = SelfieStreak.today()
                for (f in burst) {
                    val b = SelfieStreak.load(f, 1440)
                    if (b != null && SelfieStreak.postMoment(this, b, day)) ok++ else bad++
                    f.delete()
                }
                runOnUiThread { momentResult(ok, bad) }
            }.start()
            return
        }
        val raw = SelfieProvider.file(this)
        if (resultCode != RESULT_OK || !raw.exists() || raw.length() == 0L) return
        val sp = getSharedPreferences("selfie", MODE_PRIVATE)
        val kind = sp.getString("pendKind", "d") ?: "d"
        val day = sp.getLong("pendDay", SelfieStreak.today())
        if (kind == "m") {
            Thread {
                val b = SelfieStreak.load(raw, 1440)
                val ok = b != null && SelfieStreak.postMoment(this, b, SelfieStreak.today())
                raw.delete()
                runOnUiThread { momentResult(if (ok) 1 else 0, if (ok) 0 else 1) }
            }.start()
            return
        }
        val before = SelfieStreak.streak(this)
        Toast.makeText(this, Ui.t(this, "Sending… 📤", "Bhej rahe hain… 📤"), Toast.LENGTH_SHORT).show()
        Thread {
            val ok = SelfieStreak.postFromCamera(this, raw, kind, day)
            runOnUiThread {
                if (!ok) {
                    Toast.makeText(this, Ui.t(this, "Couldn't read the photo 😕", "Photo padh nahi paye 😕"), Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                justPosted = kind == "d"
                render()
                val after = SelfieStreak.streak(this)
                if (after > before) {
                    fx.burst("🔥", 34)
                    if (after in listOf(3, 7, 10, 30, 50, 100, 150, 200, 365)) celebrate(after)
                } else if (kind == "x") fx.burst("🛟", 20)
                scroll.post { scroll.smoothScrollTo(0, 0) }
            }
        }.start()
    }

    /** 🎉 milestone party */
    private fun celebrate(n: Int) {
        val t = TextView(this).apply {
            text = "🔥 $n 🔥"
            textSize = 60f
            gravity = Gravity.CENTER
            setTextColor(0xFFE8590C.toInt())
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setShadowLayer(18f, 0f, 0f, 0xFFFFD58A.toInt())
        }
        val root = window.decorView as ViewGroup
        root.addView(t, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        t.scaleX = 0.2f
        t.scaleY = 0.2f
        t.animate().scaleX(1.2f).scaleY(1.2f).setDuration(550).setInterpolator(OvershootInterpolator()).withEndAction {
            t.animate().alpha(0f).translationY(-dp(120f).toFloat()).setStartDelay(900).setDuration(600)
                .setInterpolator(AccelerateInterpolator()).withEndAction { root.removeView(t) }.start()
        }.start()
        Voice.say(this, Ui.t(this, "$n day streak! Amazing!", "$n दिन की स्ट्रीक! वाह!"))
    }

    companion object {
        const val REQ = 4710
        const val REQ_SYS = 4711
        const val REQ_PICK = 4712

        @Volatile
        var current: SelfieActivity? = null
    }
}
