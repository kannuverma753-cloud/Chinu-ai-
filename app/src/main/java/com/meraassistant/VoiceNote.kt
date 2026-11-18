package com.meraassistant

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.util.Base64
import org.json.JSONObject
import java.io.File

/** 🎤 Voice messages between the two phones (recorded in 💞, or "Komal ko voice message bhejo") */
object VoiceNote {
    /** true while a voice message is being recorded → the wake word leaves the mic alone */
    @Volatile
    var recording = false

    private var player: MediaPlayer? = null
    var playing: String? = null
        private set
    var onPlayChange: (() -> Unit)? = null

    class Item(val file: File, val mine: Boolean, val at: Long, val sec: Int)

    fun dir(ctx: Context) = File(ctx.filesDir, "voice").apply { mkdirs() }

    fun len(sec: Int) = "${sec / 60}:" + (sec % 60).toString().padStart(2, '0')

    /** Newest first. File name: <time>_<me|them>_<seconds>.m4a */
    fun all(ctx: Context): List<Item> = dir(ctx).listFiles()?.mapNotNull { f ->
        val p = f.nameWithoutExtension.split('_')
        if (p.size != 3 || f.extension != "m4a") return@mapNotNull null
        val at = p[0].toLongOrNull() ?: return@mapNotNull null
        Item(f, p[1] == "me", at, p[2].toIntOrNull() ?: 0)
    }?.sortedByDescending { it.at }.orEmpty()

    fun keepMine(ctx: Context, tmp: File, sec: Int): File {
        val f = File(dir(ctx), "${System.currentTimeMillis()}_me_$sec.m4a")
        tmp.copyTo(f, true)
        tmp.delete()
        cleanup(ctx)
        return f
    }

    fun send(ctx: Context, f: File, sec: Int, done: (Boolean) -> Unit) {
        val audio = try {
            Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
        } catch (e: Exception) {
            done(false)
            return
        }
        CoupleLink.send(ctx, "voice", JSONObject().put("id", f.nameWithoutExtension.substringBefore('_')).put("sec", sec).put("audio", audio), done)
    }

    fun onVoice(app: Context, d: JSONObject, from: String) {
        val audio = d.optString("audio")
        if (audio.isEmpty()) return
        val id = d.optString("id").filter { it.isDigit() }.ifEmpty { System.currentTimeMillis().toString() }
        val sec = d.optInt("sec", 0)
        val f = File(dir(app), "${id}_them_$sec.m4a")
        if (f.exists()) return
        try {
            f.writeBytes(Base64.decode(audio, Base64.NO_WRAP))
        } catch (e: Exception) {
            return
        }
        cleanup(app)
        CoupleLink.notify(app, "🎤  " + Ui.t(app, "Voice message from $from", "$from ka voice message"),
            Ui.t(app, "${len(sec)} · tap to listen 💖", "${len(sec)} · sunne ke liye tap karo 💖"), 365,
            Intent(app, CoupleActivity::class.java).putExtra("playVoice", f.path))
        CoupleLink.buzz(app, longArrayOf(0, 80, 60, 80))
        val open = CoupleActivity.current
        if (open != null) open.refresh()
        else AssistantService.instance?.showLove("🎤", Ui.t(app, "$from sent you a voice message", "$from ne voice message bheja"))
    }

    /** Tap = play · tap the same one again = stop */
    fun play(ctx: Context, f: File) {
        val same = playing == f.path
        stop()
        if (same) return
        try {
            val mp = MediaPlayer()
            mp.setDataSource(f.path)
            mp.setOnCompletionListener { stop() }
            mp.prepare()
            mp.start()
            player = mp
            playing = f.path
            onPlayChange?.invoke()
        } catch (e: Exception) {
            stop()
        }
    }

    fun stop() {
        val had = player != null
        try {
            player?.release()
        } catch (e: Exception) {
        }
        player = null
        playing = null
        if (had) onPlayChange?.invoke()
    }

    /** keep the last 60 voice messages */
    private fun cleanup(ctx: Context) {
        all(ctx).drop(60).forEach { it.file.delete() }
    }
}
