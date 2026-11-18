package com.meraassistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/** Recent commands (shown in Settings) */
object History {
    val items = ArrayDeque<String>()

    fun add(text: String) {
        val t = Calendar.getInstance()
        items.addFirst(String.format(Locale.US, "%02d:%02d   %s", t.get(Calendar.HOUR_OF_DAY), t.get(Calendar.MINUTE), text))
        while (items.size > 8) items.removeLast()
    }
}

class MainActivity : Activity(), TextToSpeech.OnInitListener, ChinuHost {

    private lateinit var bird: BirdView
    private lateinit var heroBird: BirdView
    private lateinit var chatList: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var emptyState: View
    private lateinit var inputText: EditText
    private lateinit var sendButton: ImageView
    private lateinit var confetti: ConfettiView
    private lateinit var statusText: TextView
    private lateinit var heardText: TextView
    private lateinit var countdownCard: View
    private lateinit var countdownTitle: TextView
    private lateinit var countdownTimer: TextView
    private lateinit var commands: CommandProcessor
    private lateinit var prefs: SharedPreferences

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var listenOnResume = false
    private var resumed = false
    private var listening = false
    private var pendingListen = false
    private var followUpListen = false
    private var wakeCommand: String? = null
    private var wakeGreet = false
    /** true = the last thing came from the mic (so Chinu answers out loud) */
    private var voiceTurn = true
    /** First open: waiting to hear "I'm Chiku" / "I'm Komal" */
    private var askingOwner = false
    private var ownerRow: View? = null

    private val currentLang get() = LangUtil.selected(this)
    private val hinglish get() = currentLang.code != "en-IN"

    private val suggestions = listOf(
        "🎵  Play music" to "koi accha sa gaana chalao",
        "💧  Water reminder" to "har ghante paani peene ka reminder do",
        "📞  Call Chiku" to "chiku ko call karo",
        "🗓️  Daily routine" to "mera daily routine set karo",
        "📸  Screenshot" to "screenshot lo",
        "😂  Tell a joke" to "joke sunao"
    )

    private val neededPermissions: Array<String> by lazy {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        list.toTypedArray()
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateCountdown()
            countdownTimer.postDelayed(this, 1000)
        }
    }

    // ======================= Lifecycle =======================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Ui.glassScreen(this)   // 💧 liquid glass backdrop

        bird = findViewById(R.id.bird)
        heroBird = findViewById(R.id.heroBird)
        chatList = findViewById(R.id.chatList)
        chatScroll = findViewById(R.id.chatScroll)
        emptyState = findViewById(R.id.emptyState)
        inputText = findViewById(R.id.inputText)
        sendButton = findViewById(R.id.sendButton)
        confetti = findViewById(R.id.confetti)
        statusText = findViewById(R.id.statusText)
        heardText = findViewById(R.id.heardText)
        countdownCard = findViewById(R.id.countdownCard)
        countdownTitle = findViewById(R.id.countdownTitle)
        countdownTimer = findViewById(R.id.countdownTimer)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        Speaker.load(this)
        BeepMuter.forceUnmute(this)

        commands = CommandProcessor(this)
        tts = TextToSpeech(this, this)
        buildSuggestions()
        Birthday.schedule(this)
        Reminders.scheduleAll(this)
        DailyLove.schedule(this)
        DailyQ.schedule(this)
        Letters.scheduleAll(this)
        SelfieStreak.schedule(this)

        val micTap = View.OnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (listening) recognizer?.stopListening() else startListening()
        }
        bird.setOnClickListener(micTap)
        heroBird.setOnClickListener(micTap)
        sendButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val typed = inputText.text.toString().trim()
            if (typed.isNotEmpty()) sendTyped(typed)
            else if (listening) recognizer?.stopListening() else startListening()
        }
        inputText.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) {
                val typed = inputText.text.toString().trim()
                if (typed.isNotEmpty()) sendTyped(typed)
                true
            } else false
        }
        inputText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                sendButton.setImageResource(if (e.isNullOrBlank()) R.drawable.ic_mic else R.drawable.ic_send)
            }
        })
        inputText.setHorizontallyScrolling(false)
        inputText.maxLines = 4
        applyInsets()
        restoreChat()
        countdownCard.setOnClickListener { confetti.burst() }
        findViewById<View>(R.id.closetButton).setOnClickListener { startActivity(Intent(this, ClosetActivity::class.java)) }
        findViewById<View>(R.id.coupleButton).setOnClickListener { startActivity(Intent(this, CoupleActivity::class.java)) }
        buildSpecials()
        findViewById<View>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        listenOnResume = wantsListen(intent)
        handleWakeIntent(intent)
        askPermissions()
    }

    /** Opening from the app icon: Chinu stays quiet. From the tile / assistant button: she listens. */
    private fun wantsListen(i: Intent?): Boolean =
        i?.action == Intent.ACTION_ASSIST || i?.action == Intent.ACTION_VOICE_COMMAND ||
            i?.getBooleanExtra("listen", false) == true

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        listenOnResume = wantsListen(intent)
        handleWakeIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        visible = true
        current = this
        WakeService.pause()
        if (prefs.getBoolean("wake", true) && hasPermission(Manifest.permission.RECORD_AUDIO)) {
            WakeService.start(this)
            if (prefs.getBoolean("welcomed", false)) askBatteryExemption()
        }
        findViewById<TextView>(R.id.greetingText).text = greeting()
        if (!prefs.contains("chat") && emptyState.visibility == View.GONE) clearChat()
        applyUiLanguage()
        CoupleLink.start(this)
        CoupleLink.poll(this)
        Updater.autoCheck(this)
        Plus.scheduleAll(this)
        Special.scheduleCupid(this)
        Bday.maybeAutoOpen(this)
        setStatus(null)
        countdownTimer.removeCallbacks(ticker)
        ticker.run()
        if (handlePendingWake()) {
            listenOnResume = false
        } else if (listenOnResume && hasPermission(Manifest.permission.RECORD_AUDIO)) {
            listenOnResume = false
            autoListen()
        }
    }

    override fun onPause() {
        BeepMuter.unmute(this)
        resumed = false
        visible = false
        if (current === this) current = null
        countdownTimer.removeCallbacks(ticker)
        if (listening) {
            recognizer?.cancel()
            listening = false
            setBird(BirdView.State.IDLE)
        }
        super.onPause()
    }

    override fun onDestroy() {
        visible = false
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    // ======================= UI =======================
    private fun greeting(): String {
        val p = Profile.load(this)
        if (Profile.daysUntil(p.birthday) == 0) return if (Speaker.owner == true) "Today is ${p.name}'s birthday 🎉" else "Happy Birthday, ${p.name} 🎉"
        val part = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..20 -> "Good evening"
            else -> "Good night"
        }
        return if (Speaker.owner == null) "Hello 👋" else "$part, ${Speaker.name(p)}"
    }

    private fun buildSuggestions() {
        val box = findViewById<LinearLayout>(R.id.suggestions)
        val dp = resources.displayMetrics.density
        var row: LinearLayout? = null
        suggestions.forEachIndexed { i, (label, cmd) ->
            if (i % 2 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                if (i > 0) lp.topMargin = (10 * dp).toInt()
                box.addView(row, lp)
            }
            val chip = TextView(this).apply {
                text = label
                textSize = 14.5f
                setTextColor(getColor(R.color.text))
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setBackgroundResource(R.drawable.bg_tile)
                setPadding((14 * dp).toInt(), (15 * dp).toInt(), (10 * dp).toInt(), (15 * dp).toInt())
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    sendTyped(cmd)
                }
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (i % 2 == 1) lp.leftMargin = (10 * dp).toInt()
            row?.addView(chip, lp)
        }
    }

    private fun runText(cmd: String) {
        if (listening) {
            recognizer?.cancel()
            listening = false
        }
        addBubble(cmd, true)
        if (askingOwner) {
            ownerFromText(cmd)
            return
        }
        History.add(cmd)
        setBird(BirdView.State.THINKING)
        commands.handle(listOf(cmd))
    }

    private fun updateCountdown() {
        val p = Profile.load(this)
        val days = Profile.daysUntil(p.birthday)
        if (days == null || days > 30) {
            countdownCard.visibility = View.GONE
            return
        }
        countdownCard.visibility = View.VISIBLE
        if (days == 0) {
            countdownTitle.text = "Today is ${p.name}'s birthday"
            countdownTimer.text = "Happy Birthday 💚"
            return
        }
        val target = Profile.nextDate(p.birthday) ?: return
        val ms = (target.timeInMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val d = ms / 86_400_000L
        val h = (ms / 3_600_000L) % 24
        val m = (ms / 60_000L) % 60
        val s = (ms / 1000L) % 60
        countdownTitle.text = "${p.name}'s birthday · ${Profile.pretty(p.birthday)}"
        countdownTimer.text = String.format(Locale.US, "%dd %02dh %02dm %02ds", d, h, m, s)
    }

    private fun askBatteryExemption() {
        if (prefs.getBoolean("askedBattery", false)) return
        prefs.edit().putBoolean("askedBattery", true).apply()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
        }
    }

    // ======================= 🎙️ Hi Chinu =======================
    private fun handleWakeIntent(i: Intent?) {
        if (i == null || !i.getBooleanExtra(WakeService.EXTRA_WAKE, false)) return
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        val cmd = i.getStringExtra(WakeService.EXTRA_COMMAND).orEmpty().trim()
        if (cmd.isNotEmpty()) wakeCommand = cmd else wakeGreet = true
        i.removeExtra(WakeService.EXTRA_WAKE)
        listenOnResume = false
    }

    private fun handlePendingWake(): Boolean {
        if (!ttsReady) return wakeCommand != null || wakeGreet
        val cmd = wakeCommand
        if (cmd != null) {
            wakeCommand = null
            runText(cmd)
            return true
        }
        if (wakeGreet) {
            wakeGreet = false
            voiceTurn = true
            val p = Profile.load(this)
            pendingListen = true
            val who = Speaker.name(p)
            speak(Bday.greet(this) ?: msg("हाँ जी?", "Yes?"))
            return true
        }
        return false
    }

    // ======================= ChinuHost =======================
    override val context: Context get() = this
    override fun selectedLang(): String = currentLang.code
    override fun reply(text: String, lang: String) = speak(text, lang)
    override fun thinking() {
        setBird(BirdView.State.THINKING)
        setStatus("Thinking…")
    }
    override fun listenNext() {
        pendingListen = true
    }
    override fun celebrate() = confetti.burst()
    override fun hideNow() {
        moveTaskToBack(true)
    }
    override fun leaving() {}

    // ======================= Listening =======================
    private fun autoListen() {
        if (tts?.isSpeaking == true || !ttsReady) pendingListen = true else startListening()
    }

    private fun startListening() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            askPermissions()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            note("Speech recognition not found. Please update the Google app.")
            return
        }
        tts?.stop()
        if (recognizer == null) {
            recognizer = Ears.create(this).also { it.setRecognitionListener(listener) }
        }
        val l = currentLang
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, l.code)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, l.code)
            if (l.extra.isNotEmpty()) putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", l.extra)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 600L)
        }
        recognizer?.cancel()
        BeepMuter.mute(this)
        listening = true
        BtMic.route(this) { if (listening) recognizer?.startListening(intent) }
        setBird(BirdView.State.LISTENING)
        showLive("")
        setStatus("Listening…")
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onRmsChanged(rmsdB: Float) {
            bird.setLevel(rmsdB)
            heroBird.setLevel(rmsdB)
        }

        override fun onEndOfSpeech() {
            setBird(BirdView.State.THINKING)
        }

        override fun onError(error: Int) {
            listening = false
            BeepMuter.unmute(this@MainActivity)
            if (Ears.shouldFallBack(error)) {
                // on-device ears not ready for this language -> use normal ones and try again
                recognizer?.destroy()
                recognizer = null
                bird.postDelayed({ if (resumed) startListening() }, 200)
                return
            }
            setBird(BirdView.State.IDLE)
            val wasFollowUp = followUpListen
            followUpListen = false
            if (wasFollowUp && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                return    // baatcheet khatam, chup rehna hi theek hai
            }
            showLive(null)
            setStatus(null)
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> setStatus("Didn't hear anything · tap the mic to try again")
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> note("No internet connection")
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> note("Please allow microphone access")
                SpeechRecognizer.ERROR_CLIENT -> {}
                else -> setStatus("Something went wrong · try again")
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!p.isNullOrBlank()) showLive(p)
        }

        override fun onResults(results: Bundle?) {
            listening = false
            followUpListen = false
            BeepMuter.unmute(this@MainActivity)
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            if (matches.isEmpty()) {
                setBird(BirdView.State.IDLE)
                showLive(null)
                setStatus("Didn't catch that · try again")
                return
            }
            showLive(null)
            voiceTurn = true
            addBubble(matches[0], true)
            if (askingOwner) {
                setBird(BirdView.State.IDLE)
                ownerFromText(matches.joinToString(" "))
                return
            }
            History.add(matches[0])
            setBird(BirdView.State.THINKING)
            commands.handle(matches)
        }
    }

    // ======================= Speaking =======================
    fun speak(text: String) = speak(text, if (hinglish) "hi-IN" else "en-IN")

    /** 💬 The partner's message (sent through their Chinu): show it in our chat and say it out loud */
    fun incoming(shown: String, spoken: String) {
        addBubble(shown, false)
        Voice.say(this, spoken)
    }

    fun speak(text: String, lang: String) {
        addBubble(text, false)
        setStatus(null)
        val spoken = LangUtil.speakable(text)
        if (ttsReady && spoken.isNotEmpty() && voiceTurn) {
            LangUtil.applyTts(tts, lang)
            speaking = true
            speakingSince = System.currentTimeMillis()
            setBird(BirdView.State.SPEAKING)
            tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "chinu-${System.currentTimeMillis()}")
        } else {
            setBird(BirdView.State.IDLE)
            if (pendingListen && resumed && voiceTurn) {
                pendingListen = false
                startListening()
            }
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        ttsReady = true
        LangUtil.applyTts(tts, if (hinglish) "hi-IN" else "en-IN")
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                speaking = true
                speakingSince = System.currentTimeMillis()
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                speaking = false
            }

            override fun onDone(utteranceId: String?) {
                runOnUiThread { afterSpeaking() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread { afterSpeaking() }
            }
        })
        welcome()
        handlePendingWake()
    }

    private fun msg(hinglishText: String, english: String) = if (hinglish) hinglishText else english

    /** First open: welcome by name · birthday: confetti + wish · before birthday: daily countdown */
    private fun welcome() {
        val p = Profile.load(this)
        if (!Speaker.hasOwner(this)) {
            askOwner(p)
            return
        }
        val boy = Speaker.owner == true
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val days = Profile.daysUntil(p.birthday)
        val bd = Profile.pretty(p.birthday)
        val todayKey = year * 1000 + Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val soon = if (boy) when (days ?: -1) {
            1 -> msg(" और हाँ... कल ${p.name} का बर्थडे है! 🎂 तैयारी हो गई?", " And... tomorrow is ${p.name}'s birthday! 🎂 All set?")
            in 2..7 -> msg(" और हाँ... ${p.name} का बर्थडे $bd को है, बस $days दिन बाकी हैं! 🎂",
                           " And... ${p.name}'s birthday is on $bd, just $days days to go! 🎂")
            else -> ""
        } else when (days ?: -1) {
            1 -> msg(" और हाँ... कल आपका बर्थडे है! 🎂 कल का पूरा दिन सिर्फ़ आपका है।",
                     " And guess what... tomorrow is your birthday! 🎂")
            in 2..7 -> msg(" और हाँ... बर्थडे आने वाला है! 🎂 $bd को आपका बर्थडे है, बस $days दिन बाकी हैं!",
                           " And... birthday soon! 🎂 Your birthday is on $bd, just $days days to go!")
            else -> ""
        }
        if (days == 0 && prefs.getInt("bdayYear", 0) != year) {
            prefs.edit().putInt("bdayYear", year).putBoolean("welcomed", true).putInt("countdownDay", todayKey).apply()
            confetti.burst(220)
            confetti.postDelayed({ confetti.burst(160) }, 2500)
            if (boy) speak(msg("आज ${p.name} का बर्थडे है! 🎂 उन्हें सबसे पहले विश करना मत भूलना 💚 बोलो \"${p.name} को कॉल करो\", मैं तुरंत लगा दूँगी।",
                               "Today is ${p.name}'s birthday! 🎂 Don't forget to wish her first 💚 Say \"call ${p.name}\" and I'll dial."))
            else speak(Birthday.randomWish(p, hinglish) + " 💌 " + p.message)
        } else if (!prefs.getBoolean("welcomed", false)) {
            prefs.edit().putBoolean("welcomed", true).putInt("countdownDay", todayKey).apply()
            if (soon.isNotEmpty()) confetti.burst(120)
            speak((if (boy) msg(
                "हाय ${p.partner}! 💚 अब से मैं आपको ${p.partner} ही बोलूँगी। आपने मुझे ${p.name} के लिए बनाया है, और आपकी भी हर काम में पूरी मदद करूँगी।",
                "Hi ${p.partner}! 💚 From now on I'll call you ${p.partner}. You made me for ${p.name}, and I'll help you with everything too.")
            else msg(
                "हाय ${p.name}! 💚 मैं चीनू हूँ। मुझे ${p.partner} ने सिर्फ़ आपके लिए बनवाया है।",
                "Hi ${p.name}! 💚 I'm Chinu. ${p.partner} made me only for you.")) + soon)
        } else if (soon.isNotEmpty() && prefs.getInt("countdownDay", 0) != todayKey) {
            prefs.edit().putInt("countdownDay", todayKey).apply()
            speak("${Speaker.name(p)}!" + soon)
        } else if (Together.wish(this) != null && prefs.getInt("specialDay", 0) != todayKey) {
            prefs.edit().putInt("specialDay", todayKey).apply()
            confetti.burst(200)
            speak(Together.wish(this) ?: "")
        } else if (pendingListen && resumed) {
            pendingListen = false
            startListening()
        }
    }

    private fun afterSpeaking() {
        speaking = false
        if (bird.state == BirdView.State.SPEAKING) setBird(BirdView.State.IDLE)
        if (pendingListen && resumed && !listening) {
            pendingListen = false
            bird.postDelayed({ if (resumed && !listening) startListening() }, 300)
            return
        }
        if (prefs.getBoolean("convo", true) && resumed && !listening) {
            followUpListen = true
            bird.postDelayed({ if (resumed && !listening) startListening() }, 300)
        }
    }

    // ======================= 💖 Closet · Us · Love Wheel =======================
    private fun buildSpecials() {
        val box = findViewById<LinearLayout>(R.id.specials)
        box.removeAllViews()
        val p = Profile.load(this)
        box.orientation = LinearLayout.VERTICAL
        val items = listOf(
            Triple("👠", Ui.t(this, "${p.name}'s Closet", "${p.name} ki Closet"), ClosetActivity::class.java),
            Triple("💞", Ui.t(this, "Us two", "Hum dono"), CoupleActivity::class.java),
            Triple("🎡", "Love Wheel", WheelActivity::class.java),
            Triple("📝", Ui.t(this, "Diary", "Diary"), DiaryActivity::class.java),
            // ✨ just-for-us extras — always here, connected or not
            Triple(if (SelfieStreak.streak(this) > 0) "🔥" else "📸", Ui.t(this, "Streak", "Streak") +
                (SelfieStreak.streak(this).takeIf { it > 0 }?.let { " $it" } ?: ""), SelfieActivity::class.java),
            Triple("💌", Ui.t(this, "Letters", "Khat"), LettersActivity::class.java),
            Triple("❓", Ui.t(this, "Question", "Sawaal"), QuestionActivity::class.java),
            Triple("🌙", Ui.t(this, "Story", "Kahani"), StoryActivity::class.java)
        )
        val lines = ArrayList<LinearLayout>()
        items.forEachIndexed { i, (e, label, cls) ->
            if (i % 4 == 0) {
                val ln = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                lines.add(ln)
                box.addView(ln, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0) topMargin = dp(10)
                })
            }
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = Ui.liquid(this@MainActivity, 20f, intArrayOf(0xFFFFE0EF.toInt(), 0xFFEDE0FF.toInt()))
                elevation = dp(2).toFloat()
                setPadding(dp(6), dp(14), dp(6), dp(12))
                addView(TextView(context).apply { text = e; textSize = 26f; gravity = Gravity.CENTER })
                addView(TextView(context).apply {
                    text = label
                    textSize = 13f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(0xFF3B1F4A.toInt())
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                })
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    startActivity(Intent(this@MainActivity, cls))
                }
            }
            Ui.liquidPress(tile)
            lines.last().addView(tile, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i % 4 > 0) leftMargin = dp(10)
            })
        }
    }

    /** 🥺 One tap: tell the partner you miss them */
    private fun buildMissButton() {
        val sugg = findViewById<LinearLayout>(R.id.suggestions)
        val parent = sugg.parent as LinearLayout
        parent.findViewWithTag<View>("missBtn")?.let { parent.removeView(it) }
        val other = Speaker.other(Profile.load(this))
        val b = TextView(this).apply {
            tag = "missBtn"
            text = Ui.t(this@MainActivity, "🥺  Miss you, $other", "🥺  $other ko miss you bhejo")
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFFF6FB1.toInt(), 0xFFB06BFF.toInt())
            ).apply { cornerRadius = dp(24).toFloat() }
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                sendTyped(Ui.t(this@MainActivity, "Send miss you to $other", "$other ko miss you bhejo"))
            }
        }
        parent.addView(b, parent.indexOfChild(sugg), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
    }

    /** 💞 "601 days together" pill under the greeting */
    private fun buildTogetherPill() {
        val greet = findViewById<TextView>(R.id.greetingText)
        val parent = greet.parent as LinearLayout
        parent.findViewWithTag<View>("togetherPill")?.let { parent.removeView(it) }
        val days = Together.days(this)
        if (days < 0) return
        val p = Profile.load(this)
        val pill = TextView(this).apply {
            tag = "togetherPill"
            text = "💞  ${p.partner} & ${p.name} · " + Ui.t(this@MainActivity, "$days days together", "$days din saath mein")
            textSize = 13.5f
            setTextColor(0xFFB0306A.toInt())
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0x22FF4F9A)
                setStroke(dp(1), 0x44FF4F9A)
            }
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setOnClickListener { startActivity(Intent(this@MainActivity, CoupleActivity::class.java)) }
        }
        parent.addView(pill, parent.indexOfChild(greet) + 1, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
    }

    /** English / Hinglish for the screen text */
    private fun applyUiLanguage() {
        inputText.hint = Ui.t(this, "Message Chinu…", "Chinu ko likho…")
        findViewById<TextView>(R.id.subtitleText).text = Ui.t(this,
            "Ask me anything, or tell me what to do.\nSay “Hey Chinu” anytime.",
            "Kuch bhi poocho, ya kaam batao.\nKabhi bhi bolo “Hey Chinu”.")
        buildSpecials()
        buildMissButton()
        buildTogetherPill()
    }

    // ======================= 🙋 Whose phone is this? =======================
    private fun askOwner(p: Profile.Data) {
        askingOwner = true
        voiceTurn = true
        if (ownerRow == null) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun tile(label: String, boy: Boolean) = TextView(this).apply {
                text = label
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.text))
                setBackgroundResource(R.drawable.bg_tile)
                setPadding(dp(8), dp(16), dp(8), dp(16))
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    chooseOwner(boy)
                }
            }
            val l1 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val l2 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) }
            row.addView(tile("🙋‍♂️  I'm ${p.partner}", true), l1)
            row.addView(tile("🙋‍♀️  I'm ${p.name}", false), l2)
            emptyState.visibility = View.GONE
            chatList.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            ownerRow = row
        }
        pendingListen = true
        speak(msg("नमस्ते! 💚 मैं चीनू हूँ। सबसे पहले ये बताओ — आप ${p.partner} हो या ${p.name}?",
                  "Hello! 💚 I'm Chinu. First, tell me — are you ${p.partner} or ${p.name}?"))
    }

    private fun ownerFromText(text: String) {
        val p = Profile.load(this)
        val words = NameMatcher.clean(text).lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val boy = words.any { NameMatcher.score(it, p.partner) >= 0.75 } ||
            Regex("chiku|cheeku|चीकू|चिकू|चीकु").containsMatchIn(text.lowercase())
        val girl = words.any { NameMatcher.score(it, p.name) >= 0.75 } ||
            Regex("komal|कोमल").containsMatchIn(text.lowercase())
        when {
            boy && !girl -> chooseOwner(true)
            girl && !boy -> chooseOwner(false)
            else -> {
                pendingListen = true
                speak(msg("माफ़ करना, ठीक से सुनाई नहीं दिया। बस इतना बोलो: \"${p.partner}\" या \"${p.name}\"",
                          "Sorry, I didn't get that. Just say \"${p.partner}\" or \"${p.name}\""))
            }
        }
    }

    private fun chooseOwner(boy: Boolean) {
        askingOwner = false
        pendingListen = false
        voiceTurn = true
        Speaker.setOwner(this, boy)
        ownerRow?.let { chatList.removeView(it) }
        ownerRow = null
        findViewById<TextView>(R.id.greetingText).text = greeting()
        prefs.edit().putBoolean("welcomed", false).apply()
        welcome()
    }

    // ======================= 💬 Chat UI =======================
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun setBird(st: BirdView.State) {
        bird.state = st
        heroBird.state = st
    }

    /** Small grey line under the name: Online / Listening… / Thinking… */
    private fun setStatus(text: String?) {
        statusText.text = text ?: if (prefs.getBoolean("wake", true)) "Online · say “Hey Chinu”" else "Online"
    }

    private fun showLive(text: String?) {
        if (text == null) {
            heardText.visibility = View.GONE
            return
        }
        heardText.visibility = View.VISIBLE
        heardText.text = if (text.isBlank()) "Listening…" else text
    }

    private fun sendTyped(text: String) {
        inputText.setText("")
        voiceTurn = false
        tts?.stop()
        pendingListen = false
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(inputText.windowToken, 0)
        runText(text)
    }

    /** Adds a chat bubble (user = right, green · Chinu = left, card) */
    private fun addBubble(text: String, user: Boolean, save: Boolean = true, animate: Boolean = true) {
        if (text.isBlank()) return
        emptyState.visibility = View.GONE
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 16f
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextColor(getColor(if (user) R.color.bubbleText else R.color.text))
            setBackgroundResource(if (user) R.drawable.bg_bubble_user else R.drawable.bg_bubble_bot)
            setPadding(dp(15), dp(10), dp(15), dp(11))
            maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
            setTextIsSelectable(false)
            setOnLongClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Chinu", text))
                android.widget.Toast.makeText(this@MainActivity, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                true
            }
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (user) Gravity.END else Gravity.START
            topMargin = dp(if (user) 14 else 6)
        }
        chatList.addView(bubble, lp)
        if (animate) {
            bubble.alpha = 0f
            bubble.translationY = dp(14).toFloat()
            bubble.animate().alpha(1f).translationY(0f).setDuration(220).start()
        }
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        if (save) saveMessage(text, user)
    }

    /** Centered grey system note (errors etc.) */
    private fun note(text: String) {
        emptyState.visibility = View.GONE
        val tv = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(getColor(R.color.text2))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(4))
        }
        chatList.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // Keep the last 40 messages so the chat is still there next time
    private fun saveMessage(text: String, user: Boolean) {
        try {
            val a = JSONArray(prefs.getString("chat", "[]"))
            a.put(JSONObject().put("t", text).put("u", user))
            val keep = JSONArray()
            for (i in maxOf(0, a.length() - 40) until a.length()) keep.put(a.get(i))
            prefs.edit().putString("chat", keep.toString()).apply()
        } catch (e: Exception) {
        }
    }

    private fun restoreChat() {
        try {
            val a = JSONArray(prefs.getString("chat", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                addBubble(o.optString("t"), o.optBoolean("u"), save = false, animate = false)
            }
        } catch (e: Exception) {
        }
        setStatus(null)
    }

    fun clearChat() {
        prefs.edit().remove("chat").apply()
        for (i in chatList.childCount - 1 downTo 0) {
            val v = chatList.getChildAt(i)
            if (v !== emptyState) chatList.removeView(v)
        }
        emptyState.visibility = View.VISIBLE
    }

    /** Edge-to-edge: keep header below the status bar and the input above the keyboard */
    private fun applyInsets() {
        val root = findViewById<View>(R.id.root)
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
                if (ime.bottom > 0) chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    // ======================= Permissions =======================
    private fun hasPermission(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        val missing = neededPermissions.filterNot { hasPermission(it) }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasPermission(Manifest.permission.RECORD_AUDIO) && prefs.getBoolean("wake", true)) {
            WakeService.start(this)
        }
    }

    companion object {
        /** WakeService checks these to decide when to listen */
        @Volatile
        var visible = false

        /** the open chat screen (to show the partner's messages in it) */
        @Volatile
        var current: MainActivity? = null

        @Volatile
        var speaking = false

        @Volatile
        var speakingSince = 0L
    }
}
