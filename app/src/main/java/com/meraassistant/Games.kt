package com.meraassistant

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar

/** 🔒 Daily locks for Komal's collections — opened with a password won in a game */
object Unlock {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("closet", Context.MODE_PRIVATE)

    fun today(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }

    /** Today's 4-digit password for a collection */
    fun password(col: String): String {
        var h = 7L
        for (ch in "chinu-$col-${today()}-komal") h = (h * 31 + ch.code) % 1_000_000_007L
        return (1000 + (h % 9000)).toString()
    }

    fun isOpen(ctx: Context, col: String) = prefs(ctx).getInt("open_$col", 0) == today()

    fun open(ctx: Context, col: String) = prefs(ctx).edit().putInt("open_$col", today()).apply()

    /** Password she has won today (so she can see it again) */
    fun won(ctx: Context, col: String): Boolean = prefs(ctx).getInt("won_$col", 0) == today()

    fun markWon(ctx: Context, col: String) = prefs(ctx).edit().putInt("won_$col", today()).apply()
}

// ======================= ❌⭕ Zero-Kata board =======================
class TicTacToeView(ctx: Context) : View(ctx) {
    val cells = IntArray(9)            // 0 empty, 1 = X, 2 = O
    var onTap: ((Int) -> Unit)? = null
    var winLine: IntArray? = null
    private val d = ctx.resources.displayMetrics.density
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt(); strokeWidth = 3f * d; strokeCap = Paint.Cap.ROUND }
    private val xP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF4F9A.toInt(); strokeWidth = 7f * d; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
    private val oP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8A5CFF.toInt(); strokeWidth = 7f * d; style = Paint.Style.STROKE }
    private val winP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC34C759.toInt(); strokeWidth = 9f * d; strokeCap = Paint.Cap.ROUND }

    override fun onMeasure(w: Int, h: Int) {
        val s = MeasureSpec.getSize(w)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val s = width / 3f
        for (i in 1..2) {
            c.drawLine(s * i, s * 0.12f, s * i, width - s * 0.12f, grid)
            c.drawLine(s * 0.12f, s * i, width - s * 0.12f, s * i, grid)
        }
        for (i in 0 until 9) {
            val cx = (i % 3) * s + s / 2
            val cy = (i / 3) * s + s / 2
            val r = s * 0.27f
            when (cells[i]) {
                1 -> {
                    c.drawLine(cx - r, cy - r, cx + r, cy + r, xP)
                    c.drawLine(cx + r, cy - r, cx - r, cy + r, xP)
                }
                2 -> c.drawCircle(cx, cy, r, oP)
            }
        }
        winLine?.let { l ->
            val a = l[0]
            val b = l[2]
            c.drawLine((a % 3) * s + s / 2, (a / 3) * s + s / 2, (b % 3) * s + s / 2, (b / 3) * s + s / 2, winP)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            val s = width / 3f
            val i = (e.y / s).toInt().coerceIn(0, 2) * 3 + (e.x / s).toInt().coerceIn(0, 2)
            onTap?.invoke(i)
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun reset() {
        cells.fill(0)
        winLine = null
        invalidate()
    }

    companion object {
        val LINES = arrayOf(
            intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
            intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
            intArrayOf(0, 4, 8), intArrayOf(2, 4, 6)
        )

        /** 0 none · 1 X wins · 2 O wins · 3 draw */
        fun result(b: IntArray): Pair<Int, IntArray?> {
            for (l in LINES) if (b[l[0]] != 0 && b[l[0]] == b[l[1]] && b[l[1]] == b[l[2]]) return b[l[0]] to l
            return (if (b.all { it != 0 }) 3 else 0) to null
        }

        /** Chinu's move: wins if she can, usually blocks, sometimes makes a cute mistake */
        fun chinuMove(b: IntArray, me: Int): Int {
            val you = 3 - me
            val free = (0 until 9).filter { b[it] == 0 }
            fun winning(who: Int) = free.firstOrNull { i -> b[i] = who; val w = result(b).first == who; b[i] = 0; w }
            winning(me)?.let { return it }
            if (Math.random() < 0.65) winning(you)?.let { return it }
            if (b[4] == 0 && Math.random() < 0.5) return 4
            return free.random()
        }
    }
}

// ======================= 🃏 Picture match =======================
class MemoryView(ctx: Context) : View(ctx) {
    private val faces = listOf("👠", "👚", "👗", "💄", "👜", "💍", "🎀", "🌸").shuffled().take(6)
    private val deck = (faces + faces).shuffled().toMutableList()
    private val open = BooleanArray(12)
    private val done = BooleanArray(12)
    private var first = -1
    private var busy = false
    var moves = 0
    var onWin: (() -> Unit)? = null
    var onMove: (() -> Unit)? = null
    private val d = ctx.resources.displayMetrics.density
    private val back = Paint(Paint.ANTI_ALIAS_FLAG)
    private val front = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEEFFFFFF.toInt() }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = 0xCCFFFFFF.toInt() }
    private val emoji = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val r = RectF()
    private val h = Handler(Looper.getMainLooper())

    override fun onMeasure(w: Int, hs: Int) {
        val s = MeasureSpec.getSize(w)
        setMeasuredDimension(s, (s * 4f / 3f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val cw = width / 3f
        val ch = height / 4f
        val g = 6f * d
        back.shader = android.graphics.LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
            0xFFFF8CC6.toInt(), 0xFFB48CFF.toInt(), android.graphics.Shader.TileMode.CLAMP)
        emoji.textSize = ch * 0.45f
        for (i in 0 until 12) {
            r.set((i % 3) * cw + g, (i / 3) * ch + g, (i % 3 + 1) * cw - g, (i / 3 + 1) * ch - g)
            if (open[i] || done[i]) {
                front.alpha = if (done[i]) 150 else 238
                c.drawRoundRect(r, 16f * d, 16f * d, front)
                c.drawText(deck[i], r.centerX(), r.centerY() + emoji.textSize * 0.35f, emoji)
            } else {
                c.drawRoundRect(r, 16f * d, 16f * d, back)
                emoji.alpha = 160
                c.drawText("✨", r.centerX(), r.centerY() + emoji.textSize * 0.35f, emoji)
                emoji.alpha = 255
            }
            c.drawRoundRect(r, 16f * d, 16f * d, rim)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP || busy) return true
        val i = (e.y / (height / 4f)).toInt().coerceIn(0, 3) * 3 + (e.x / (width / 3f)).toInt().coerceIn(0, 2)
        if (open[i] || done[i]) return true
        open[i] = true
        invalidate()
        if (first < 0) {
            first = i
        } else {
            moves++
            onMove?.invoke()
            val a = first
            first = -1
            if (deck[a] == deck[i]) {
                done[a] = true
                done[i] = true
                if (done.all { it }) h.postDelayed({ onWin?.invoke() }, 400)
            } else {
                busy = true
                h.postDelayed({
                    open[a] = false
                    open[i] = false
                    busy = false
                    invalidate()
                }, 700)
            }
        }
        performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

/**
 * 🎮 Games screen.
 *  mode "ttt"   = Zero-Kata vs Chinu
 *  mode "match" = picture match
 *  mode "online"= Zero-Kata with your partner (via Couple link)
 *  col = collection to unlock (optional)
 */
class GameActivity : Activity() {
    private lateinit var title: TextView
    private lateinit var info: TextView
    private lateinit var holder: FrameLayout
    private var col: String? = null
    private var mode = "ttt"

    // online game state
    private var board: TicTacToeView? = null
    private var mySym = 1
    private var myTurn = true
    private var gameId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        col = intent.getStringExtra("col")
        mode = intent.getStringExtra("mode") ?: "ttt"
        val root = FrameLayout(this)
        root.addView(GlassBackground(this, if (mode == "online") GlassBackground.LOVE else GlassBackground.PINK))
        val col1 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val p = Ui.dp(this@GameActivity, 20f)
            setPadding(p, Ui.dp(this@GameActivity, 48f), p, p)
        }
        title = Ui.label(this, "", 26f, 0xFF3B1F4A.toInt(), true).apply { gravity = Gravity.CENTER }
        info = Ui.label(this, "", 15f, 0xFF6B4E7A.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, Ui.dp(context, 6f), 0, 0) }
        holder = FrameLayout(this).apply {
            background = Ui.glass(this@GameActivity, 28f, 0x55)
            val p = Ui.dp(this@GameActivity, 14f)
            setPadding(p, p, p, p)
        }
        col1.addView(title)
        col1.addView(info)
        col1.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(this@GameActivity, 22f)
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(Ui.glassButton(this, Ui.t(this, "↻  New game", "↻  Naya game")) { start() })
        if (mode != "online") {
            row.addView(View(this), LinearLayout.LayoutParams(Ui.dp(this, 12f), 1))
            row.addView(Ui.glassButton(this, if (mode == "ttt") Ui.t(this, "🃏  Picture match", "🃏  Picture match") else Ui.t(this, "❌⭕  Zero-Kata", "❌⭕  Zero-Kata")) {
                mode = if (mode == "ttt") "match" else "ttt"
                start()
            })
        }
        col1.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(this@GameActivity, 22f)
        })
        root.addView(col1)
        setContentView(root)
        if (mode == "online") {
            current = this
            gameId = intent.getStringExtra("game") ?: ""
            mySym = intent.getIntExtra("sym", 1)
        }
        start()
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    private fun start() {
        holder.removeAllViews()
        when (mode) {
            "match" -> startMatch()
            "online" -> startOnline(fresh = gameId.isEmpty())
            else -> startTtt()
        }
    }

    // ---------- vs Chinu ----------
    private fun startTtt() {
        title.text = Ui.t(this, "Beat Chinu at Zero-Kata", "Chinu ko Zero-Kata mein harao")
        info.text = Ui.t(this, "You are ❌ · Chinu is ⭕", "Aap ❌ ho · Chinu ⭕ hai")
        val b = TicTacToeView(this)
        holder.addView(b)
        var over = false
        b.onTap = tap@{ i ->
            if (over || b.cells[i] != 0) return@tap
            b.cells[i] = 1
            b.invalidate()
            if (finished(b) { over = true }) return@tap
            info.text = Ui.t(this, "Chinu is thinking… 🤔", "Chinu soch rahi hai… 🤔")
            b.postDelayed({
                b.cells[TicTacToeView.chinuMove(b.cells, 2)] = 2
                b.invalidate()
                if (!finished(b) { over = true }) info.text = Ui.t(this, "Your turn ❌", "Aapki baari ❌")
            }, 550)
        }
    }

    private fun finished(b: TicTacToeView, end: () -> Unit): Boolean {
        val (res, line) = TicTacToeView.result(b.cells)
        if (res == 0) return false
        end()
        b.winLine = line
        b.invalidate()
        when (res) {
            1 -> won()
            2 -> info.text = Ui.t(this, "Chinu won this time 😜 Try again!", "Is baar Chinu jeet gayi 😜 Phir se try karo!")
            else -> info.text = Ui.t(this, "It's a draw 🤝 One more?", "Draw ho gaya 🤝 Ek aur?")
        }
        return true
    }

    // ---------- picture match ----------
    private fun startMatch() {
        title.text = Ui.t(this, "Picture match", "Picture match")
        info.text = Ui.t(this, "Find all the pairs ✨", "Saare jode dhoondo ✨")
        val m = MemoryView(this)
        m.onMove = { info.text = Ui.t(this, "Moves: ${m.moves}", "Chaalein: ${m.moves}") }
        m.onWin = { won() }
        holder.addView(m)
    }

    private fun won() {
        info.text = Ui.t(this, "You won! 🎉", "Aap jeet gayi! 🎉")
        val c = col
        val msg = if (c != null) {
            Unlock.markWon(this, c)
            Ui.t(this, "Today's password for your collection is", "Aaj ka collection password hai") + "\n\n" + Unlock.password(c)
        } else Ui.t(this, "Well played! 💖", "Bahut badhiya khela! 💖")
        AlertDialog.Builder(this)
            .setTitle("🎉 " + Ui.t(this, "You won!", "Jeet gayi!"))
            .setMessage(msg)
            .setPositiveButton(if (c != null) Ui.t(this, "Go unlock", "Kholne chalo") else "OK") { _, _ -> if (c != null) finish() }
            .setCancelable(false)
            .show()
    }

    // ---------- online with partner ----------
    private fun startOnline(fresh: Boolean) {
        val p = Profile.load(this)
        val other = Speaker.other(p)
        title.text = Ui.t(this, "Zero-Kata with $other", "$other ke saath Zero-Kata")
        val b = TicTacToeView(this)
        board = b
        holder.addView(b)
        if (!CoupleLink.paired(this)) {
            info.text = Ui.t(this, "Connect both phones first (💞 on the home screen)", "Pehle dono phones jodo (home screen par 💞)")
            return
        }
        if (fresh) {
            gameId = System.currentTimeMillis().toString(36)
            mySym = 1
            myTurn = true
            CoupleLink.send(this, "ttt", org.json.JSONObject().put("a", "invite").put("g", gameId))
            info.text = Ui.t(this, "Invite sent 💌 You are ❌, you start", "Invite bhej diya 💌 Aap ❌ ho, pehle aap")
        } else {
            myTurn = mySym == 1
            info.text = if (myTurn) Ui.t(this, "Your turn", "Aapki baari") else Ui.t(this, "$other's turn…", "$other ki baari…")
        }
        b.onTap = tap@{ i ->
            if (!myTurn || b.cells[i] != 0 || TicTacToeView.result(b.cells).first != 0) return@tap
            b.cells[i] = mySym
            b.invalidate()
            myTurn = false
            CoupleLink.send(this, "ttt", org.json.JSONObject().put("a", "move").put("g", gameId).put("i", i))
            if (!onlineEnd(b)) info.text = Ui.t(this, "$other's turn…", "$other ki baari…")
        }
    }

    private fun onlineEnd(b: TicTacToeView): Boolean {
        val (res, line) = TicTacToeView.result(b.cells)
        if (res == 0) return false
        b.winLine = line
        b.invalidate()
        info.text = when (res) {
            mySym -> Ui.t(this, "You won! 🎉", "Aap jeet gaye! 🎉")
            3 -> Ui.t(this, "Draw 🤝", "Draw 🤝")
            else -> Ui.t(this, "${Speaker.other(Profile.load(this))} won 😄", "${Speaker.other(Profile.load(this))} jeet gaye 😄")
        }
        return true
    }

    /** A move arrived from the partner */
    fun remoteMove(g: String, i: Int) {
        val b = board ?: return
        if (g != gameId || i !in 0..8 || b.cells[i] != 0) return
        b.cells[i] = 3 - mySym
        b.invalidate()
        myTurn = true
        if (!onlineEnd(b)) info.text = Ui.t(this, "Your turn", "Aapki baari")
    }

    companion object {
        @Volatile
        var current: GameActivity? = null

        fun open(ctx: Context, mode: String, col: String? = null) {
            ctx.startActivity(Intent(ctx, GameActivity::class.java).putExtra("mode", mode).putExtra("col", col)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
