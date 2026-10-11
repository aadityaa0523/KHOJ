package app.linkfill.core.voice

/** What a spoken command means. Indexes point into the list of family names given to [VoiceIntentParser.parse]. */
sealed interface VoiceIntent {
    data object Sos : VoiceIntent
    data class Find(val member: Int) : VoiceIntent
    /** Light up a family member's phone, or this phone when [member] is null. */
    data class Light(val member: Int?) : VoiceIntent
    data object Unknown : VoiceIntent
}

/**
 * A small offline command reader for what the phone's speech recogniser wrote: English, Hindi, Marathi, Tamil, Telugu and
 * Bengali. It is rules and fuzzy matching, not a learned model: word lists for SOS and light-up, and a name matcher that
 * compares the sound skeleton of each word with each family name, so "Gopi", "गोपी" and "கோபி" match each other. Vowels are
 * dropped and look-alike consonants (k/g, t/d, p/b, s/c/j) are merged, because recognisers disagree about exactly those.
 * The caller always shows the result and waits for a tap, so a wrong match costs one tap, never an action.
 */
object VoiceIntentParser {
    private val SOS = listOf(
        "sos", "help", "emergency", "save me",
        "मदद", "बचाओ", "सहायता", "एसओएस", "एमरजेंसी", "मदत", "वाचवा",
        "உதவி", "காப்பாற்று", "ஆபத்து", "எஸ்ஓஎஸ்",
        "సహాయం", "సాయం", "కాపాడండి", "ఎస్ఓఎస్",
        "সাহায্য", "বাঁচাও", "বাঁচান", "এসওএস",
    )
    private val LIGHT = listOf(
        "light", "torch", "flash", "glow", "shine",
        "रोशनी", "टॉर्च", "चमका", "जला", "लाइट", "प्रकाश", "दिवा", "चमकव",
        "ஒளி", "டார்ச்", "ஒளிர", "விளக்கு", "லைட்",
        "వెలుగు", "లైట్", "టార్చ్", "వెలిగించు",
        "আলো", "টর্চ", "লাইট",
    )
    /** Words that mean the same family role in different languages, so "mom", "माँ" and "அம்மா" find a member saved as "Maa". */
    private val ROLES = listOf(
        setOf("maa", "ma", "mom", "mum", "mummy", "mother", "mata", "amma", "माँ", "मां", "माता", "अम्मा", "आई", "அம்மா", "అమ్మ", "মা", "মায়ের"),
        setOf("papa", "pa", "dad", "daddy", "father", "baba", "appa", "nanna", "पापा", "पिता", "बाबा", "அப்பா", "నాన్న", "బాబా", "বাবা"),
    )
    private val SPLIT = Regex("[^\\p{L}\\p{M}]+")

    fun parse(text: String, names: List<String>): VoiceIntent {
        val t = text.lowercase()
        if (SOS.any { t.contains(it) }) return VoiceIntent.Sos
        val tokens = t.split(SPLIT).filter { it.isNotBlank() }
        val member = matchName(tokens, names)
        val light = LIGHT.any { t.contains(it) }
        return when {
            light -> VoiceIntent.Light(member)
            member != null -> VoiceIntent.Find(member)
            else -> VoiceIntent.Unknown
        }
    }

    private fun matchName(tokens: List<String>, names: List<String>): Int? {
        var best = Int.MAX_VALUE
        var winner: Int? = null
        var tie = false
        for ((i, name) in names.withIndex()) {
            val n = name.lowercase().trim()
            val ns = skeleton(n)
            if (ns.isEmpty()) continue
            var score = Int.MAX_VALUE
            for (tok in tokens) {
                val s = when {
                    tok == n -> 0
                    ROLES.any { tok in it && n in it } -> 0
                    ns.length < 2 -> Int.MAX_VALUE // one-letter skeletons ("Maa") match only by exact word or role, "में" must not match
                    else -> skeleton(tok).let { ts ->
                        when {
                            ts == ns -> 0
                            ts.length >= 3 && ns.length >= 3 && edit(ts, ns) <= 1 -> 1
                            ns.length >= 2 && ts.length == ns.length + 1 && ts.startsWith(ns) -> 1 // a case ending: கோபியைத் for கோபி
                            else -> Int.MAX_VALUE
                        }
                    }
                }
                if (s < score) score = s
            }
            if (score == Int.MAX_VALUE) continue
            if (score < best) { best = score; winner = i; tie = false } else if (score == best) tie = true
        }
        return if (tie) null else winner
    }

    // Indic consonants in Unicode order from the first consonant (ka) to ha: Devanagari, Bengali and Telugu share this layout.
    private const val CONS = "kkggnccjjnttddnttddnnppbbmyrrlllvsssh"

    private fun toLatin(cp: Int): String? = when (cp) {
        in 0x0915..0x0939 -> CONS[cp - 0x0915] + "a"
        in 0x0995..0x09B9 -> CONS[cp - 0x0995] + "a"
        in 0x0C15..0x0C39 -> CONS[cp - 0x0C15] + "a"
        0x0B95 -> "ka"; 0x0B99 -> "na"; 0x0B9A -> "sa"; 0x0B9C -> "ja"; 0x0B9E -> "na"; 0x0B9F, 0x0BA4 -> "ta"
        0x0BA3, 0x0BA8, 0x0BA9 -> "na"; 0x0BAA -> "pa"; 0x0BAE -> "ma"; 0x0BAF -> "ya"; 0x0BB0, 0x0BB1 -> "ra"
        0x0BB2, 0x0BB3, 0x0BB4 -> "la"; 0x0BB5 -> "va"; 0x0BB6, 0x0BB7, 0x0BB8 -> "sa"; 0x0BB9 -> "ha"
        in 0x0900..0x0DFF -> "a" // vowels, vowel signs, virama and the rest: only mark that a vowel was there
        else -> null
    }

    /** The sound skeleton of a word: the same for the Latin and the Indic spelling of one name. */
    internal fun skeleton(word: String): String {
        val sb = StringBuilder()
        for (ch in word.lowercase()) {
            val c = toLatin(ch.code)
            if (c != null) sb.append(c) else if (ch.isLetter() && ch.code < 0x250) sb.append(ch)
        }
        var s = sb.toString()
        for ((a, b) in listOf("sh" to "s", "ch" to "s", "th" to "t", "dh" to "t", "kh" to "k", "gh" to "k", "bh" to "p", "ph" to "p", "jh" to "s", "ck" to "k")) s = s.replace(a, b)
        val out = StringBuilder()
        var last = ' '
        for (ch in s) {
            if (ch == last) continue
            last = ch
            when (ch) {
                'a', 'e', 'i', 'o', 'u', 'y', 'h' -> {}
                'b' -> out.append('p'); 'f' -> out.append('p')
                'g', 'q' -> out.append('k')
                'd' -> out.append('t')
                'c', 'j', 'z' -> out.append('s')
                'w' -> out.append('v')
                'x' -> out.append("ks")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    private fun edit(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1).also { it[0] = i }
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }
}
