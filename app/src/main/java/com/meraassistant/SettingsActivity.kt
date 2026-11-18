package com.meraassistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Apple-style grouped settings: everything that used to crowd the main screen lives here. */
class SettingsActivity : Activity() {

    private lateinit var container: LinearLayout
    private lateinit var prefs: SharedPreferences
    private val dp by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        Ui.glassScreen(this)   // 💧 liquid glass backdrop
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        Speaker.load(this)
        container = findViewById(R.id.container)
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun px(v: Int) = (v * dp).toInt()

    /** Rebuild all sections (keeps the two header views) */
    private fun render() {
        while (container.childCount > 2) container.removeViewAt(2)
        val p = Profile.load(this)

        // ---------- Voice ----------
        section("VOICE", "Chinu listens offline only for her name. While a video or song is playing, say “Hey Chinu” clearly.").apply {
            switchRow(this, "Hi Chinu", prefs.getBoolean("wake", true)) { on ->
                prefs.edit().putBoolean("wake", on).apply()
                if (on) WakeService.start(this@SettingsActivity) else WakeService.stop(this@SettingsActivity)
            }
            divider(this)
            switchRow(this, "Keep listening after replies", prefs.getBoolean("convo", true)) { on ->
                prefs.edit().putBoolean("convo", on).apply()
            }
            divider(this)
            switchRow(this, "🎧 Use earbuds mic when connected", prefs.getBoolean("budsMic", true)) { on ->
                prefs.edit().putBoolean("budsMic", on).apply()
            }
            divider(this)
            switchRow(this, "🎧 “Hey Chinu” through earbuds", prefs.getBoolean("budsWake", false)) { on ->
                prefs.edit().putBoolean("budsWake", on).apply()
                if (on) AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Earbuds wake word")
                    .setMessage("Chinu will listen for “Hey Chinu” through your earbuds. While this is on, Bluetooth stays in call mode, so music in the buds may sound a little lower in quality and the battery drains faster.\n\nTip: you can also just hold the earbud button (set Chinu as the default assistant).")
                    .setPositiveButton("OK", null).show()
            }
            divider(this)
            row(this, "Wake word sensitivity", listOf("Low · fewest mistakes", "Normal", "High · hears from far")[prefs.getInt("wakeSense", 1).coerceIn(0, 2)]) {
                val opts = arrayOf("Low · fewest mistakes", "Normal (recommended)", "High · hears from far")
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Wake word sensitivity")
                    .setSingleChoiceItems(opts, prefs.getInt("wakeSense", 1).coerceIn(0, 2)) { d, w ->
                        prefs.edit().putInt("wakeSense", w).apply()
                        d.dismiss(); render()
                    }
                    .show()
            }
            divider(this)
            switchRow(this, "Silent listening (less accurate)", prefs.getBoolean("silentEars", false)) { on ->
                prefs.edit().putBoolean("silentEars", on).apply()
                Ears.onDeviceFailed = false
            }
            divider(this)
            row(this, "Language", LangUtil.selected(this@SettingsActivity).label.substringBefore(" (")) { pickLanguage() }
            divider(this)
            row(this, "🎵 Music app", appLabel("musicPkg")) {
                pickApp("musicPkg", "Which app plays your songs?", listOf("music", "spotify", "saavn", "wynk", "gaana"))
            }
            divider(this)
            row(this, "🎬 Video app", appLabel("videoPkg")) {
                pickApp("videoPkg", "Which app plays your videos?", listOf("youtube", "video", "tube", "player"))
            }
        }

        // ---------- Messages ----------
        section("MESSAGES", "How Chinu types WhatsApp messages. You can also say “English mein bhejo” or “Hinglish mein likho”.").apply {
            row(this, "Type messages in", MsgStyle.label(MsgStyle.default(this@SettingsActivity))) { pickMsgStyle() }
        }

        // ---------- Reminders ----------
        val rems = Reminders.all(this)
        section("REMINDERS", "Say “Komal ka daily routine set karo”, “roz 6 baje padhai ka reminder” or “har ghante paani ka reminder”.").apply {
            if (rems.isEmpty()) {
                row(this, "No reminders yet", "", null)
            }
            rems.forEachIndexed { i, r ->
                if (i > 0) divider(this)
                row(this, r.title(), Reminders.describe(r) + "  ✕") {
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("Delete reminder?")
                        .setMessage(r.title() + "\n" + Reminders.describe(r))
                        .setPositiveButton("Delete") { _, _ -> Reminders.remove(this@SettingsActivity, r.id); render() }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            divider(this)
            row(this, "🗓️ Auto daily routine", "Water · food · study · sleep") {
                Reminders.addRoutine(this@SettingsActivity, Speaker.name(Profile.load(this@SettingsActivity)))
                render()
            }
            divider(this)
            row(this, "💧 Add water reminder (every hour)", "") {
                Reminders.addWater(this@SettingsActivity, Speaker.name(Profile.load(this@SettingsActivity)), 60, 8, 22)
                render()
            }
        }

        // ---------- AI ----------
        val hasKey = Brain.key(this).isNotEmpty()
        section("AI", if (hasKey) "Ask anything, in any language." else "Without a key Chinu answers “who / what is…” questions from Wikipedia.").apply {
            row(this, "AI brain", if (hasKey) "Gemini ✓" else "Basic", null)
            divider(this)
            row(this, if (hasKey) "Change AI key" else "Add AI key", "") { editAiKey() }
            divider(this)
            row(this, "Get a free key", "") {
                open(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
            }
        }

        // ---------- Permissions ----------
        val acc = AssistantService.instance != null
        val notif = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val battery = pm.isIgnoringBatteryOptimizations(packageName)
        section("PERMISSIONS", "Accessibility is needed for the popup, screenshots and auto-sending WhatsApp. Also turn on Autostart in phone settings.").apply {
            row(this, "Accessibility", if (acc) "On" else "Off", if (acc) null else 0xFFFF3B30.toInt()) {
                open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            divider(this)
            row(this, "Notifications", if (notif) "On" else "Off", if (notif) null else 0xFFFF3B30.toInt()) {
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            divider(this)
            row(this, "Run in background", if (battery) "Allowed" else "Restricted", if (battery) null else 0xFFFF3B30.toInt()) {
                open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
            divider(this)
            row(this, "App settings (Autostart)", "") {
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            divider(this)
            row(this, "Default assistant", "") {
                if (!open(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))) open(Intent(Settings.ACTION_SETTINGS))
            }
        }

        // ---------- Profile ----------
        section("PROFILE", null).apply {
            row(this, "This phone belongs to", when (Speaker.owner) { true -> p.partner; false -> p.name; else -> "Not set" }) {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Whose phone is this?")
                    .setItems(arrayOf("🙋‍♂️  ${p.partner}", "🙋‍♀️  ${p.name}")) { _, w ->
                        Speaker.setOwner(this@SettingsActivity, w == 0)
                        render()
                    }
                    .show()
            }
            divider(this)
            row(this, "Name", p.name, null)
            divider(this)
            row(this, "Made by", "${p.partner} 💚", null)
            Profile.daysUntil(p.birthday)?.let { d ->
                divider(this)
                row(this, "Birthday", Profile.pretty(p.birthday) + if (d == 0) " · Today 🎉" else " · in $d days", null)
            }
            divider(this)
            row(this, "Edit profile", "") { editProfile() }
        }

        // ---------- Recent ----------
        if (History.items.isNotEmpty()) {
            section("RECENT", null).apply {
                History.items.forEachIndexed { i, h ->
                    if (i > 0) divider(this)
                    row(this, h, "", null)
                }
            }
        }

        // ---------- App ----------
        section("APP", null).apply {
            row(this, "App language", if (Ui.hinglish(this@SettingsActivity)) "Hinglish" else "English") {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("App language")
                    .setSingleChoiceItems(arrayOf("English", "Hinglish (Hindi in English letters)"),
                        if (Ui.hinglish(this@SettingsActivity)) 1 else 0) { d, w ->
                        prefs.edit().putString("uiLang", if (w == 1) "hinglish" else "en").apply()
                        d.dismiss(); render()
                    }
                    .show()
            }
            divider(this)
            row(this, "💞 Couple connection", if (CoupleLink.paired(this@SettingsActivity)) "Connected" else "Not connected") {
                startActivity(Intent(this@SettingsActivity, CoupleActivity::class.java))
            }
            divider(this)
            row(this, "👠 ${p.name}'s Closet", "") { startActivity(Intent(this@SettingsActivity, ClosetActivity::class.java)) }
            divider(this)
            row(this, "🎡 Love Wheel", "") { startActivity(Intent(this@SettingsActivity, WheelActivity::class.java)) }
            divider(this)
            row(this, "📝 Our Diary", "") { startActivity(Intent(this@SettingsActivity, DiaryActivity::class.java)) }
        }

        // ---------- ✨ Just for us (works even before the phones are connected) ----------
        section("JUST FOR US", null).apply {
            val st = SelfieStreak.streak(this@SettingsActivity)
            row(this, "📸 Selfie streak", if (st > 0) "🔥 $st" else "") { startActivity(Intent(this@SettingsActivity, SelfieActivity::class.java)) }
            divider(this)
            row(this, "🎬 Our selfie reel", "") { startActivity(Intent(this@SettingsActivity, ReelActivity::class.java)) }
            divider(this)
            row(this, "💌 Open-when letters", "") { startActivity(Intent(this@SettingsActivity, LettersActivity::class.java)) }
            divider(this)
            row(this, "❓ Today's question", "") { startActivity(Intent(this@SettingsActivity, QuestionActivity::class.java)) }
            divider(this)
            row(this, "🌙 Bedtime story", "") { startActivity(Intent(this@SettingsActivity, StoryActivity::class.java)) }
        }

        // ---------- Conversation ----------
        section("CONVERSATION", null).apply {
            row(this, "Clear chat history", "") {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Clear chat history?")
                    .setMessage("Your conversation with Chinu will be removed from this phone.")
                    .setPositiveButton("Clear") { _, _ ->
                        prefs.edit().remove("chat").apply()
                        History.items.clear()
                        Brain.clearHistory()
                        render()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        // ---------- About ----------
        section("ABOUT", null).apply {
            addView(TextView(this@SettingsActivity).apply {
                text = getString(R.string.features)
                textSize = 14f
                setTextColor(getColor(R.color.text))
                setLineSpacing(px(5).toFloat(), 1f)
                setPadding(px(16), px(14), px(16), px(14))
            })
        }
        container.addView(TextView(this).apply {
            text = "Chinu · v5.0 · made with 💚 by ${p.partner}"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.text2))
            setPadding(0, px(24), 0, 0)
        })
    }

    // ======================= Building blocks =======================
    private fun section(title: String, footer: String?): LinearLayout {
        container.addView(TextView(this).apply {
            text = title
            textSize = 12.5f
            letterSpacing = 0.08f
            setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL))
            setTextColor(getColor(R.color.accent2))
            setPadding(px(16), px(26), px(16), px(8))
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_section)
            clipToOutline = true
        }
        container.addView(card)
        if (footer != null) {
            container.addView(TextView(this).apply {
                text = footer
                textSize = 13f
                setTextColor(getColor(R.color.text2))
                setPadding(px(16), px(8), px(16), 0)
            })
        }
        return card
    }

    private fun divider(card: LinearLayout) {
        val v = View(this).apply { setBackgroundColor(getColor(R.color.separator)) }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        lp.leftMargin = px(16)
        card.addView(v, lp)
    }

    private fun row(card: LinearLayout, title: String, value: String, valueColor: Int? = null, onClick: (() -> Unit)? = null) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(48)
            setPadding(px(16), px(12), px(16), px(12))
            if (onClick != null) {
                setBackgroundResource(R.drawable.bg_row)
                setOnClickListener { onClick() }
            }
        }
        r.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(if (onClick != null && value.isEmpty()) getColor(R.color.accent2) else getColor(R.color.text))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (value.isNotEmpty()) {
            r.addView(TextView(this).apply {
                text = value
                textSize = 16f
                maxLines = 1
                setTextColor(valueColor ?: getColor(R.color.text2))
                setPadding(px(8), 0, 0, 0)
            })
        }
        if (onClick != null && value.isNotEmpty()) {
            r.addView(TextView(this).apply {
                text = "  ›"
                textSize = 20f
                setTextColor(getColor(R.color.text2))
            })
        }
        card.addView(r)
    }

    private fun switchRow(card: LinearLayout, title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(48)
            setPadding(px(16), px(6), px(12), px(6))
        }
        r.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(getColor(R.color.text))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, on -> onChange(on) }
        })
        card.addView(r)
    }

    private fun open(i: Intent): Boolean = try {
        startActivity(i)
        true
    } catch (e: Exception) {
        false
    }

    // ======================= Dialogs =======================
    private fun appLabel(key: String): String {
        val pkg = prefs.getString(key, "").orEmpty()
        if (pkg.isEmpty()) return "Auto"
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            "Auto"
        }
    }

    /** Installed apps with their icons — pick the exact one (e.g. blue YT Music / YouTube Premium) */
    private fun pickApp(key: String, title: String, firstWords: List<String>) {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedWith(compareBy({ a -> firstWords.none { a.first.lowercase().contains(it) } }, { it.first.lowercase() }))
        val rows = listOf(Pair("Auto (let Chinu choose)", "")) + apps
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int) = rows[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
                val (name, pkg) = rows[position]
                val row = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(px(18), px(10), px(18), px(10))
                }
                val icon = android.widget.ImageView(this@SettingsActivity)
                try {
                    if (pkg.isNotEmpty()) icon.setImageDrawable(packageManager.getApplicationIcon(pkg))
                    else icon.setImageResource(R.mipmap.ic_launcher)
                } catch (e: Exception) {
                }
                row.addView(icon, LinearLayout.LayoutParams(px(42), px(42)))
                val texts = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(px(14), 0, 0, 0)
                }
                texts.addView(TextView(this@SettingsActivity).apply {
                    text = name
                    textSize = 16f
                    setTextColor(getColor(R.color.text))
                })
                if (pkg.isNotEmpty()) texts.addView(TextView(this@SettingsActivity).apply {
                    text = pkg
                    textSize = 11f
                    setTextColor(getColor(R.color.text2))
                })
                row.addView(texts)
                return row
            }
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setAdapter(adapter) { _, which ->
                prefs.edit().putString(key, rows[which].second).apply()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickLanguage() {
        val labels = LangUtil.languages.map { it.label }.toTypedArray()
        val cur = prefs.getInt("lang", 0)
        AlertDialog.Builder(this)
            .setTitle("Language")
            .setSingleChoiceItems(labels, cur) { d, which ->
                prefs.edit().putInt("lang", which).apply()
                d.dismiss()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickMsgStyle() {
        val opts = MsgStyle.ALL
        val cur = opts.indexOf(MsgStyle.default(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Type messages in")
            .setSingleChoiceItems(opts.map { MsgStyle.label(it) }.toTypedArray(), cur) { d, w ->
                prefs.edit().putString("msgStyle", opts[w]).apply()
                d.dismiss(); render()
            }
            .show()
    }

    private fun editAiKey() {
        val e = EditText(this).apply {
            hint = "Paste Gemini API key (AIza…)"
            setText(prefs.getString("geminiKey", ""))
            isSingleLine = true
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22), px(8), px(22), 0)
            addView(e)
        }
        AlertDialog.Builder(this)
            .setTitle("Gemini AI key")
            .setMessage("Free key: aistudio.google.com/apikey → Create API key → copy → paste here.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                Brain.setKey(this, e.text.toString())
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editProfile() {
        val p = Profile.load(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22), px(8), px(22), 0)
        }
        fun field(hintText: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
            val e = EditText(this)
            e.hint = hintText
            e.setText(value)
            e.inputType = type
            box.addView(e)
            return e
        }
        val name = field("Name (e.g. Komal)", p.name)
        val partner = field("Made by (e.g. Chiku)", p.partner)
        val relation = field("Relation (e.g. girlfriend)", p.relation)
        val birthday = field("Birthday DD-MM (e.g. 19-11)", p.birthday, InputType.TYPE_CLASS_DATETIME)
        val anniversary = field("Anniversary DD-MM", p.anniversary, InputType.TYPE_CLASS_DATETIME)
        val favourites = field("Favourite things", p.favourites, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        val phone = field("${p.partner}'s phone number", p.partnerPhone, InputType.TYPE_CLASS_PHONE)
        val message = field("Special message", p.message, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        val scroll = ScrollView(this)
        scroll.addView(box)
        AlertDialog.Builder(this)
            .setTitle("Profile")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                Profile.save(this, Profile.Data(
                    name = name.text.toString().trim().ifBlank { p.name },
                    partner = partner.text.toString().trim(),
                    relation = relation.text.toString().trim(),
                    birthday = birthday.text.toString().trim(),
                    anniversary = anniversary.text.toString().trim(),
                    favourites = favourites.text.toString().trim(),
                    partnerPhone = phone.text.toString().trim(),
                    message = message.text.toString().trim()
                ))
                Birthday.schedule(this)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
