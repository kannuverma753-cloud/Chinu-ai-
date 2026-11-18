package com.meraassistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Interface implemented by every Chinu screen (full app + Siri-style overlay). */
interface ChinuHost {
    val context: Context
    fun selectedLang(): String
    fun reply(text: String, lang: String)
    fun thinking()
    fun listenNext()
    fun celebrate()
    fun hideNow()
    fun leaving()
}

/**
 * Google's speech recogniser plays a short "beep" when it starts/stops.
 * We mute the sound streams for that moment so Chinu stays silent.
 */
/**
 * 🎧 Earbuds / Bluetooth headset microphone.
 * While Chinu listens, the mic of the connected buds is used (Bluetooth "call" link),
 * and it is released right after so music sounds normal again.
 */
object BtMic {
    @Volatile
    private var on = false

    /** Bluetooth headset mic, if buds are connected */
    fun headset(ctx: Context): android.media.AudioDeviceInfo? {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                am.availableCommunicationDevices.firstOrNull {
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
                }
            } else {
                am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** The buds' mic as a recording input (for AudioRecord.setPreferredDevice) */
    fun input(ctx: Context): android.media.AudioDeviceInfo? {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        return try {
            am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS).firstOrNull {
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (android.os.Build.VERSION.SDK_INT >= 31 && it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun enabled(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("budsMic", true)

    /** Switch the mic to the buds (if any). Returns true if it had to switch now (needs a moment). */
    fun use(ctx: Context): Boolean {
        if (!enabled(ctx) || on) return false
        val dev = headset(ctx) ?: return false
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                on = am.setCommunicationDevice(dev)
            } else {
                @Suppress("DEPRECATION")
                am.startBluetoothSco()
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = true
                on = true
            }
            on
        } catch (e: Exception) {
            false
        }
    }

    /** Run start() with the buds' mic ready (waits a moment the first time) */
    fun route(ctx: Context, start: () -> Unit) {
        if (use(ctx)) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ start() }, 700)
        else start()
    }

    fun release(ctx: Context) {
        if (!on) return
        on = false
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            else {
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                am.stopBluetoothSco()
            }
        } catch (e: Exception) {
        }
    }
}

object BeepMuter {
    private val streams = intArrayOf(
        android.media.AudioManager.STREAM_SYSTEM,
        android.media.AudioManager.STREAM_NOTIFICATION,
        android.media.AudioManager.STREAM_MUSIC
    )
    private val muted = HashSet<Int>()

    fun mute(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        for (s in streams) {
            if (s == android.media.AudioManager.STREAM_MUSIC && am.isMusicActive) continue
            if (s in muted) continue
            try {
                am.adjustStreamVolume(s, android.media.AudioManager.ADJUST_MUTE, 0)
                muted.add(s)
            } catch (e: Exception) {
            }
        }
    }

    /** Safety: agar app beech mein band hua ho to sab awaazein wapas ON */
    fun forceUnmute(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        for (s in streams) {
            try {
                if (am.isStreamMute(s)) am.adjustStreamVolume(s, android.media.AudioManager.ADJUST_UNMUTE, 0)
            } catch (e: Exception) {
            }
        }
        muted.clear()
    }

    fun unmute(ctx: Context) {
        BtMic.release(ctx)
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        for (s in muted) {
            try {
                am.adjustStreamVolume(s, android.media.AudioManager.ADJUST_UNMUTE, 0)
            } catch (e: Exception) {
            }
        }
        muted.clear()
    }
}

/**
 * Speech recogniser factory. The phone's ON-DEVICE recogniser makes no beep sounds,
 * so it's used when available; otherwise Google's normal one (with its beep muted).
 */
object Ears {
    @Volatile
    var onDeviceFailed = false

    fun create(ctx: Context): android.speech.SpeechRecognizer {
        // Google online recogniser is far more accurate for Hindi/Hinglish.
        // The silent on-device one is used only if the user turns it on in Settings.
        val silent = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("silentEars", false)
        if (silent && !onDeviceFailed && android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                if (android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
                    return android.speech.SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                }
            } catch (e: Exception) {
            }
        }
        return android.speech.SpeechRecognizer.createSpeechRecognizer(ctx)
    }

    /** Language not downloaded on-device etc. -> switch to the normal recogniser */
    fun shouldFallBack(error: Int): Boolean {
        if (onDeviceFailed) return false
        val bad = error == 12 || error == 13 || error == android.speech.SpeechRecognizer.ERROR_CLIENT ||
            error == android.speech.SpeechRecognizer.ERROR_SERVER
        if (bad) onDeviceFailed = true
        return bad
    }
}

/** Languages + script detection + text-to-speech voice selection. */
object LangUtil {
    class Lang(val label: String, val code: String, val extra: Array<String> = emptyArray())

    val languages = listOf(
        Lang("Auto · Hinglish (Hindi + English)", "hi-IN", arrayOf("en-IN", "en-US")),
        Lang("English", "en-IN", arrayOf("en-US", "hi-IN")),
        Lang("Hindi", "hi-IN"),
        Lang("Marathi", "mr-IN", arrayOf("en-IN")),
        Lang("Bengali", "bn-IN", arrayOf("en-IN")),
        Lang("Tamil", "ta-IN", arrayOf("en-IN")),
        Lang("Telugu", "te-IN", arrayOf("en-IN")),
        Lang("Gujarati", "gu-IN", arrayOf("en-IN")),
        Lang("Kannada", "kn-IN", arrayOf("en-IN")),
        Lang("Malayalam", "ml-IN", arrayOf("en-IN")),
        Lang("Punjabi", "pa-IN", arrayOf("en-IN")),
        Lang("Urdu", "ur-IN", arrayOf("en-IN"))
    )

    fun selected(ctx: Context): Lang {
        val i = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("lang", 0)
        return languages[i.coerceIn(0, languages.size - 1)]
    }

    /** Detects the language from the script the user spoke in. */
    fun detect(text: String, selected: String): String {
        for (c in text) {
            when (Character.UnicodeBlock.of(c)) {
                Character.UnicodeBlock.DEVANAGARI -> return if (selected == "mr-IN") "mr-IN" else "hi-IN"
                Character.UnicodeBlock.BENGALI -> return "bn-IN"
                Character.UnicodeBlock.TAMIL -> return "ta-IN"
                Character.UnicodeBlock.TELUGU -> return "te-IN"
                Character.UnicodeBlock.GUJARATI -> return "gu-IN"
                Character.UnicodeBlock.KANNADA -> return "kn-IN"
                Character.UnicodeBlock.MALAYALAM -> return "ml-IN"
                Character.UnicodeBlock.GURMUKHI -> return "pa-IN"
                Character.UnicodeBlock.ARABIC -> return "ur-IN"
                else -> {}
            }
        }
        return if (selected == "hi-IN") "hi-IN" else "en-IN"
    }

    /** Sets the TTS voice for this language, with safe fallbacks. */
    fun applyTts(tts: TextToSpeech?, lang: String) {
        if (tts == null) return
        // Mark Chinu's voice as "assistant" audio so it is never mistaken for a song playing
        try {
            tts.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        } catch (e: Exception) {
        }
        for (tag in listOf(lang, "hi-IN", "en-IN")) {
            val r = tts.setLanguage(Locale.forLanguageTag(tag))
            if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) return
        }
        tts.language = Locale.ENGLISH
    }

    /** Emojis are not read aloud. */
    fun speakable(text: String) =
        text.replace(Regex("[\\p{So}\\p{Sk}\\p{Cs}\\uFE0F\\u200D*#_`]"), "").trim()
}

/**
 * Chinu's AI brain: answers any question directly on screen.
 * - With a free Gemini key: full AI (any question, any language).
 * - Without a key: answers "who/what is ..." from Wikipedia.
 */
object Brain {
    private val main = Handler(Looper.getMainLooper())
    private val history = ArrayDeque<Pair<String, String>>()
    // Fastest first; every model has its own free quota, so on "too many requests" we try the next one
    private val models = listOf("gemini-flash-lite-latest", "gemini-flash-latest", "gemini-2.5-flash-lite", "gemini-2.5-flash", "gemini-3.5-flash")

    @Volatile
    private var goodModel: String? = null

    /** App mein daali key, warna build ke waqt GitHub Secret se aayi key */
    fun key(ctx: Context): String {
        val own = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("geminiKey", "").orEmpty().trim()
        return own.ifEmpty { BuildConfig.GEMINI_KEY.trim() }
    }

    fun clearHistory() {
        synchronized(history) { history.clear() }
    }

    fun setKey(ctx: Context, k: String) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("geminiKey", k.trim()).apply()
        goodModel = null
    }

    fun ask(ctx: Context, question: String, lang: String, done: (String) -> Unit) {
        val app = ctx.applicationContext
        val p = Profile.load(app)
        Thread {
            val ans = try {
                val k = key(app)
                (if (k.isNotEmpty()) gemini(k, question, p) else null)
                    ?: wikipedia(question, lang)
                    ?: fallback(lang, k.isNotEmpty())
            } catch (e: Exception) {
                fallback(lang, key(app).isNotEmpty())
            }
            main.post { done(ans) }
        }.start()
    }

    private fun fallback(lang: String, hasKey: Boolean): String {
        val en = lang == "en-IN"
        return when {
            hasKey && en -> "I couldn't get an answer right now. Please check your internet and try again."
            hasKey -> "अभी जवाब नहीं मिल पाया। इंटरनेट चेक करके फिर से पूछो।"
            en -> "I couldn't find that. Add a free Gemini AI key in Chinu's settings and I'll answer anything!"
            else -> "इसका जवाब मुझे नहीं मिला। ऐप में फ़्री जेमिनी AI key डाल दो, फिर मैं हर सवाल का जवाब दे पाऊँगी!"
        }
    }

    // ---------------- 🎵 Pick a real song for a mood ----------------
    private val recentSongs = ArrayDeque<String>()

    private val moodSongs = mapOf(
        "cheer" to listOf("Zinda - Siddharth Mahadevan", "Ilahi - Arijit Singh", "Phir Se Ud Chala - Mohit Chauhan", "Aashayein - KK",
            "Kar Har Maidaan Fateh - Sukhwinder Singh", "Zindagi Na Milegi Dobara - Shankar Ehsaan Loy", "Lakshya - Shankar Mahadevan", "Love You Zindagi - Amit Trivedi"),
        "happy" to listOf("Badtameez Dil - Benny Dayal", "Gallan Goodiyaan", "London Thumakda - Labh Janjua", "Nashe Si Chadh Gayi - Arijit Singh",
            "Kala Chashma", "Senorita - Zindagi Na Milegi Dobara", "Balam Pichkari", "Ude Dil Befikre - Benny Dayal"),
        "hard" to listOf("Zinda - Bhaag Milkha Bhaag", "Sultan Title Track", "Chak De India - Sukhwinder Singh", "Malhari - Vishal Dadlani",
            "Apna Time Aayega - Ranveer Singh", "Brothers Anthem - Vishal Dadlani", "Jee Karda - Badlapur", "Dangal Title Track"),
        "romantic" to listOf("Kesariya - Arijit Singh", "Raataan Lambiyan - Jubin Nautiyal", "Tum Hi Ho - Arijit Singh", "Pehla Nasha - Udit Narayan",
            "Tera Ban Jaunga - Akhil Sachdeva", "Tum Se Hi - Mohit Chauhan", "Hawayein - Arijit Singh", "Agar Tum Mil Jao - Shreya Ghoshal"),
        "sad" to listOf("Channa Mereya - Arijit Singh", "Agar Tum Saath Ho - Arijit Singh", "Kabira - Rekha Bhardwaj", "Tujhe Kitna Chahne Lage - Arijit Singh",
            "Phir Le Aaya Dil - Arijit Singh", "Humdard - Arijit Singh"),
        "party" to listOf("Kala Chashma", "Tareefan - Badshah", "Lungi Dance - Honey Singh", "Abhi Toh Party Shuru Hui Hai - Badshah",
            "Kar Gayi Chull - Badshah", "Swag Se Swagat - Vishal Dadlani", "Dil Dhadakne Do", "Proper Patola - Badshah"),
        "chill" to listOf("Iktara - Kavita Seth", "Kun Faya Kun - A R Rahman", "Khairiyat - Arijit Singh", "Shayad - Arijit Singh",
            "Pee Loon - Mohit Chauhan", "Ilahi - Arijit Singh", "Phir Se Ud Chala - Mohit Chauhan"),
        "devotional" to listOf("Hanuman Chalisa - Hariharan", "Om Jai Jagdish Hare", "Shiv Tandav Stotram", "Achyutam Keshavam"),
        "old" to listOf("Lag Ja Gale - Lata Mangeshkar", "Pal Pal Dil Ke Paas - Kishore Kumar", "Tere Bina Zindagi Se - Lata Mangeshkar",
            "Yeh Shaam Mastani - Kishore Kumar", "Ek Ajnabee Haseena Se - Kishore Kumar")
    )

    private fun moodOf(t: String): String {
        val x = t.lowercase()
        fun any(vararg w: String) = w.any { x.contains(it) }
        return when {
            any("sad song", "sad gaana", "sad gaane", "dard", "दर्द", "breakup", "ब्रेकअप", "रोने", "udaas gaane", "उदास गाने") -> "sad"
            any("mood accha nahi", "mood acha nahi", "mood kharab", "mood off", "moodoff", "upset", "tension", "टेंशन", "udaas", "उदास",
                "dukhi", "दुखी", "मूड अच्छा नहीं", "मूड खराब", "मूड ठीक नहीं", "mood theek nahi", "low feel", "feeling low", "sad") -> "cheer"
            any("hard", "हार्ड", "gym", "जिम", "workout", "energy", "एनर्जी", "josh", "जोश", "motivation", "मोटिवेशन", "power", "पावर", "dhamakedar", "धमाकेदार") -> "hard"
            any("romantic", "रोमांटिक", "pyaar", "प्यार", "love", "लव", "ishq", "इश्क़") -> "romantic"
            any("party", "पार्टी", "dance", "डांस", "nachne", "नाचने") -> "party"
            any("khushi", "ख़ुशी", "खुशी", "happy", "हैप्पी", "mast", "मस्त", "cheerful") -> "happy"
            any("chill", "relax", "रिलैक्स", "sukoon", "सुकून", "shant", "शांत", "neend", "नींद", "sleep", "calm", "soft") -> "chill"
            any("bhakti", "भक्ति", "bhajan", "भजन", "devotional", "aarti", "आरती") -> "devotional"
            any("purane", "पुराने", "old song", "old is gold", "retro", "रेट्रो") -> "old"
            else -> "cheer"
        }
    }

    private fun localPick(request: String): String {
        val list = moodSongs[moodOf(request)] ?: moodSongs.getValue("cheer")
        return (list.filter { it !in recentSongs }.ifEmpty { list }).random()
    }

    private fun remember(song: String) {
        recentSongs.addLast(song)
        while (recentSongs.size > 8) recentSongs.removeFirst()
    }

    /** "mera mood accha nahi hai, koi gaana chalao" -> "Zinda - Siddharth Mahadevan" */
    fun pickSong(ctx: Context, request: String, done: (String) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            var song: String? = null
            val k = key(app)
            if (k.isNotEmpty()) {
                try {
                    val prompt = "The user asked their music assistant: \"$request\". " +
                        "Pick ONE real, popular, well-known song that best fits this mood or request " +
                        "(prefer Hindi/Bollywood/Punjabi unless they ask for English or something else). " +
                        "Do not pick any of these recently played songs: ${recentSongs.joinToString(", ")}. " +
                        "Reply with ONLY the song in this exact format and nothing else: Song Title - Artist"
                    val body = JSONObject()
                        .put("contents", JSONArray().put(turn("user", prompt)))
                        .put("generationConfig", JSONObject().put("maxOutputTokens", 300).put("temperature", 1.0))
                        .toString()
                    for (m in listOfNotNull(goodModel) + models.filter { it != goodModel }) {
                        val (code, resp) = request(
                            "https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent",
                            body, mapOf("x-goog-api-key" to k)
                        )
                        if (code != 200) continue
                        val parts = JSONObject(resp).optJSONArray("candidates")?.optJSONObject(0)
                            ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                        val sb = StringBuilder()
                        for (i in 0 until parts.length()) {
                            val part = parts.optJSONObject(i) ?: continue
                            if (!part.optBoolean("thought", false)) sb.append(part.optString("text"))
                        }
                        val line = sb.toString().lines().map { it.trim().trim('"', '*', '`', '.') }.firstOrNull { it.isNotBlank() }
                        if (!line.isNullOrBlank() && line.length < 90) {
                            song = line
                            break
                        }
                    }
                } catch (e: Exception) {
                }
            }
            val pick = song ?: localPick(request)
            remember(pick)
            main.post { done(pick) }
        }.start()
    }

    /** Simple one-shot Gemini call (no history). Returns null on failure. */
    fun oneShot(k: String, prompt: String, maxTokens: Int = 400, temp: Double = 0.3): String? {
        val body = JSONObject()
            .put("contents", JSONArray().put(turn("user", prompt)))
            .put("generationConfig", JSONObject().put("maxOutputTokens", maxTokens).put("temperature", temp))
            .toString()
        for (m in listOfNotNull(goodModel) + models.filter { it != goodModel }) {
            try {
                val (code, resp) = request(
                    "https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent",
                    body, mapOf("x-goog-api-key" to k)
                )
                if (code != 200) continue
                val parts = JSONObject(resp).optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                val sb = StringBuilder()
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    if (!part.optBoolean("thought", false)) sb.append(part.optString("text"))
                }
                val t = sb.toString().replace(Regex("[*#`]+"), "").trim().trim('"', '“', '”')
                if (t.isNotBlank()) return t
            } catch (e: Exception) {
            }
        }
        return null
    }

    /**
     * 🧠 The router: understands ANY way of saying a command (Hindi / English / Hinglish,
     * mis-heard words, long sentences) and turns it into one clear intent.
     */
    fun route(ctx: Context, said: String, alternatives: List<String>, names: String, done: (JSONObject?) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            var res: JSONObject? = null
            val k = key(app)
            if (k.isNotEmpty()) {
                val alts = alternatives.filter { it != said }.take(2)
                val prompt = "You are the command router of Chinu, a voice assistant on an Android phone (India). " +
                    "The user spoke in Hindi, English or Hinglish; speech recognition may have mis-heard some words — infer what they meant. " +
                    "People: $names (Chiku made the app for Komal).\n" +
                    "Heard: \"$said\"" + (if (alts.isNotEmpty()) "  (other guesses: " + alts.joinToString(" | ") { "\"$it\"" } + ")" else "") + "\n\n" +
                    "Return ONLY one JSON object: {\"intent\":..., \"name\":\"\", \"message\":\"\", \"query\":\"\", \"app\":\"\", \"mood\":false, \"cmd\":\"\"}\n" +
                    "intent is one of:\n" +
                    "- \"chat\": any question, information, advice, recipe, maths, translation, story, small talk, or anything that needs a spoken answer\n" +
                    "- \"call\": phone call. name = contact name exactly as said (or the number)\n" +
                    "- \"whatsapp\": send a message. name = recipient, message = the message words EXACTLY as said (only drop instruction words like 'bhejo', 'WhatsApp pe', 'English mein'; " +
                    "if they ask for emojis like 'heart wala emoji' put the real emoji characters in message)\n" +
                    "- \"music\": play a song/music/artist. query = song and/or artist (empty if none); mood = true when they describe a mood/type instead of a song (sad, happy, romantic, party, 'koi accha gaana'); app = app name only if they named one\n" +
                    "- \"video\": play/search a video on YouTube. query = what to search; app = app name only if named\n" +
                    "- \"reminder\": reminders, daily routine, 'yaad dilana' (handled separately)\n" +
                    "- \"open_app\": open an app. app = its name\n" +
                    "- \"personal\": about Komal's birthday, Chiku & Komal, who made Chinu\n" +
                    "- \"couple\": send a hug / kiss / 'miss you' / 'love you' / love note to the partner through Chinu (NOT WhatsApp unless WhatsApp is said). " +
                    "Also ANY other words to pass on to the partner ('Komal ko bolo khana kha le', 'tell Chiku I reached home') → query = note, " +
                    "message = only the words to pass on, exactly as said. A voice message / voice note / recording for the partner → query = voice. " +
                    "query = one of hug, kiss, miss, love, think, note, voice; message = the note text for note\n" +
                    "- \"diary\": write something in our shared diary / notes. message = the text to write exactly as said (empty if they only want to open it)\n" +
                    "- \"letters\": open-when letters / khat / chitthi for the partner (write or read a sealed letter)\n" +
                    "- \"question\": today's couple question / 'aaj ka sawaal' / daily question\n" +
                    "- \"selfie_reel\": show/play our selfie reel / slideshow of our selfies\n" +
                    "- \"selfie_streak\": our daily couple selfie streak / 'streak' / 'aaj ki selfie streak' (NOT just 'take a selfie' → device)\n" +
                    "- \"story\": a bedtime story about the two of us / 'sone se pehle kahani' / 'hamari kahani sunao' (NOT general stories about other topics → chat)\n" +
                    "- \"closet\": open Komal's closet / heels / shoes / tops / t-shirts collection. query = shoes or tops\n" +
                    "- \"game\": play a game. query = wheel (love wheel / spin / truth or dare), ttt (zero kata / tic tac toe), match (picture match), together (play with partner)\n" +
                    "- \"device\": control the phone. cmd = ONE canonical English command from: " +
                    "'take screenshot', 'take screenshot of <app>', 'lock phone', 'go home', 'go back', 'show recent apps', 'open notifications', 'open quick settings', " +
                    "'turn on <wifi|bluetooth|hotspot|mobile data|airplane mode|location|do not disturb|auto rotate|torch|dark mode>', 'turn off <same>', " +
                    "'volume up', 'volume down', 'volume max', 'mute', 'brightness up', 'brightness down', 'next song', 'previous song', 'pause music', 'resume music', " +
                    "'set alarm for <h:mm am/pm>', 'set timer for <n> minutes', 'set timer for <n> seconds', 'battery level', 'what is the date', 'what time is it', " +
                    "'navigate to <place>', 'open camera', 'take a selfie', 'tell a joke'\n" +
                    "Examples: \"bhai zara wifi band kar de\" -> {\"intent\":\"device\",\"cmd\":\"turn off wifi\"}; " +
                    "\"मम्मी को फ़ोन लगाओ\" -> {\"intent\":\"call\",\"name\":\"मम्मी\"}; " +
                    "\"arijit ka koi sad song lagao\" -> {\"intent\":\"music\",\"query\":\"Arijit Singh sad\",\"mood\":true}; " +
                    "\"केसरिया गाना बजाओ\" -> {\"intent\":\"music\",\"query\":\"Kesariya\"}; " +
                    "\"कोमल को एक किस भेजो\" -> {\"intent\":\"couple\",\"query\":\"kiss\"}; " +
                    "\"chiku ko jhappi do\" -> {\"intent\":\"couple\",\"query\":\"hug\"}; " +
                    "\"paneer butter masala kaise banate hain\" -> {\"intent\":\"chat\"}."
                try {
                    val t = oneShot(k, prompt, 1024, 0.0)
                    res = t?.let { Regex("\\{[\\s\\S]*\\}").find(it)?.value }?.let { JSONObject(it) }
                } catch (e: Exception) {
                }
            }
            main.post { done(res) }
        }.start()
    }

    /** Understands a spoken reminder command. Returns a JSON object (see prompt) or null. */
    fun parseReminder(ctx: Context, command: String, names: String, done: (JSONObject?) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            var res: JSONObject? = null
            val k = key(app)
            if (k.isNotEmpty()) {
                val now = SimpleDateFormat("yyyy-MM-dd HH:mm EEEE", Locale.ENGLISH).format(Date())
                val prompt = "You turn a spoken reminder command into JSON for a phone assistant. Speech recognition may have mis-heard words. " +
                    "Now: $now. People: $names.\nCommand: \"$command\"\n" +
                    "Return ONLY one JSON object with these keys: " +
                    "\"action\": \"add\" | \"routine\" | \"list\" | \"delete\" | \"clear\" (clear = delete all; routine = they ask to set up an automatic daily routine / timetable of reminders for water, food, study, sleep without giving exact times); " +
                    "\"who\": the person to remind (one of the people names, or \"\" if not said / 'me'); " +
                    "\"water\": true if it is about drinking water; " +
                    "\"text\": short reminder text in natural Hindi Devanagari (or English if the command was English), e.g. \"दवाई खा लो\" — empty for water; " +
                    "\"every_min\": repeat interval in minutes (0 if one time; 'har ghante' = 60; water without any time given = 60; EVERY DAY at a fixed time like 'roz 2 baje khana' = 1440); " +
                    "\"minute\": minute (0-59) of the daily time, e.g. 1:30 -> 30; " +
                    "\"from_hour\", \"to_hour\": 0-23 window for repeating reminders (default 8 and 22); for every-day reminders both = that hour (24h); " +
                    "\"at\": for a one-time reminder the exact time as \"yyyy-MM-dd HH:mm\" in the future (e.g. '10 minute baad', 'kal subah 7 baje', 'shaam 5 baje'), else \"\"."
                try {
                    val t = oneShot(k, prompt, 400, 0.1)
                    res = t?.let { Regex("\\{[\\s\\S]*\\}").find(it)?.value }?.let { JSONObject(it) }
                } catch (e: Exception) {
                }
            }
            main.post { done(res) }
        }.start()
    }

    /**
     * Understands a spoken "send a WhatsApp message" command.
     * Returns (recipient name or number, message) — null if the AI is not available.
     */
    fun parseMessageCommand(ctx: Context, command: String, done: (Pair<String, String>?) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            var res: Pair<String, String>? = null
            val k = key(app)
            if (k.isNotEmpty()) {
                val prompt = "A user gave a voice command to their phone assistant to send a WhatsApp message. Speech recognition may have mis-heard words. " +
                    "Command: \"$command\"\n" +
                    "Find WHO the message is for (the contact name exactly as said, or a phone number) and WHAT the message text is. " +
                    "Copy the message words EXACTLY as the user said them — do not change, add, shorten or rephrase anything. " +
                    "If the user asks to send emojis with it (e.g. 'heart wala emoji', 'हार्ट/हर्ट वाला इमोजी', 'smile emoji', 'kiss emoji', 'earth emoji', '2 dil wale emoji'), " +
                    "put those real emoji characters in the message (at the end unless they say where, repeated if they say a number) and leave out the emoji instruction words. " +
                    "Example: \"कोमल को हाय भेजो हार्ट वाले इमोजी के साथ\" -> {\"to\":\"कोमल\",\"message\":\"हाय ❤️\"}. " +
                    "Only leave out the instructions like 'send', 'WhatsApp pe', 'likh ke bhejo', 'English mein type karo', 'translate karke'. " +
                    "Examples: \"कोमल को हाय का मैसेज भेजो\" -> {\"to\":\"कोमल\",\"message\":\"हाय\"}; " +
                    "\"Rahul ko bolo ki main 5 minute mein aa raha hoon\" -> {\"to\":\"Rahul\",\"message\":\"main 5 minute mein aa raha hoon\"}; " +
                    "\"सर को इंग्लिश में भेजो गुड मॉर्निंग सर कैसे हैं आप\" -> {\"to\":\"सर\",\"message\":\"गुड मॉर्निंग सर कैसे हैं आप\"}. " +
                    "Reply with ONLY one JSON object: {\"to\":\"...\",\"message\":\"...\"} (message may be empty if none was said)."
                try {
                    val t = oneShot(k, prompt, 300, 0.1)
                    val j = t?.let { Regex("\\{[\\s\\S]*\\}").find(it)?.value }?.let { JSONObject(it) }
                    if (j != null) {
                        val who = j.optString("to").trim()
                        if (who.isNotBlank()) res = Pair(who, j.optString("message").trim())
                    }
                } catch (e: Exception) {
                }
            }
            main.post { done(res) }
        }.start()
    }

    /**
     * Cleans up a spoken message before sending it on WhatsApp:
     * fixes mis-heard words and writes it in the wanted style
     * (english = translate to English, hinglish = Hindi words in English letters, hindi = Devanagari).
     */
    fun rewriteMessage(ctx: Context, text: String, style: String, done: (String) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            var out: String? = null
            val k = key(app)
            if (k.isNotEmpty() && text.isNotBlank()) {
                val how = when (style) {
                    MsgStyle.ENGLISH -> "Ignore the exact-words rule only for language: translate it faithfully into correct, natural English with the same meaning and nothing added (e.g. \"गुड मॉर्निंग सर, कैसे हैं आप\" -> \"Good morning sir, how are you?\")."
                    MsgStyle.HINGLISH -> "Write it in Hinglish: the very same words, just written in English (Roman) letters the way Indians text on WhatsApp " +
                        "(e.g. \"तुम कहाँ हो\" -> \"Tum kahan ho?\", \"खाना खा लिया\" -> \"Khana kha liya?\"). No Devanagari at all."
                    MsgStyle.HINDI -> "Write it in correct Hindi using Devanagari script (English words like phone/ok may stay as they are)."
                    else -> "Keep the same language and script the user spoke in."
                }
                val prompt = "A user dictated a WhatsApp message by voice. Speech recognition may have mis-heard some words. " +
                    "Message: \"$text\"\n" +
                    "Keep the user's EXACT words and word order — do not rephrase, shorten, add or remove anything, no greeting or sign-off that wasn't there. " +
                    "Only correct spelling and add proper punctuation/capitals. " +
                    how + " Keep every emoji exactly as it is. Reply with ONLY the final message text, nothing else."
                out = oneShot(k, prompt)?.lines()?.joinToString(" ") { it.trim() }?.trim()
                if (out != null && (out!!.length > text.length * 4 + 60)) out = null
            }
            val result = out ?: if (style == MsgStyle.HINGLISH) NameMatcher.toLatin(text).replaceFirstChar { it.uppercase() } else text
            main.post { done(result) }
        }.start()
    }

    // ---------------- Gemini ----------------
    private fun turn(role: String, text: String) =
        JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun gemini(key: String, q: String, p: Profile.Data): String? {
        val today = SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.ENGLISH).format(Date())
        val sys = "You are Chinu, a warm, friendly and very smart female AI voice assistant living inside ${p.name}'s Android phone. " +
            "${p.partner} made you only for ${p.name} (${p.partner}'s ${p.relation}). " +
            "The person talking to you right now is ${Speaker.name(p)}; call them by that name when it feels natural. " +
            "The user message comes from speech recognition and may contain mis-heard or misspelt words — silently work out what they most likely meant and answer that. " +
            "Answer like a real AI assistant: give the actual answer directly. Never say you will search Google or tell the user to search. " +
            "Never say you cannot play songs, open apps or control the phone, and never tell the user to do it themselves on YouTube or Google — the app does those things. " +
            "Your words are read out by a text-to-speech voice, so write them the way a person really talks. " +
            "If the user speaks Hindi or Hinglish, reply in natural, everyday spoken Hindi written in DEVANAGARI script " +
            "(use common English words the way Indians say them, e.g. फ़ोन, ऐप, टाइम, ओके). NEVER write Hindi in Roman/Latin letters. " +
            "Use correct, warm feminine forms for yourself (मैं बता रही हूँ, मैं कर दूँगी). " +
            "If the user speaks English, reply in simple natural English. For other Indian languages, reply in that language's own script. " +
            "Your answer will be spoken aloud, so sound natural. For simple questions answer in 1 to 3 short sentences. " +
            "For recipes, how-to, or step-by-step questions give the real steps clearly (up to 8 short numbered steps like 1. 2. 3.), with quantities when useful. " +
            "You know a lot: food, health tips, studies, people, places, science, tech, love advice, jokes. Always give a real, helpful answer. " +
            "No markdown symbols. Current date and time: $today."
        val contents = JSONArray()
        for ((u, a) in history) {
            contents.put(turn("user", u))
            contents.put(turn("model", a))
        }
        contents.put(turn("user", q))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("maxOutputTokens", 1200).put("temperature", 0.7))
            .toString()
        val order = listOfNotNull(goodModel) + models.filter { it != goodModel }
        for (m in order) {
            val (code, resp) = request(
                "https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent",
                body, mapOf("x-goog-api-key" to key)
            )
            if (code == 200) {
                val parts = JSONObject(resp).optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts") ?: return null
                val sb = StringBuilder()
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    if (part.optBoolean("thought", false)) continue
                    sb.append(part.optString("text"))
                }
                val text = sb.toString().replace(Regex("[*#`]+"), "").trim()
                if (text.isBlank()) return null
                goodModel = m
                history.addLast(q to text)
                while (history.size > 6) history.removeFirst()
                return text
            }
            if (code == 400 && resp.contains("API_KEY", ignoreCase = true) || code == 401 || code == 403) {
                return "जेमिनी AI key सही नहीं है। चीनू की सेटिंग्स में AI key चेक कर लो।"
            }
            // 404 = model not available, 429 = quota over, 5xx = busy -> try the next model
        }
        return null
    }

    // ---------------- Wikipedia (no key needed) ----------------
    private val questionWords = listOf(
        "ke baare mein batao", "ke bare mein batao", "ke baare me batao", "ke bare me batao", "ke baare mein", "ke bare mein",
        "के बारे में बताओ", "के बारे में", "kaun hai", "kaun he", "kon hai", "kaun the", "kaun tha", "kya hai", "kya hota hai",
        "kya hote hain", "who is", "who was", "what is", "what are", "tell me about", "explain", "batao", "bataiye", "btao",
        "कौन है", "कौन थे", "कौन था", "क्या है", "क्या होता है", "बताओ", "बताइए", "chinu", "चीनू", "hey", "hi ", "please", "?"
    )

    private fun topicOf(q: String): String {
        var t = " ${q.lowercase()} "
        for (w in questionWords) t = t.replace(w, " ")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    private fun wikiLang(text: String, lang: String): String {
        for (c in text) {
            when (Character.UnicodeBlock.of(c)) {
                Character.UnicodeBlock.DEVANAGARI -> return if (lang == "mr-IN") "mr" else "hi"
                Character.UnicodeBlock.BENGALI -> return "bn"
                Character.UnicodeBlock.TAMIL -> return "ta"
                Character.UnicodeBlock.TELUGU -> return "te"
                Character.UnicodeBlock.GUJARATI -> return "gu"
                Character.UnicodeBlock.KANNADA -> return "kn"
                Character.UnicodeBlock.MALAYALAM -> return "ml"
                Character.UnicodeBlock.GURMUKHI -> return "pa"
                Character.UnicodeBlock.ARABIC -> return "ur"
                else -> {}
            }
        }
        return "en"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun wikipedia(q: String, lang: String): String? {
        val topic = topicOf(q)
        if (topic.length < 2) return null
        val wl = wikiLang(topic, lang)
        val (c1, s) = request("https://$wl.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=1&srsearch=" + enc(topic))
        if (c1 != 200) return null
        val title = JSONObject(s).optJSONObject("query")?.optJSONArray("search")
            ?.optJSONObject(0)?.optString("title").orEmpty()
        if (title.isBlank()) return null
        val (c2, sum) = request("https://$wl.wikipedia.org/api/rest_v1/page/summary/" + enc(title.replace(' ', '_')))
        if (c2 != 200) return null
        val extract = JSONObject(sum).optString("extract").trim()
        if (extract.isBlank()) return null
        val sentences = extract.split(Regex("(?<=[.!?।])\\s+"))
        return sentences.take(3).joinToString(" ")
    }

    // ---------------- HTTP ----------------
    private fun request(url: String, body: String? = null, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        // ⚡ ask Gemini to answer straight away (no slow "thinking" step)
        if (body != null && url.contains(":generateContent") && !body.contains("thinkingConfig")) {
            val fast = try {
                val j = JSONObject(body)
                val gc = j.optJSONObject("generationConfig") ?: JSONObject().also { j.put("generationConfig", it) }
                gc.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                j.toString()
            } catch (e: Exception) {
                null
            }
            if (fast != null) {
                val r = try {
                    rawRequest(url, fast, headers)
                } catch (e: Exception) {
                    null
                }
                if (r != null && r.first != 400) return r
            }
        }
        return rawRequest(url, body, headers)
    }

    private fun rawRequest(url: String, body: String? = null, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "ChinuAI/2.0 (Android voice assistant)")
        for ((k, v) in headers) c.setRequestProperty(k, v)
        if (body != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        c.disconnect()
        return code to text
    }
}


/** How Chinu types WhatsApp messages */
object MsgStyle {
    const val AUTO = "auto"
    const val HINGLISH = "hinglish"
    const val ENGLISH = "english"
    const val HINDI = "hindi"
    val ALL = listOf(AUTO, HINGLISH, ENGLISH, HINDI)

    fun label(s: String) = when (s) {
        HINGLISH -> "Hinglish (Hindi in English letters)"
        ENGLISH -> "English (translate)"
        HINDI -> "Hindi (हिंदी)"
        else -> "As spoken (fix typos)"
    }

    fun default(ctx: Context): String =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("msgStyle", HINGLISH) ?: HINGLISH

    /** Style asked for inside the command itself, e.g. "English mein bhejo" */
    fun fromCommand(t: String): String? {
        val x = t.lowercase()
        if (Regex("hinglish|हिंग्लिश|हिंगलिश|roman|रोमन|english (letters|alphabet)|इंग्लिश (अक्षर|अल्फ़ाबेट|अल्फाबेट)").containsMatchIn(x)) return HINGLISH
        if (Regex("(english|इंग्लिश|अंग्रेज़ी|अंग्रेजी|angrezi)\\s*(में|mein|me|main|मे)|translate|ट्रांसलेट|in english").containsMatchIn(x)) return ENGLISH
        if (Regex("(hindi|हिंदी|हिन्दी)\\s*(में|mein|me|main|मे)|in hindi").containsMatchIn(x)) return HINDI
        return null
    }

    private val EMOJI = listOf(
        listOf("heart", "hart", "हार्ट", "हर्ट", "dil", "दिल", "love", "लव") to "❤️",
        listOf("kiss", "किस", "chumma") to "😘",
        listOf("hug", "हग") to "🤗",
        listOf("smile", "स्माइल", "muskan", "मुस्कान") to "😊",
        listOf("laugh", "laughing", "हँसने", "हंसने", "हँसी", "lol") to "😂",
        listOf("sad", "सैड", "rone", "रोने", "dukh", "दुख") to "😢",
        listOf("angry", "gussa", "गुस्से", "गुस्सा", "एंग्री") to "😠",
        listOf("earth", "अर्थ", "duniya", "दुनिया", "globe") to "🌍",
        listOf("rose", "रोज़", "gulab", "गुलाब", "flower", "फूल", "फ्लावर") to "🌹",
        listOf("moon", "चाँद", "चांद", "मून") to "🌙",
        listOf("star", "स्टार", "तारा") to "⭐",
        listOf("fire", "फायर", "आग") to "🔥",
        listOf("thumbs", "like", "लाइक", "थम्स") to "👍",
        listOf("namaste", "नमस्ते", "pray", "हाथ जोड़") to "🙏",
        listOf("cake", "केक", "birthday", "बर्थडे") to "🎂",
        listOf("party", "पार्टी") to "🎉",
        listOf("sun", "सूरज", "सन") to "☀️",
        listOf("sleep", "नींद", "सोने") to "😴",
        listOf("wink", "आँख मारने", "विंक") to "😉",
        listOf("cool", "कूल") to "😎",
        listOf("blush", "शर्माने", "शरमाने") to "☺️"
    )
    private val NUM = mapOf("do" to 2, "दो" to 2, "two" to 2, "teen" to 3, "तीन" to 3, "three" to 3, "char" to 4, "चार" to 4, "paanch" to 5, "पाँच" to 5, "पांच" to 5)

    /** "hi heart wala emoji ke saath" -> ("hi", "❤️") (only used when the AI is not available) */
    fun pullEmojis(text: String): Pair<String, String> {
        val r = Regex("(?i)(?:(\\d+|do|दो|two|teen|तीन|three|char|चार|paanch|पाँच|पांच)\\s+)?(\\S+)\\s*(wala|wale|wali|वाला|वाले|वाली|ka|का|के)?\\s*(emoji|emojis|imoji|इमोजी|ईमोजी|इमोजीस)\\s*(ke saath|ke sath|के साथ|bhi|भी|with|डालो|daalo|lagao|लगाओ)?")
        var out = text
        val sb = StringBuilder()
        for (m in r.findAll(text).toList()) {
            val words = (m.groupValues[1] + " " + m.groupValues[2]).lowercase()
            val e = EMOJI.firstOrNull { (keys, _) -> keys.any { words.contains(it) } }?.second ?: continue
            val n = m.groupValues[1].let { it.toIntOrNull() ?: NUM[it.lowercase()] } ?: 1
            repeat(n.coerceIn(1, 5)) { sb.append(e) }
            out = out.replace(m.value, " ")
        }
        out = out.replace(Regex("(?i)\\b(ke saath|ke sath|with)\\s*$"), "").replace(Regex("\\s+"), " ").trim()
        return out to sb.toString()
    }

    /** Removes the "English mein / translate karke" part from the message text */
    fun strip(t: String): String =
        t.replace(Regex("(?i)\\b(in english|in hindi|in hinglish)\\b"), " ")
            .replace(Regex("(?i)(को\\s*)?(english|इंग्लिश|अंग्रेज़ी|अंग्रेजी|angrezi|hinglish|हिंग्लिश|हिंगलिश|hindi|हिंदी|हिन्दी)\\s*(में|mein|me|main|मे)?\\s*(ट्रांसलेट|translate)?\\s*(करके|कर के|karke|kar ke|करकर)?\\s*(टाइप|type|लिख|likh)?\\s*(करके|कर के|karke|kar ke|कर|kar|करो|karo|दो|do)?"), " ")
            .replace(Regex("(?i)(ट्रांसलेट|translate)\\s*(करके|कर के|karke|kar ke|करो|karo|कर)?"), " ")
            .replace(Regex("\\s+"), " ").trim()
}
