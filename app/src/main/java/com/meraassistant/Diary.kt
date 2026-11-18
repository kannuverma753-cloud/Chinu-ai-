package com.meraassistant

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 📝 Our Diary — shared notes between Chiku's and Komal's phones.
 * Each note shows a small name tag of who wrote it. Synced through the encrypted Couple link.
 */
object Diary {
    class Note(val id: String, val text: String, val author: String, val at: Long, val upd: Long, val del: Boolean) {
        fun json(): JSONObject = JSONObject().put("id", id).put("text", text).put("author", author)
            .put("at", at).put("upd", upd).put("del", del)

        companion object {
            fun of(j: JSONObject) = Note(j.optString("id"), j.optString("text"), j.optString("author"),
                j.optLong("at"), j.optLong("upd"), j.optBoolean("del"))
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("diary", Context.MODE_PRIVATE)

    /** all notes incl. deleted markers (needed for syncing) */
    private fun raw(ctx: Context): MutableList<Note> = try {
        val a = JSONArray(prefs(ctx).getString("notes", "[]"))
        (0 until a.length()).map { Note.of(a.getJSONObject(it)) }.toMutableList()
    } catch (e: Exception) {
        mutableListOf()
    }

    private fun store(ctx: Context, l: List<Note>) {
        val a = JSONArray()
        l.sortedBy { it.at }.takeLast(500).forEach { a.put(it.json()) }
        prefs(ctx).edit().putString("notes", a.toString()).apply()
    }

    fun list(ctx: Context): List<Note> = raw(ctx).filter { !it.del }.sortedByDescending { it.at }

    fun me(ctx: Context): String {
        Speaker.load(ctx)
        return Speaker.name(Profile.load(ctx))
    }

    /** write a new note (or edit one) and send it to the partner */
    fun put(ctx: Context, text: String, id: String? = null, del: Boolean = false) {
        val l = raw(ctx)
        val now = System.currentTimeMillis()
        val old = l.firstOrNull { it.id == id }
        val n = Note(id ?: (now.toString(36) + (100..999).random()), text.take(1500),
            old?.author ?: me(ctx), old?.at ?: now, now, del)
        l.removeAll { it.id == n.id }
        l.add(n)
        store(ctx, l)
        CoupleLink.send(ctx, "note_put", n.json())
    }

    /** A note arrived from the partner → keep the newest version. Returns true if it changed something. */
    fun merge(ctx: Context, j: JSONObject): Boolean {
        val n = Note.of(j)
        if (n.id.isEmpty()) return false
        val l = raw(ctx)
        val old = l.firstOrNull { it.id == n.id }
        if (old != null && old.upd >= n.upd) return false
        l.removeAll { it.id == n.id }
        l.add(n)
        store(ctx, l)
        return true
    }

    /** "These are the notes I have" — the partner then sends anything I'm missing */
    fun hello(ctx: Context, reply: Boolean = false) {
        val have = JSONObject()
        raw(ctx).sortedByDescending { it.upd }.take(150).forEach { have.put(it.id, it.upd) }
        CoupleLink.send(ctx, "note_hello", JSONObject().put("have", have).put("reply", reply))
    }

    fun onHello(ctx: Context, d: JSONObject) {
        val have = d.optJSONObject("have") ?: JSONObject()
        for (n in raw(ctx).sortedByDescending { it.upd }.take(150)) {
            if (have.optLong(n.id, -1L) < n.upd) CoupleLink.send(ctx, "note_put", n.json())
        }
        if (!d.optBoolean("reply")) hello(ctx, reply = true)
    }
}

class DiaryActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var input: EditText
    private val handFont: Typeface by lazy { Typeface.create("casual", Typeface.NORMAL) }
    private val titleFont: Typeface by lazy { Typeface.create("cursive", Typeface.BOLD) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Speaker.load(this)
        val root = FrameLayout(this)
        root.addView(GlassBackground(this))
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(44f), dp(16f), dp(30f))
        }
        val p = Profile.load(this)
        col.addView(TextView(this).apply {
            text = Ui.t(this@DiaryActivity, "Our Diary", "Hamari Diary")
            textSize = 40f
            typeface = titleFont
            setTextColor(0xFF3B1F4A.toInt())
        })
        col.addView(Ui.label(this, "📝 ${p.partner} & ${p.name} · " + Ui.t(this, "write about your day, together", "apne din ke baare mein likho, saath mein"),
            14f, 0xFF6B4E7A.toInt()).apply { setPadding(dp(2f), 0, 0, dp(16f)) })

        // compose card
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glass(this@DiaryActivity, 26f, 0x88)
            setPadding(dp(16f), dp(12f), dp(16f), dp(14f))
        }
        input = EditText(this).apply {
            hint = Ui.t(this@DiaryActivity, "How was your day? ✍️", "Aaj ka din kaisa tha? ✍️")
            textSize = 18f
            typeface = handFont
            setTextColor(0xFF2E1A38.toInt())
            setHintTextColor(0x993B1F4A.toInt())
            background = null
            minLines = 3
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        card.addView(input)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(Ui.label(this, "— ${Diary.me(this)}", 13f, authorColor(Diary.me(this)), true).apply { typeface = titleFont; textSize = 18f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Ui.primaryButton(this, Ui.t(this, "Save 💖", "Save 💖")) { save() }.apply { setPadding(dp(22f), dp(10f), dp(22f), dp(10f)) })
        card.addView(bar)
        col.addView(card)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        scroll.addView(col)
        root.addView(scroll)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        current = this
        CoupleLink.start(this)
        // sync with the partner's diary (at most every 10 minutes, to stay within the free relay's limits)
        val sp = getSharedPreferences("diary", MODE_PRIVATE)
        if (CoupleLink.paired(this) && System.currentTimeMillis() - sp.getLong("lastHello", 0) > 10 * 60_000L) {
            sp.edit().putLong("lastHello", System.currentTimeMillis()).apply()
            Diary.hello(this)
        }
        render()
    }

    override fun onPause() {
        if (current === this) current = null
        super.onPause()
    }

    private fun dp(v: Float) = Ui.dp(this, v)

    private fun authorColor(name: String): Int =
        if (name.equals(Profile.load(this).name, true)) 0xFFD63384.toInt() else 0xFF5B5BD6.toInt()

    private fun save() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        Diary.put(this, t)
        input.setText("")
        Toast.makeText(this, if (CoupleLink.paired(this)) Ui.t(this, "Saved & shared 💞", "Save ho gaya, dono phones mein 💞")
            else Ui.t(this, "Saved 💖 (connect phones in 💞 to share)", "Save ho gaya 💖 (share ke liye 💞 se phones jodo)"), Toast.LENGTH_SHORT).show()
        render()
    }

    fun render() {
        list.removeAllViews()
        val notes = Diary.list(this)
        if (notes.isEmpty()) {
            list.addView(Ui.label(this, Ui.t(this, "No pages yet. Write the first one 🌸", "Abhi koi page nahi. Pehla aap likho 🌸"),
                16f, 0xFF6B4E7A.toInt()).apply { typeface = handFont; gravity = Gravity.CENTER; setPadding(0, dp(30f), 0, 0) })
            return
        }
        val dayFmt = SimpleDateFormat("EEEE, d MMMM", Locale.ENGLISH)
        val timeFmt = SimpleDateFormat("h:mm a", Locale.ENGLISH)
        var lastDay = ""
        val me = Diary.me(this)
        for (n in notes) {
            val day = dayLabel(n.at, dayFmt)
            if (day != lastDay) {
                lastDay = day
                list.addView(TextView(this).apply {
                    text = day
                    textSize = 22f
                    typeface = titleFont
                    setTextColor(0xFF8A4F9E.toInt())
                    setPadding(dp(4f), dp(22f), 0, dp(6f))
                })
            }
            val page = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.glass(this@DiaryActivity, 22f, 0x66)
                setPadding(dp(16f), dp(14f), dp(16f), dp(10f))
            }
            page.addView(TextView(this).apply {
                text = n.text
                textSize = 17.5f
                typeface = handFont
                setTextColor(0xFF2E1A38.toInt())
                setLineSpacing(dp(4f).toFloat(), 1f)
            })
            // small name tag: — Komal / — Chiku
            val foot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            foot.addView(Ui.label(this, timeFmt.format(Date(n.at)) + if (n.upd - n.at > 60_000) " · edited" else "",
                11.5f, 0xFF9A86A6.toInt()), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            foot.addView(TextView(this).apply {
                text = "— ${n.author}"
                textSize = 16f
                typeface = titleFont
                setTextColor(authorColor(n.author))
            })
            page.addView(foot)
            if (n.author.equals(me, true)) page.setOnLongClickListener { edit(n); true }
            list.addView(page, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8f)
            })
        }
        list.addView(Ui.label(this, Ui.t(this, "Long-press your own page to edit or delete", "Apna page edit/delete karne ke liye dabake rakho"),
            11.5f, 0xFF9A86A6.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, dp(16f), 0, 0) })
    }

    private fun dayLabel(at: Long, f: SimpleDateFormat): String {
        val a = Calendar.getInstance().apply { timeInMillis = at }
        val b = Calendar.getInstance()
        val same = a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
        return when {
            same && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR) -> Ui.t(this, "Today", "Aaj")
            same && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR) - 1 -> Ui.t(this, "Yesterday", "Kal")
            else -> f.format(Date(at))
        }
    }

    private fun edit(n: Diary.Note) {
        val e = EditText(this).apply {
            setText(n.text)
            typeface = handFont
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(this).setTitle(Ui.t(this, "Edit page", "Page edit karo")).setView(e)
            .setPositiveButton("Save") { _, _ ->
                val t = e.text.toString().trim()
                if (t.isNotEmpty()) Diary.put(this, t, n.id)
                render()
            }
            .setNeutralButton(Ui.t(this, "Delete", "Delete")) { _, _ ->
                Diary.put(this, "", n.id, del = true)
                render()
            }
            .setNegativeButton("Cancel", null).show()
    }

    companion object {
        @Volatile
        var current: DiaryActivity? = null
    }
}
