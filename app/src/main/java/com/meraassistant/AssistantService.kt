package com.meraassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Chinu's "hands": system actions that a normal app can't do.
 * Screenshot, lock, home, back, recents, notifications, power menu, split screen,
 * scrolling, and pressing WhatsApp's Send button automatically.
 */
class AssistantService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var sendUntil = 0L
    private var onSent: (() -> Unit)? = null
    private var overlay: ChinuOverlay? = null

    /** ✨ Siri-style Chinu on top of any screen */
    /** 💞 Full-screen floating hearts + a little glass card (when the partner sends love) */
    fun showLove(emoji: String, text: String) {
        try {
            val wm = getSystemService(android.view.WindowManager::class.java)
            val root = android.widget.FrameLayout(this)
            val fx = LoveFx(this)
            root.addView(fx, android.widget.FrameLayout.LayoutParams(-1, -1))
            val card = android.widget.TextView(this).apply {
                this.text = "$emoji  $text"
                textSize = 17f
                setTextColor(0xFF3B1F4A.toInt())
                gravity = android.view.Gravity.CENTER
                background = Ui.glass(this@AssistantService, 26f, 0xDD)
                val pd = Ui.dp(this@AssistantService, 18f)
                setPadding(pd, pd, pd, pd)
                elevation = Ui.dp(this@AssistantService, 10f).toFloat()
            }
            root.addView(card, android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.CENTER).apply {
                val m = Ui.dp(this@AssistantService, 28f)
                setMargins(m, 0, m, 0)
            })
            val lp = android.view.WindowManager.LayoutParams(
                -1, -1,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
            )
            wm.addView(root, lp)
            card.scaleX = 0.6f
            card.scaleY = 0.6f
            card.alpha = 0f
            card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(350).start()
            fx.post { fx.burst(emoji, 34) }
            handler.postDelayed({
                card.animate().alpha(0f).setDuration(300).start()
            }, 4200)
            handler.postDelayed({
                try {
                    wm.removeView(root)
                } catch (e: Exception) {
                }
            }, 5200)
        } catch (e: Exception) {
        }
    }

    fun showOverlay(command: String) {
        try {
            val o = overlay ?: ChinuOverlay(this).also { overlay = it }
            o.show(command)
        } catch (e: Exception) {
            // overlay fail ho to bhi service crash na ho (warna Android Accessibility band kar deta hai)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Speaker.load(this)
        instance = this
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("accWasOn", true).apply()
        BeepMuter.forceUnmute(this)
        try {
            if (overlay == null) overlay = ChinuOverlay(this)
        } catch (e: Exception) {
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        overlay?.dismiss(true)
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            handleEvent(event)
        } catch (e: Exception) {
        }
    }

    private fun handleEvent(event: AccessibilityEvent?) {
        if (System.currentTimeMillis() > sendUntil) return
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") trySend()
    }

    // ---------------- WhatsApp auto-send ----------------
    /** Next 15 seconds: jaise hi WhatsApp chat khule, Send button daba do */
    fun armWhatsAppSend(sent: (() -> Unit)? = null) {
        sendUntil = System.currentTimeMillis() + 15_000
        onSent = sent
        // events na aaye to bhi kuch baar khud try karo
        for (d in listOf(1500L, 2500L, 3500L, 5000L)) handler.postDelayed({ trySend() }, d)
    }

    private fun trySend() {
        if (System.currentTimeMillis() > sendUntil) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return
        val byId = root.findAccessibilityNodeInfosByViewId("$pkg:id/send")
        val target = byId.firstOrNull { it.isVisibleToUser && it.isEnabled } ?: findByDescription(root)
        if (target != null && clickNode(target)) {
            sendUntil = 0
            onSent?.invoke()
            onSent = null
        }
    }

    private fun findByDescription(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > 30) return null
        val d = node.contentDescription?.toString()?.lowercase() ?: ""
        if ((d == "send" || d == "भेजें") && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val r = findByDescription(node.getChild(i), depth + 1)
            if (r != null) return r
        }
        return null
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var hops = 0
        while (cur != null && hops < 4) {
            if (cur.isClickable) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            cur = cur.parent
            hops++
        }
        return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    // ---------------- 🎵 Auto play (no tapping needed) ----------------
    private fun retry(times: Int, every: Long, first: Long, block: () -> Boolean) {
        var left = times
        val r = object : Runnable {
            override fun run() {
                val done = try {
                    block()
                } catch (e: Exception) {
                    false
                }
                left--
                if (!done && left > 0) handler.postDelayed(this, every)
            }
        }
        handler.postDelayed(r, first)
    }

    /** A real song/video is playing (Chinu's own voice does not count) */
    private fun musicPlaying(): Boolean {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        try {
            val media = am.activePlaybackConfigurations.any { c ->
                val u = c.audioAttributes.usage
                u == android.media.AudioAttributes.USAGE_MEDIA || u == android.media.AudioAttributes.USAGE_GAME ||
                    u == android.media.AudioAttributes.USAGE_UNKNOWN
            }
            return media && !MainActivity.speaking
        } catch (e: Exception) {
        }
        return am.isMusicActive && !MainActivity.speaking
    }

    /** Pause whatever is playing right now (so a new song can be detected) */
    fun mediaPauseKey() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE))
        } catch (e: Exception) {
        }
    }

    /** Press the hardware-style PLAY key (works for most music apps) */
    private fun mediaPlayKey() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
        } catch (e: Exception) {
        }
    }

    /** Text of a node plus everything inside it (a whole result row) */
    private fun rowText(n: AccessibilityNodeInfo): String {
        val sb = StringBuilder(label(n))
        for (c in allNodes(n).take(40)) sb.append(' ').append(label(c))
        return sb.toString().lowercase()
    }

    /** Score a search-result row: more song-title words = better, "Song" rows best, videos/artists/playlists worse */
    private fun songScore(row: String, words: List<String>): Int {
        var s = words.count { row.contains(it) } * 10
        if (s == 0) return 0
        if (Regex("\\b(song|गाना|गीत)\\b").containsMatchIn(row)) s += 15
        if (row.contains("top result") || row.contains("सबसे")) s += 5
        if (Regex("\\b(video|वीडियो)\\b").containsMatchIn(row)) s -= 6
        if (Regex("\\b(artist|album|playlist|profile|subscribers|episode|podcast)\\b").containsMatchIn(row)) s -= 12
        return s
    }

    private fun label(n: AccessibilityNodeInfo): String =
        ((n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")).lowercase().trim()

    private fun findNode(root: AccessibilityNodeInfo?, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var seen = 0
        while (stack.isNotEmpty() && seen < 2500) {
            val n = stack.removeFirst()
            seen++
            if (n.isVisibleToUser && pred(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
        }
        return null
    }

    private fun allNodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        if (root == null) return out
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty() && out.size < 2500) {
            val n = stack.removeFirst()
            out.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
        }
        return out
    }

    private fun height(n: AccessibilityNodeInfo): Int {
        val r = Rect()
        n.getBoundsInScreen(r)
        return r.height()
    }

    private fun top(n: AccessibilityNodeInfo): Int {
        val r = Rect()
        n.getBoundsInScreen(r)
        return r.top
    }

    private fun typeAndSearch(edit: AccessibilityNodeInfo, query: String) {
        edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        edit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
        edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (Build.VERSION.SDK_INT >= 30) {
            edit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
    }

    /** YouTube (or any YouTube-like app): tap the first video in the search results */
    fun autoPlayYouTube(query: String, pkg: String = "com.google.android.youtube") {
        val screenH = resources.displayMetrics.heightPixels
        retry(times = 10, every = 1200, first = 3000) {
            val root = rootInActiveWindow ?: return@retry false
            if (root.packageName?.toString() != pkg) return@retry false
            val video = allNodes(root)
                .filter { n ->
                    val l = label(n)
                    n.isVisibleToUser && top(n) > screenH * 0.12 &&
                        (l.contains("minute") || l.contains("second") || l.contains("views") || l.contains("मिनट") || l.contains("व्यू"))
                }
                .minByOrNull { top(it) } ?: return@retry false
            clickNode(video)
        }
    }

    /**
     * Any app: wait a bit (some apps start playing by themselves); if nothing is playing,
     * type the song in the app's search box, open the first result and press Play.
     */
    fun autoPlayInApp(pkg: String, query: String, waitForMusic: Boolean, resultsLink: Intent? = null) {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.length >= 3 }
        val screenH = resources.displayMetrics.heightPixels
        var step = 0
        var searchBottom = 0
        handler.postDelayed({
            if (waitForMusic && musicPlaying()) return@postDelayed
            var firstDelay = 0L
            if (resultsLink != null) {
                // App ka search results page seedha kholo, phir pehla gaana tap karo
                try {
                    startActivity(resultsLink.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    step = 3
                    firstDelay = 2800L
                } catch (e: Exception) {
                }
            }
            retry(times = 16, every = 1100, first = firstDelay) {
                val root = rootInActiveWindow ?: return@retry false
                if (root.packageName?.toString() != pkg) return@retry false
                when (step) {
                    0 -> {
                        val edit = findNode(root) { it.isEditable }
                        if (edit != null) {
                            typeAndSearch(edit, query)
                            searchBottom = top(edit) + 60
                            step = 2
                        } else {
                            val s = findNode(root) { n ->
                                val l = label(n)
                                (n.isClickable || n.parent?.isClickable == true) && (l.contains("search") || l.contains("खोज"))
                            }
                            if (s != null) {
                                clickNode(s)
                                step = 1
                            }
                        }
                        false
                    }
                    1 -> {
                        val edit = findNode(root) { it.isEditable }
                        if (edit != null) {
                            typeAndSearch(edit, query)
                            searchBottom = top(edit) + 60
                            step = 2
                        }
                        false
                    }
                    2 -> {
                        step = 3            // results load ho rahe hain
                        false
                    }
                    3 -> {
                        if (musicPlaying()) return@retry true
                        val minTop = maxOf(searchBottom, (screenH * 0.12).toInt())
                        // whole clickable rows below the search bar, best "song" match first
                        val rows = allNodes(root)
                            .filter { n -> n.isVisibleToUser && n.isClickable && !n.isEditable && !n.isScrollable && top(n) > minTop && height(n) < screenH * 0.3 }
                            .map { n -> n to songScore(rowText(n), words) }
                            .filter { it.second > 0 }
                        val best = rows.maxWithOrNull(compareBy<Pair<AccessibilityNodeInfo, Int>> { it.second }.thenBy { -top(it.first) })
                        val target = best?.first ?: allNodes(root)
                            .filter { n -> n.isVisibleToUser && !n.isEditable && top(n) > minTop && words.any { label(n).contains(it) } }
                            .minByOrNull { top(it) }
                        if (target != null) {
                            clickNode(target)
                            step = 4
                        }
                        false
                    }
                    4 -> {
                        if (musicPlaying()) return@retry true
                        val play = findNode(root) { n ->
                            val l = label(n)
                            l == "play" || l.startsWith("play ") || l.contains("play all") || l.contains("shuffle") ||
                                l == "चलाएं" || l.contains("प्ले") || l == "resume"
                        }
                        if (play != null) clickNode(play)
                        step = 5
                        false
                    }
                    else -> {
                        if (!musicPlaying()) mediaPlayKey()
                        true
                    }
                }
            }
        }, if (waitForMusic) 3500L else 2500L)
    }

    // ---------------- ⚙️ Turn any setting ON / OFF (like a person would) ----------------
    /** Looks at every window on screen (the quick-settings shade is a separate window) */
    private fun allRoots(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        try {
            for (w in windows) w.root?.let { out.add(it) }
        } catch (e: Exception) {
        }
        rootInActiveWindow?.let { if (out.none { r -> r == it }) out.add(it) }
        return out
    }

    /** null = can't tell, true = on, false = off */
    private fun stateOf(n: AccessibilityNodeInfo): Boolean? {
        var cur: AccessibilityNodeInfo? = n
        var hops = 0
        while (cur != null && hops < 3) {
            if (cur.isCheckable) return cur.isChecked
            val sd = if (Build.VERSION.SDK_INT >= 30) cur.stateDescription?.toString()?.lowercase() else null
            val l = (label(cur) + " " + (sd ?: "")).lowercase()
            if (Regex("\\b(on|enabled|चालू|ऑन|connected)\\b").containsMatchIn(l)) return true
            if (Regex("\\b(off|disabled|बंद|ऑफ)\\b").containsMatchIn(l)) return false
            cur = cur.parent
            hops++
        }
        return null
    }

    /**
     * Pulls down Quick Settings, finds the tile (e.g. "Hotspot"), checks if it is on/off and taps it if needed.
     * If the tile isn't there, opens the setting's page and flips its switch instead.
     */
    fun toggleSetting(keywords: List<String>, wantOn: Boolean?, settingsAction: String, done: (Boolean) -> Unit) {
        val keys = keywords.map { it.lowercase() }
        fun matches(n: AccessibilityNodeInfo) = n.isVisibleToUser && keys.any { label(n).contains(it) }

        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) }, 700)
        var tries = 0
        val tryShade = object : Runnable {
            override fun run() {
                tries++
                val tile = allRoots().asSequence().mapNotNull { r -> findNode(r) { matches(it) } }.firstOrNull()
                if (tile != null) {
                    val now = stateOf(tile)
                    if (wantOn == null || now == null || now != wantOn) clickNode(tile)
                    handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 900)
                    handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 1300)
                    done(true)
                    return
                }
                if (tries < 6) {
                    handler.postDelayed(this, 700)
                    return
                }
                // Not in the shade -> open the setting page and flip its switch
                performGlobalAction(GLOBAL_ACTION_BACK)
                try {
                    startActivity(Intent(settingsAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    done(false)
                    return
                }
                retry(times = 8, every = 900, first = 1500) {
                    val root = rootInActiveWindow ?: return@retry false
                    val sw = findNode(root) { n ->
                        n.isCheckable && n.isVisibleToUser &&
                            (keys.any { k -> label(n).contains(k) || (n.parent?.let { p -> label(p).contains(k) } == true) } ||
                                n.className?.toString()?.contains("Switch") == true)
                    } ?: return@retry false
                    if (wantOn == null || sw.isChecked != wantOn) clickNode(sw)
                    done(true)
                    true
                }
            }
        }
        handler.postDelayed(tryShade, 1600)
    }

    // ---------------- System actions ----------------
    fun takeScreenshot(delayMs: Long) = global(GLOBAL_ACTION_TAKE_SCREENSHOT, delayMs)

    fun global(action: Int, delayMs: Long) {
        handler.postDelayed({ performGlobalAction(action) }, delayMs)
    }

    /** Screen ko upar/neeche scroll karta hai */
    fun scroll(down: Boolean, delayMs: Long) {
        handler.postDelayed({
            val dm = resources.displayMetrics
            val x = dm.widthPixels / 2f
            val h = dm.heightPixels.toFloat()
            val path = Path()
            if (down) {
                path.moveTo(x, h * 0.72f)
                path.lineTo(x, h * 0.28f)
            } else {
                path.moveTo(x, h * 0.28f)
                path.lineTo(x, h * 0.72f)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 320))
                .build()
            dispatchGesture(gesture, null, null)
        }, delayMs)
    }

    companion object {
        @Volatile
        var instance: AssistantService? = null
            private set
    }
}
