package com.meraassistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sin

/** 🌐 App language: English or Hinglish (Hindi in English letters) */
object Ui {
    fun hinglish(ctx: Context): Boolean =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("uiLang", "en") == "hinglish"

    /** t(ctx, "Unlock", "Kholo") */
    fun t(ctx: Context, en: String, hi: String): String = if (hinglish(ctx)) hi else en

    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun bold(tv: TextView): TextView {
        tv.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        return tv
    }

    fun label(ctx: Context, text: String, size: Float, color: Int, boldText: Boolean = false): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (boldText) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

    /** A frosted "liquid glass" pill/card background */
    fun glass(ctx: Context, radiusDp: Float = 24f, alpha: Int = 0x55, strong: Boolean = false): Drawable =
        GlassDrawable(ctx, radiusDp, if (strong) 0x88 else alpha)

    /** Tinted liquid glass (e.g. pink→lilac tiles) */
    fun liquid(ctx: Context, radiusDp: Float, tint: IntArray, alpha: Int = 0xB0): Drawable =
        GlassDrawable(ctx, radiusDp, alpha, tint)

    fun isNight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** 💧 Jelly press: squish on touch, wobble back on release (click still works) */
    fun liquidPress(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.93f).scaleY(0.95f).setDuration(110)
                        .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(420)
                        .setInterpolator(android.view.animation.OvershootInterpolator(3.2f)).start()
            }
            false
        }
    }

    /** Put the moving liquid-glass backdrop behind an XML screen (main chat, settings) */
    fun glassScreen(a: android.app.Activity) {
        val content = a.findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>("glassBg") != null) return
        content.getChildAt(0)?.background = null
        content.addView(GlassBackground(a, if (isNight(a)) GlassBackground.NIGHT else GlassBackground.PINK, isNight(a))
            .apply { tag = "glassBg" }, 0,
            android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Glass button */
    fun glassButton(ctx: Context, text: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xFF3B1F4A.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = glass(ctx, 22f, 0x77)
            val p = dp(ctx, 14f)
            setPadding(p, dp(ctx, 12f), p, dp(ctx, 12f))
            isClickable = true
            liquidPress(this)
            setOnClickListener { onClick() }
        }

    /** Solid pink→purple gradient button */
    fun primaryButton(ctx: Context, text: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFFF6FB1.toInt(), 0xFFB06BFF.toInt())
            ).apply { cornerRadius = dp(ctx, 26f).toFloat() }
            foreground = GlassDrawable(ctx, 26f, 0x00)   // glossy liquid shine on top of the colour
            elevation = dp(ctx, 4f).toFloat()
            setPadding(dp(ctx, 18f), dp(ctx, 14f), dp(ctx, 18f), dp(ctx, 14f))
            liquidPress(this)
            setOnClickListener { onClick() }
        }
}

/**
 * 💧 Liquid glass (like iOS 26): see-through body, a curved specular highlight across the top,
 * light bending along the bottom edge, and a bright double rim. Optional colour tint.
 */
class GlassDrawable(ctx: Context, radiusDp: Float, private val fillAlpha: Int, private val tint: IntArray? = null) : Drawable() {
    private val r = radiusDp * ctx.resources.displayMetrics.density
    private val d = ctx.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spec = Paint(Paint.ANTI_ALIAS_FLAG)
    private val caustic = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.4f * d }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d }
    private val rect = RectF()
    private val tmp = RectF()
    private val clip = android.graphics.Path()
    private var built = android.graphics.Rect()

    private fun withAlpha(c: Int, a: Int) = (a.coerceIn(0, 255) shl 24) or (c and 0xFFFFFF)

    private fun build() {
        val h = rect.height()
        // body: tinted or milky, a little clearer towards the bottom
        fill.shader = if (tint != null) LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
            tint.map { withAlpha(it, fillAlpha) }.toIntArray(), null, Shader.TileMode.CLAMP)
        else LinearGradient(0f, rect.top, 0f, rect.bottom,
            withAlpha(0xFFFFFF, fillAlpha + 0x18), withAlpha(0xFFFFFF, (fillAlpha * 0.7f).toInt()), Shader.TileMode.CLAMP)
        // soft top sheen
        shine.shader = LinearGradient(0f, rect.top, 0f, rect.top + h * 0.6f, 0x70FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        // curved specular streak (top-left) — the "liquid" bulge
        spec.shader = RadialGradient(rect.left + rect.width() * 0.28f, rect.top + h * 0.05f,
            maxOf(rect.width() * 0.55f, 1f), intArrayOf(0x88FFFFFF.toInt(), 0x22FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        // light bending along the bottom edge
        caustic.shader = LinearGradient(0f, rect.bottom - h * 0.35f, 0f, rect.bottom,
            0x00FFFFFF, 0x55FFFFFF, Shader.TileMode.CLAMP)
        // bright double rim: strong top-left, fading, catching light again bottom-right
        rim.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(0xFFFFFFFF.toInt(), 0x55FFFFFF, 0x22FFFFFF, 0xBBFFFFFF.toInt()), floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
        inner.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        clip.reset()
        clip.addRoundRect(rect, r, r, android.graphics.Path.Direction.CW)
        built = android.graphics.Rect(bounds)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        rect.set(b.left + 0.7f * d, b.top + 0.7f * d, b.right - 0.7f * d, b.bottom - 0.7f * d)
        if (built != b) build()
        if (fillAlpha > 0) canvas.drawRoundRect(rect, r, r, fill)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(rect, shine)
        canvas.drawRect(rect, spec)
        canvas.drawRect(rect, caustic)
        canvas.restore()
        canvas.drawRoundRect(rect, r, r, rim)
        tmp.set(rect.left + 1.6f * d, rect.top + 1.6f * d, rect.right - 1.6f * d, rect.bottom - 1.6f * d)
        val ri = maxOf(r - 1.6f * d, 0f)
        canvas.drawRoundRect(tmp, ri, ri, inner)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/** Soft, slowly moving pastel blobs behind the glass (pink · lilac · peach · mint) */
class GlassBackground(ctx: Context, private val palette: IntArray = PINK, private val night: Boolean = false) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val base = Paint()

    companion object {
        val PINK = intArrayOf(0xFFFFB3D9.toInt(), 0xFFD7B8FF.toInt(), 0xFFFFD6B8.toInt(), 0xFFB8F0E0.toInt())
        val LOVE = intArrayOf(0xFFFF9EC4.toInt(), 0xFFFFC2A8.toInt(), 0xFFE0A8FF.toInt(), 0xFFFF8FA3.toInt())
        val NIGHT = intArrayOf(0xFFB0306E.toInt(), 0xFF5B3BC4.toInt(), 0xFF1F7A5C.toInt(), 0xFF8A3FB5.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        base.shader = if (night) LinearGradient(0f, 0f, w, h, 0xFF120A16.toInt(), 0xFF07100C.toInt(), Shader.TileMode.CLAMP)
        else LinearGradient(0f, 0f, w, h, 0xFFFFF4FA.toInt(), 0xFFF3EEFF.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, base)
        val t = SystemClock.uptimeMillis() / 1000f
        val spots = arrayOf(floatArrayOf(0.2f, 0.18f, 0.55f), floatArrayOf(0.85f, 0.3f, 0.5f),
            floatArrayOf(0.25f, 0.75f, 0.6f), floatArrayOf(0.8f, 0.85f, 0.45f))
        for ((i, s) in spots.withIndex()) {
            val cx = w * s[0] + cos(t * 0.3f + i * 1.7f) * w * 0.08f
            val cy = h * s[1] + sin(t * 0.25f + i * 2.1f) * h * 0.05f
            val rad = w * s[2] * (1f + 0.07f * sin(t * 0.5f + i * 1.3f))   // blobs slowly breathe like liquid
            val c = palette[i % palette.size]
            p.shader = RadialGradient(cx, cy, rad, ((if (night) 0x88 else 0xAA) shl 24) or (c and 0xFFFFFF), c and 0xFFFFFF, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, rad, p)
        }
        postInvalidateDelayed(50)
    }
}

/** Tiny image loader: memory + disk cache, downsampled, off the main thread */
object ImageLoader {
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private val mem = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun load(view: ImageView, src: String, maxPx: Int = 700) {
        view.tag = src
        mem.get(src)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)
        val ctx = view.context.applicationContext
        pool.execute {
            val bmp = try {
                fetch(ctx, src, maxPx)
            } catch (e: Throwable) {
                null
            }
            if (bmp != null) mem.put(src, bmp)
            main.post {
                if (view.tag == src && bmp != null) {
                    view.alpha = 0f
                    view.setImageBitmap(bmp)
                    view.animate().alpha(1f).setDuration(250).start()
                }
            }
        }
    }

    private fun fetch(ctx: Context, src: String, maxPx: Int): Bitmap? {
        val file: File = if (src.startsWith("/")) File(src) else {
            val f = File(File(ctx.cacheDir, "img").apply { mkdirs() }, md5(src))
            if (!f.exists() || f.length() == 0L) {
                val c = URL(src).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 20000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "ChinuApp/1.0 (personal Android app)")
                if (c.responseCode !in 200..299) {
                    c.disconnect()
                    return null
                }
                val tmp = File(f.path + ".part")
                c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                c.disconnect()
                tmp.renameTo(f)
            }
            f
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= maxPx && o.outHeight / (sample * 2) >= maxPx) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
