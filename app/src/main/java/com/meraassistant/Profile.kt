package com.meraassistant

import android.content.Context
import java.util.Calendar

/**
 * 💖 जिसके फ़ोन में Chinu install होगा, उसकी पहले से भरी details।
 * यहाँ बदलें, या app के अंदर "💖 Profile → Edit" से बदलें।
 */
object Profile {

    // ================= पहले से भरी details =================
    private const val NAME = "Komal"
    private const val PARTNER = "Chiku"
    private const val RELATION = "girlfriend"
    private const val BIRTHDAY = "19-11"     // "DD-MM": 19 November 🎂
    private const val ANNIVERSARY = ""       // "DD-MM"
    private const val FAVOURITES = ""        // जैसे "chocolate, pink colour, Arijit Singh के गाने"
    private const val PARTNER_PHONE = ""     // Chiku का नंबर, जैसे "9876543210"
    private const val MESSAGE = "कोमल, तुम मेरी ज़िंदगी की सबसे प्यारी इंसान हो। हमेशा ऐसे ही मुस्कुराती रहना। — चीकू 💚"
    // =======================================================

    data class Data(
        val name: String,
        val partner: String,
        val relation: String,
        val birthday: String,
        val anniversary: String,
        val favourites: String,
        val partnerPhone: String,
        val message: String
    )

    fun load(ctx: Context): Data {
        val p = ctx.getSharedPreferences("profile", Context.MODE_PRIVATE)
        return Data(
            name = p.getString("name", null) ?: NAME,
            partner = p.getString("partner", null) ?: PARTNER,
            relation = p.getString("relation", null) ?: RELATION,
            birthday = p.getString("birthday", null) ?: BIRTHDAY,
            anniversary = p.getString("anniversary", null) ?: ANNIVERSARY,
            favourites = p.getString("favourites", null) ?: FAVOURITES,
            partnerPhone = p.getString("partnerPhone", null) ?: PARTNER_PHONE,
            message = p.getString("message", null) ?: MESSAGE
        )
    }

    fun save(ctx: Context, d: Data) {
        ctx.getSharedPreferences("profile", Context.MODE_PRIVATE).edit()
            .putString("name", d.name)
            .putString("partner", d.partner)
            .putString("relation", d.relation)
            .putString("birthday", d.birthday)
            .putString("anniversary", d.anniversary)
            .putString("favourites", d.favourites)
            .putString("partnerPhone", d.partnerPhone)
            .putString("message", d.message)
            .apply()
    }

    private fun parse(ddmm: String): Pair<Int, Int>? {
        val parts = ddmm.trim().split(Regex("[-/.\\s]+"))
        if (parts.size < 2) return null
        val d = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (d !in 1..31 || m !in 1..12) return null
        return d to m
    }

    private fun todayMidnight(): Calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    /** अगली "DD-MM" तारीख की रात 12 बजे का समय (आज हो तो आज की) */
    fun nextDate(ddmm: String): Calendar? {
        val (d, m) = parse(ddmm) ?: return null
        val today = todayMidnight()
        val target = (today.clone() as Calendar).apply {
            set(Calendar.MONTH, m - 1)
            set(Calendar.DAY_OF_MONTH, d)
        }
        if (target.before(today)) target.add(Calendar.YEAR, 1)
        return target
    }

    /** "DD-MM" तक कितने दिन बचे (0 = आज)। गलत तारीख हो तो null */
    fun daysUntil(ddmm: String): Int? {
        val target = nextDate(ddmm) ?: return null
        return Math.round((target.timeInMillis - todayMidnight().timeInMillis) / 86_400_000.0).toInt()
    }

    private val months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    /** "14-02" -> "14 February" */
    fun pretty(ddmm: String): String {
        val (d, m) = parse(ddmm) ?: return ddmm
        return "$d " + months[m - 1]
    }
}

/**
 * 🎙️ Who is talking right now? Decided from the voice when "Hi Chinu" is said:
 * deeper (male) voice = partner (Chiku), higher (female) voice = owner (Komal).
 */
object Speaker {
    @Volatile
    private var partner = false

    @Volatile
    private var at = 0L

    /** Who owns this phone: true = Chiku (partner), false = Komal, null = not asked yet */
    @Volatile
    var owner: Boolean? = null
        private set

    fun load(ctx: Context) {
        val v = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("owner", "").orEmpty()
        owner = when (v) {
            "partner" -> true
            "girl" -> false
            else -> null
        }
    }

    fun setOwner(ctx: Context, isPartner: Boolean) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("owner", if (isPartner) "partner" else "girl").apply()
        owner = isPartner
    }

    fun hasOwner(ctx: Context): Boolean {
        load(ctx)
        return owner != null
    }

    /** Voice guess (boy / girl) — only used when the phone owner is not set */
    fun set(isPartner: Boolean) {
        partner = isPartner
        at = System.currentTimeMillis()
    }

    /** true = Chiku is talking. The phone owner (asked on first open) always wins. */
    fun isPartner(): Boolean = owner ?: (partner && System.currentTimeMillis() - at < 10 * 60_000L)

    /** Name of the person talking */
    fun name(p: Profile.Data): String = if (isPartner()) p.partner else p.name

    /** Name of the other person */
    fun other(p: Profile.Data): String = if (isPartner()) p.name else p.partner
}
