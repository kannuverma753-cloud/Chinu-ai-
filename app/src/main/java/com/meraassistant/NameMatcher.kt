package com.meraassistant

import android.icu.text.Transliterator

/**
 * Matches a spoken name with a contact / app name.
 * - Emojis and symbols in names are ignored ("Mummy ❤️" -> "Mummy")
 * - Hindi script is compared with English script ("मम्मी" == "Mummy")
 */
object NameMatcher {

    private val toLatinTr: Transliterator? = try {
        Transliterator.getInstance("Any-Latin; Latin-ASCII")
    } catch (e: Exception) {
        null
    }

    /** Removes emojis, symbols and extra spaces. Keeps letters (any script), digits and vowel signs. */
    fun clean(s: String): String =
        s.replace(Regex("[^\\p{L}\\p{N}\\p{M}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** "राहुल ❤️" -> "rahula" */
    fun toLatin(s: String): String {
        val c = clean(s)
        val t = toLatinTr?.transliterate(c) ?: c
        return t.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** Sound key: "mummy" and "mammi" both -> "mm" */
    fun soundKey(s: String): String {
        var x = toLatin(s).replace(" ", "")
        val pairs = listOf(
            "ph" to "f", "bh" to "b", "kh" to "k", "gh" to "g", "th" to "t",
            "dh" to "d", "sh" to "s", "ch" to "c", "ck" to "k",
            "w" to "v", "y" to "i", "z" to "j", "q" to "k"
        )
        for ((a, b) in pairs) x = x.replace(a, b)
        val collapsed = collapse(x)
        if (collapsed.isEmpty()) return ""
        val rest = collapsed.substring(1).filter { it !in "aeiou" }
        return collapse(collapsed.substring(0, 1) + rest)
    }

    private fun collapse(s: String): String {
        val sb = StringBuilder()
        for (c in s) if (sb.isEmpty() || sb[sb.length - 1] != c) sb.append(c)
        return sb.toString()
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return 1.0 - dp[b.length].toDouble() / maxOf(a.length, b.length)
    }

    private fun wordScore(a: String, b: String): Double {
        if (a == b) return 1.0
        val ka = soundKey(a)
        val kb = soundKey(b)
        val key = if (ka.isNotEmpty() && ka == kb) 0.9 else similarity(ka, kb) * 0.8
        return maxOf(key, similarity(a, b))
    }

    /**
     * 1.0  = exact same name
     * 0.95 = every spoken word matches a word of the name
     * lower = fuzzy
     */
    fun score(spoken: String, candidate: String): Double {
        val s = toLatin(spoken)
        val c = toLatin(candidate)
        if (s.isEmpty() || c.isEmpty()) return 0.0
        if (s == c) return 1.0
        val sw = s.split(" ").filter { it.isNotBlank() }
        val cw = c.split(" ").filter { it.isNotBlank() }
        // har bola gaya shabd naam ke kisi shabd se mile
        val perWord = sw.map { w -> cw.maxOf { wordScore(w, it) } }
        val allWords = perWord.minOrNull() ?: 0.0
        val tokenScore = if (allWords >= 0.85) {
            // jitne zyada shabd naam ke match hue, utna better
            0.88 + 0.07 * (sw.size.toDouble() / cw.size).coerceAtMost(1.0)
        } else allWords * 0.9
        val whole = wordScore(s.replace(" ", ""), c.replace(" ", ""))
        return maxOf(tokenScore, whole * 0.95)
    }

    fun <T> best(spoken: String, items: List<T>, threshold: Double = 0.6, label: (T) -> String): T? {
        var bestItem: T? = null
        var bestScore = 0.0
        for (item in items) {
            val sc = score(spoken, label(item))
            if (sc > bestScore) {
                bestScore = sc
                bestItem = item
            }
        }
        return if (bestScore >= threshold) bestItem else null
    }
}
