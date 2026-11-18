package com.meraassistant

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.random.Random

/** 👠👚 Komal's closet: daily picks (real, freely-licensed photos) + favourites + her own items */
object Closet {
    class Item(val src: String, val title: String, val credit: String, val own: Boolean = false) {
        fun json(): JSONObject = JSONObject().put("s", src).put("t", title).put("c", credit).put("o", own)

        companion object {
            fun of(j: JSONObject) = Item(j.optString("s"), j.optString("t"), j.optString("c"), j.optBoolean("o"))
        }
    }

    val COLS = listOf("shoes", "tops")

    fun name(ctx: Context, col: String) =
        if (col == "shoes") Ui.t(ctx, "👠 Heels & Shoes", "👠 Heels & Shoes") else Ui.t(ctx, "👚 Tops & Tees", "👚 Tops & Tees")

    private val QUERIES = mapOf(
        "shoes" to listOf("high heels shoes", "stiletto heels", "women sandals heels", "block heels", "ankle boots women",
            "wedge heels", "pumps shoes women", "women sneakers fashion", "strappy heels", "platform heels"),
        "tops" to listOf("crop top fashion", "women t-shirt fashion", "blouse fashion women", "women top outfit",
            "off shoulder top", "graphic tee women", "women shirt fashion", "summer top women", "knit top women", "tank top fashion")
    )

    private val main = Handler(Looper.getMainLooper())
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("closet", Context.MODE_PRIVATE)

    // ---------- today's picks ----------
    fun daily(ctx: Context, col: String, done: (List<Item>) -> Unit) {
        val app = ctx.applicationContext
        val p = prefs(app)
        val day = Unlock.today()
        if (p.getInt("day_$col", 0) == day) {
            val cached = list(p.getString("daily_$col", "[]"))
            if (cached.isNotEmpty()) {
                done(cached)
                return
            }
        }
        Thread {
            val rnd = Random(day * 31 + col.hashCode())
            val qs = QUERIES.getValue(col).shuffled(rnd).take(2)
            val found = ArrayList<Item>()
            for (q in qs) found += openverse(q)
            if (found.size < 14) for (q in qs) found += commons(q)
            val picks = found.distinctBy { it.src }.shuffled(rnd).take(16)
            if (picks.isNotEmpty()) {
                val a = JSONArray()
                picks.forEach { a.put(it.json()) }
                p.edit().putInt("day_$col", day).putString("daily_$col", a.toString()).apply()
            }
            val result = picks.ifEmpty { list(p.getString("daily_$col", "[]")) }   // yesterday's if offline
            main.post { done(result) }
        }.start()
    }

    private fun get(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "ChinuApp/1.0 (personal Android app)")
        val body = if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
        c.disconnect()
        body
    } catch (e: Exception) {
        null
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Openverse: 800M+ openly licensed photos (Flickr, Wikimedia…) */
    private fun openverse(q: String): List<Item> {
        val body = get("https://api.openverse.org/v1/images/?q=${enc(q)}&page_size=20&mature=false") ?: return emptyList()
        return try {
            val arr = JSONObject(body).optJSONArray("results") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                var url = o.optString("url")
                if (url.isBlank()) return@mapNotNull null
                // Flickr: ask for the 640px version instead of the huge original
                if (url.contains("staticflickr.com")) url = url.replace(Regex("_[a-z]\\.jpg$"), "_z.jpg")
                val by = o.optString("creator").ifBlank { o.optString("source") }
                Item(url, o.optString("title").take(60), "📷 $by · ${o.optString("license").uppercase()}")
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Wikimedia Commons as a backup */
    private fun commons(q: String): List<Item> {
        val body = get("https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6" +
            "&gsrlimit=20&gsrsearch=${enc("filetype:bitmap $q")}&prop=imageinfo&iiprop=url&iiurlwidth=640") ?: return emptyList()
        return try {
            val pages = JSONObject(body).optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
            pages.keys().asSequence().mapNotNull { k ->
                val pg = pages.optJSONObject(k) ?: return@mapNotNull null
                val info = pg.optJSONArray("imageinfo")?.optJSONObject(0) ?: return@mapNotNull null
                val url = info.optString("thumburl").ifBlank { info.optString("url") }
                if (url.isBlank()) null
                else Item(url, pg.optString("title").removePrefix("File:").substringBeforeLast('.').take(60), "📷 Wikimedia Commons")
            }.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun list(s: String?): List<Item> = try {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).map { Item.of(a.getJSONObject(it)) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(ctx: Context, key: String, items: List<Item>) {
        val a = JSONArray()
        items.forEach { a.put(it.json()) }
        prefs(ctx).edit().putString(key, a.toString()).apply()
    }

    // ---------- favourites ----------
    fun favs(ctx: Context, col: String) = list(prefs(ctx).getString("fav_$col", "[]"))
    fun isFav(ctx: Context, col: String, it: Item) = favs(ctx, col).any { f -> f.src == it.src }
    fun toggleFav(ctx: Context, col: String, it: Item): Boolean {
        val f = favs(ctx, col)
        val now = !f.any { x -> x.src == it.src }
        save(ctx, "fav_$col", if (now) listOf(it) + f else f.filter { x -> x.src != it.src })
        return now
    }

    // ---------- her own items ----------
    fun mine(ctx: Context, col: String) = list(prefs(ctx).getString("mine_$col", "[]"))

    fun addMine(ctx: Context, col: String, uri: Uri, title: String): Boolean = try {
        val dir = File(ctx.filesDir, "closet/$col").apply { mkdirs() }
        val f = File(dir, "${System.currentTimeMillis()}.jpg")
        ctx.contentResolver.openInputStream(uri)?.use { inp -> f.outputStream().use { inp.copyTo(it) } }
        if (f.length() > 0) {
            save(ctx, "mine_$col", listOf(Item(f.path, title, Ui.t(ctx, "Added by you 💖", "Aapne add kiya 💖"), true)) + mine(ctx, col))
            true
        } else false
    } catch (e: Exception) {
        false
    }

    fun removeMine(ctx: Context, col: String, it: Item) {
        save(ctx, "mine_$col", mine(ctx, col).filter { x -> x.src != it.src })
        try {
            File(it.src).delete()
        } catch (e: Exception) {
        }
    }
}

/** The closet screen: glass tabs · lock · today's picks · favourites · my collection */
class ClosetActivity : Activity() {
    private lateinit var body: LinearLayout
    private var col = "shoes"
    private val tabs = HashMap<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        col = intent.getStringExtra("col") ?: "shoes"
        val root = FrameLayout(this)
        root.addView(GlassBackground(this))
        val scroll = ScrollView(this)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(44f), dp(16f), dp(30f))
        }
        val p = Profile.load(this)
        outer.addView(Ui.label(this, Ui.t(this, "${p.name}'s Closet ✨", "${p.name} ki Closet ✨"), 30f, 0xFF3B1F4A.toInt(), true))
        outer.addView(Ui.label(this, Ui.t(this, "New picks every day · made with 💖 by ${p.partner}",
            "Roz naye designs · ${p.partner} ki taraf se 💖"), 14f, 0xFF6B4E7A.toInt()).apply { setPadding(0, dp(2f), 0, dp(16f)) })

        // glass segmented tabs
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.glass(this@ClosetActivity, 24f, 0x55)
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        }
        for (c in Closet.COLS) {
            val t = TextView(this).apply {
                text = Closet.name(this@ClosetActivity, c)
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, dp(11f), 0, dp(11f))
                setOnClickListener {
                    col = c
                    render()
                }
            }
            tabs[c] = t
            seg.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        outer.addView(seg)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(body)
        scroll.addView(outer)
        root.addView(scroll)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun dp(v: Float) = Ui.dp(this, v)

    private fun render() {
        for ((c, t) in tabs) {
            val on = c == col
            t.background = if (on) Ui.glass(this, 20f, 0xDD) else null
            t.setTextColor(if (on) 0xFF3B1F4A.toInt() else 0xFF7A6487.toInt())
            Ui.bold(t).typeface = if (on) android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD) else android.graphics.Typeface.DEFAULT
        }
        body.removeAllViews()
        if (!Unlock.isOpen(this, col)) renderLock() else renderOpen()
    }

    // ---------- 🔒 locked ----------
    private fun renderLock() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Ui.glass(this@ClosetActivity, 30f, 0x66)
            setPadding(dp(20f), dp(26f), dp(20f), dp(22f))
        }
        card.addView(Ui.label(this, "🔒", 54f, 0xFF3B1F4A.toInt()))
        card.addView(Ui.label(this, Ui.t(this, "Today's collection is locked", "Aaj ki collection locked hai"), 20f, 0xFF3B1F4A.toInt(), true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6f), 0, dp(4f))
        })
        card.addView(Ui.label(this, Ui.t(this, "Win a quick game against Chinu to get today's password 🎮",
            "Chinu ko ek chhota game harao aur aaj ka password pao 🎮"), 14.5f, 0xFF6B4E7A.toInt()).apply { gravity = Gravity.CENTER })

        card.addView(Ui.primaryButton(this, Ui.t(this, "🎮  Play & win the password", "🎮  Khelo aur password jeeto")) {
            GameActivity.open(this, if (Math.random() < 0.5) "ttt" else "match", col)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18f) })

        if (Unlock.won(this, col)) {
            card.addView(Ui.label(this, Ui.t(this, "You won today! Password: ", "Aaj aap jeet chuki ho! Password: ") + Unlock.password(col),
                14f, 0xFF8A4F9E.toInt(), true).apply { gravity = Gravity.CENTER; setPadding(0, dp(12f), 0, 0) })
        }
        val input = EditText(this).apply {
            hint = Ui.t(this@ClosetActivity, "Enter password", "Password daalo")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
            textSize = 20f
            letterSpacing = 0.3f
            background = Ui.glass(this@ClosetActivity, 20f, 0x99)
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        }
        card.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14f) })
        card.addView(Ui.glassButton(this, Ui.t(this, "🔓  Unlock", "🔓  Kholo")) {
            if (input.text.toString().trim() == Unlock.password(col)) {
                Unlock.open(this, col)
                Toast.makeText(this, "🎉 " + Ui.t(this, "Unlocked!", "Khul gaya!"), Toast.LENGTH_SHORT).show()
                render()
            } else {
                input.animate().translationX(dp(10f).toFloat()).setDuration(60).withEndAction {
                    input.animate().translationX(0f).setDuration(60).start()
                }.start()
                Toast.makeText(this, Ui.t(this, "Wrong password 🙈 Win a game to get it", "Galat password 🙈 Game jeet ke lo"), Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })
        body.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18f) })
    }

    // ---------- 🔓 open ----------
    private fun renderOpen() {
        // My collection
        section(Ui.t(this, "MY COLLECTION", "MERI COLLECTION"))
        val mine = Closet.mine(this, col)
        grid(listOf<Closet.Item?>(null) + mine)            // null = "+ Add" tile
        // Favourites
        val favs = Closet.favs(this, col)
        if (favs.isNotEmpty()) {
            section(Ui.t(this, "FAVOURITES ❤️", "FAVOURITES ❤️"))
            grid(favs)
        }
        // Today's picks
        section(Ui.t(this, "TODAY'S PICKS ✨", "AAJ KE DESIGNS ✨"))
        val loading = Ui.label(this, Ui.t(this, "Finding pretty things for you…", "Aapke liye sundar cheezein dhoondh rahi hoon…"),
            14f, 0xFF6B4E7A.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, dp(20f), 0, dp(20f)) }
        body.addView(loading)
        val forCol = col
        Closet.daily(this, col) { items ->
            if (forCol != col || isFinishing) return@daily
            val idx = body.indexOfChild(loading)
            body.removeView(loading)
            if (items.isEmpty()) {
                body.addView(Ui.label(this, Ui.t(this, "Couldn't load today's picks. Check the internet 🌐", "Aaj ke designs load nahi hue. Internet check karo 🌐"),
                    14f, 0xFF6B4E7A.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, dp(20f), 0, dp(20f)) }, idx)
            } else grid(items, idx)
            body.addView(Ui.label(this, Ui.t(this, "Photos: openly-licensed images via Openverse & Wikimedia Commons",
                "Photos: Openverse aur Wikimedia Commons ki free-license images"), 11f, 0xFF8E7A99.toInt()).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(14f), 0, 0)
            })
        }
    }

    private fun section(t: String) {
        body.addView(Ui.label(this, t, 12.5f, 0xFF8A4F9E.toInt(), true).apply {
            letterSpacing = 0.1f
            setPadding(dp(4f), dp(24f), 0, dp(10f))
        })
    }

    private fun grid(items: List<Closet.Item?>, at: Int = -1) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var row: LinearLayout? = null
        items.forEachIndexed { i, it ->
            if (i % 2 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0) topMargin = dp(12f)
                })
            }
            val tile = if (it == null) addTile() else tile(it)
            row?.addView(tile, LinearLayout.LayoutParams(0, dp(230f), 1f).apply { if (i % 2 == 1) leftMargin = dp(12f) })
        }
        if (items.size % 2 == 1) row?.addView(View(this), LinearLayout.LayoutParams(0, dp(230f), 1f).apply { leftMargin = dp(12f) })
        if (at >= 0) body.addView(box, at) else body.addView(box)
    }

    private val round = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, Ui.dp(view.context, 22f).toFloat())
        }
    }

    private fun tile(item: Closet.Item): View {
        val f = FrameLayout(this).apply {
            background = Ui.glass(this@ClosetActivity, 22f, 0x66)
            outlineProvider = round
            clipToOutline = true
        }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        f.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ImageLoader.load(img, item.src, 600)
        val heart = TextView(this).apply {
            text = if (Closet.isFav(this@ClosetActivity, col, item)) "❤️" else "🤍"
            textSize = 18f
            gravity = Gravity.CENTER
            background = Ui.glass(this@ClosetActivity, 18f, 0xAA)
            setOnClickListener {
                val on = Closet.toggleFav(this@ClosetActivity, col, item)
                text = if (on) "❤️" else "🤍"
                animate().scaleX(1.3f).scaleY(1.3f).setDuration(120).withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(120).start() }.start()
            }
        }
        f.addView(heart, FrameLayout.LayoutParams(dp(38f), dp(38f), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(8f); rightMargin = dp(8f)
        })
        if (item.title.isNotBlank()) {
            f.addView(TextView(this).apply {
                text = item.title
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(0xFF3B1F4A.toInt())
                background = Ui.glass(this@ClosetActivity, 14f, 0xCC)
                setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                setMargins(dp(8f), 0, dp(8f), dp(8f))
            })
        }
        f.setOnClickListener { view(item) }
        if (item.own) f.setOnLongClickListener {
            AlertDialog.Builder(this).setTitle(Ui.t(this, "Remove this?", "Ise hata dein?"))
                .setPositiveButton(Ui.t(this, "Remove", "Hatao")) { _, _ -> Closet.removeMine(this, col, item); render() }
                .setNegativeButton("Cancel", null).show()
            true
        }
        return f
    }

    private fun addTile(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = Ui.glass(this@ClosetActivity, 22f, 0x55)
        addView(Ui.label(this@ClosetActivity, "＋", 40f, 0xFF8A4F9E.toInt()).apply { gravity = Gravity.CENTER })
        addView(Ui.label(this@ClosetActivity, Ui.t(this@ClosetActivity, "Add your own", "Apna add karo"), 14f, 0xFF6B4E7A.toInt(), true).apply {
            gravity = Gravity.CENTER
        })
        setOnClickListener { pickPhoto() }
    }

    private fun view(item: Closet.Item) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(12f), dp(12f), dp(4f))
        }
        val img = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            outlineProvider = round
            clipToOutline = true
        }
        box.addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420f)))
        ImageLoader.load(img, item.src, 1200)
        box.addView(Ui.label(this, item.credit, 12f, 0xFF8E7A99.toInt()).apply { setPadding(dp(4f), dp(8f), 0, 0) })
        AlertDialog.Builder(this).setTitle(item.title.ifBlank { Closet.name(this, col) }).setView(box)
            .setPositiveButton("OK", null).show()
    }

    // ---------- add from gallery ----------
    private fun pickPhoto() {
        val i = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES)
        else Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        try {
            startActivityForResult(i, 41)
        } catch (e: Exception) {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), 41)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != 41 || resultCode != RESULT_OK || uri == null) return
        val name = EditText(this).apply { hint = Ui.t(this@ClosetActivity, "Name (optional)", "Naam (optional)") }
        AlertDialog.Builder(this).setTitle(Ui.t(this, "Add to my collection", "Meri collection mein daalo")).setView(name)
            .setPositiveButton(Ui.t(this, "Add", "Daalo")) { _, _ ->
                if (Closet.addMine(this, col, uri, name.text.toString().trim())) render()
                else Toast.makeText(this, Ui.t(this, "Couldn't add the photo", "Photo add nahi hui"), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null).show()
    }
}
