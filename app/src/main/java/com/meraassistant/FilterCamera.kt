@file:Suppress("DEPRECATION")

package com.meraassistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.YuvImage
import android.graphics.drawable.GradientDrawable
import android.hardware.Camera
import android.media.FaceDetector
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.sin

/** Where the face is on screen (view pixels) */
class FaceBox(val left: Float, val top: Float, val w: Float, val h: Float) {
    val cx get() = left + w / 2f
    val eyeY get() = top + h * 0.40f
    val noseY get() = top + h * 0.60f
    val mouthY get() = top + h * 0.78f

    fun lerp(o: FaceBox, k: Float) = FaceBox(left + (o.left - left) * k, top + (o.top - top) * k, w + (o.w - w) * k, h + (o.h - h) * k)
}

/**
 * 🐶🐱🐰 Snapchat-style face filters, all drawn in code (no image files needed).
 * The same painter draws the live preview AND the final photo, so what you see is what gets sent.
 */
object FaceFilters {
    /** id, bubble emoji, English name, Hinglish name */
    class F(val id: String, val icon: String, val en: String, val hi: String)

    val ALL = listOf(
        F("none", "✨", "Natural", "Natural"),
        F("dog", "🐶", "Doggy", "Doggy"),
        F("cat", "🐱", "Kitty", "Billi"),
        F("bunny", "🐰", "Bunny", "Khargosh"),
        F("flowers", "🌸", "Flower crown", "Phoolon ka taj"),
        F("hearts", "😍", "Heart eyes", "Dil wali aankhein"),
        F("cool", "😎", "Cool shades", "Cool chashma"),
        F("crown", "👑", "Queen", "Rani"),
        F("blush", "🥰", "Blush", "Sharmeeli"),
        F("shy", "🙈", "Hide my face", "Chehra chhupao")
    )

    /** Colour looks for the whole photo */
    val LOOKS = listOf("Natural", "Glow ✨", "Warm 🌅", "Pink 🌸", "B&W 🖤", "Vintage 📜")

    fun look(i: Int): ColorMatrixColorFilter? {
        val m = ColorMatrix()
        when (i) {
            1 -> {
                m.setSaturation(0.92f)
                m.postConcat(ColorMatrix(floatArrayOf(1.07f, 0f, 0f, 0f, 14f, 0f, 1.07f, 0f, 0f, 14f, 0f, 0f, 1.07f, 0f, 16f, 0f, 0f, 0f, 1f, 0f)))
            }
            2 -> m.set(floatArrayOf(1.12f, 0f, 0f, 0f, 8f, 0f, 1.02f, 0f, 0f, 4f, 0f, 0f, 0.86f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
            3 -> m.set(floatArrayOf(1.08f, 0f, 0f, 0f, 14f, 0f, 0.95f, 0f, 0f, 0f, 0f, 0f, 1.06f, 0f, 12f, 0f, 0f, 0f, 1f, 0f))
            4 -> m.setSaturation(0f)
            5 -> m.set(floatArrayOf(0.39f, 0.77f, 0.19f, 0f, 10f, 0.35f, 0.69f, 0.17f, 0f, 6f, 0.27f, 0.53f, 0.13f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
            else -> return null
        }
        return ColorMatrixColorFilter(m)
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val emoji = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private fun e(c: Canvas, s: String, x: Float, y: Float, size: Float, alpha: Int = 255) {
        emoji.textSize = size
        emoji.alpha = alpha
        c.drawText(s, x, y + size * 0.36f, emoji)
    }

    private fun oval(c: Canvas, x: Float, y: Float, rw: Float, rh: Float, color: Int, deg: Float = 0f) {
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = color
        c.save()
        c.rotate(deg, x, y)
        c.drawOval(RectF(x - rw, y - rh, x + rw, y + rh), p)
        c.restore()
    }

    private fun blush(c: Canvas, f: FaceBox, strength: Int) {
        for (s in listOf(-1f, 1f)) {
            val x = f.cx + s * f.w * 0.29f
            val y = f.eyeY + f.h * 0.22f
            val r = f.w * 0.17f
            p.style = Paint.Style.FILL
            p.shader = RadialGradient(x, y, r, Color.argb(strength, 255, 92, 140), Color.argb(0, 255, 92, 140), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r, p)
        }
        p.shader = null
    }

    /** t = seconds (for little animations) */
    fun draw(c: Canvas, id: String, f: FaceBox?, t: Float) {
        if (f == null || id == "none") return
        val w = f.w
        when (id) {
            "dog" -> {
                val swing = sin(t * 2.2f) * 5f
                for (s in listOf(-1f, 1f)) {
                    val x = f.cx + s * w * 0.50f
                    val y = f.top + f.h * 0.10f
                    oval(c, x, y, w * 0.17f, w * 0.30f, 0xFF8B5A2B.toInt(), s * (28f + swing))
                    oval(c, x + s * w * 0.01f, y + w * 0.03f, w * 0.10f, w * 0.21f, 0xFF5C3A1A.toInt(), s * (28f + swing))
                }
                // tongue (wiggles) then nose
                val tw = w * 0.085f
                val th = w * (0.13f + 0.02f * sin(t * 7f))
                p.shader = null; p.color = 0xFFFF6F91.toInt(); p.style = Paint.Style.FILL
                c.drawRoundRect(RectF(f.cx - tw, f.mouthY - th * 0.2f, f.cx + tw, f.mouthY + th * 1.6f), tw, tw, p)
                p.color = 0xFFE0466F.toInt(); p.strokeWidth = w * 0.012f; p.style = Paint.Style.STROKE
                c.drawLine(f.cx, f.mouthY, f.cx, f.mouthY + th * 1.1f, p)
                p.style = Paint.Style.FILL
                oval(c, f.cx, f.noseY, w * 0.12f, w * 0.08f, 0xFF1E1410.toInt())
                oval(c, f.cx - w * 0.04f, f.noseY - w * 0.03f, w * 0.03f, w * 0.018f, 0x88FFFFFF.toInt())
            }
            "cat" -> {
                for (s in listOf(-1f, 1f)) {
                    val bx = f.cx + s * w * 0.30f
                    val by = f.top + f.h * 0.06f
                    val tip = by - w * 0.38f + sin(t * 2f + s) * w * 0.01f
                    val outer = Path().apply { moveTo(bx - w * 0.17f, by); lineTo(bx + s * w * 0.06f, tip); lineTo(bx + w * 0.17f, by); close() }
                    val inner = Path().apply { moveTo(bx - w * 0.09f, by - w * 0.02f); lineTo(bx + s * w * 0.05f, tip + w * 0.11f); lineTo(bx + w * 0.09f, by - w * 0.02f); close() }
                    p.shader = null; p.style = Paint.Style.FILL
                    p.color = 0xFF4A4458.toInt(); c.drawPath(outer, p)
                    p.color = 0xFFFF9EC4.toInt(); c.drawPath(inner, p)
                    // whiskers
                    p.color = 0xEE2B2433.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = w * 0.011f; p.strokeCap = Paint.Cap.ROUND
                    for (k in -1..1) c.drawLine(f.cx + s * w * 0.13f, f.noseY + w * 0.05f, f.cx + s * w * 0.55f, f.noseY + w * (0.02f + k * 0.07f), p)
                }
                p.style = Paint.Style.FILL; p.color = 0xFFFF7FAF.toInt()
                c.drawPath(Path().apply {
                    moveTo(f.cx - w * 0.06f, f.noseY - w * 0.02f); lineTo(f.cx + w * 0.06f, f.noseY - w * 0.02f); lineTo(f.cx, f.noseY + w * 0.05f); close()
                }, p)
            }
            "bunny" -> {
                for (s in listOf(-1f, 1f)) {
                    val x = f.cx + s * w * 0.20f
                    val y = f.top - f.h * 0.28f
                    val d = s * (10f + sin(t * 1.8f + s) * 4f)
                    oval(c, x, y, w * 0.12f, w * 0.40f, 0xFFFFFFFF.toInt(), d)
                    oval(c, x, y + w * 0.04f, w * 0.065f, w * 0.30f, 0xFFFFB3CF.toInt(), d)
                }
                oval(c, f.cx, f.noseY, w * 0.06f, w * 0.045f, 0xFFFF7FAF.toInt())
                p.shader = null; p.color = 0xFFFFFFFF.toInt(); p.style = Paint.Style.FILL
                c.drawRoundRect(RectF(f.cx - w * 0.07f, f.mouthY - w * 0.02f, f.cx - w * 0.005f, f.mouthY + w * 0.09f), w * 0.02f, w * 0.02f, p)
                c.drawRoundRect(RectF(f.cx + w * 0.005f, f.mouthY - w * 0.02f, f.cx + w * 0.07f, f.mouthY + w * 0.09f), w * 0.02f, w * 0.02f, p)
                blush(c, f, 120)
            }
            "flowers" -> {
                val list = listOf("🌸", "🌼", "🌺", "🌷", "🌺", "🌼", "🌸")
                for ((i, s) in list.withIndex()) {
                    val k = (i - 3) / 3f                          // -1 … 1
                    val x = f.cx + k * w * 0.58f
                    val y = f.top + f.h * 0.04f + k * k * w * 0.22f - w * 0.06f
                    e(c, s, x, y + sin(t * 2f + i) * w * 0.01f, w * (0.24f - abs(k) * 0.04f))
                }
                blush(c, f, 110)
            }
            "hearts" -> {
                val beat = 1f + 0.12f * sin(t * 8f).coerceAtLeast(0f)
                for (s in listOf(-1f, 1f)) e(c, "💗", f.cx + s * w * 0.21f, f.eyeY, w * 0.30f * beat)
                for (i in 0 until 6) {
                    val ph = ((t * 0.45f + i / 6f) % 1f)
                    val x = f.cx + (if (i % 2 == 0) -1 else 1) * w * (0.55f + 0.08f * sin(t * 3f + i))
                    e(c, "💕", x, f.top + f.h * (0.9f - ph * 1.2f), w * 0.13f, (255 * (1f - ph)).toInt())
                }
            }
            "cool" -> {
                p.shader = null
                for (s in listOf(-1f, 1f)) {
                    val x = f.cx + s * w * 0.21f
                    val r = RectF(x - w * 0.18f, f.eyeY - w * 0.10f, x + w * 0.18f, f.eyeY + w * 0.11f)
                    p.style = Paint.Style.FILL; p.color = 0xF0101015.toInt(); c.drawRoundRect(r, w * 0.07f, w * 0.07f, p)
                    p.style = Paint.Style.STROKE; p.strokeWidth = w * 0.018f; p.color = 0xFF2B2B33.toInt(); c.drawRoundRect(r, w * 0.07f, w * 0.07f, p)
                    p.color = 0x66FFFFFF; p.strokeWidth = w * 0.014f
                    c.drawLine(x - w * 0.10f, f.eyeY - w * 0.04f, x - w * 0.02f, f.eyeY - w * 0.07f, p)
                }
                p.color = 0xFF2B2B33.toInt(); p.strokeWidth = w * 0.03f
                c.drawLine(f.cx - w * 0.04f, f.eyeY - w * 0.05f, f.cx + w * 0.04f, f.eyeY - w * 0.05f, p)
                e(c, "✨", f.cx + w * 0.48f, f.eyeY - w * 0.16f, w * 0.14f, (160 + 95 * sin(t * 4f)).toInt().coerceIn(0, 255))
            }
            "crown" -> {
                e(c, "👑", f.cx, f.top - f.h * 0.06f + sin(t * 2f) * w * 0.012f, w * 0.62f)
                for (i in 0 until 4) {
                    val a = (sin(t * 3f + i * 1.7f) * 0.5f + 0.5f)
                    e(c, "✨", f.cx + (if (i < 2) -1 else 1) * w * (0.45f + (i % 2) * 0.12f), f.top + f.h * (0.05f + (i % 2) * 0.2f),
                        w * 0.12f, (255 * a).toInt())
                }
            }
            "blush" -> {
                blush(c, f, 165)
                e(c, "✨", f.cx - w * 0.5f, f.eyeY - w * 0.25f, w * 0.12f, (180 + 75 * sin(t * 3f)).toInt().coerceIn(0, 255))
                e(c, "💖", f.cx + w * 0.5f, f.top + f.h * 0.05f, w * 0.13f)
            }
            "shy" -> e(c, "🙈", f.cx, f.top + f.h * 0.48f, f.h * 1.15f)
        }
    }
}

/** Transparent layer above the camera that draws the chosen filter, ~30 fps */
class FilterOverlay(ctx: Context) : View(ctx) {
    var filter = "none"
    var face: FaceBox? = null
    private val start = SystemClock.uptimeMillis()
    fun time() = (SystemClock.uptimeMillis() - start) / 1000f

    override fun onDraw(c: Canvas) {
        FaceFilters.draw(c, filter, face, time())
        postInvalidateOnAnimation()
    }
}

/** 📸 Chinu's own selfie camera with live face filters */
class FilterCameraActivity : Activity(), TextureView.SurfaceTextureListener {
    private lateinit var root: FrameLayout
    private lateinit var stage: FrameLayout
    private lateinit var tex: TextureView
    private lateinit var overlay: FilterOverlay
    private lateinit var nameLabel: TextView
    private lateinit var hint: TextView
    private lateinit var review: FrameLayout
    private val bubbles = ArrayList<TextView>()
    private val main = Handler(Looper.getMainLooper())

    private var cam: Camera? = null
    private var front = true
    private var info = Camera.CameraInfo()
    private var displayDeg = 0
    private var pw = 0
    private var ph = 0
    private var hwFaces = false
    private var lastFaceAt = 0L
    private var target: FaceBox? = null
    private var lookIdx = 0
    private var busy = false
    private var lastScan = 0L
    private var shot: Bitmap? = null

    // 📷📷📷 multi-click mode ("Aaj ke pal"): click 15, 30… photos without leaving the camera
    private var multi = false
    private var maxShots = 50
    private var shots = 0
    private var saving = 0
    private var holding = false
    private lateinit var counter: TextView
    private lateinit var doneBtn: TextView
    private lateinit var lastThumb: ImageView
    private val saver = java.util.concurrent.Executors.newSingleThreadExecutor()

    companion object {
        /** Where multi-click photos wait until Chinu picks them up (001.jpg, 002.jpg …) */
        fun burstDir(ctx: Context) = java.io.File(ctx.cacheDir, "burst")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        front = intent.getBooleanExtra("front", true)
        multi = intent.getBooleanExtra("multi", false)
        maxShots = intent.getIntExtra("max", 50).coerceAtLeast(1)
        if (multi) burstDir(this).apply { deleteRecursively(); mkdirs() }
        val start = intent.getStringExtra("filter") ?: getSharedPreferences("selfie", MODE_PRIVATE).getString("lastFilter", "none") ?: "none"

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        stage = FrameLayout(this)
        tex = TextureView(this).apply { surfaceTextureListener = this@FilterCameraActivity }
        overlay = FilterOverlay(this)
        stage.addView(tex, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        stage.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP))

        // top bar: close · colour look · flip
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14f), dp(36f), dp(14f), dp(8f))
        }
        top.addView(roundBtn("✕") { if (multi && shots > 0) finishBurst() else { setResult(RESULT_CANCELED); finish() } })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(roundBtn("🎨") { nextLook() })
        top.addView(roundBtn("🔄") { flip() }, LinearLayout.LayoutParams(dp(46f), dp(46f)).apply { leftMargin = dp(10f) })
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        hint = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
            text = Ui.t(this@FilterCameraActivity, "Bring your face into the frame 🙂", "Apna chehra camera ke saamne lao 🙂")
        }
        root.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            topMargin = dp(96f)
        })

        // bottom: filter name · filter carousel · shutter
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x00000000))
            setPadding(0, dp(30f), 0, dp(26f))
        }
        nameLabel = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
        }
        bottom.addView(nameLabel)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        }
        for (f in FaceFilters.ALL) {
            val b = TextView(this).apply {
                text = f.icon
                textSize = 26f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x55FFFFFF) }
                setOnClickListener { choose(f.id, true) }
                tag = f.id
            }
            bubbles.add(b)
            row.addView(b, LinearLayout.LayoutParams(dp(58f), dp(58f)).apply { leftMargin = dp(6f); rightMargin = dp(6f) })
        }
        bottom.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        val shutter = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FFFFFF)
                setStroke(dp(6f), Color.WHITE)
            }
            if (!multi) setOnClickListener { capture(this) }
        }
        if (multi) {
            // tap = one photo · press & hold = keeps clicking (about 2 photos a second)
            val repeat = object : Runnable {
                override fun run() {
                    if (!holding) return
                    capture(shutter)
                    if (shots < maxShots) main.postDelayed(this, 480)
                }
            }
            val startHold = Runnable {
                holding = true
                main.post(repeat)
            }
            shutter.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> main.postDelayed(startHold, 450)
                    android.view.MotionEvent.ACTION_UP -> {
                        main.removeCallbacks(startHold)
                        main.removeCallbacks(repeat)
                        if (!holding) capture(v)
                        holding = false
                        v.performClick()
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        main.removeCallbacks(startHold)
                        main.removeCallbacks(repeat)
                        holding = false
                    }
                }
                true
            }
            val shutRow = FrameLayout(this)
            shutRow.addView(shutter, FrameLayout.LayoutParams(dp(78f), dp(78f), Gravity.CENTER))
            lastThumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { setColor(0x55FFFFFF); cornerRadius = dp(12f).toFloat(); setStroke(dp(2f), Color.WHITE) }
                clipToOutline = true
                visibility = View.INVISIBLE
            }
            shutRow.addView(lastThumb, FrameLayout.LayoutParams(dp(54f), dp(54f), Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = dp(28f) })
            doneBtn = TextView(this).apply {
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFFF6FB1.toInt(), 0xFFB06BFF.toInt()))
                    .apply { cornerRadius = dp(24f).toFloat() }
                setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
                visibility = View.INVISIBLE
                setOnClickListener { finishBurst() }
            }
            shutRow.addView(doneBtn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = dp(18f) })
            bottom.addView(shutRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(84f)).apply { topMargin = dp(6f) })
            counter = TextView(this).apply {
                textSize = 13.5f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setShadowLayer(8f, 0f, 0f, Color.BLACK)
            }
            bottom.addView(counter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6f)
            })
            updateCounter()
        } else bottom.addView(shutter, LinearLayout.LayoutParams(dp(78f), dp(78f)).apply { topMargin = dp(8f) })
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        review = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(Color.BLACK) }
        root.addView(review, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        choose(start, false)

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 7)
        }
    }

    private fun dp(v: Float) = Ui.dp(this, v)

    private fun roundBtn(t: String, click: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x55000000) }
        layoutParams = LinearLayout.LayoutParams(dp(46f), dp(46f))
        setOnClickListener {
            it.animate().rotationBy(180f).setDuration(250).start()
            click()
        }
    }

    private fun choose(id: String, bounce: Boolean) {
        val f = FaceFilters.ALL.firstOrNull { it.id == id } ?: FaceFilters.ALL[0]
        overlay.filter = f.id
        nameLabel.text = "${f.icon}  " + Ui.t(this, f.en, f.hi)
        getSharedPreferences("selfie", MODE_PRIVATE).edit().putString("lastFilter", f.id).apply()
        for (b in bubbles) {
            val on = b.tag == f.id
            (b.background as? GradientDrawable)?.setStroke(if (on) dp(3f) else 0, Color.WHITE)
            b.animate().scaleX(if (on) 1.18f else 1f).scaleY(if (on) 1.18f else 1f).setDuration(220)
                .setInterpolator(OvershootInterpolator(3f)).start()
        }
        if (bounce) {
            nameLabel.alpha = 0f
            nameLabel.translationY = dp(10f).toFloat()
            nameLabel.animate().alpha(1f).translationY(0f).setDuration(250).start()
        }
    }

    private fun nextLook() {
        lookIdx = (lookIdx + 1) % FaceFilters.LOOKS.size
        tex.setLayerPaint(Paint().apply { colorFilter = FaceFilters.look(lookIdx) })
        Toast.makeText(this, "🎨 " + FaceFilters.LOOKS[lookIdx], Toast.LENGTH_SHORT).show()
    }

    // ---------------- camera ----------------
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (tex.isAvailable) openCamera()
        } else {
            Toast.makeText(this, Ui.t(this, "Chinu needs the camera for selfies 📸", "Selfie ke liye Chinu ko camera ki permission chahiye 📸"),
                Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (tex.isAvailable && review.visibility != View.VISIBLE) openCamera()
    }

    override fun onPause() {
        closeCamera()
        super.onPause()
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        if (review.visibility != View.VISIBLE) openCamera()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        closeCamera()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    private fun cameraId(): Int {
        val want = if (front) Camera.CameraInfo.CAMERA_FACING_FRONT else Camera.CameraInfo.CAMERA_FACING_BACK
        for (i in 0 until Camera.getNumberOfCameras()) {
            val ci = Camera.CameraInfo()
            Camera.getCameraInfo(i, ci)
            if (ci.facing == want) return i
        }
        return if (Camera.getNumberOfCameras() > 0) 0 else -1
    }

    private fun openCamera() {
        if (cam != null || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val id = cameraId()
        if (id < 0) return fail()
        try {
            val c = Camera.open(id)
            info = Camera.CameraInfo().also { Camera.getCameraInfo(id, it) }
            displayDeg = if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) (360 - info.orientation % 360) % 360 else info.orientation % 360
            c.setDisplayOrientation(displayDeg)
            val params = c.parameters
            val sizes = params.supportedPreviewSizes
            val best = sizes.filter { it.width <= 1920 && abs(it.width.toFloat() / it.height - 4f / 3f) < 0.05f }.maxByOrNull { it.width * it.height }
                ?: sizes.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height } ?: sizes[0]
            params.setPreviewSize(best.width, best.height)
            if (params.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) == true) {
                params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            }
            c.parameters = params
            pw = best.width
            ph = best.height
            // stage keeps the camera's shape (portrait), so faces map 1:1 onto the overlay
            val sw = resources.displayMetrics.widthPixels
            val rotated = displayDeg % 180 != 0
            val ratio = if (rotated) pw.toFloat() / ph else ph.toFloat() / pw
            stage.layoutParams = FrameLayout.LayoutParams(sw, (sw * ratio).toInt(), Gravity.TOP).apply { topMargin = dp(84f) }
            c.setPreviewTexture(tex.surfaceTexture)
            c.startPreview()
            cam = c
            hwFaces = params.maxNumDetectedFaces > 0
            if (hwFaces) {
                c.setFaceDetectionListener { faces, _ -> onHwFaces(faces) }
                try {
                    c.startFaceDetection()
                } catch (e: Exception) {
                    hwFaces = false
                }
            }
            if (!hwFaces) startSoftwareFaces(c)
            main.post(follow)
        } catch (e: Exception) {
            closeCamera()
            fail()
        }
    }

    private fun fail() {
        Toast.makeText(this, Ui.t(this, "Couldn't open the camera — using the phone's camera app", "Camera nahi khula — phone ka camera use kar rahe hain"),
            Toast.LENGTH_LONG).show()
        setResult(RESULT_FIRST_USER)
        finish()
    }

    private fun closeCamera() {
        main.removeCallbacks(follow)
        val c = cam ?: return
        cam = null
        try {
            c.setPreviewCallbackWithBuffer(null)
            if (hwFaces) c.stopFaceDetection()
            c.stopPreview()
            c.release()
        } catch (e: Exception) {
        }
    }

    private fun flip() {
        front = !front
        target = null
        overlay.face = null
        stage.animate().scaleX(0f).setDuration(130).withEndAction {
            closeCamera()
            openCamera()
            stage.animate().scaleX(1f).setDuration(200).setInterpolator(OvershootInterpolator()).start()
        }.start()
    }

    // ---------------- 🙂 face tracking ----------------
    /** Hardware face detection (most phones): rectangles in -1000…1000 camera space */
    private fun onHwFaces(faces: Array<Camera.Face>?) {
        val f = faces?.maxByOrNull { it.rect.width() * it.rect.height() } ?: return
        val m = Matrix()
        m.setScale(if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) -1f else 1f, 1f)
        m.postRotate(displayDeg.toFloat())
        m.postScale(tex.width / 2000f, tex.height / 2000f)
        m.postTranslate(tex.width / 2f, tex.height / 2f)
        val r = RectF(f.rect)
        m.mapRect(r)
        // the camera's box is a bit tight around the face → give the filter some room
        setTarget(FaceBox(r.left - r.width() * 0.04f, r.top - r.height() * 0.10f, r.width() * 1.08f, r.height() * 1.15f))
    }

    /** Fallback for phones without hardware detection: Android's built-in FaceDetector on small preview frames */
    private fun startSoftwareFaces(c: Camera) {
        val size = pw * ph * 3 / 2
        c.addCallbackBuffer(ByteArray(size))
        c.setPreviewCallbackWithBuffer { data, camera ->
            val now = SystemClock.uptimeMillis()
            if (!busy && data != null && now - lastScan > 220) {
                busy = true
                lastScan = now
                val copy = data.copyOf()
                Thread { scan(copy) }.start()
            }
            camera.addCallbackBuffer(data)
        }
    }

    private fun scan(nv21: ByteArray) {
        try {
            val out = ByteArrayOutputStream()
            YuvImage(nv21, android.graphics.ImageFormat.NV21, pw, ph, null).compressToJpeg(Rect(0, 0, pw, ph), 55, out)
            val bytes = out.toByteArray()
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = maxOf(1, pw / 320) })
            if (raw == null) {
                busy = false
                return
            }
            // turn the frame the same way the screen shows it (rotate, and mirror for the front camera)
            val m = Matrix().apply {
                postRotate(info.orientation.toFloat())
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) postScale(-1f, 1f)
            }
            val turned = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            val evenW = turned.width - turned.width % 2
            val b565 = Bitmap.createBitmap(evenW, turned.height, Bitmap.Config.RGB_565)
            Canvas(b565).drawBitmap(turned, 0f, 0f, null)
            val found = arrayOfNulls<FaceDetector.Face>(1)
            val n = FaceDetector(evenW, turned.height, 1).findFaces(b565, found)
            val face = found[0]
            if (n > 0 && face != null && face.confidence() > 0.3f) {
                val mid = PointF()
                face.getMidPoint(mid)
                val sx = tex.width.toFloat() / evenW
                val sy = tex.height.toFloat() / turned.height
                val d = face.eyesDistance() * sx
                val cx = mid.x * sx
                val ey = mid.y * sy
                val w = d * 2.5f
                val h = d * 3.2f
                main.post { setTarget(FaceBox(cx - w / 2f, ey - h * 0.40f, w, h)) }
            }
        } catch (e: Exception) {
        }
        busy = false
    }

    private fun setTarget(b: FaceBox) {
        target = b
        lastFaceAt = SystemClock.uptimeMillis()
    }

    /** smooth movement + hide the filter when the face is gone */
    private val follow = object : Runnable {
        override fun run() {
            val t = target
            val gone = SystemClock.uptimeMillis() - lastFaceAt > 700
            overlay.face = if (t == null || gone) null else overlay.face?.lerp(t, 0.45f) ?: t
            val showHint = overlay.face == null && overlay.filter != "none"
            if (showHint != (hint.alpha > 0.5f)) hint.animate().alpha(if (showHint) 1f else 0f).setDuration(200).start()
            main.postDelayed(this, 33)
        }
    }

    // ---------------- 📸 capture & review ----------------
    private fun capture(btn: View) {
        if (cam == null) return
        btn.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        btn.animate().scaleX(0.82f).scaleY(0.82f).setDuration(90).withEndAction {
            btn.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(OvershootInterpolator()).start()
        }.start()
        val frame = tex.bitmap ?: return
        // exactly what's on screen: camera frame + colour look + face filter
        val out = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(frame, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = FaceFilters.look(lookIdx) })
        FaceFilters.draw(c, overlay.filter, overlay.face, overlay.time())
        if (multi) {
            keepShot(out)
            return
        }
        shot = out
        // white flash
        val flash = View(this).apply { setBackgroundColor(Color.WHITE) }
        root.addView(flash, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        flash.animate().alpha(0f).setDuration(380).withEndAction { root.removeView(flash) }.start()
        closeCamera()
        showReview(out)
    }

    // ---------------- 📷📷📷 multi-click ----------------
    private fun updateCounter() {
        counter.text = if (shots == 0) Ui.t(this, "Every click goes straight to your partner 💌 · hold for many",
            "Har click seedha partner ke paas jayegi 💌 · daba ke rakho = lagatar")
        else Ui.t(this, "📤 $shots sent · click again!", "📤 $shots bhej di · aur kheencho!")
        doneBtn.text = Ui.t(this, "✓ Done", "✓ Ho gaya")
        doneBtn.visibility = if (shots > 0) View.VISIBLE else View.INVISIBLE
    }

    /** Multi-click: keep the camera running, save the photo quietly in the background */
    private fun keepShot(out: Bitmap) {
        if (shots >= maxShots) {
            holding = false
            Toast.makeText(this, Ui.t(this, "That's $maxShots photos for today 😅", "Aaj ki $maxShots photos ho gayi 😅"), Toast.LENGTH_SHORT).show()
            return
        }
        if (saving >= 3) return          // phone still busy saving — skip this one so memory stays safe
        shots++
        saving++
        val n = shots
        updateCounter()
        val flash = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0.6f }
        root.addView(flash, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        flash.animate().alpha(0f).setDuration(220).withEndAction { root.removeView(flash) }.start()
        val small = Bitmap.createScaledBitmap(out, maxOf(1, out.width / 6), maxOf(1, out.height / 6), true)
        lastThumb.setImageBitmap(small)
        lastThumb.visibility = View.VISIBLE
        lastThumb.scaleX = 1.4f
        lastThumb.scaleY = 1.4f
        lastThumb.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator()).start()
        val app = applicationContext
        saver.execute {
            val ok = SelfieStreak.postMoment(app, out, SelfieStreak.today())
            out.recycle()
            main.post {
                saving--
                if (!ok && !isFinishing) Toast.makeText(this, Ui.t(this, "Photo $n couldn't be sent 😕", "Photo $n nahi gayi 😕"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Hand all clicked photos back to Chinu (after the last one is saved) */
    private fun finishBurst() {
        if (shots == 0) return
        holding = false
        doneBtn.isEnabled = false
        doneBtn.text = "⏳"
        saver.execute {
            main.post {
                setResult(RESULT_OK, android.content.Intent().putExtra("sent", shots))
                finish()
            }
        }
    }

    private fun showReview(b: Bitmap) {
        review.removeAllViews()
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(b)
        }
        review.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18f), 0, dp(18f), dp(30f))
        }
        fun pill(t: String, filled: Boolean, click: () -> Unit) = TextView(this).apply {
            text = t
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(if (filled) Color.WHITE else 0xFF3B1F4A.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = if (filled) GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFFF6FB1.toInt(), 0xFFB06BFF.toInt()))
                .apply { cornerRadius = dp(28f).toFloat() }
            else GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(28f).toFloat() }
            setPadding(0, dp(16f), 0, dp(16f))
            setOnClickListener { click() }
        }
        bar.addView(pill(Ui.t(this, "🔄 Retake", "🔄 Dobara"), false) { retake() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(pill(Ui.t(this, "💌 Send", "💌 Bhejo"), true) { send() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f).apply { leftMargin = dp(12f) })
        review.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        review.visibility = View.VISIBLE
        img.scaleX = 0.85f
        img.scaleY = 0.85f
        img.rotation = -4f
        img.animate().scaleX(1f).scaleY(1f).rotation(0f).setDuration(380).setInterpolator(OvershootInterpolator(1.6f)).start()
        bar.translationY = dp(120f).toFloat()
        bar.animate().translationY(0f).setStartDelay(150).setDuration(350).setInterpolator(OvershootInterpolator()).start()
    }

    private fun retake() {
        shot = null
        review.animate().alpha(0f).setDuration(200).withEndAction {
            review.visibility = View.GONE
            review.alpha = 1f
            openCamera()
        }.start()
    }

    private fun send() {
        val b = shot ?: return
        try {
            SelfieProvider.file(this).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            setResult(RESULT_OK)
        } catch (e: Exception) {
            setResult(RESULT_CANCELED)
        }
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (review.visibility == View.VISIBLE) retake()
        else if (multi && shots > 0) finishBurst()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        saver.shutdown()
        super.onDestroy()
    }
}
