package com.meraassistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Chinu's brain: decides what to do for each spoken sentence.
 * Understands English, Hinglish, Hindi and other Indian languages.
 * Replies in Hinglish (or English when English is selected).
 */
class CommandProcessor(private val host: ChinuHost) {

    private val ctx: Context get() = host.context

    /** Language of what the user just said (used for replies + AI answers) */
    private var curLang = "hi-IN"

    private fun hinglish() = curLang in setOf("hi-IN", "mr-IN", "ur-IN", "pa-IN")
    private fun profile() = Profile.load(ctx)
    private fun celebrate() = host.celebrate()
    private fun listenNext() = host.listenNext()
    private fun reply(text: String) = host.reply(text, if (hinglish()) "hi-IN" else "en-IN")

    // ======================= Keywords =======================
    private val SCREENSHOT = listOf(
        "screenshot", "screen shot", "screen capture", "capture screen", "screenshort",
        "स्क्रीनशॉट", "स्क्रीन शॉट", "स्क्रीनशॉर्ट", "स्क्रीनशोट",
        "স্ক্রিনশট", "ஸ்கிரீன்ஷாட்", "స్క్రీన్‌షాట్", "స్క్రీన్షాట్", "સ્ક્રીનશૉટ", "સ્ક્રીનશોટ",
        "ಸ್ಕ್ರೀನ್‌ಶಾಟ್", "ಸ್ಕ್ರೀನ್ಶಾಟ್", "സ്ക്രീൻഷോട്ട്", "ਸਕ੍ਰੀਨਸ਼ਾਟ", "ਸਕਰੀਨਸ਼ਾਟ", "اسکرین شاٹ"
    )
    private val CALL = listOf(
        "call", "dial", "ring ", "phone karo", "phone lagao", "phone kar", "fon karo",
        "कॉल", "कोल", "फोन करो", "फ़ोन करो", "फोन लगाओ", "फ़ोन लगाओ", "फोन लाव", "फोन कर",
        "কল", "ফোন কর", "கால்", "அழை", "కాల్", "કૉલ", "કોલ", "ಕಾಲ್", "ಕರೆ ಮಾಡು", "കോൾ", "ਕਾਲ", "کال", "فون کرو"
    )
    private val WHATSAPP = listOf(
        "whatsapp", "whats app", "watsapp", "whatsap", "व्हाट्सएप", "व्हाट्सऐप", "वॉट्सऐप", "वाट्सएप",
        "व्हाट्सअप", "वॉट्सएप", "হোয়াটসঅ্যাপ", "வாட்ஸ்அப்", "వాట్సాప్", "વોટ્સએપ", "ವಾಟ್ಸಾಪ್", "ਵਟਸਐਪ", "واٹس ایپ"
    )
    private val MESSAGE = listOf(
        "message", "msg", "मैसेज", "मेसेज", "संदेश", "chat", "चैट", "bhejo", "भेजो", "send", "text", "likho", "लिखो"
    )
    private val SEND_WORDS = listOf("bhejo", "भेजो", "send", "kar do", "कर दो", "karo", "करो")
    private val LEAD_WORDS = setOf("bhejo", "भेजो", "karo", "करो", "kar", "कर", "do", "दो", "send", "ki", "कि", "की", "that", "saying", "bolo", "बोलो", "likho", "लिखो", "please", "plz")
    private val MSG_DELIMS = listOf(
        " ki ", " kih ", " कि ", " की ", " that ", " saying ", " bolo ", " बोलो ", " likho ", " लिखो ",
        " message ", " मैसेज ", " मेसेज ", " msg "
    )
    private val TORCH = listOf("torch", "flashlight", "flash light", "टॉर्च", "टोर्च", "फ्लैशलाइट", "फ्लैश", "টর্চ", "டார்ச்", "టార్చ్", "ટોર્ચ", "ಟಾರ್ಚ್", "ടോർച്ച്", "ਟਾਰਚ", "ٹارچ")
    private val OFF = listOf("off", "band", "बंद", "ऑफ", "বন্ধ", "அணை", "ஆஃப்", "ఆఫ్", "બંધ", "ಆಫ್", "ഓഫ്", "ਬੰਦ", "بند")
    private val TIME = listOf("time", "kitne baje", "kya baja", "समय", "टाइम", "कितने बजे", "किती वाजले", "কটা বাজে", "மணி என்ன", "టైం", "ટાઇમ", "ಸಮಯ", "സമയം", "ਟਾਈਮ", "وقت")
    private val DATE = listOf("date", "what day", "which day", "tarikh", "tareekh", "aaj kya din", "तारीख", "तारीख़", "दिनांक", "कौन सा दिन", "आज क्या दिन", "তারিখ", "தேதி", "తేదీ", "તારીખ", "ದಿನಾಂಕ", "തീയതി", "ਤਾਰੀਖ", "تاریخ")
    private val OPEN = listOf(
        "open", "launch", "start ", "kholo", "khol do", "chalao", "खोलो", "खोल", "ओपन", "चलाओ", "उघड",
        "খোলো", "திற", "తెరువు", "ખોલ", "ತೆರೆ", "തുറക്ക", "ਖੋਲ੍ਹ", "کھولو"
    )
    private val ALARM = listOf("alarm", "अलार्म", "wake me", "jagana", "jaga dena", "जगाना", "उठाना", "অ্যালার্ম", "அலாரம்", "అలారం", "એલાર્મ", "ಅಲಾರಾಂ", "അലാറം", "ਅਲਾਰਮ", "الارم")
    private val REMIND = listOf(
        "remind", "reminder", "रिमाइंडर", "रिमाइंड", "याद दिला", "याद दिलाना", "याद दिलाओ", "याद करा", "yaad dila", "yad dila", "yaad kara"
    )
    private val TIMER = listOf("timer", "टाइमर", "টাইমার", "டைமர்", "టైమర్", "ટાઇમર", "ಟೈಮರ್", "ടൈമർ", "ਟਾਈਮਰ", "ٹائمر")
    private val VOLUME = listOf("volume", "वॉल्यूम", "आवाज", "आवाज़", "awaaz", "awaz", "sound", "साउंड", "ভলিউম", "வால்யூம்", "వాల్యూమ్", "વોલ્યુમ", "ವಾಲ್ಯೂಮ್", "വോളിയം", "ਵਾਲੀਅਮ", "والیوم")
    private val DOWN = listOf("kam", "कम", "down", "decrease", "low", "dheere", "धीरे", "धीमी", "reduce", "quiet", "কমাও", "குறை", "తగ్గించు", "ઘટાડ", "ಕಡಿಮೆ", "കുറയ്ക്ക", "ਘਟਾ", "کم")
    private val MAX = listOf("full", "फुल", "पूरा", "पूरी", "max", "maximum")
    private val MUTE = listOf("mute", "म्यूट", "silent", "साइलेंट")
    private val BATTERY = listOf("battery", "बैटरी", "बेटरी", "charging", "ব্যাটারি", "பேட்டரி", "బ్యాటరీ", "બેટરી", "ಬ್ಯಾಟರಿ", "ബാറ്ററി", "ਬੈਟਰੀ", "بیٹری")
    private val WIFI = listOf("wifi", "wi-fi", "wi fi", "वाईफाई", "वाई फाई", "वायफाय", "ওয়াইফাই", "வைஃபை", "వైఫై", "વાઇફાઇ", "ವೈಫೈ", "വൈഫൈ", "ਵਾਈਫਾਈ")
    private val BLUETOOTH = listOf("bluetooth", "blue tooth", "ब्लूटूथ", "ব্লুটুথ", "ப்ளூடூத்", "బ్లూటూత్", "બ્લૂટૂથ", "ಬ್ಲೂಟೂತ್", "ബ്ലൂടൂത്ത്")
    private val DATA = listOf("mobile data", "internet", "इंटरनेट", "डेटा", "डाटा", "net on", "net off")
    private val BRIGHTNESS = listOf("brightness", "ब्राइटनेस", "roshni", "रोशनी")
    private val HOTSPOT = listOf("hotspot", "hot spot", "हॉटस्पॉट")
    private val LOCATION = listOf("location", "gps", "लोकेशन")
    private val AIRPLANE = listOf("airplane", "aeroplane", "flight mode", "एयरप्लेन", "फ्लाइट मोड")
    private val DND = listOf("do not disturb", "dnd", "डू नॉट डिस्टर्ब")
    private val LOCK = listOf("लॉक", "লক", "லாக்", "లాక్", "લોક", "ಲಾಕ್", "ലോക്ക്", "ਲਾਕ", "لاک")
    private val HOME = listOf("home screen", "go home", "home jao", "home pe", "होम स्क्रीन", "होम पे", "होम पर", "होम जाओ")
    private val BACK = listOf("go back", "back jao", "peeche jao", "wapas jao", "पीछे जाओ", "वापस जाओ")
    private val NOTIFICATIONS = listOf("notification", "नोटिफिकेशन")
    private val QUICK_SETTINGS = listOf("quick setting", "control center", "क्विक सेटिंग")
    private val RECENTS = listOf("recent app", "recents", "रीसेंट")
    private val POWER = listOf("power menu", "पावर मेनू", "switch off", "restart", "रीस्टार्ट", "phone band", "फोन बंद", "फ़ोन बंद", "स्विच ऑफ")
    private val SPLIT = listOf("split screen", "स्प्लिट स्क्रीन", "split")
    private val SCROLL = listOf("scroll", "स्क्रॉल", "स्क्रोल")
    private val UPWARD = listOf("up", "upar", "ऊपर", "उपर", "top")
    private val YOUTUBE = listOf("youtube", "you tube", "यूट्यूब", "यू ट्यूब", "ইউটিউব", "யூடியூப்", "యూట్యూబ్", "યુટ્યુબ", "ಯೂಟ್ಯೂಬ್", "യൂട്യൂബ്", "ਯੂਟਿਊਬ", "یوٹیوب")
    private val MUSIC = listOf("gaana", "gaane", "gane", "gana", "गाना", "गाने", "song", "सॉन्ग", "music", "म्यूजिक", "म्यूज़िक", "गीत", "গান", "பாட்டு", "పాట", "ગીત", "ಹಾಡು", "പാട്ട്", "ਗੀਤ", "گانا")
    private val PLAY = listOf(
        "play", "प्ले", "chalao", "chala do", "chalana", "chalaiye", "चलाओ", "चला दो", "चलाना", "चलाइए", "चलाइये",
        "lagao", "laga do", "लगाओ", "लगा दो", "bajao", "baja do", "बजाओ", "बजा दो", "sunao", "suna do", "सुनाओ", "सुना दो",
        "sunna", "sunni", "सुनना", "सुननी", "sunte", "start", "स्टार्ट", "search", "सर्च", "dikhao", "दिखाओ", "dekhna", "देखना",
        "pe ", "par ", "पर ", "पे "
    )
    private val PAUSE = listOf("pause", "roko", "रोको", "ruko", "रुको", "stop", "band karo", "बंद करो", "band kar")
    private val NEXT = listOf("next song", "next track", "agla gaana", "agla gana", "agla song", "अगला गाना", "skip")
    private val PREV = listOf("previous song", "previous track", "pichla gaana", "pichhla gaana", "pichla song", "पिछला गाना")
    private val RESUME = listOf("resume", "play music", "music chalu", "gaana chalu", "गाना चालू", "continue music")
    private val NAVIGATE = listOf("navigate", "navigation", "direction", "rasta", "raasta", "रास्ता", "le chalo", "ले चलो", "map", "मैप", "how to reach", "कैसे जाएं", "রাস্তা", "வழி", "దారి", "રસ્તો", "ದಾರಿ", "വഴി", "ਰਸਤਾ", "راستہ")
    private val CAMERA = listOf("camera", "कैमरा", "selfie", "सेल्फी", "photo khincho", "photo lo", "फोटो खींचो", "फ़ोटो खींचो", "ক্যামেরা", "கேமரா", "కెమెరా", "કેમેરા", "ಕ್ಯಾಮೆರಾ", "ക്യാമറ", "ਕੈਮਰਾ", "کیمرہ")
    private val SELFIE = listOf("selfie", "सेल्फी", "front")
    private val JOKE = listOf("joke", "जोक", "चुटकुला", "chutkula", "hasao", "हँसाओ", "हंसाओ", "funny")
    private val IDENTITY = listOf("kaun ho", "कौन हो", "who are you", "your name", "tumhara naam", "तुम्हारा नाम", "aapka naam", "आपका नाम", "tera naam", "तेरा नाम")
    private val HOW_ARE_YOU = listOf("kaise ho", "कैसे हो", "kaisi ho", "कैसी हो", "how are you", "kya haal", "क्या हाल")
    private val THANKS = listOf("thank", "थैंक", "धन्यवाद", "shukriya", "शुक्रिया", "dhanyavad")
    private val HELP = listOf("help", "मदद", "kya kar sakti", "क्या कर सकती", "kya kar sakte", "what can you do")
    private val SEARCH = listOf(
        "search", "सर्च", "google", "गूगल", "khojo", "खोजो", "dhundo", "ढूंढो", "ढूँढो",
        "what is", "who is", "kya hai", "क्या है", "kaun hai", "कौन है", "kya hota", "meaning", "मतलब"
    )
    private val CANCEL = listOf("cancel", "rehne do", "chhodo", "chodo", "छोड़ो", "रहने दो", "mat karo", "नहीं", "nahi")
    private val GREET_TOKENS = setOf("hi", "hello", "hey", "namaste", "नमस्ते", "हेलो", "हैलो", "हाय", "chinu", "चीनू", "चिनू")

    // 💖 Personal
    private val ASSISTANT_NAMES = listOf("chinu", "चीनू", "चिनू", "चिन्नू", "चीनु")
    private val MY_NAME = listOf("mera naam", "मेरा नाम", "my name", "main kaun", "मैं कौन", "who am i", "mujhe jante", "mujhe jaanti", "मुझे जानती", "do you know me")
    private val WHO = listOf("kaun", "कौन", "who")
    private val BIRTHDAY = listOf("birthday", "bday", "बर्थडे", "जन्मदिन", "janamdin", "janmdin")
    private val WISH = listOf("wish", "विश", "बधाई", "badhai", "mubarak", "मुबारक", "happy birthday", "हैप्पी बर्थडे", "celebrate", "party", "पार्टी")
    private val ANNIVERSARY = listOf("anniversary", "एनिवर्सरी", "सालगिरह", "salgirah")
    private val FAVOURITE = listOf("favourite", "favorite", "फेवरेट", "पसंद", "pasand")
    private val LOVE = listOf("i love you", "love you", "आई लव यू", "लव यू", "pyar karti", "pyaar karti", "pyar karta", "pyaar karta", "प्यार करती", "प्यार करता")
    private val MISS = listOf("miss", "मिस", "याद आ", "yaad aa")
    private val SPECIAL_MSG = listOf("message", "मैसेज", "मेसेज", "संदेश", "sandesh", "letter")
    private val SPECIAL_HINT = listOf("special", "स्पेशल", "love", "लव", "प्यार", "pyaar", "pyar", "sunao", "सुनाओ", "padho", "पढ़ो")

    // Filler words removed while extracting names
    private val COMMON_FILLER = setOf(
        "chinu", "hey", "hi", "hello", "ok", "okay", "चीनू", "चिनू", "चिन्नू", "हे", "हेलो", "please", "plz", "प्लीज",
        "प्लीज़", "jara", "zara", "जरा", "ज़रा", "भाई", "bhai", "yaar", "यार", "the", "a", "to", "my", "now", "abhi", "अभी",
        "me", "mujhe", "मुझे", "mere", "meri", "मेरे", "मेरी", "usko", "उसको", "unko", "उनको"
    )
    private val CALL_FILLER = COMMON_FILLER + setOf(
        "call", "dial", "ring", "make", "phone", "fon", "ko", "karo", "kar", "do", "lagao", "par", "pe", "on",
        "को", "कॉल", "कोल", "करो", "कर", "दो", "फोन", "फ़ोन", "लगाओ", "पर", "पे", "ला", "लाव", "करा",
        "কল", "কে", "করো", "கால்", "பண்ணு", "కాల్", "కి", "చెయ్యి", "કૉલ", "કોલ", "ને", "કરો", "ಕಾಲ್", "ಗೆ", "ಮಾಡು",
        "കോൾ", "ਕਾਲ", "ਨੂੰ", "ਕਰੋ", "کال", "کو", "کرو", "فون"
    )
    private val WA_FILLER = CALL_FILLER + setOf(
        "whatsapp", "whats", "app", "watsapp", "व्हाट्सएप", "व्हाट्सऐप", "वॉट्सऐप", "वाट्सएप", "व्हाट्सअप", "वॉट्सएप",
        "message", "msg", "मैसेज", "मेसेज", "संदेश", "chat", "चैट", "bhejo", "भेजो", "send", "text", "में", "via", "pr"
    )
    private val OPEN_FILLER = COMMON_FILLER + setOf(
        "open", "launch", "start", "app", "kholo", "khol", "chalao", "karo", "खोलो", "खोल", "ओपन", "चलाओ", "करो",
        "ऐप", "एप", "एप्प", "दो", "do", "उघड", "খোলো", "திற", "తెరువు", "ખોલ", "ತೆರೆ", "തുറക്കൂ", "ਖੋਲ੍ਹੋ", "کھولو"
    )
    private val SHOT_FILLER = OPEN_FILLER + setOf(
        "ka", "ki", "ke", "का", "की", "के", "of", "le", "lo", "lena", "लो", "ले", "लेना", "take", "screen", "page",
        "पेज", "is", "iss", "इस", "this", "current", "wala", "वाला", "kar", "कर", "ek", "एक", "mera", "मेरा"
    )
    private val MEDIA_FILLER = COMMON_FILLER + setOf(
        "youtube", "you", "tube", "यूट्यूब", "यू", "ट्यूब", "play", "chalao", "चलाओ", "lagao", "लगाओ", "bajao", "बजाओ",
        "sunao", "सुनाओ", "search", "सर्च", "dikhao", "दिखाओ", "pe", "par", "पर", "पे", "on", "ke", "ka", "ki", "के", "का",
        "की", "gaana", "gaane", "gane", "gana", "गाना", "गाने", "song", "songs", "सॉन्ग", "music", "म्यूजिक", "म्यूज़िक",
        "koi", "कोई", "video", "वीडियो", "karo", "करो", "kar", "कर", "do", "दो", "wala", "wale", "वाला", "वाले", "ek", "एक",
        "प्ले", "चला", "चलाना", "बजा", "लगा", "सुना", "सुनना", "सुननी", "sunna", "sunni", "chala", "baja", "laga", "suna",
        "है", "hai", "हैं", "मुझे", "mujhe", "को", "ko", "please", "प्लीज़", "प्लीज", "स्टार्ट", "start", "देखना", "dekhna"
    )
    private val NAV_FILLER = COMMON_FILLER + setOf(
        "navigate", "navigation", "direction", "directions", "for", "rasta", "raasta", "रास्ता", "le", "chalo", "ले",
        "चलो", "map", "maps", "मैप", "show", "batao", "बताओ", "dikhao", "दिखाओ", "ka", "ki", "ke", "का", "की", "के",
        "pe", "par", "पर", "पे", "tak", "तक", "jana", "जाना", "hai", "है", "how", "reach", "कैसे", "जाएं", "में"
    )
    private val SEARCH_FILLER = COMMON_FILLER + setOf(
        "search", "सर्च", "google", "गूगल", "karo", "करो", "kar", "कर", "khojo", "खोजो", "dhundo", "ढूंढो",
        "pe", "par", "पर", "पे", "on", "for", "batao", "बताओ", "about", "के", "बारे", "में"
    )

    // ======================= Conversation memory =======================
    private class Contact(val name: String, val clean: String, val number: String)

    private sealed class Pending {
        class WaMessage(val contact: Contact) : Pending()
        class Choose(val options: List<Contact>, val action: String, val message: String) : Pending()
        class AskName(val action: String) : Pending()
        object PartnerNote : Pending()
    }

    private var pending: Pending? = null

    /** Message style asked for in the current command ("English mein bhejo"), else null */
    private var cmdStyle: String? = null

    // ======================= Main =======================
    fun handle(matches: List<String>) {
        if (matches.isEmpty()) return
        val first = matches.first().trim()
        curLang = LangUtil.detect(first, host.selectedLang())
        val p = pending
        val said = MsgStyle.fromCommand(first)
        if (p == null) cmdStyle = said else if (said != null) cmdStyle = said
        if (p != null) {
            pending = null
            if (has(first.lowercase(), CANCEL) && first.split(" ").size <= 3) {
                reply(msg("ठीक है, कैंसिल कर दिया 👍", "Okay, cancelled 👍"))
                return
            }
            when (p) {
                is Pending.WaMessage -> sendWhatsApp(p.contact, first)
                is Pending.PartnerNote -> sendPartnerNote(first)
                is Pending.AskName -> {
                    val n = stripWords(first, CALL_FILLER)
                    if (n.isBlank()) reply(msg("नाम समझ नहीं आया, एक बार फिर से बोलो", "Didn't catch the name, please try again"))
                    else resolveContact(n, p.action, "")
                }
                is Pending.Choose -> {
                    val pick = pickOption(matches, p.options)
                    if (pick == null) reply(msg("समझ नहीं आया, पूरा कमांड फिर से बोलो", "Didn't get that, please say the full command again"))
                    else doContactAction(pick, p.action, p.message)
                }
            }
            return
        }
        // 🛟 location · SOS · battery · countdown · our list
        if (careCommand(first)) return
        // 💬 "Komal ko bolo khana kha le" / "Chiku ko voice message bhejo" → straight to the partner's phone
        if (partnerMessage(first)) return
        // ⚡ a real question → start writing the answer right now, in parallel with the router
        if (Brain.key(ctx).isNotEmpty() && isRealQuestion(first.lowercase())) speculate(first)
        // 🧠 Online: the AI router understands any phrasing. Offline: keyword matching.
        if (Brain.key(ctx).isNotEmpty() && !quickLocal(first.lowercase())) {
            host.thinking()
            val p = profile()
            val token = ++routeToken
            // never keep the user waiting: if the AI is slow, fall back to the keyword brain
            val giveUp = Runnable {
                if (routeToken == token) {
                    routeToken++
                    keywordRun(matches, first)
                }
            }
            mainHandler.postDelayed(giveUp, 4500)
            Brain.route(ctx, first, matches, listOf(p.name, p.partner).filter { it.isNotBlank() }.joinToString(", ")) { j ->
                if (routeToken != token) return@route
                routeToken++
                mainHandler.removeCallbacks(giveUp)
                if (j == null) keywordRun(matches, first) else dispatch(j, first, matches)
            }
            return
        }
        keywordRun(matches, first)
    }

    private var routeToken = 0
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun keywordRun(matches: List<String>, first: String) {
        for (raw in matches) {
            if (tryRun(raw.lowercase().trim())) return
        }
        askAi(first)
    }

    /** Short, crystal-clear commands run instantly without asking the AI */
    private fun quickLocal(t: String): Boolean {
        val words = t.split(Regex("\\s+")).count { it.isNotBlank() }
        if (words > 4) return false
        return has(t, SCREENSHOT) || has(t, TORCH) || has(t, NEXT) || has(t, PREV) || has(t, BATTERY) ||
            (has(t, TIME) && words <= 3) || (has(t, DATE) && words <= 3) || isGreeting(t) || has(t, THANKS)
    }

    private fun dispatch(j: org.json.JSONObject, first: String, matches: List<String>) {
        val intent = j.optString("intent")
        val name = j.optString("name").trim()
        val message = j.optString("message").trim()
        val query = j.optString("query").trim()
        val appName = j.optString("app").trim()
        val mood = j.optBoolean("mood", false)
        val cmd = j.optString("cmd").trim().lowercase()
        val low = first.lowercase()
        when (intent) {
            "chat" -> askAi(first)
            "personal" -> if (!personal(low)) askAi(first)
            "reminder" -> reminderCommand(first)
            "diary" -> {
                if (message.isBlank()) {
                    reply(msg("आपकी डायरी खोल रही हूँ 📝", "Opening your diary 📝"))
                    go(Intent(ctx, DiaryActivity::class.java))
                } else {
                    Diary.put(ctx, message)
                    reply(msg("डायरी में लिख दिया 📝💖", "Written in your diary 📝💖"))
                }
            }
            "couple" -> coupleSend(query.ifBlank { coupleKind(low) ?: "love" }, message)
            "letters" -> openLetters()
            "question" -> openQuestion()
            "story" -> openStory()
            "selfie_streak" -> openSelfieStreak()
            "selfie_reel" -> openReel()
            "closet" -> {
                reply(msg("आपकी क्लोज़ेट खोल रही हूँ 👠✨", "Opening your closet 👠✨"))
                go(Intent(ctx, ClosetActivity::class.java).putExtra("col", if (query.contains("top")) "tops" else "shoes"))
            }
            "game" -> when {
                query.contains("wheel") -> { reply(msg("लव व्हील खोल रही हूँ 🎡", "Opening the Love Wheel 🎡")); go(Intent(ctx, WheelActivity::class.java)) }
                query.contains("together") -> { reply(msg("चलो साथ में खेलते हैं ❌⭕", "Let's play together ❌⭕")); go(Intent(ctx, GameActivity::class.java).putExtra("mode", "online")) }
                query.contains("match") -> { reply(msg("पिक्चर मैच! 🃏", "Picture match! 🃏")); go(Intent(ctx, GameActivity::class.java).putExtra("mode", "match")) }
                else -> { reply(msg("चलो ज़ीरो-काटा खेलते हैं! ❌⭕ मैं हारने वाली नहीं 😜", "Let's play Zero-Kata! ❌⭕")); go(Intent(ctx, GameActivity::class.java).putExtra("mode", "ttt")) }
            }
            "call" -> {
                val p = profile()
                val digits = asciiDigits(name).filter { it.isDigit() }
                when {
                    digits.length >= 3 -> dial(digits, digits)
                    name.isBlank() -> callCommand(low)
                    mentionsPartner(name, p) -> callPartner(p)
                    else -> resolveContact(name, "call", "")
                }
            }
            "whatsapp" -> {
                if (name.isBlank()) whatsappLocal(low) else whatsappTo(name, message)
            }
            "music" -> {
                val app = if (appName.isNotBlank()) findApp(appName) ?: NameMatcher.best(appName, allApps(), 0.6) { it.label } else null
                val target = app ?: ytMusicApp()
                when {
                    mood || query.isBlank() -> moodPlay(first, target)
                    target != null -> playOn(target, query)
                    else -> youtube("$query song")
                }
            }
            "video" -> {
                if (query.isBlank()) {
                    val v = videoApp()
                    reply(msg("${v.label} खोल रही हूँ 🎬", "Opening ${v.label} 🎬"))
                    ctx.packageManager.getLaunchIntentForPackage(v.pkg)?.let { go(it) }
                } else youtube(query)
            }
            "open_app" -> openApp(if (appName.isNotBlank()) "open $appName" else low)
            "device" -> {
                if (cmd.isNotBlank() && tryRun(cmd)) return
                keywordRun(matches, first)
            }
            else -> keywordRun(matches, first)
        }
    }

    // ⚡ answer prepared in advance for questions
    private var specQ: String? = null
    private var specA: String? = null
    private var specWait: ((String) -> Unit)? = null

    private fun speculate(q: String) {
        specQ = q
        specA = null
        specWait = null
        Brain.ask(ctx, q, curLang) { a ->
            if (specQ != q) return@ask
            val w = specWait
            if (w != null) {
                specQ = null
                specWait = null
                w(a)
            } else specA = a
        }
    }

    /** Koi bhi sawaal: seedha AI se jawab, screen pe */
    private fun askAi(question: String) {
        if (specQ != null && specQ.equals(question.trim(), ignoreCase = true)) {
            val lang = curLang
            val ready = specA
            if (ready != null) {
                specQ = null
                specA = null
                host.reply(ready, lang)
            } else {
                host.thinking()
                specWait = { a -> host.reply(a, lang) }
            }
            return
        }
        specQ = null
        host.thinking()
        val lang = curLang
        Brain.ask(ctx, question, lang) { answer -> host.reply(answer, lang) }
    }

    private fun tryRun(text: String): Boolean {
        when {
            has(text, SCREENSHOT) -> screenshot(text)
            systemAction(text) -> {}
            has(text, WHATSAPP) && has(text, MESSAGE) -> whatsappCommand(text)
            has(text, listOf("message", "msg", "मैसेज", "मेसेज")) && has(text, SEND_WORDS) && !has(text, SPECIAL_HINT) -> whatsappCommand(text)
            has(text, REMIND) -> reminderCommand(text)
            coupleKind(text) != null && !has(text, WHATSAPP) && (mentionsPartner(text, profile()) || has(text, SEND_WORDS)) ->
                coupleSend(coupleKind(text) ?: "love", "")
            has(text, CALL) && !has(text, WHATSAPP) -> callCommand(text)
            has(text, STORY_WORDS) -> openStory()
            has(text, REEL_WORDS) -> openReel()
            has(text, STREAK_WORDS) -> openSelfieStreak()
            has(text, DAILY_Q_WORDS) -> openQuestion()
            has(text, LETTER_WORDS) -> openLetters()
            personal(text) -> {}
            has(text, ALARM) -> setAlarm(text)
            has(text, TIMER) -> setTimer(text)
            isRealQuestion(text) -> askAi(text)
            has(text, TORCH) -> setTorch(!has(text, OFF))
            has(text, VOLUME) || has(text, MUTE) -> volume(text)
            has(text, NEXT) -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, "अगला गाना ⏭", "Next track ⏭")
            has(text, PREV) -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "पिछला गाना ⏮", "Previous track ⏮")
            has(text, MUSIC) && has(text, PAUSE) -> mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE, "गाना रोक दिया ⏸", "Music paused ⏸")
            has(text, RESUME) -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, "गाना चालू ▶️", "Music resumed ▶️")
            toggleCommand(text) -> {}
            has(text, WIFI) -> panel(Settings.Panel.ACTION_WIFI, "WiFi")
            has(text, BLUETOOTH) -> panel(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth")
            has(text, HOTSPOT) -> panel("android.settings.TETHER_SETTINGS", "Hotspot")
            has(text, AIRPLANE) -> panel(Settings.ACTION_AIRPLANE_MODE_SETTINGS, "Airplane mode")
            has(text, LOCATION) -> panel(Settings.ACTION_LOCATION_SOURCE_SETTINGS, "Location")
            has(text, DND) -> panel("android.settings.ZEN_MODE_SETTINGS", "Do Not Disturb")
            has(text, DATA) -> panel(Settings.Panel.ACTION_INTERNET_CONNECTIVITY, "Internet")
            has(text, BRIGHTNESS) -> panel(Settings.ACTION_DISPLAY_SETTINGS, "Brightness")
            has(text, BATTERY) -> battery()
            has(text, DATE) -> tellDate()
            has(text, TIME) -> tellTime()
            calculate(text) -> {}
            playInApp(text) -> {}
            has(text, VIDEO_WORDS) && has("$text ", PLAY + listOf("dikhao ", "दिखाओ ")) && !has(text, MUSIC) -> {
                val q = mediaQuery(text)
                if (q.isBlank()) {
                    val v = videoApp()
                    reply(msg("${v.label} खोल रही हूँ 🎬", "Opening ${v.label} 🎬"))
                    ctx.packageManager.getLaunchIntentForPackage(v.pkg)?.let { go(it) }
                } else youtube(q)
            }
            has(text, YOUTUBE) && has("$text ", PLAY) && mediaQuery(text).isNotBlank() -> youtube(mediaQuery(text))
            has(text, MUSIC) && (has("$text ", PLAY) || !has("$text ", QUESTION_WORDS)) -> {
                val q = mediaQuery(text)
                if (isVague(text, q)) moodPlay(text, null)
                else {
                    val y = ytMusicApp()
                    if (y != null) playOn(y, q) else youtube("$q song")
                }
            }
            has(text, NAVIGATE) -> navigate(text)
            has(text, CAMERA) -> camera(has(text, SELFIE))
            has(text, JOKE) -> joke()
            has(text, IDENTITY) -> {
                val p = profile()
                if (Speaker.isPartner()) reply(msg("मैं चीनू हूँ 💚 आपने ही तो मुझे ${p.name} के लिए बनवाया है, ${p.partner}!",
                                                   "I'm Chinu 💚 You made me for ${p.name}, ${p.partner}!"))
                else reply(msg("मैं चीनू हूँ 💚 मुझे ${p.partner} ने सिर्फ़ ${p.name} के लिए बनवाया है। बोलो, क्या करूँ?",
                               "I'm Chinu 💚 ${p.partner} made me only for ${p.name}. What can I do for you?"))
            }
            has(text, HOW_ARE_YOU) -> reply(msg("मैं एकदम बढ़िया हूँ! आप बताओ, क्या करूँ? 😊", "I'm doing great! What can I do for you? 😊"))
            has(text, THANKS) -> reply(msg("कोई बात नहीं! मैं हमेशा हाज़िर हूँ 😊", "You're welcome! Always here for you 😊"))
            has(text, HELP) -> reply(msg(
                "मैं कॉल, व्हाट्सऐप मैसेज, स्क्रीनशॉट, अलार्म, टाइमर, टॉर्च, वॉल्यूम, गाने, रास्ता, कैलकुलेटर, फ़ोन लॉक, और भी बहुत कुछ कर सकती हूँ। कुछ भी पूछो, जवाब दूँगी 💪",
                "I can call, send WhatsApp messages, take screenshots, set alarms and timers, control torch, volume and music, navigate, calculate, search, lock your phone and much more 💪"))
            isGreeting(text) -> {
                val n = Speaker.name(profile())
                reply(msg("हाँ $n, बोलो, मैं सुन रही हूँ 💚", "Hi $n! I'm listening 💖"))
            }
            has(text, OPEN) -> openApp(text)
            has(text, SEARCH) -> askAi(text)
            else -> return false
        }
        return true
    }

    // ======================= Helpers =======================
    private fun msg(hinglishText: String, english: String) = if (hinglish()) hinglishText else english

    // ---------- 💌 letters · ❓ daily question · 🌙 bedtime story ----------
    private val STORY_WORDS = listOf("bedtime story", "bed time story", "sone se pehle kahani", "सोने से पहले कहानी", "hamari kahani", "humari kahani",
        "हमारी कहानी", "raat ki kahani", "रात की कहानी")
    private val DAILY_Q_WORDS = listOf("aaj ka sawal", "aaj ka sawaal", "आज का सवाल", "today's question", "todays question", "daily question")
    private val LETTER_WORDS = listOf("open when", "khat kholo", "khat likho", "khat padh", "mera khat", "ख़त", "खत खोलो", "खत पढ़",
        "chitthi", "चिट्ठी", "love letter")

    private fun openLetters() {
        val n = Letters.sealedCount(ctx)
        reply(if (n > 0) msg("आपके लिए $n बंद ख़त इंतज़ार कर रहे हैं 💌", "$n sealed letter${if (n == 1) "" else "s"} waiting for you 💌")
        else msg("ख़त वाली डायरी खोल रही हूँ 💌", "Opening your open-when letters 💌"))
        go(Intent(ctx, LettersActivity::class.java))
    }

    private fun openQuestion() {
        reply(msg("आज का सवाल: ", "Today's question: ") + DailyQ.question(ctx))
        go(Intent(ctx, QuestionActivity::class.java))
    }

    private val STREAK_WORDS = listOf("streak", "स्ट्रीक", "selfie streak")

    private val REEL_WORDS = listOf("selfie reel", "hamari reel", "humari reel", "हमारी रील", "सेल्फी रील", "selfie slideshow")

    private fun openReel() {
        if (SelfieStreak.reelDays(ctx, 60).isEmpty()) {
            reply(msg("अभी कोई सेल्फी नहीं है, पहले स्ट्रीक शुरू करो 📸", "No selfies yet — start the streak first 📸"))
            go(Intent(ctx, SelfieActivity::class.java))
            return
        }
        reply(msg("आपकी सेल्फी रील चला रही हूँ 🎬💞", "Playing your selfie reel 🎬💞"))
        go(Intent(ctx, ReelActivity::class.java).putExtra("back", 60))
    }

    private fun openSelfieStreak() {
        val s = SelfieStreak.streak(ctx)
        reply(when {
            SelfieStreak.canRescue(ctx) && !SelfieStreak.rescued(ctx) ->
                msg("स्ट्रीक टूट गई है, पर आज डेयर करके बचा सकते हो! 🛟", "The streak broke, but you can still save it today with a dare! 🛟")
            !SelfieStreak.posted(SelfieStreak.mine(ctx)) -> msg("आपकी $s दिन की स्ट्रीक है 🔥 आज की सेल्फी अभी बाकी है!", "You're on a $s day streak 🔥 Today's selfie is still missing!")
            else -> msg("आपकी $s दिन की स्ट्रीक है 🔥", "You're on a $s day streak 🔥")
        })
        go(Intent(ctx, SelfieActivity::class.java))
    }

    private fun openStory() {
        reply(msg("चलो, आपके लिए एक प्यारी सी कहानी लिखती हूँ 🌙", "Let me write you a little bedtime story 🌙"))
        go(Intent(ctx, StoryActivity::class.java))
    }

    private fun ask(question: String) {
        reply(question)
        listenNext()
    }

    private fun has(text: String, words: List<String>) = words.any { text.contains(it) }

    private fun stripWords(text: String, filler: Set<String>): String =
        NameMatcher.clean(text)
            .lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it !in filler }
            .joinToString(" ")
            .trim()

    private fun removeAll(text: String, words: List<String>): String {
        var t = text
        for (w in words) t = t.replace(w, " ")
        return t
    }

    private val QUESTION_WORDS = listOf(
        "kaise", "kaisa", "kaisi", "kyun", "kyu ", "kyon", "kya ", "kaun", "kab ", "kahan", "kidhar", "recipe", "explain",
        "meaning", "matlab", "ke baare", "ke bare", "how ", "why ", "what ", "who ", "when ", "where ", "which ",
        "kisne", "किसने", "kis film", "किस फ़िल्म", "किस फिल्म", "lyrics", "लिरिक्स",
        "कैसे", "कैसा", "कैसी", "क्यों", "क्या", "कौन", "कब", "कहाँ", "कहां", "रेसिपी", "मतलब", "बारे"
    )

    /** A real question (5+ words with a question word) that isn't a navigation / YouTube request */
    private fun isRealQuestion(text: String): Boolean {
        val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
        return words >= 5 && has("$text ", QUESTION_WORDS) && !has(text, NAVIGATE) && !has(text, YOUTUBE) &&
            !(has(text, MUSIC) && has("$text ", PLAY))
    }

    private fun isGreeting(text: String): Boolean {
        val tokens = NameMatcher.clean(text).lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        return tokens.size <= 3 && tokens.any { it in GREET_TOKENS }
    }

    /** Converts digits of any script (९, ৯, ௯...) into 0-9 */
    private fun asciiDigits(s: String): String = buildString {
        for (c in s) if (c.isDigit()) append(Character.getNumericValue(c)) else append(c)
    }

    private fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun go(intent: Intent): Boolean = try {
        if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        host.leaving()
        true
    } catch (e: Exception) {
        false
    }

    private fun isInstalled(pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    private fun service(): AssistantService? {
        val s = AssistantService.instance
        if (s == null) {
            reply(msg("इसके लिए पहले सेटिंग्स में चीनू की ऐक्सेसिबिलिटी ऑन कर दो 🙏", "Please turn on Chinu in Accessibility settings first 🙏"))
            go(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        return s
    }

    // ======================= Screenshot =======================
    private fun screenshot(text: String) {
        val s = service() ?: return
        // "WhatsApp ka screenshot lo" -> pehle WhatsApp kholo, phir screenshot
        val target = stripWords(removeAll(text, SCREENSHOT), SHOT_FILLER)
        if (target.isNotBlank()) {
            val app = findApp(target)
            val launch = app?.let { ctx.packageManager.getLaunchIntentForPackage(it.pkg) }
            if (app != null && launch != null) {
                reply(msg("${app.label} खोलकर स्क्रीनशॉट ले रही हूँ 📸", "Opening ${app.label} and taking a screenshot 📸"))
                go(launch)
                s.takeScreenshot(3200)
                return
            }
        }
        reply(msg("ठीक है, स्क्रीनशॉट ले रही हूँ 📸", "Okay, taking a screenshot 📸"))
        host.hideNow()
        s.takeScreenshot(1500)
    }

    // ======================= System =======================
    private class Sys(val action: Int, val hi: String, val en: String, val leave: Boolean)

    private fun systemAction(text: String): Boolean {
        val sys = when {
            (has(text, LOCK) || Regex("\\block\\b").containsMatchIn(text)) && !text.contains("अनलॉक") ->
                Sys(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "फ़ोन लॉक कर रही हूँ 🔒", "Locking your phone 🔒", false)
            has(text, POWER) ->
                Sys(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG, "पावर मेन्यू खोल रही हूँ", "Opening power menu", false)
            has(text, QUICK_SETTINGS) ->
                Sys(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "क्विक सेटिंग्स खोल रही हूँ", "Opening quick settings", false)
            has(text, NOTIFICATIONS) ->
                Sys(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "नोटिफ़िकेशन दिखा रही हूँ 🔔", "Showing notifications 🔔", false)
            has(text, RECENTS) ->
                Sys(AccessibilityService.GLOBAL_ACTION_RECENTS, "हाल के ऐप्स दिखा रही हूँ", "Showing recent apps", false)
            has(text, SPLIT) ->
                Sys(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN, "स्प्लिट स्क्रीन कर रही हूँ", "Toggling split screen", true)
            has(text, HOME) ->
                Sys(AccessibilityService.GLOBAL_ACTION_HOME, "होम स्क्रीन पर जा रही हूँ 🏠", "Going home 🏠", false)
            has(text, BACK) ->
                Sys(AccessibilityService.GLOBAL_ACTION_BACK, "पीछे जा रही हूँ", "Going back", true)
            has(text, SCROLL) -> {
                val s = service() ?: return true
                val up = has(text, UPWARD)
                reply(msg(if (up) "ऊपर स्क्रॉल कर रही हूँ" else "नीचे स्क्रॉल कर रही हूँ", if (up) "Scrolling up" else "Scrolling down"))
                host.hideNow()
                s.scroll(!up, 1300)
                return true
            }
            else -> return false
        }
        val s = service() ?: return true
        reply(msg(sys.hi, sys.en))
        if (sys.leave) host.hideNow()
        s.global(sys.action, 1200)
        return true
    }

    // ======================= Contacts =======================
    private fun loadContacts(): List<Contact> {
        val list = mutableListOf<Contact>()
        if (!granted(Manifest.permission.READ_CONTACTS)) return list
        val seen = HashSet<String>()
        val cursor = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        ) ?: return list
        cursor.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0)
                val num = c.getString(1)
                if (n != null && num != null) {
                    val clean = NameMatcher.clean(n)          // emoji hata do
                    val digits = num.filter { it.isDigit() }.takeLast(10)
                    if (clean.isNotBlank() && digits.isNotEmpty() && seen.add(clean.lowercase() + "|" + digits)) {
                        list.add(Contact(n, clean, num))
                    }
                }
            }
        }
        return list
    }

    /** Best contact dhoondo; agar 2-3 milte-julte naam hon to poochho kaunsa */
    private fun resolveContact(name: String, action: String, message: String) {
        if (!granted(Manifest.permission.READ_CONTACTS)) {
            reply(msg("प्लीज़ कॉन्टैक्ट्स की परमिशन दे दो 🙏", "Please allow Contacts permission 🙏"))
            return
        }
        // 💞 Komal / Chiku → the name they're saved under on this phone ("Meri Dumbo" / "My Man")
        partnerAlias(name)?.let { alias ->
            val hit = loadContacts().map { it to NameMatcher.score(alias, it.clean) }.filter { it.second >= 0.8 }.maxByOrNull { it.second }
            if (hit != null) {
                doContactAction(hit.first, action, message)
                return
            }
        }
        val ranked = loadContacts()
            .map { it to NameMatcher.score(name, it.clean) }
            .filter { it.second >= 0.6 }
            .sortedByDescending { it.second }
        if (ranked.isEmpty()) {
            reply(msg("\"$name\" नाम का कोई कॉन्टैक्ट नहीं मिला 😕", "Couldn't find a contact named \"$name\" 😕"))
            return
        }
        val top = ranked[0]
        val close = ranked
            .filter { it.second >= top.second - 0.06 }
            .map { it.first }
            .distinctBy { it.clean.lowercase() }
            .take(3)
        if (top.second >= 0.99 || close.size == 1) {
            doContactAction(top.first, action, message)
        } else {
            pending = Pending.Choose(close, action, message)
            val list = close.mapIndexed { i, c -> "${i + 1}. ${c.clean}" }.joinToString(", ")
            ask(msg("कौन सा वाला? $list", "Which one? $list"))
        }
    }

    /** On Chiku's phone Komal is saved as "Meri Dumbo", on Komal's phone Chiku is "My Man" */
    private fun partnerAlias(name: String): String? {
        Speaker.load(ctx)
        val other = Speaker.other(profile())
        if (other.isBlank()) return null
        val words = NameMatcher.clean(name).split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.none { NameMatcher.score(it, other) >= 0.85 }) return null
        return if (Speaker.isPartner()) "Meri Dumbo" else "My Man"
    }

    private fun pickOption(matches: List<String>, options: List<Contact>): Contact? {
        val t = matches.first().lowercase()
        val idx = when {
            has(t, listOf("first", "pehla", "pahla", "पहला", "pehle", "one", "ek", "एक", " 1")) || t.trim() == "1" -> 0
            has(t, listOf("second", "doosra", "dusra", "दूसरा", "two", "do ", "दो", " 2")) || t.trim() == "2" -> 1
            has(t, listOf("third", "teesra", "tisra", "तीसरा", "three", "teen", "तीन", " 3")) || t.trim() == "3" -> 2
            else -> -1
        }
        if (idx in options.indices) return options[idx]
        for (m in matches) {
            val pick = NameMatcher.best(m, options, 0.5) { it.clean }
            if (pick != null) return pick
        }
        return null
    }

    private fun doContactAction(c: Contact, action: String, message: String) {
        when (action) {
            "call" -> dial(c.number, c.clean)
            else -> sendWhatsApp(c, message)
        }
    }

    /** "Rahul ko ..." -> "rahul" ; "send message to Rahul saying hi" -> "rahul" */
    private fun extractName(part: String, filler: Set<String>): String {
        val t = " ${part.lowercase()} "
        for (m in listOf(" ko ", " को ", " kon ")) {
            val i = t.indexOf(m)
            if (i > 0) {
                val before = stripWords(t.substring(0, i), filler)
                if (before.isNotBlank()) return before
            }
        }
        val r = Regex("\\bto ([\\p{L}\\p{M}\\s]+?)(?: on | via | saying | that |$)").find(part.lowercase())
        if (r != null) {
            val n = stripWords(r.groupValues[1], filler)
            if (n.isNotBlank()) return n
        }
        return stripWords(part, filler)
    }

    // ======================= Call =======================
    private fun callCommand(text: String) {
        val digits = asciiDigits(text).filter { it.isDigit() }
        if (digits.length >= 3) {
            dial(digits, digits)
            return
        }
        val p = profile()
        val name = extractName(text, CALL_FILLER)
        if (name.isBlank() || mentionsPartner(name, p)) {
            if (mentionsPartner(text, p)) {
                callPartner(p)
                return
            }
            pending = Pending.AskName("call")
            ask(msg("किसको कॉल करूँ? 📞", "Whom should I call? 📞"))
            return
        }
        resolveContact(name, "call", "")
    }

    private fun callPartner(p: Profile.Data) {
        if (p.partnerPhone.isNotBlank()) {
            dial(p.partnerPhone, "${p.partner} 💖")
            return
        }
        resolveContact(p.partner, "call", "")
    }

    private fun dial(number: String, label: String) {
        val clean = number.filter { it.isDigit() || it == '+' }
        reply(msg("$label को कॉल कर रही हूँ 📞", "Calling $label 📞"))
        val action = if (granted(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        go(Intent(action, Uri.parse("tel:$clean")))
    }

    // ======================= WhatsApp =======================
    private fun whatsappCommand(text: String) {
        if (Brain.key(ctx).isEmpty()) {
            whatsappLocal(text)
            return
        }
        host.thinking()
        Brain.parseMessageCommand(ctx, text) { r ->
            if (r == null) {
                whatsappLocal(text)
                return@parseMessageCommand
            }
            whatsappTo(r.first, r.second)
        }
    }

    private fun whatsappTo(who: String, message: String) {
        val p = profile()
        val digits = asciiDigits(who).filter { it.isDigit() }
        when {
            digits.length >= 10 -> sendWhatsApp(Contact(digits, digits, digits), message)
            mentionsPartner(who, p) && p.partnerPhone.isNotBlank() ->
                sendWhatsApp(Contact(p.partner, p.partner, p.partnerPhone), message)
            else -> resolveContact(who, "wa", message)
        }
    }

    /** Without AI: split the sentence into name + message by key words */
    private fun whatsappLocal(text: String) {
        var namePart = text
        var message = ""
        val padded = " $text "
        for (d in MSG_DELIMS) {
            val i = padded.indexOf(d)
            if (i > 0) {
                val after = padded.substring(i + d.length).trim()
                // "message" ke baad sirf "bhejo/karo" ho to wo message nahi hai
                val afterClean = stripWords(after, WA_FILLER + setOf("ki", "कि", "that", "saying", "bolo", "बोलो", "likho", "लिखो"))
                if (afterClean.isNotBlank()) {
                    // shuru ke "bhejo / karo / ki" jaise shabd hata do
                    val words = after.split(Regex("\\s+")).toMutableList()
                    while (words.isNotEmpty() && words[0].lowercase() in LEAD_WORDS) words.removeAt(0)
                    val m = words.joinToString(" ").trim()
                    if (m.isBlank()) continue
                    namePart = padded.substring(0, i)
                    message = m
                    break
                }
            }
        }
        val digits = asciiDigits(namePart).filter { it.isDigit() }
        if (digits.length >= 10) {
            sendWhatsApp(Contact(digits, digits, digits), message)
            return
        }
        val p = profile()
        val name = extractName(namePart, WA_FILLER)
        if (name.isBlank()) {
            if (mentionsPartner(text, p)) {
                if (p.partnerPhone.isNotBlank()) sendWhatsApp(Contact(p.partner, p.partner, p.partnerPhone), message)
                else resolveContact(p.partner, "wa", message)
                return
            }
            reply(msg("व्हाट्सऐप खोल रही हूँ 💬", "Opening WhatsApp 💬"))
            knownApp("whatsapp")?.let { app -> ctx.packageManager.getLaunchIntentForPackage(app.pkg)?.let { go(it) } }
            return
        }
        if (mentionsPartner(name, p) && p.partnerPhone.isNotBlank()) {
            sendWhatsApp(Contact(p.partner, p.partner, p.partnerPhone), message)
            return
        }
        resolveContact(name, "wa", message)
    }

    private fun sendWhatsApp(c: Contact, rawMessage: String) {
        val (noEmojiWords, emojis) = MsgStyle.pullEmojis(rawMessage)
        val stripped = MsgStyle.strip(noEmojiWords).ifBlank { if (MsgStyle.fromCommand(noEmojiWords) != null) "" else noEmojiWords }
        val message = (stripped + if (emojis.isNotEmpty()) " $emojis" else "").trim()
        if (message.isBlank()) {
            pending = Pending.WaMessage(c)
            ask(msg("${c.clean} को क्या मैसेज भेजूँ? ✍️", "What should I send to ${c.clean}? ✍️"))
            return
        }
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { isInstalled(it) }
        if (pkg == null) {
            reply(msg("इस फ़ोन में व्हाट्सऐप नहीं मिला", "WhatsApp is not installed"))
            return
        }
        val style = cmdStyle ?: MsgStyle.default(ctx)
        cmdStyle = null
        host.thinking()
        // fix mis-heard words + English / Hinglish / Hindi, then send
        Brain.rewriteMessage(ctx, message, style) { finalText -> deliverWhatsApp(c, finalText, pkg) }
    }

    private fun deliverWhatsApp(c: Contact, message: String, pkg: String) {
        var n = c.number.filter { it.isDigit() }.trimStart('0')
        if (n.length == 10) n = "91$n"
        val url = "https://wa.me/$n?text=" + Uri.encode(message)
        val svc = AssistantService.instance
        if (svc != null) {
            svc.armWhatsAppSend()
            reply(msg("${c.clean} को व्हाट्सऐप पर भेज रही हूँ: \"$message\" ✅", "Sending to ${c.clean} on WhatsApp: \"$message\" ✅"))
        } else {
            reply(msg("मैसेज लिख दिया है, बस सेंड दबा दो। अपने-आप भेजने के लिए ऐक्सेसिबिलिटी ऑन कर दो",
                      "Message is ready, just tap Send. Turn on Accessibility for auto-send"))
        }
        go(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg))
    }

    // ======================= 🛟 Care commands =======================
    private fun careCommand(raw: String): Boolean {
        val low = raw.lowercase().trim()
        if (!CoupleLink.paired(ctx)) return false
        val other = Speaker.other(profile())
        val words = NameMatcher.clean(raw).split(Regex("\\s+")).filter { it.isNotBlank() }
        val namesOther = other.isNotBlank() && words.any { NameMatcher.score(it, other) >= 0.85 }
        val send = has(low, SEND_WORDS) || has(low, listOf("share", "शेयर", "bhej", "भेज"))
        fun openCare(what: String) = go(Intent(ctx, CoupleActivity::class.java).putExtra("care", what))
        when {
            Bday.isToday() && has(low, listOf("surprise", "सरप्राइज", "sarprise", "birthday gift", "बर्थडे गिफ्ट")) -> {
                reply(msg("🎁 ये रहा आपका सरप्राइज़!", "🎁 Here's your surprise!"))
                go(Intent(ctx, BdayActivity::class.java).putExtra("preview", Bday.chiku(ctx)))
            }
            has(low, listOf("sapna", "sapne", "सपना", "सपने", "dream")) && has(low, listOf("matlab", "मतलब", "meaning", "dekha", "देखा", "diary", "डायरी")) -> {
                reply(msg("अपना सपना लिखो, मैं मतलब बताती हूँ 🔮", "Write your dream, I'll tell you what it means 🔮"))
                openCare(if (has(low, listOf("hamare", "हमारे", "our", "sab", "सब"))) "dreams" else "dream")
            }
            has(low, listOf("coupon", "कूपन", "kupan")) -> {
                val mine = has(low, listOf("mere", "मेरे", "my", "wallet", "use", "यूज़", "dikhao", "दिखाओ"))
                reply(if (mine) msg("ये रहे आपके कूपन 🎟️", "Here are your coupons 🎟️") else msg("$other को कौन सा कूपन देना है? 🎟️", "Which coupon for $other? 🎟️"))
                openCare(if (mine) "coupons" else "coupon")
            }
            has(low, listOf("open when", "kholna jab", "खोलना जब")) -> {
                val mine = has(low, listOf("mere", "मेरे", "my", "dikhao", "दिखाओ", "aaye", "आए"))
                reply(msg("💌 ठीक है", "💌 Okay"))
                openCare(if (mine) "letters" else "letter")
            }
            has(low, listOf("baarish ki awaaz", "barish ki awaz", "बारिश की आवाज़", "बारिश की आवाज", "sleep sound", "neend wali", "नींद वाली",
                "saath so", "साथ सो", "sone ja raha", "sone ja rahi", "सोने जा रहा", "सोने जा रही")) -> {
                reply(msg("गुड नाइट 🌙 आवाज़ चुनो", "Good night 🌙 pick a sound"))
                openCare("sleep")
            }
            Regex("\\bsos\\b|एसओएस|emergency|इमरजेंसी").containsMatchIn(low) -> {
                reply(msg("SOS भेजने से पहले एक बार पक्का कर लो 🆘", "Confirm once to send the SOS 🆘"))
                openCare("sos")
            }
            has(low, listOf("location", "लोकेशन", "lokeshan", "loction")) && (send || namesOther) -> {
                reply(msg("$other को आपकी लोकेशन भेज रही हूँ 📍", "Sending your location to $other 📍"))
                openCare("loc")
            }
            namesOther && has(low, listOf("photo", "फोटो", "फ़ोटो", "pic", "selfie", "सेल्फी")) &&
                has(low, listOf("maang", "मांग", "माँग", "मँग", "request", "ask")) -> {
                Plus.askPhoto(ctx)
                reply(msg("$other से फ़ोटो माँग ली 📸", "Asked $other for a photo 📸"))
            }
            namesOther && has(low, listOf("gaana", "gana", "गाना", "song", "सॉन्ग")) && send -> {
                val drop = setOf("ko", "को", "ke", "के", "liye", "लिए", "ye", "yeh", "ये", "यह", "wala", "वाला", "ek", "एक", "gaana", "gana", "गाना",
                    "song", "सॉन्ग", "bhejo", "भेजो", "send", "karo", "करो", "kar", "do", "दो", "share", "शेयर", "chinu", "चीनू", "hey", "please")
                val q = NameMatcher.clean(raw).split(Regex("\\s+"))
                    .filter { w -> w.isNotBlank() && w.lowercase() !in drop && NameMatcher.score(w, other) < 0.85 }.joinToString(" ")
                if (q.isBlank()) openCare("song")
                else {
                    Plus.sendSong(ctx, q)
                    reply(msg("$other को गाना भेज दिया 🎵 “$q”", "Sent the song to $other 🎵 “$q”"))
                }
            }
            has(low, listOf("hisaab", "hisab", "हिसाब", "kharcha", "खर्चा", "expense")) -> {
                val amt = Regex("(\\d+(?:\\.\\d+)?)").find(asciiDigits(low))?.value?.toDoubleOrNull()
                if (amt != null && amt > 0) {
                    val what = NameMatcher.clean(raw).split(Regex("\\s+")).filter { w ->
                        w.isNotBlank() && w.none { it.isDigit() } && w.lowercase() !in setOf("hisaab", "hisab", "हिसाब", "kharcha", "खर्चा", "expense",
                            "mein", "me", "में", "add", "karo", "करो", "likho", "लिखो", "jodo", "जोड़ो", "daalo", "डालो", "rupaye", "रुपये", "rs", "₹",
                            "maine", "मैंने", "diye", "दिए", "ka", "का", "ke", "के", "ki", "की", "chinu", "चीनू", "hamare", "हमारे", "hamara", "हमारा")
                    }.joinToString(" ")
                    Plus.addExpense(ctx, amt, what)
                    reply(msg("हिसाब में जोड़ दिया 💰 ₹${amt.toLong()} $what", "Added to our expenses 💰 ₹${amt.toLong()} $what") +
                        (Plus.balanceLine(ctx)?.let { "\n$it" } ?: ""))
                } else {
                    reply(Plus.balanceLine(ctx) ?: msg("हमारा हिसाब खोल रही हूँ 💰", "Opening our expenses 💰"))
                    openCare("exp")
                }
            }
            has(low, listOf("date idea", "डेट आइडिया", "date ka idea", "kahan ghumne", "कहाँ घूमने", "kya karein aaj", "क्या करें आज")) -> {
                reply("💡 " + Fun.randomIdea())
            }
            namesOther && has(low, listOf("compliment", "कॉम्प्लिमेंट", "tareef", "तारीफ़", "तारीफ")) -> {
                Fun.sendCompliment(ctx)
                reply(msg("$other को एक प्यारा सा कॉम्प्लिमेंट भेज दिया 😍", "Sent a sweet compliment to $other 😍"))
            }
            namesOther && has(low, listOf("mausam", "मौसम", "weather", "वेदर", "temperature", "garmi", "thand", "baarish")) -> {
                Fun.askWeather(ctx)
                reply(msg("$other के यहाँ का मौसम पूछ रही हूँ 🌦️", "Asking for the weather at $other's place 🌦️"))
            }
            has(low, listOf("sorry", "सॉरी", "maafi", "माफ़ी", "maaf kar", "माफ़ कर")) && namesOther -> {
                reply(msg("$other को सॉरी भेजने के लिए लिख दो 🥺", "Write your sorry for $other 🥺"))
                openCare("sorry")
            }
            has(low, listOf("paani piya", "पानी पिया", "glass paani", "ग्लास पानी", "drank water", "water piya")) -> {
                More.addWater(ctx)
                reply(msg("शाबाश! 💧 आज ${More.water(ctx, "me")}/${More.WATER_GOAL} ग्लास", "Well done! 💧 ${More.water(ctx, "me")}/${More.WATER_GOAL} glasses today"))
            }
            has(low, listOf("aaj ka challenge", "आज का चैलेंज", "today's challenge", "couple challenge")) -> {
                reply("🎯 " + More.challenge())
            }
            namesOther && has(low, listOf("kitni door", "कितनी दूर", "kitne door", "कितने दूर", "how far", "distance")) -> {
                reply(More.distanceLine(ctx) ?: msg("$other से लोकेशन माँगनी होगी 📏", "Need $other's location first 📏"))
                openCare("far")
            }
            namesOther && has(low, listOf("wishlist", "विशलिस्ट", "kya chahiye", "क्या चाहिए", "gift")) -> {
                openCare("wish")
                reply(msg("$other की विशलिस्ट खोल रही हूँ 🎁", "Opening $other's wishlist 🎁"))
            }
            has(low, listOf("rashifal", "राशिफल", "horoscope", "होरोस्कोप")) -> reply("🔮 " + Xtra.horoscope())
            has(low, listOf("love meter", "लव मीटर")) -> reply(Xtra.meterLine(ctx))
            has(low, listOf("shayari", "शायरी")) -> openCare("shayari")
            has(low, listOf("hamara gaana", "हमारा गाना", "our song")) -> openCare("oursong")
            has(low, listOf("hamari kahani", "हमारी कहानी", "our story")) && !has(low, listOf("sunao", "सुनाओ", "suna do")) -> openCare("story")
            has(low, listOf("hamara pet", "हमारा पेट", "bunny", "बनी")) -> {
                reply(Xtra.petLine(ctx))
                openCare("pet")
            }
            has(low, listOf("hamare stats", "हमारे स्टैट्स", "our stats")) -> openCare("stats")
            has(low, listOf("toss karo", "टॉस करो", "sikka uchhalo", "सिक्का उछालो", "dice", "paasa", "पासा")) -> openCare("toss")
            has(low, listOf("chaand", "चाँद", "चांद", "moon")) && has(low, listOf("dekh", "देख", "look")) -> {
                reply(Special.moonLine(ctx))
                openCare("moon")
            }
            has(low, listOf("collage", "कोलाज")) -> openCare("collage")
            has(low, listOf("kavita likh", "कविता लिख", "poem likh", "write a poem", "love letter likh", "लव लेटर लिख")) -> openCare("ai")
            has(low, listOf("call date", "कॉल डेट")) -> openCare("call")
            has(low, listOf("timeline", "टाइमलाइन", "love story", "लव स्टोरी")) -> openCare("timeline")
            has(low, listOf("hum teeno", "हम तीनों", "teeno khel", "तीनों खेल", "pathar kaagaz", "पत्थर कागज़", "rock paper", "shabd ki chain", "word chain",
                "chinu ka quiz", "tap battle", "reaction race", "number dhoondh")) -> {
                reply(msg("चलो खेलते हैं — आप, $other और मैं! 🎮", "Let's play — you, $other and me! 🎮"))
                openCare("games")
            }
            has(low, listOf("bucket list", "बकेट लिस्ट")) -> {
                reply(msg("हमारी बकेट लिस्ट खोल रही हूँ 🪣", "Opening our bucket list 🪣"))
                openCare("bucket")
            }
            namesOther && has(low, listOf("battery", "बैटरी", "charge", "चार्ज", "charging")) -> {
                Care.askStatus(ctx)
                val known = Care.statusLine(ctx)
                reply(msg("$other के फ़ोन से पूछ रही हूँ 🔋" + (known?.let { " (पिछली बार: $it)" } ?: ""),
                    "Asking $other's phone 🔋" + (known?.let { " (last: $it)" } ?: "")))
            }
            has(low, listOf("milne", "मिलने", "milenge", "मिलेंगे", "meet")) && has(low, listOf("kitne din", "कितने दिन", "how many days", "kab", "कब", "countdown")) -> {
                val line = Care.countdownLine(ctx)
                if (line != null) reply(line)
                else {
                    reply(msg("अभी मिलने की तारीख़ सेट नहीं है, चलो सेट करते हैं ⏳", "No meeting date yet — let's set it ⏳"))
                    openCare("meet")
                }
            }
            Regex("(?:^|\\s)(?:list|लिस्ट)(?:\\s|$)").containsMatchIn(low) && has(low, listOf("hamari", "humari", "हमारी", "our", "shopping", "शॉपिंग", "saman", "सामान", "mein", "me ", "में")) -> {
                val add = Regex("^(.+?)\\s+(?:ko\\s+|को\\s+)?(?:hamari\\s+|humari\\s+|हमारी\\s+|our\\s+|shopping\\s+|शॉपिंग\\s+)?(?:list|लिस्ट)\\s*(?:mein|me|में|par|पर)\\s+(?:add|daal|dal|daalo|likh|likho|jod|jodo|जोड़|जोड़ो|डाल|डालो|लिख|लिखो)")
                    .find(low)?.groupValues?.get(1)
                    ?: Regex("(?:^|\\s)(?:list|लिस्ट)\\s*(?:mein|me|में)\\s+(.+?)\\s+(?:add|daal|dal|daalo|likh|likho|jod|jodo|जोड़|जोड़ो|डाल|डालो|लिख|लिखो)")
                        .find(low)?.groupValues?.get(1)
                val item = add?.split(Regex("\\s+"))?.dropWhile { it in setOf("chinu", "चीनू", "hey", "ok") }?.joinToString(" ")?.trim().orEmpty()
                if (item.isNotEmpty()) {
                    Care.addItem(ctx, item)
                    reply(msg("हमारी लिस्ट में जोड़ दिया 📝 “$item”", "Added to our list 📝 “$item”"))
                } else {
                    reply(msg("हमारी लिस्ट खोल रही हूँ 📝", "Opening our list 📝"))
                    openCare("list")
                }
            }
            else -> return false
        }
        return true
    }

    // ======================= 💬 Own words / 🎤 voice message to the partner =======================
    private val TELL_WORDS = setOf("bolo", "bol", "bolna", "boldo", "bolde", "batao", "bata", "bataa", "batado", "kaho", "kah", "keh",
        "kehna", "kehdo", "kahdo", "message", "msg", "tell", "say", "बोलो", "बोल", "बोलना", "बताओ", "बता", "कहो", "कह", "कहना", "मैसेज")
    private val TELL_GLUE = setOf("ko", "को", "se", "से", "do", "दो", "de", "दे", "dena", "देना", "ki", "कि", "that", "to", "ke", "के",
        "ye", "yeh", "ये", "यह", "please", "plz", "zara", "ज़रा", "जरा", "bhejo", "भेजो", "send", "chinu", "चीनू", "चिनू", "hey", "hi", "ek", "एक")
    private val VOICE_WORDS = Regex("voice|वॉइस|वॉयस|awaaz|aawaz|awaz|आवाज़|आवाज|audio|ऑडियो|recording|रिकॉर्डिंग")

    private fun tok(w: String) = w.lowercase().trim(',', '.', '!', '?', '"', '\'', '।', ':')

    /** Understands "<partner> ko bolo …", "tell <partner> …", "<partner> ko voice message bhejo". true = handled */
    private fun partnerMessage(raw: String): Boolean {
        val low = raw.lowercase()
        if (has(low, WHATSAPP)) return false
        val other = Speaker.other(profile())
        if (other.isBlank()) return false
        val words = raw.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val nameIdx = words.indexOfFirst { w -> NameMatcher.clean(w).let { it.isNotBlank() && NameMatcher.score(it, other) >= 0.85 } }
        if (nameIdx < 0) return false
        val lw = words.map { tok(it) }
        // 🎤 voice message
        if (VOICE_WORDS.containsMatchIn(low) && (lw.any { it in TELL_WORDS } || has(low, SEND_WORDS))) {
            if (!CoupleLink.paired(ctx)) return coupleSend("love", "").let { true }
            reply(msg("$other के लिए वॉइस मैसेज रिकॉर्ड करो 🎤", "Record your voice message for $other 🎤"))
            go(Intent(ctx, CoupleActivity::class.java).putExtra("voice", true))
            return true
        }
        var t = -1
        for (i in maxOf(0, nameIdx - 1)..minOf(lw.size - 1, nameIdx + 3)) if (i != nameIdx && lw[i] in TELL_WORDS) {
            t = i
            break
        }
        if (t < 0) return false
        // "Komal KO bolo …" (not "Chinu bolo Komal kaun hai") · "tell Komal …"
        if (t > nameIdx && lw.subList(nameIdx + 1, t).none { it in setOf("ko", "को", "se", "से", "ku") }) return false
        if (t < nameIdx && lw[t] != "tell") return false
        var body = words.drop(maxOf(t, nameIdx) + 1).dropWhile { tok(it) in TELL_GLUE || tok(it) in TELL_WORDS }
        // "khana kha lo, Komal ko bolo" → the message came first
        if (body.isEmpty()) body = words.take(minOf(t, nameIdx)).dropWhile { tok(it) in TELL_GLUE }
        val text = body.joinToString(" ").trim().trim(',', '.', ' ')
        // "Komal ko kiss bhejo" etc. stay hugs / kisses
        if (coupleKind(low) != null && text.split(" ").size <= 3) return false
        if (text.isBlank()) {
            if (!CoupleLink.paired(ctx)) return coupleSend("love", "").let { true }
            pending = Pending.PartnerNote
            reply(msg("$other को क्या बोलना है? 💬", "What should I tell $other? 💬"))
            return true
        }
        sendPartnerNote(text)
        return true
    }

    private fun sendPartnerNote(text: String) {
        val other = Speaker.other(profile())
        if (!CoupleLink.paired(ctx)) {
            coupleSend("love", "")
            return
        }
        CoupleLink.send(ctx, "cmsg", org.json.JSONObject().put("text", text)) { ok ->
            if (!ok) reply(msg("इंटरनेट नहीं है, $other तक नहीं पहुँचा 😕", "No internet — it didn't reach $other 😕"))
        }
        reply(msg("$other की स्क्रीन पर भेज दिया, और मैं उन्हें बोलकर भी बता दूँगी 💬🗣️ “$text”",
            "Sent to $other's screen — their Chinu will say it out loud too 💬🗣️ “$text”"))
    }

    // ======================= 💞 Hug / kiss to the partner =======================
    private fun coupleKind(t: String): String? = when {
        Regex("kiss|किस|chumma|चुम्मा|pappi|पप्पी|kissi").containsMatchIn(t) -> "kiss"
        Regex("\\bhug\\b|हग|jhappi|झप्पी|gale").containsMatchIn(t) -> "hug"
        Regex("miss (you|kar)|मिस (यू|कर)").containsMatchIn(t) -> "miss"
        Regex("i love you|आई लव यू|love you|लव यू").containsMatchIn(t) -> "love"
        else -> null
    }

    private fun coupleSend(kindRaw: String, note: String) {
        val p = profile()
        val other = Speaker.other(p)
        if (!CoupleLink.paired(ctx)) {
            reply(msg("पहले दोनों फ़ोन जोड़ने होंगे 💞 होम स्क्रीन पर 💞 बटन दबाओ और कोड से कनेक्ट करो।",
                      "First connect both phones 💞 Tap the 💞 button on the home screen."))
            return
        }
        if (kindRaw == "voice") {
            reply(msg("$other के लिए वॉइस मैसेज रिकॉर्ड करो 🎤", "Record your voice message for $other 🎤"))
            go(Intent(ctx, CoupleActivity::class.java).putExtra("voice", true))
            return
        }
        val kind = if (kindRaw == "note" && note.isBlank()) "love" else kindRaw
        if (kind == "note") {
            sendPartnerNote(note)
            return
        }
        val emoji = CoupleLink.KINDS[kind]?.first ?: "❤️"
        CoupleLink.send(ctx, "love", org.json.JSONObject().put("k", kind))
        AssistantService.instance?.showLove(emoji, msg("$other को भेज दिया", "Sent to $other"))
        reply(when (kind) {
            "kiss" -> msg("$other को एक प्यारा सा किस भेज दिया 😘", "Sent a sweet kiss to $other 😘")
            "hug" -> msg("$other को एक प्यारी सी झप्पी भेज दी 🤗", "Sent a warm hug to $other 🤗")
            "miss" -> msg("$other को बता दिया कि आप उन्हें मिस कर रहे हो 🥺", "Told $other you miss them 🥺")
            "think" -> msg("$other को बता दिया कि आप उनके बारे में सोच रहे हो 💭", "Told $other you're thinking of them 💭")
            else -> msg("$other को आई लव यू भेज दिया ❤️", "Sent “I love you” to $other ❤️")
        })
    }

    // ======================= ⏰ Reminders =======================
    private fun reminderCommand(text: String) {
        val p = profile()
        val names = listOf(p.name, p.partner).filter { it.isNotBlank() }.joinToString(", ")
        if (Brain.key(ctx).isEmpty()) {
            reminderLocal(text)
            return
        }
        host.thinking()
        Brain.parseReminder(ctx, text, names) { j ->
            if (j == null) {
                reminderLocal(text)
                return@parseReminder
            }
            try {
                val action = j.optString("action", "add")
                val water = j.optBoolean("water", false)
                when (action) {
                    "routine" -> routineReminders(whoFor(j.optString("who"), text))
                    "list" -> listReminders()
                    "clear" -> clearReminders()
                    "delete" -> deleteReminder(water)
                    else -> {
                        val who = whoFor(j.optString("who"), text)
                        val every = j.optInt("every_min", 0)
                        val at = j.optString("at").let { a ->
                            if (a.isBlank()) 0L
                            else try {
                                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH).parse(a)?.time ?: 0L
                            } catch (e: Exception) {
                                0L
                            }
                        }
                        val from = j.optInt("from_hour", 8)
                        val to = j.optInt("to_hour", 22)
                        val rText = j.optString("text").trim()
                        val minute = j.optInt("minute", 0)
                        when {
                            every >= 1440 -> confirmReminder(Reminders.addDaily(ctx, who, if (water) "पानी पी लो 💧" else rText, from, minute))
                            water && at <= 0L -> confirmReminder(Reminders.addWater(ctx, who, if (every > 0) every else 60, from, to))
                            every > 0 -> confirmReminder(Reminders.add(ctx, who, rText, water, every, from, to, 0L))
                            at > System.currentTimeMillis() -> confirmReminder(Reminders.add(ctx, who, rText, water, 0, from, to, at))
                            else -> reminderLocal(text)
                        }
                    }
                }
            } catch (e: Exception) {
                reminderLocal(text)
            }
        }
    }

    /** Who should be reminded: the name said, else the person talking */
    private fun whoFor(said: String, text: String): String {
        val p = profile()
        val s = said.trim()
        if (s.isNotBlank()) {
            if (NameMatcher.score(s, p.name) >= 0.7) return p.name
            if (NameMatcher.score(s, p.partner) >= 0.7) return p.partner
        }
        val tokens = NameMatcher.clean(text).split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.any { NameMatcher.score(it, p.name) >= 0.9 }) return p.name
        if (tokens.any { NameMatcher.score(it, p.partner) >= 0.9 }) return p.partner
        if (s.isNotBlank() && s.length < 25 && !Regex("(?i)^(me|mujhe|मुझे|main|मैं|self)$").matches(s)) return s
        return Speaker.name(p)
    }

    private fun confirmReminder(r: Reminders.Rem) {
        val what = if (r.water) "पानी पीने का" else "\"${r.text}\" का"
        reply(msg("ठीक है! ${r.who} को $what रिमाइंडर दूँगी — ${Reminders.describeHi(r)} ⏰ बोलकर भी बताऊँगी और नोटिफ़िकेशन भी आएगा।",
                  "Done! I'll remind ${r.who}${if (r.water) " to drink water" else ": " + r.text} — ${Reminders.describe(r)} ⏰"))
    }

    private fun routineReminders(who: String) {
        Reminders.addRoutine(ctx, who)
        reply(msg("$who का डेली रूटीन सेट कर दिया 🗓️ हर घंटे पानी 💧, 9 बजे नाश्ता, 1:30 बजे लंच, 5 बजे पढ़ाई 📚, 8:30 बजे डिनर और 11 बजे सोने का रिमाइंडर। टाइम बदलना हो तो बोल देना, जैसे \"रोज़ 6 बजे पढ़ाई का रिमाइंडर\"।",
                  "Daily routine set for $who 🗓️ Water every hour, breakfast 9, lunch 1:30, study 5, dinner 8:30, sleep 11."))
    }

    private fun listReminders() {
        val list = Reminders.all(ctx)
        if (list.isEmpty()) {
            reply(msg("अभी कोई रिमाइंडर सेट नहीं है।", "No reminders set right now."))
            return
        }
        val hi = list.joinToString("। ") { (if (it.water) "${it.who} — पानी" else "${it.who} — ${it.text}") + ", " + Reminders.describeHi(it) }
        val en = list.joinToString(". ") { it.title() + ", " + Reminders.describe(it) }
        reply(msg("${list.size} रिमाइंडर हैं: $hi", "${list.size} reminders: $en"))
    }

    private fun clearReminders() {
        Reminders.clear(ctx)
        reply(msg("सारे रिमाइंडर हटा दिए 👍", "All reminders deleted 👍"))
    }

    private fun deleteReminder(water: Boolean) {
        val list = Reminders.all(ctx)
        val target = if (water) list.filter { it.water } else listOfNotNull(list.lastOrNull())
        if (target.isEmpty()) {
            reply(msg("ऐसा कोई रिमाइंडर नहीं मिला।", "No such reminder found."))
            return
        }
        target.forEach { Reminders.remove(ctx, it.id) }
        reply(msg(if (water) "पानी वाला रिमाइंडर बंद कर दिया 👍" else "\"${target[0].title()}\" रिमाइंडर हटा दिया 👍",
                  if (water) "Water reminder turned off 👍" else "Deleted \"${target[0].title()}\" 👍"))
    }

    /** Without AI: understands the common ways of saying it */
    private fun reminderLocal(raw: String) {
        val t = asciiDigits(raw.lowercase())
        val water = Regex("pani|paani|पानी|water|hydrat|हाइड्रेट").containsMatchIn(t)
        val del = Regex("delete|remove|hata|हटा|band|बंद|cancel|कैंसिल|off|ऑफ").containsMatchIn(t)
        val all = Regex("\\b(all|sab|saare|sare)\\b|सब|सारे").containsMatchIn(t)
        if (Regex("dikha|दिखा|list|kitne|कितने|kaun se|कौन से|batao|बताओ").containsMatchIn(t) && !Regex("\\d").containsMatchIn(t)) {
            listReminders(); return
        }
        if (del) {
            if (all) clearReminders() else deleteReminder(water)
            return
        }
        val who = whoFor("", raw)
        if (Regex("routine|रूटीन|timetable|time table|टाइम टेबल|टाइमटेबल|auto|ऑटो").containsMatchIn(t)) {
            routineReminders(who); return
        }
        val unitH = "(?:ghante|ghanta|ghanton|घंटे|घंटा|घंटों|hour|hours|hr)"
        val unitM = "(?:min|mins|minute|minutes|मिनट|मिनिट)"
        // "har 2 ghante" / "every 30 minutes" / "har ghante"
        Regex("(?:har|हर|every|each)\\s*(\\d+)?\\s*($unitH|$unitM)").find(t)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: 1
            val every = if (Regex(unitH).matches(m.groupValues[2])) n * 60 else n
            val range = Regex("(\\d{1,2})\\s*(?:baje|बजे)?\\s*(?:se|से|from)\\s*(?:\\D{0,12}?)(\\d{1,2})\\s*(?:baje|बजे)?\\s*(?:tak|तक|to|till)").find(t)
            var from = 8
            var to = 22
            if (range != null) {
                from = range.groupValues[1].toInt()
                to = range.groupValues[2].toInt()
                if (to <= 12 && to < from) to += 12
            }
            val text = if (water) "" else reminderText(raw)
            confirmReminder(
                if (water) Reminders.addWater(ctx, who, every, from, to)
                else Reminders.add(ctx, who, text, false, every, from, to, 0L)
            )
            return
        }
        // "10 minute baad" / "2 ghante baad" / "in 10 minutes"
        val after = Regex("(\\d+)\\s*($unitH|$unitM)\\s*(?:baad|बाद|me|में|mein|later)").find(t)
            ?: Regex("in\\s*(\\d+)\\s*($unitH|$unitM)").find(t)
        if (after != null) {
            val n = after.groupValues[1].toInt()
            val mins = if (Regex(unitH).matches(after.groupValues[2])) n * 60 else n
            val at = System.currentTimeMillis() + mins * 60_000L
            confirmReminder(Reminders.add(ctx, who, if (water) "" else reminderText(raw), water, 0, 8, 22, at))
            return
        }
        // "shaam 5 baje" / "kal subah 7:30 baje" / "at 6 pm"
        val clock = Regex("(\\d{1,2})(?:[:.](\\d{2}))?\\s*(baje|बजे|am|pm|a\\.m\\.|p\\.m\\.|o'clock)").find(t)
        if (clock != null) {
            var h = clock.groupValues[1].toInt()
            val m = clock.groupValues[2].toIntOrNull() ?: 0
            val tag = clock.groupValues[3]
            val pm = tag.startsWith("p") || Regex("shaam|sham|शाम|evening|raat|रात|night|dopahar|दोपहर|afternoon").containsMatchIn(t)
            val am = tag.startsWith("a") || Regex("subah|सुबह|morning").containsMatchIn(t)
            if (pm && h < 12 && !(Regex("raat|रात").containsMatchIn(t) && h <= 4)) h += 12
            val c = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, h % 24); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            if (Regex("\\b(roz|rooz|daily|everyday)\\b|रोज़|रोज|every day|हर दिन|har din").containsMatchIn(t)) {
                confirmReminder(Reminders.addDaily(ctx, who, if (water) "पानी पी लो 💧" else reminderText(raw), h % 24, m))
                return
            }
            val tomorrow = Regex("\\bkal\\b|कल|tomorrow").containsMatchIn(t)
            if (tomorrow) c.add(Calendar.DAY_OF_YEAR, 1)
            else if (c.timeInMillis <= System.currentTimeMillis()) {
                if (!am && !pm && h < 12) c.add(Calendar.HOUR_OF_DAY, 12) else c.add(Calendar.DAY_OF_YEAR, 1)
                if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
            }
            confirmReminder(Reminders.add(ctx, who, if (water) "" else reminderText(raw), water, 0, 8, 22, c.timeInMillis))
            return
        }
        if (water) {
            confirmReminder(Reminders.addWater(ctx, who, 60, 8, 22))
            return
        }
        reply(msg("कब याद दिलाऊँ? जैसे बोलो: \"10 मिनट बाद दवाई का रिमाइंडर\" या \"हर घंटे पानी का रिमाइंडर\"",
                  "When should I remind you? Say e.g. \"remind me in 10 minutes to take medicine\""))
    }

    /** "mujhe 5 baje dawai khane ka reminder do" -> "dawai khane" */
    private fun reminderText(raw: String): String {
        var t = " " + raw + " "
        val junk = listOf(
            "remind me to", "remind me", "reminder", "remind", "रिमाइंडर", "रिमाइंड", "याद दिलाना", "याद दिलाओ", "याद दिला देना", "याद दिला दो", "याद दिला",
            "yaad dilana", "yaad dila dena", "yaad dila do", "yaad dila", "set karo", "सेट करो", "सेट कर दो", "लगा दो", "laga do", "दे देना", "दे दो", "de do",
            "dena", "देना", "मुझे", "mujhe", "chinu", "चीनू", "please", "प्लीज़", "का", "ka", "की", "ki", "को", "ko"
        )
        t = t.replace(Regex("\\d+\\s*(baje|बजे|minute|minutes|मिनट|ghante|घंटे|hour|hours)\\s*(baad|बाद|me|में|later)?", RegexOption.IGNORE_CASE), " ")
        t = t.replace(Regex("(?i)\\b(roz|daily|everyday|har din|kal|aaj|subah|shaam|sham|raat|dopahar|tomorrow|today|at|in|pm|am)\\b"), " ")
        t = t.replace(Regex("रोज़|रोज|हर दिन|कल|आज|सुबह|शाम|रात|दोपहर"), " ")
        val p = profile()
        for (n in listOf(p.name, p.partner)) if (n.isNotBlank()) t = t.replace(Regex("(?i)\\b" + Regex.escape(n) + "\\b"), " ")
        for (j in junk.sortedByDescending { it.length }) t = t.replace(Regex("(?i)(^|\\s)" + Regex.escape(j) + "(?=\\s|$)"), " ")
        return t.replace(Regex("\\s+"), " ").trim().ifBlank { "रिमाइंडर" }
    }

    // ======================= 💖 Personal =======================
    private fun mentionsPartner(text: String, p: Profile.Data): Boolean {
        if (p.partner.isBlank()) return false
        val tokens = NameMatcher.clean(text).split(Regex("\\s+")).filter { it.isNotBlank() }
        return tokens.any { NameMatcher.score(it, p.partner) >= 0.9 }
    }

    private fun dateAnswer(ddmm: String, label: String) {
        val p = profile()
        val days = Profile.daysUntil(ddmm)
        if (days == null) {
            reply(msg("$label की तारीख़ अभी मुझे नहीं बताई गई है। प्रोफ़ाइल में डाल दो 💚",
                      "Nobody told me the $label date yet. Add it in Profile 💖"))
            return
        }
        val pd = Profile.pretty(ddmm)
        reply(when (days) {
            0 -> msg("आज ही आपका $label है! 🎉 हैप्पी $label ${p.name}! 💚", "It's today! 🎉 Happy $label ${p.name}! 💖")
            1 -> msg("आपका $label कल है, $pd को! 🎂", "Your $label is tomorrow, on $pd! 🎂")
            else -> msg("आपका $label $pd को है, बस $days दिन बाकी हैं 🎂", "Your $label is on $pd, $days days to go 🎂")
        })
    }

    private fun personal(text: String): Boolean {
        val p = profile()
        val partnerHere = mentionsPartner(text, p)
        when {
            has(text, MY_NAME) && Speaker.isPartner() -> reply(msg(
                "आप ${p.partner} हो 💚, ${p.name} के सबसे ख़ास इंसान, और मुझे बनाने वाले भी! 😊",
                "You're ${p.partner} 💚, ${p.name}'s favourite person and my creator! 😊"))
            has(text, MY_NAME) -> reply(msg(
                "आप ${p.name} हो 💚, ${p.partner} की ${p.relation}! मैं आपको अच्छे से जानती हूँ 😊",
                "You're ${p.name} 💚, ${p.partner}'s ${p.relation}! Of course I know you 😊"))
            partnerHere && has(text, WHO) -> reply(msg(
                "${p.partner} आपके सबसे ख़ास इंसान हैं 💚 उन्होंने ही मुझे, यानी चीनू को, सिर्फ़ आपके लिए बनवाया है।",
                "${p.partner} is your most special person 💖 He made me, Chinu, only for you."))
            has(text, ASSISTANT_NAMES) && has(text, WHO) -> reply(msg(
                "चीनू मैं हूँ, आपकी AI असिस्टेंट 💚 मुझे ${p.partner} ने सिर्फ़ ${p.name} के लिए बनवाया है।",
                "I'm Chinu, your AI assistant 💖 ${p.partner} made me only for ${p.name}."))
            has(text, WISH) && (has(text, BIRTHDAY) || Profile.daysUntil(p.birthday) == 0 || has(text, listOf("wish", "विश"))) -> {
                celebrate()
                reply(Birthday.randomWish(p, hinglish()))
            }
            has(text, BIRTHDAY) -> {
                if (Profile.daysUntil(p.birthday) == 0) celebrate()
                dateAnswer(p.birthday, "birthday")
            }
            has(text, ANNIVERSARY) -> dateAnswer(p.anniversary, "anniversary")
            has(text, FAVOURITE) -> reply(
                if (p.favourites.isBlank()) msg("आपकी पसंद अभी मुझे नहीं बताई गई है। प्रोफ़ाइल में डाल दो 💚", "I don't know your favourites yet. Add them in Profile 💖")
                else msg("आपको पसंद है: ${p.favourites} 💚", "Your favourites: ${p.favourites} 💖"))
            has(text, LOVE) && Speaker.isPartner() -> reply(msg("${p.name} भी आपसे बहुत ज़्यादा प्यार करती हैं 💚", "${p.name} loves you even more 💚"))
            has(text, LOVE) -> reply(msg("${p.partner} भी आपसे बहुत ज़्यादा प्यार करते हैं 💚", "${p.partner} loves you even more 💚"))
            has(text, MISS) && Speaker.isPartner() -> reply(msg(
                "${p.name} भी आपको मिस कर रही होंगी 💚 बोलो तो उन्हें कॉल लगा दूँ?",
                "${p.name} must be missing you too 💚 Want me to call her?"))
            has(text, MISS) && (partnerHere || has(text, listOf("you", "यू", "तुम्हें"))) -> reply(msg(
                "${p.partner} भी आपको मिस कर रहे होंगे 💚 बोलो \"${p.partner} को कॉल करो\", मैं तुरंत लगा दूँगी।",
                "${p.partner} must be missing you too 💖 Say \"call ${p.partner}\" and I'll dial right away."))
            has(text, SPECIAL_MSG) && !has(text, WHATSAPP) && (partnerHere || has(text, SPECIAL_HINT)) -> reply("💌 " + p.message)
            else -> return false
        }
        return true
    }

    // ======================= Alarm + Timer =======================
    private fun setAlarm(text: String) {
        val t = asciiDigits(text)
        val clock = Regex("(\\d{1,2})\\s*[:.]\\s*(\\d{2})").find(t)
        var hour: Int
        var minute = 0
        if (clock != null) {
            hour = clock.groupValues[1].toInt()
            minute = clock.groupValues[2].toInt()
        } else {
            val nums = Regex("\\d+").findAll(t).map { it.value }.toList()
            if (nums.isEmpty()) {
                ask(msg("कितने बजे का अलार्म लगाऊँ? ⏰", "What time should I set the alarm for? ⏰"))
                return
            }
            hour = nums[0].toInt()
            if (nums.size >= 2) minute = nums[1].toInt().coerceIn(0, 59)
        }
        if (has(t, listOf("साढ़े", "साढे", "sadhe", "saade", "saadhe", "half past"))) minute = 30
        if (has(t, listOf("सवा", "sawa", "quarter past"))) minute = 15
        val pm = has(t, listOf("pm", "p.m", "p m", "शाम", "shaam", "sham", "रात", "raat", "दोपहर", "dopahar", "evening", "night", "afternoon"))
        val am = has(t, listOf("am", "a.m", "सुबह", "subah", "morning"))
        if (pm && hour < 12) hour += 12
        if (am && hour == 12) hour = 0
        if (hour > 23 || minute > 59) {
            reply(msg("ये टाइम सही नहीं लगा, फिर से बोलो", "That time doesn't look right, please try again"))
            return
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Chinu")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val shown = String.format(Locale.US, "%d:%02d %s", if (hour % 12 == 0) 12 else hour % 12, minute, if (hour < 12) "AM" else "PM")
        if (go(intent)) reply(msg("$shown का अलार्म लगा दिया ⏰", "Alarm set for $shown ⏰"))
        else reply(msg("अलार्म नहीं लग पाया", "Couldn't set the alarm"))
    }

    private fun setTimer(text: String) {
        val t = asciiDigits(text)
        var secs = 0
        Regex("(\\d+)\\s*(घंटे|घंटा|ghante|ghanta|hours|hour|hrs|hr|मिनट|minutes|minute|mins|min|minat|सेकंड|सेकेंड|seconds|second|secs|sec)")
            .findAll(t).forEach {
                val n = it.groupValues[1].toInt()
                val u = it.groupValues[2]
                secs += when {
                    u.startsWith("घ") || u.startsWith("gh") || u.startsWith("h") -> n * 3600
                    u.startsWith("से") || u.startsWith("sec") -> n
                    else -> n * 60
                }
            }
        if (secs == 0) {
            val n = Regex("\\d+").find(t)?.value?.toInt()
            if (n == null) {
                ask(msg("कितने मिनट का टाइमर लगाऊँ? ⏱", "How long should the timer be? ⏱"))
                return
            }
            secs = n * 60
        }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secs)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Chinu")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val m = secs / 60
        val s = secs % 60
        val shown = if (m > 0 && s > 0) "$m min $s sec" else if (m > 0) "$m min" else "$s sec"
        if (go(intent)) reply(msg("$shown का टाइमर शुरू कर दिया ⏱", "Timer started for $shown ⏱"))
        else reply(msg("टाइमर नहीं लग पाया", "Couldn't start the timer"))
    }

    // ======================= Torch, Volume, Media =======================
    private fun setTorch(on: Boolean) {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (id == null) {
            reply(msg("इस फ़ोन में फ़्लैश नहीं मिला", "No flashlight found"))
            return
        }
        try {
            cm.setTorchMode(id, on)
            reply(if (on) msg("टॉर्च ऑन कर दी 🔦", "Torch is on 🔦") else msg("टॉर्च बंद कर दी", "Torch is off"))
        } catch (e: Exception) {
            reply(msg("टॉर्च नहीं चल पाई", "Couldn't control the torch"))
        }
    }

    private fun volume(text: String) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = AudioManager.STREAM_MUSIC
        val ui = AudioManager.FLAG_SHOW_UI
        when {
            text.contains("unmute") || text.contains("अनम्यूट") -> {
                am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, ui)
                reply(msg("आवाज़ वापस चालू 🔊", "Unmuted 🔊"))
            }
            has(text, MUTE) || has(text, OFF) -> {
                am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, ui)
                reply(msg("आवाज़ बंद कर दी 🔇", "Muted 🔇"))
            }
            has(text, MAX) -> {
                am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                am.setStreamVolume(stream, am.getStreamMaxVolume(stream), ui)
                reply(msg("वॉल्यूम फ़ुल कर दिया 🔊", "Volume at maximum 🔊"))
            }
            has(text, DOWN) -> {
                repeat(3) { am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, ui) }
                reply(msg("वॉल्यूम कम कर दिया 🔉", "Volume down 🔉"))
            }
            else -> {
                am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                repeat(3) { am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, ui) }
                reply(msg("वॉल्यूम बढ़ा दिया 🔊", "Volume up 🔊"))
            }
        }
    }

    private fun mediaKey(code: Int, hi: String, en: String) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        reply(msg(hi, en))
    }

    // ======================= ⚙️ Settings ON / OFF =======================
    private val ON_WORDS = listOf(" on", "chalu", "चालू", "ऑन", "enable", "start", "shuru", "शुरू", "khol", "जला", "jala")
    private class Toggle(val names: List<String>, val tiles: List<String>, val action: String, val hi: String, val en: String)

    private val TOGGLES = listOf(
        Toggle(HOTSPOT, listOf("hotspot", "हॉटस्पॉट", "tethering"), "android.settings.TETHER_SETTINGS", "हॉटस्पॉट", "Hotspot"),
        Toggle(WIFI, listOf("wi-fi", "wifi", "wlan", "वाई-फ़ाई", "वाईफाई"), Settings.ACTION_WIFI_SETTINGS, "वाईफ़ाई", "WiFi"),
        Toggle(BLUETOOTH, listOf("bluetooth", "ब्लूटूथ"), Settings.ACTION_BLUETOOTH_SETTINGS, "ब्लूटूथ", "Bluetooth"),
        Toggle(DATA, listOf("mobile data", "data", "डेटा", "internet"), Settings.ACTION_DATA_ROAMING_SETTINGS, "मोबाइल डेटा", "Mobile data"),
        Toggle(AIRPLANE, listOf("airplane", "aeroplane", "flight", "एयरप्लेन", "फ़्लाइट"), Settings.ACTION_AIRPLANE_MODE_SETTINGS, "एयरप्लेन मोड", "Airplane mode"),
        Toggle(LOCATION, listOf("location", "लोकेशन", "gps"), Settings.ACTION_LOCATION_SOURCE_SETTINGS, "लोकेशन", "Location"),
        Toggle(DND, listOf("do not disturb", "dnd", "डू नॉट डिस्टर्ब"), "android.settings.ZEN_MODE_SETTINGS", "डू नॉट डिस्टर्ब", "Do Not Disturb"),
        Toggle(listOf("auto rotate", "auto-rotate", "rotation", "rotate", "ऑटो रोटेट", "रोटेशन"), listOf("auto-rotate", "auto rotate", "rotate", "रोटेट"), Settings.ACTION_DISPLAY_SETTINGS, "ऑटो रोटेट", "Auto-rotate"),
        Toggle(listOf("battery saver", "power saver", "बैटरी सेवर", "पावर सेवर"), listOf("battery saver", "power saver", "saver", "सेवर"), Settings.ACTION_BATTERY_SAVER_SETTINGS, "बैटरी सेवर", "Battery saver"),
        Toggle(listOf("dark mode", "dark theme", "डार्क मोड"), listOf("dark mode", "dark theme", "डार्क"), Settings.ACTION_DISPLAY_SETTINGS, "डार्क मोड", "Dark mode"),
        Toggle(listOf("nfc", "एनएफसी"), listOf("nfc"), Settings.ACTION_NFC_SETTINGS, "NFC", "NFC")
    )

    /** "hotspot off karo", "wifi chalu karo", "bluetooth band kar do" */
    private fun toggleCommand(text: String): Boolean {
        val t = "$text "
        val wantOn: Boolean? = when {
            has(t, OFF) -> false
            has(t, ON_WORDS) -> true
            else -> null
        }
        if (wantOn == null && !has(t, listOf("toggle", "badlo", "बदलो"))) return false
        val tg = TOGGLES.firstOrNull { has(text, it.names) } ?: return false
        val svc = AssistantService.instance
        if (svc == null) {
            reply(msg("${tg.hi} की सेटिंग खोल रही हूँ, यहाँ से बदल लो ⚙️", "Opening ${tg.en} settings ⚙️"))
            go(Intent(tg.action))
            return true
        }
        val doing = when (wantOn) {
            true -> msg("${tg.hi} ऑन कर रही हूँ ✅", "Turning ${tg.en} on ✅")
            false -> msg("${tg.hi} बंद कर रही हूँ ⛔", "Turning ${tg.en} off ⛔")
            null -> msg("${tg.hi} बदल रही हूँ", "Switching ${tg.en}")
        }
        reply(doing)
        host.hideNow()
        svc.toggleSetting(tg.tiles, wantOn, tg.action) { ok ->
            if (!ok) reply(msg("${tg.hi} का बटन नहीं मिला, सेटिंग खोल दी है", "Couldn't find the ${tg.en} switch"))
        }
        return true
    }

    private fun panel(action: String, label: String) {
        reply(msg("$label की सेटिंग खोल रही हूँ ⚙️", "Opening $label settings ⚙️"))
        if (!go(Intent(action))) go(Intent(Settings.ACTION_SETTINGS))
    }

    // ======================= Battery, Date, Time =======================
    private fun battery() {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        reply(msg(
            "बैटरी $pct परसेंट है" + if (charging) ", और चार्ज हो रही है ⚡" else " 🔋",
            "Battery is at $pct%" + if (charging) " and charging ⚡" else " 🔋"
        ))
    }

    private fun tellDate() {
        val d = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.ENGLISH).format(Date())
        reply(msg("आज $d है 📅", "Today is $d 📅"))
    }

    private fun tellTime() {
        val cal = Calendar.getInstance()
        var h = cal.get(Calendar.HOUR)
        if (h == 0) h = 12
        val m = cal.get(Calendar.MINUTE)
        val ampm = if (cal.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
        val shown = "$h:${m.toString().padStart(2, '0')} $ampm"
        reply(msg("अभी $shown हो रहे हैं 🕐", "It's $shown 🕐"))
    }

    // ======================= Calculator =======================
    private fun calculate(text: String): Boolean {
        var t = " " + asciiDigits(text) + " "
        val ops = listOf(
            "multiplied by" to "*", "divided by" to "/", "plus" to "+", "प्लस" to "+", "जमा" to "+", "जोड़" to "+",
            "minus" to "-", "माइनस" to "-", "घटा" to "-", "into" to "*", "times" to "*", "multiply" to "*",
            "गुणा" to "*", "गुना" to "*", "guna" to "*", "×" to "*", " x " to " * ", "divide" to "/",
            "भाग" to "/", "÷" to "/", "बटा" to "/", "upon" to "/"
        )
        for ((w, o) in ops) t = t.replace(w, " $o ")
        val m = Regex("\\d+(?:\\.\\d+)?(?:\\s*[-+*/]\\s*\\d+(?:\\.\\d+)?)+").find(t) ?: return false
        val tokens = Regex("\\d+(?:\\.\\d+)?|[-+*/]").findAll(m.value).map { it.value }.toList()
        val nums = mutableListOf(tokens[0].toDouble())
        val adds = mutableListOf<Char>()
        var i = 1
        while (i < tokens.size - 1) {
            val op = tokens[i][0]
            val v = tokens[i + 1].toDouble()
            when (op) {
                '*' -> nums[nums.lastIndex] = nums.last() * v
                '/' -> nums[nums.lastIndex] = nums.last() / v
                else -> {
                    adds.add(op)
                    nums.add(v)
                }
            }
            i += 2
        }
        var res = nums[0]
        for (k in adds.indices) res = if (adds[k] == '+') res + nums[k + 1] else res - nums[k + 1]
        if (res.isInfinite() || res.isNaN()) {
            reply(msg("ज़ीरो से भाग नहीं हो सकता 😅", "Can't divide by zero 😅"))
            return true
        }
        val shown = if (res == Math.floor(res) && kotlin.math.abs(res) < 1e15) res.toLong().toString()
        else String.format(Locale.US, "%.4f", res).trimEnd('0').trimEnd('.')
        reply(msg("जवाब है $shown 🧮", "The answer is $shown 🧮"))
        return true
    }

    // ======================= YouTube / Navigation / Camera =======================
    private fun mediaQuery(text: String) = stripWords(text, MEDIA_FILLER)

    /** 🎬 The video app picked in Settings (e.g. YouTube Premium), else YouTube */
    private fun videoApp(): App {
        val chosen = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("videoPkg", "").orEmpty()
        if (chosen.isNotEmpty() && ctx.packageManager.getLaunchIntentForPackage(chosen) != null) {
            val lbl = try {
                ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(chosen, 0)).toString()
            } catch (e: Exception) {
                "YouTube"
            }
            return App(lbl, chosen)
        }
        return App("YouTube", "com.google.android.youtube")
    }

    private fun youtube(query: String, announce: Boolean = true) {
        val v = videoApp()
        if (announce) reply(msg("${v.label} पर \"$query\" चला रही हूँ 🎬", "Playing \"$query\" on ${v.label} 🎬"))
        val url = "https://www.youtube.com/results?search_query=" + Uri.encode(query)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(v.pkg)
        if (go(intent)) AssistantService.instance?.autoPlayYouTube(query, v.pkg)
        else go(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private val VIDEO_WORDS = listOf("video", "वीडियो", "videos", "dikhao", "दिखाओ", "movie trailer", "trailer", "ट्रेलर")

    // ======================= 🎵 Play in ANY app =======================
    private val PLAY_STRICT = listOf(
        "play", "प्ले", "chalao", "chala do", "चलाओ", "चला दो", "चलाना", "bajao", "baja do", "बजाओ", "बजा दो",
        "lagao", "laga do", "लगाओ", "लगा दो", "sunao", "suna do", "सुनाओ", "सुना दो", "सुनना", "sunna"
    )
    private val APP_WORDS = setOf(
        "app", "ऐप", "एप", "mein", "me", "main", "में", "pe", "par", "पे", "पर", "on", "in", "open", "kholo", "khol", "खोलो", "खोल",
        "aur", "और", "and", "then", "usme", "usmein", "isme", "उसमें", "इसमें", "uspe", "उसपे", "karke", "करके", "ke", "के"
    )

    /** Music apps people say in Hindi too */
    private val MUSIC_APPS = listOf(
        listOf("spotify", "स्पॉटिफाई", "स्पोटिफाई") to listOf("com.spotify.music"),
        listOf("jiosaavn", "jio saavn", "saavn", "जियो सावन", "सावन") to listOf("com.jio.media.jiobeats", "com.saavn.android"),
        listOf("wynk", "विंक") to listOf("com.bsbportal.music"),
        listOf("gaana app", "गाना ऐप") to listOf("com.gaana"),
        listOf("amazon music", "अमेज़न म्यूजिक") to listOf("com.amazon.mp3"),
        listOf("apple music") to listOf("com.apple.android.music"),
        listOf("resso") to listOf("com.moonvideo.android.resso"),
        listOf("hungama") to listOf("com.hungama.myplay.activity")
    )

    /** 💙 Chiku's "YT Music" (blue icon with an M). Found by its name; if there are two, the BLUE one wins. */
    private val YT_MUSIC_NAMES = listOf(
        "yt music", "ytmusic", "y t music", "youtube music", "you tube music", "why tea music", "white music",
        "वाईटी म्यूजिक", "वाई टी म्यूजिक", "वाईटी म्यूज़िक", "वाई टी म्यूज़िक", "यूट्यूब म्यूजिक", "यूट्यूब म्यूज़िक", "yt म्यूजिक"
    )

    /** How blue is an app's icon? (blue minus red, averaged) */
    private fun blueness(pkg: String): Int = try {
        val d = ctx.packageManager.getApplicationIcon(pkg)
        val bmp = android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        d.setBounds(0, 0, 48, 48)
        d.draw(c)
        var score = 0L
        for (x in 0 until 48) for (y in 0 until 48) {
            val px = bmp.getPixel(x, y)
            if ((px ushr 24) < 128) continue
            score += (px and 0xFF) - ((px shr 16) and 0xFF)
        }
        bmp.recycle()
        (score / (48 * 48)).toInt()
    } catch (e: Exception) {
        0
    }

    /** The music app picked in Settings (if any), else the bluest "YT Music" */
    private fun ytMusicApp(): App? {
        val chosen = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("musicPkg", "").orEmpty()
        if (chosen.isNotEmpty() && ctx.packageManager.getLaunchIntentForPackage(chosen) != null) {
            val lbl = try {
                ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(chosen, 0)).toString()
            } catch (e: Exception) {
                "Music"
            }
            return App(lbl, chosen)
        }
        val found = allApps().filter { a ->
            val l = a.label.lowercase().replace(Regex("[^a-z]"), "")
            l.contains("ytmusic") || l.contains("youtubemusic")
        }
        if (found.size <= 1) return found.firstOrNull()
        return found.maxByOrNull { blueness(it.pkg) }
    }

    /** Finds an installed app named inside the sentence: "spotify pe kesariya chalao" -> Spotify */
    private fun appInText(text: String): Pair<App, List<String>>? {
        val t = text.lowercase()
        val ySaid = YT_MUSIC_NAMES.filter { t.contains(it) }
        if (ySaid.isNotEmpty()) ytMusicApp()?.let { return it to ySaid }
        for ((names, pkgs) in MUSIC_APPS) {
            val said = names.filter { t.contains(it) }
            if (said.isNotEmpty()) {
                for (p in pkgs) {
                    if (ctx.packageManager.getLaunchIntentForPackage(p) != null) {
                        val lbl = try {
                            ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p, 0)).toString()
                        } catch (e: Exception) {
                            names.first()
                        }
                        return App(lbl, p) to said
                    }
                }
            }
        }
        // any other installed app whose name appears in the sentence (longest name wins)
        val latin = " " + NameMatcher.toLatin(text) + " "
        val hit = allApps()
            .map { it to NameMatcher.toLatin(it.label) }
            .filter { (_, l) -> l.length >= 4 && l != "gaana" && latin.contains(" $l ") }
            .maxByOrNull { it.second.length } ?: return null
        return hit.first to hit.first.label.lowercase().split(" ")
    }

    private fun playInApp(text: String): Boolean {
        if (!has(text, PLAY_STRICT)) return false
        val (app, said) = appInText(text) ?: return false
        // YouTube / chosen video app -> video path
        val vPkg = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("videoPkg", "").orEmpty()
        if (app.pkg == "com.google.android.youtube" || app.pkg == vPkg ||
            (app.label.lowercase().contains("youtube") && !app.label.lowercase().contains("music"))) return false
        var rest = text.lowercase()
        for (w in said) rest = rest.replace(w, " ")
        val query = stripWords(rest, MEDIA_FILLER + APP_WORDS + PLAY_STRICT.toSet())
        if (query.isNotBlank() && isVague(text, query)) {
            moodPlay(text, app)
            return true
        }
        return playOn(app, query)
    }

    // ---------- 🎵 Mood music ----------
    private val VAGUE_WORDS = listOf(
        "mood", "मूड", "accha sa", "acha sa", "अच्छा सा", "badhiya", "बढ़िया", "koi", "कोई", "kuch", "कुछ", "aisa", "ऐसा",
        "hisaab", "हिसाब", "pasand", "पसंद", "hard", "हार्ड", "khushi", "ख़ुशी", "खुशी", "happy", "sad", "udaas", "उदास",
        "romantic", "रोमांटिक", "party", "पार्टी", "gym", "जिम", "workout", "chill", "relax", "sukoon", "सुकून", "neend", "नींद",
        "energy", "motivation", "bhakti", "भक्ति", "bhajan", "भजन", "purane", "पुराने", "dance", "डांस", "pyaar", "प्यार", "dukhi", "दुखी"
    )

    /** "mera mood accha nahi, koi gaana chalao" is a mood, not a song name */
    private fun isVague(text: String, query: String): Boolean =
        query.isBlank() || has(text.lowercase(), VAGUE_WORDS) || query.split(" ").count { it.isNotBlank() } > 5

    private fun moodPlay(text: String, appArg: App?) {
        host.thinking()
        Brain.pickSong(ctx, text) { song ->
            val app = appArg ?: ytMusicApp()
            val t = text.lowercase()
            val why = when {
                has(t, listOf("mood", "मूड", "udaas", "उदास", "dukhi", "दुखी", "tension")) && !has(t, listOf("sad song", "sad gaan", "dard", "दर्द")) ->
                    msg("आपका मूड अच्छा करने के लिए", "To lift your mood,")
                has(t, listOf("hard", "हार्ड", "gym", "जिम", "workout", "josh", "जोश", "energy")) -> msg("जोश भरने के लिए", "For some energy,")
                has(t, listOf("khushi", "ख़ुशी", "खुशी", "happy", "mast")) -> msg("ख़ुशी वाला गाना,", "Something happy,")
                has(t, listOf("romantic", "रोमांटिक", "pyaar", "प्यार", "love")) -> msg("कुछ रोमांटिक,", "Something romantic,")
                else -> msg("आपके लिए", "For you,")
            }
            reply(msg("$why \"$song\" चला रही हूँ 🎵", "$why playing \"$song\" 🎵"))
            if (app != null) playOn(app, song, announce = false) else youtube(song, announce = false)
        }
    }

    private fun playOn(app: App, query: String, announce: Boolean = true): Boolean {
        val launch = ctx.packageManager.getLaunchIntentForPackage(app.pkg) ?: return false
        if (query.isBlank()) {
            reply(msg("${app.label} खोल रही हूँ 🎵", "Opening ${app.label} 🎵"))
            go(launch)
            return true
        }
        if (announce) reply(msg("${app.label} पर \"$query\" चला रही हूँ 🎵", "Playing \"$query\" on ${app.label} 🎵"))
        val svc = AssistantService.instance
        // YT Music (official or mod): its own search page, used if "play from search" doesn't start a song
        val isYtMusic = app.pkg.contains("youtube.music") ||
            app.label.lowercase().replace(Regex("[^a-z]"), "").let { it.contains("ytmusic") || it.contains("youtubemusic") }
        val resultsLink = if (isYtMusic)
            Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=" + Uri.encode(query))).setPackage(app.pkg)
        else null
        // Stop the old song first, so we can tell when the NEW one really starts
        svc?.mediaPauseKey()
        // 1) Music apps that understand "play this song" start playing by themselves
        val direct = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(app.pkg)
            .putExtra(SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        if (ctx.packageManager.queryIntentActivities(direct, 0).isNotEmpty() && go(direct)) {
            svc?.autoPlayInApp(app.pkg, query, waitForMusic = true, resultsLink = resultsLink)
            return true
        }
        if (resultsLink != null && svc != null && go(resultsLink)) {
            svc.autoPlayInApp(app.pkg, query, waitForMusic = false, resultsLink = null)
            return true
        }
        // 2) Otherwise: open the app, type the song in its search, tap the first result
        go(launch)
        if (svc != null) svc.autoPlayInApp(app.pkg, query, waitForMusic = false)
        else reply(msg("अपने-आप चलाने के लिए ऐक्सेसिबिलिटी ऑन कर दो", "Turn on Accessibility so I can press play for you"))
        return true
    }

    private fun navigate(text: String) {
        val place = stripWords(text, NAV_FILLER)
        if (place.isBlank()) {
            reply(msg("मैप्स खोल रही हूँ 🧭", "Opening Maps 🧭"))
            go(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")))
            return
        }
        reply(msg("$place का रास्ता दिखा रही हूँ 🧭", "Navigating to $place 🧭"))
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place)))
        if (!go(nav)) go(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))))
    }

    private fun camera(selfie: Boolean) {
        reply(msg(if (selfie) "सेल्फ़ी कैमरा खोल रही हूँ 🤳" else "कैमरा खोल रही हूँ 📷",
                  if (selfie) "Opening selfie camera 🤳" else "Opening camera 📷"))
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        if (selfie) {
            intent.putExtra("android.intent.extras.CAMERA_FACING", 1)
            intent.putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
            intent.putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        }
        go(intent)
    }

    // ======================= Jokes =======================
    private val hinglishJokes = listOf(
        "टीचर: सबसे ज़्यादा बर्फ़ कहाँ मिलती है? स्टूडेंट: जी, फ़्रिज में! 😂",
        "डॉक्टर: आपको रोज़ सुबह दौड़ना चाहिए। मरीज़: डॉक्टर साहब, मैं तो रोज़ सपने में दौड़ता हूँ! 😄",
        "मोबाइल की बैटरी और नींद, दोनों तभी ख़त्म होती हैं जब सबसे ज़्यादा ज़रूरत हो! 😅",
        "पप्पू: मम्मी, मैं बड़ा होकर फ़ोन बनूँगा। मम्मी: क्यों? पप्पू: ताकि सब मुझे हर वक़्त हाथ में लेकर घूमें! 🤣",
        "वाईफ़ाई का पासवर्ड पूछने वाले रिश्तेदार सबसे सच्चे होते हैं, वो दिल से कनेक्ट होना चाहते हैं! 😆"
    )
    private val englishJokes = listOf(
        "Why did the phone wear glasses? Because it lost its contacts! 😂",
        "I told my Wi-Fi a joke. It didn't get the connection. 😄",
        "Why was the smartphone so tired? It had too many tabs open! 😅",
        "Why don't skeletons fight each other? They don't have the guts! 🤣",
        "What do you call a sleeping dinosaur? A dino-snore! 😆"
    )

    private fun joke() {
        reply(if (hinglish()) hinglishJokes.random() else englishJokes.random())
    }

    // ======================= Search =======================
    private fun webSearch(query: String) {
        val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        if (!go(intent)) {
            go(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))))
        }
    }

    // ======================= Apps =======================
    private class App(val label: String, val pkg: String)

    private fun allApps(): List<App> {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0).map {
            App(it.loadLabel(pm).toString(), it.activityInfo.packageName)
        }
    }

    /** Popular apps by package name: these always open, even if the label differs */
    private val KNOWN_APPS = listOf(
        listOf("whatsapp", "व्हाट्सएप", "व्हाट्सऐप", "वॉट्सऐप", "वाट्सएप") to listOf("com.whatsapp", "com.whatsapp.w4b"),
        listOf("instagram", "insta", "इंस्टाग्राम", "इंस्टा") to listOf("com.instagram.android"),
        listOf("youtube", "यूट्यूब") to listOf("com.google.android.youtube"),
        listOf("facebook", "फेसबुक") to listOf("com.facebook.katana", "com.facebook.lite"),
        listOf("snapchat", "स्नैपचैट") to listOf("com.snapchat.android"),
        listOf("telegram", "टेलीग्राम") to listOf("org.telegram.messenger"),
        listOf("chrome", "क्रोम", "browser", "ब्राउज़र") to listOf("com.android.chrome", "com.mi.globalbrowser", "com.heytap.browser"),
        listOf("gmail", "जीमेल", "email", "mail") to listOf("com.google.android.gm"),
        listOf("maps", "map", "मैप", "मैप्स") to listOf("com.google.android.apps.maps"),
        listOf("play store", "playstore", "प्ले स्टोर") to listOf("com.android.vending"),
        listOf("spotify", "स्पॉटिफाई") to listOf("com.spotify.music"),
        listOf("phonepe", "phone pe", "फोनपे", "फ़ोन पे") to listOf("com.phonepe.app"),
        listOf("google pay", "gpay", "गूगल पे") to listOf("com.google.android.apps.nbu.paisa.user"),
        listOf("paytm", "पेटीएम") to listOf("net.one97.paytm"),
        listOf("amazon", "अमेज़न", "अमेजन") to listOf("in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping"),
        listOf("flipkart", "फ्लिपकार्ट") to listOf("com.flipkart.android"),
        listOf("netflix", "नेटफ्लिक्स") to listOf("com.netflix.mediaclient"),
        listOf("gallery", "गैलरी", "photos", "फोटोज़", "फोटो") to listOf("com.miui.gallery", "com.google.android.apps.photos", "com.coloros.gallery3d", "com.oppo.gallery3d", "com.sec.android.gallery3d"),
        listOf("settings", "setting", "सेटिंग", "सेटिंग्स") to listOf("com.android.settings"),
        listOf("calculator", "कैलकुलेटर") to listOf("com.miui.calculator", "com.google.android.calculator", "com.coloros.calculator"),
        listOf("clock", "घड़ी", "क्लॉक") to listOf("com.android.deskclock", "com.google.android.deskclock", "com.coloros.alarmclock"),
        listOf("contacts", "कॉन्टैक्ट") to listOf("com.android.contacts", "com.google.android.contacts"),
        listOf("messages", "sms", "मैसेजेस") to listOf("com.google.android.apps.messaging", "com.android.mms"),
        listOf("dialer", "phone app", "डायलर") to listOf("com.google.android.dialer", "com.android.contacts", "com.android.dialer")
    )

    private fun knownApp(name: String): App? {
        val n = name.lowercase()
        for ((names, pkgs) in KNOWN_APPS) {
            if (names.any { n.contains(it) }) {
                for (p in pkgs) {
                    if (ctx.packageManager.getLaunchIntentForPackage(p) != null) {
                        val label = try {
                            ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p, 0)).toString()
                        } catch (e: Exception) {
                            names.first()
                        }
                        return App(label, p)
                    }
                }
            }
        }
        return null
    }

    private fun findApp(name: String): App? {
        if (YT_MUSIC_NAMES.any { name.lowercase().contains(it) } || name.lowercase().trim() == "music") {
            ytMusicApp()?.let { return it }
        }
        // "youtube kholo" -> the video app picked in Settings (e.g. YouTube Premium)
        val n = name.lowercase()
        if ((n.contains("youtube") || n.contains("यूट्यूब")) && !n.contains("music") && !n.contains("म्यूज")) {
            val vPkg = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("videoPkg", "").orEmpty()
            if (vPkg.isNotEmpty()) return videoApp()
        }
        knownApp(name)?.let { return it }
        val apps = allApps()
        NameMatcher.best(name, apps, 0.7) { it.label }?.let { return it }
        // last try: label contains the spoken word
        val latin = NameMatcher.toLatin(name)
        return apps.firstOrNull { latin.length >= 3 && NameMatcher.toLatin(it.label).contains(latin) }
    }

    private fun openApp(text: String) {
        val name = stripWords(text, OPEN_FILLER)
        if (name.isBlank()) {
            ask(msg("कौन सा ऐप खोलूँ?", "Which app should I open?"))
            return
        }
        val match = findApp(name) ?: NameMatcher.best(name, allApps(), 0.6) { it.label }
        val launch = match?.let { ctx.packageManager.getLaunchIntentForPackage(it.pkg) }
        if (match == null || launch == null) {
            reply(msg("\"$name\" ऐप नहीं मिला 😕", "Couldn't find app \"$name\" 😕"))
            return
        }
        reply(msg("${match.label} खोल रही हूँ 🚀", "Opening ${match.label} 🚀"))
        go(launch)
    }
}
