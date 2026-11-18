package com.meraassistant

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * ✨ Siri-style Chinu: a small card with a green bird pops up on ANY screen
 * (home screen, inside apps) — no need to open the Chinu app.
 * Listens, answers on screen, and speaks.
 */
class ChinuOverlay(private val svc: AccessibilityService) : ChinuHost, RecognitionListener, TextToSpeech.OnInitListener {

    override val context: Context get() = svc

    private val wm = svc.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val processor = CommandProcessor(this)

    private var root: FrameLayout? = null
    private var bird: BirdView? = null
    private var heard: TextView? = null
    private var answer: TextView? = null
    private var card: View? = null

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var listening = false
    private var listenAfter = false
    private var pendingSpeech: Pair<String, String>? = null
    private var followUps = 0          // how many times we kept listening after a reply
    private var inFollowUp = false
    private val autoClose = Runnable { dismiss(true) }

    init {
        // Awaaz pehle se taiyaar, taaki jawab turant bole
        tts = TextToSpeech(svc, this)
    }

    // ======================= Show / hide =======================
    fun show(command: String) {
        if (root == null) build()
        active = true
        followUps = 0
        inFollowUp = false
        WakeService.pause()
        handler.removeCallbacks(autoClose)
        if (tts == null) tts = TextToSpeech(svc, this)
        if (command.isNotBlank()) {
            heard?.text = "“$command”"
            addHistoryFromOverlay(command)
            processor.handle(listOf(command))
        } else {
            val p = Profile.load(svc)
            listenAfter = true
            val en = selectedLang() == "en-IN"
            val who = Speaker.name(p)
            reply(Bday.greet(svc) ?: (if (en) "Yes?" else "हाँ जी?"), "hi-IN".takeIf { Bday.greet(svc) != null } ?: (if (en) "en-IN" else "hi-IN"))
        }
    }

    private fun addHistoryFromOverlay(cmd: String) {
        heard?.text = "“$cmd”"
    }

    private fun build() {
        val dp = svc.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        // Small floating card at the bottom (Apple style) — the rest of the screen stays usable
        val r = FrameLayout(svc).apply { setPadding(px(12), 0, px(12), px(18)) }

        // Card with a softly glowing, moving green edge (like Siri / Gemini)
        val c = GlowCard(svc).apply {
            elevation = px(16).toFloat()
            setPadding(px(14), px(10), px(14), px(16))
            isClickable = true
            setOnClickListener { if (listening) recognizer?.stopListening() else startListening() }
        }
        val col = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }

        // top row: parakeet · name/status · close
        val top = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val b = BirdView(svc)
        top.addView(b, LinearLayout.LayoutParams(px(60), px(56)))
        val nameCol = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        nameCol.addView(TextView(svc).apply {
            text = "Chinu"
            textSize = 16f
            setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD))
            setTextColor(svc.getColor(R.color.text))
        })
        val h = TextView(svc).apply {
            setTextColor(svc.getColor(R.color.text2))
            textSize = 13f
            maxLines = 2
            text = "Listening…"
        }
        nameCol.addView(h)
        val nlp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        nlp.leftMargin = px(4)
        top.addView(nameCol, nlp)
        val x = TextView(svc).apply {
            text = "✕"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(svc.getColor(R.color.text2))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(svc.getColor(R.color.pressed))
            }
            setOnClickListener { dismiss(true) }
        }
        top.addView(x, LinearLayout.LayoutParams(px(32), px(32)))
        col.addView(top)

        // the answer
        val a = TextView(svc).apply {
            setTextColor(svc.getColor(R.color.text))
            textSize = 17f
            setLineSpacing(px(3).toFloat(), 1f)
            maxLines = 8
            movementMethod = android.text.method.ScrollingMovementMethod()
            setPadding(px(4), px(6), px(4), 0)
        }
        col.addView(a, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        c.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        r.addView(c, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.BOTTOM
        try {
            wm.addView(r, lp)
        } catch (e: Exception) {
            return
        }
        root = r
        bird = b
        heard = h
        answer = a
        card = c

        // Slide up + pop, like Siri
        c.alpha = 0f
        c.translationY = px(120).toFloat()
        c.scaleX = 0.92f
        c.scaleY = 0.92f
        c.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(420).setInterpolator(OvershootInterpolator(1.2f)).start()
        b.scaleX = 0.3f
        b.scaleY = 0.3f
        b.animate().scaleX(1f).scaleY(1f).setStartDelay(120).setDuration(380)
            .setInterpolator(OvershootInterpolator(2f)).start()
    }

    /** stopVoice=false: the spoken reply keeps playing after the overlay hides */
    fun dismiss(stopVoice: Boolean) {
        handler.removeCallbacks(autoClose)
        BeepMuter.unmute(svc)
        try {
            recognizer?.cancel()
        } catch (e: Exception) {
        }
        listening = false
        listenAfter = false
        if (stopVoice) {
            tts?.stop()
            MainActivity.speaking = false
        }
        val r = root ?: run {
            active = false
            return
        }
        root = null
        bird = null
        heard = null
        answer = null
        card = null
        active = false
        r.animate().alpha(0f).translationY(r.height.toFloat() * 0.6f).setDuration(220).withEndAction {
            try {
                wm.removeView(r)
            } catch (e: Exception) {
            }
        }.start()
        WakeService.pause()
    }

    private fun removeNow() {
        val r = root ?: return
        root = null
        bird = null
        heard = null
        answer = null
        card = null
        active = false
        try {
            recognizer?.cancel()
        } catch (e: Exception) {
        }
        listening = false
        try {
            wm.removeViewImmediate(r)
        } catch (e: Exception) {
        }
        WakeService.pause()
    }

    // ======================= ChinuHost =======================
    override fun selectedLang(): String = LangUtil.selected(svc).code

    override fun reply(text: String, lang: String) {
        handler.removeCallbacks(autoClose)
        answer?.text = text
        bird?.state = BirdView.State.SPEAKING
        speak(text, lang)
    }

    override fun thinking() {
        bird?.state = BirdView.State.THINKING
        answer?.text = "…"
    }

    override fun listenNext() {
        listenAfter = true
    }

    override fun celebrate() {}

    override fun hideNow() = removeNow()

    override fun leaving() = dismiss(false)

    // ======================= Voice =======================
    private fun speak(text: String, lang: String) {
        val spoken = LangUtil.speakable(text)
        if (spoken.isEmpty()) {
            afterSpeaking()
            return
        }
        if (!ttsReady) {
            pendingSpeech = text to lang
            return
        }
        LangUtil.applyTts(tts, lang)
        MainActivity.speaking = true
        MainActivity.speakingSince = System.currentTimeMillis()
        tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "ov-${System.currentTimeMillis()}")
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        ttsReady = true
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                MainActivity.speaking = true
                MainActivity.speakingSince = System.currentTimeMillis()
            }

            override fun onDone(utteranceId: String?) {
                handler.post { afterSpeaking() }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                MainActivity.speaking = false
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handler.post { afterSpeaking() }
            }
        })
        pendingSpeech?.let {
            pendingSpeech = null
            speak(it.first, it.second)
        }
    }

    private fun afterSpeaking() {
        MainActivity.speaking = false
        if (root == null) return
        if (listenAfter) {
            listenAfter = false
            inFollowUp = false
            handler.postDelayed({ if (root != null) startListening() }, 120)
        } else if (followUps < 8) {
            // Baatcheet chalti rahe: jawab ke baad phir se suno
            followUps++
            inFollowUp = true
            handler.postDelayed({ if (root != null) startListening() }, 180)
        } else {
            bird?.state = BirdView.State.IDLE
            handler.postDelayed(autoClose, 6000)
        }
    }

    private fun startListening() {
        if (root == null) return
        handler.removeCallbacks(autoClose)
        tts?.stop()
        if (recognizer == null) {
            recognizer = Ears.create(svc).also { it.setRecognitionListener(this) }
        }
        val l = LangUtil.selected(svc)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, l.code)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, l.code)
            if (l.extra.isNotEmpty()) putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", l.extra)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 600L)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        try {
            recognizer?.cancel()
            BeepMuter.mute(svc)
            listening = true
            BtMic.route(svc) { if (listening) recognizer?.startListening(intent) }
            bird?.state = BirdView.State.LISTENING
            heard?.text = "Listening…"
        } catch (e: Exception) {
            handler.postDelayed(autoClose, 3000)
        }
    }

    // ======================= RecognitionListener =======================
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onRmsChanged(rmsdB: Float) {
        bird?.setLevel(rmsdB)
    }

    override fun onEndOfSpeech() {
        bird?.state = BirdView.State.THINKING
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!p.isNullOrBlank()) heard?.text = p
    }

    override fun onResults(results: Bundle?) {
        listening = false
        BeepMuter.unmute(svc)
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        if (matches.isEmpty()) {
            onError(SpeechRecognizer.ERROR_NO_MATCH)
            return
        }
        heard?.text = "“${matches[0]}”"
        bird?.state = BirdView.State.THINKING
        inFollowUp = false
        History.add(matches[0])
        processor.handle(matches)
    }

    override fun onError(error: Int) {
        listening = false
        BeepMuter.unmute(svc)
        if (Ears.shouldFallBack(error)) {
            recognizer?.destroy()
            recognizer = null
            handler.postDelayed({ if (root != null) startListening() }, 200)
            return
        }
        bird?.state = BirdView.State.IDLE
        val silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        if (inFollowUp && silence) {
            // aap chup ho gaye -> Chinu bhi chupchaap chali jaati hai
            handler.postDelayed(autoClose, 1200)
            return
        }
        heard?.text = "Didn't catch that — tap me to try again"
        handler.postDelayed(autoClose, 5000)
    }

    companion object {
        /** WakeService pauses while the overlay is open */
        @Volatile
        var active = false
    }
}


/** Rounded card whose border glows with a slowly turning green gradient */
private class GlowCard(ctx: Context) : FrameLayout(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.card) }
    private val edge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2.5f * d
    }
    private val glow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 8f * d
        alpha = 70
        maskFilter = android.graphics.BlurMaskFilter(6f * d, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val rect = android.graphics.RectF()
    private val m = android.graphics.Matrix()
    private val colors = intArrayOf(
        0xFF34C759.toInt(), 0xFF5AC8FA.toInt(), 0xFFB4EC51.toInt(), 0xFF30D158.toInt(), 0xFF64D2FF.toInt(), 0xFF34C759.toInt()
    )
    private var shader: android.graphics.SweepGradient? = null

    init {
        setWillNotDraw(false)
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)   // needed for the blur glow
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        shader = android.graphics.SweepGradient(w / 2f, h / 2f, colors, null)
        edge.shader = shader
        glow.shader = shader
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val inset = 5f * d
        rect.set(inset, inset, width - inset, height - inset)
        val r = 28f * d
        m.setRotate((android.os.SystemClock.uptimeMillis() % 4000L) / 4000f * 360f, width / 2f, height / 2f)
        shader?.setLocalMatrix(m)
        canvas.drawRoundRect(rect, r, r, glow)
        canvas.drawRoundRect(rect, r, r, fill)
        canvas.drawRoundRect(rect, r, r, edge)
        super.onDraw(canvas)
        postInvalidateOnAnimation()
    }
}
