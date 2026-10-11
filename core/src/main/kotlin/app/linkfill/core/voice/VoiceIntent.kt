package app.linkfill.core.voice

/** Where a spoken "open ..." should go. */
enum class VoiceTarget { RADAR, GROUP, SETTINGS, BANDS, REWIND }

/** What a spoken command means. Indexes point into the list of family names given to [VoiceIntentParser.parse]. */
sealed interface VoiceIntent {
    data object Sos : VoiceIntent
    data class Find(val member: Int) : VoiceIntent
    /** Light up a family member's phone, or this phone when [member] is null. */
    data class Light(val member: Int?) : VoiceIntent
    data object StopLight : VoiceIntent
    /** "Where is X": answered aloud with distance and direction, nothing else happens. */
    data class Where(val member: Int) : VoiceIntent
    data object WhoNear : VoiceIntent
    data class Battery(val member: Int) : VoiceIntent
    /** "Tell family ...": [text] is what to send. */
    data class Message(val text: String) : VoiceIntent
    data class Open(val target: VoiceTarget) : VoiceIntent
    data class Circle(val metres: Int) : VoiceIntent
    data class NightMode(val on: Boolean) : VoiceIntent
    data class BatterySaver(val on: Boolean) : VoiceIntent
    data object HelpMe : VoiceIntent
    data object Unknown : VoiceIntent
}

/**
 * A small offline command reader for what the phone's speech recogniser wrote: English, Hindi, Marathi, Tamil, Telugu and
 * Bengali. It is rules and fuzzy matching, not a learned model: word lists for each command, and a name matcher that
 * compares the sound skeleton of each word with each family name, so "Gopi", "गोपी" and "கோபி" match each other. Vowels are
 * dropped and look-alike consonants (k/g, t/d, p/b, s/c/j) are merged, because recognisers disagree about exactly those.
 * The caller shows the result and waits for a tap before anything that sends, lights, or changes a setting.
 */
object VoiceIntentParser {
    private val HELP_ME = listOf("what can i say", "what can you do", "how do i use", "क्या कर सकते", "क्या बोल", "என்ன சொல்ல", "என்ன செய்ய முடியும்", "எப்படி பயன்படுத்த")
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
    private val STOP = listOf("stop", "turn off", "switch off", "बंद करो", "बंद कर", "रुको", "நிறுத்து", "அணை", "ఆపు", "থামাও")
    private val OFF = listOf("off", "disable", "बंद", "நிறுத்து", "அணை", "ఆపు", "বন্ধ")
    private val WHERE = listOf("where", "how far", "distance", "कहाँ", "कहां", "कितनी दूर", "कुठे", "எங்கே", "எவ்வளவு தூரம்", "ఎక్కడ", "কোথায়")
    private val BATTERY = listOf("battery", "charge", "बैटरी", "பேட்டரி", "ఛార్జ్", "ব্যাটারি")
    private val WHO_NEAR = listOf("who is near", "who's near", "who is nearby", "who's nearby", "nearby", "who is here", "आसपास", "पास में कौन", "कौन कौन", "அருகில் யார்", "யார் யார்", "அருகில் உள்ளவர்கள்")
    private val NIGHT = listOf("night mode", "dark mode", "डार्क मोड", "रात मोड", "இரவு", "டார்க்")
    private val DAY = listOf("day mode", "light mode", "bright mode", "दिन मोड", "லைட் மோட்", "பகல்")
    private val SAVER = listOf("battery saver", "power saving", "बैटरी सेवर", "बैटरी बचत", "பேட்டரி சேமிப்பு", "பேட்டரி சேவர்")
    private val CIRCLE = listOf("circle", "range", "सर्कल", "दायरा", "वृत्त", "வட்டம்", "எல்லை")
    private val OPEN = listOf("open", "show", "go to", "take me", "खोल", "दिखा", "திற", "காட்டு", "போ")
    private val MESSAGE = listOf("message ", "tell family ", "tell everyone ", "send message ", "संदेश ", "परिवार को बताओ ", "செய்தி ", "குடும்பத்திற்கு சொல் ")
    private val TARGETS = listOf(
        VoiceTarget.SETTINGS to listOf("settings", "सेटिंग", "அமைப்பு"),
        VoiceTarget.REWIND to listOf("rewind", "camera", "रिवाइंड", "கேமரா"),
        VoiceTarget.BANDS to listOf("wristband", "band", "बैंड", "रिस्टबैंड", "பேண்ட்", "கைப்பட்டை"),
        VoiceTarget.GROUP to listOf("group", "family", "ग्रुप", "परिवार", "குழு", "குடும்ப"),
        VoiceTarget.RADAR to listOf("radar", "home", "रडार", "ரேடார்"),
    )
    /** Words that mean the same family role in different languages, so "mom", "माँ" and "அம்மா" find a member saved as "Maa". */
    private val ROLES = listOf(
        setOf("maa", "ma", "mom", "mum", "mummy", "mother", "mata", "amma", "माँ", "मां", "माता", "अम्मा", "आई", "அம்மா", "అమ్మ", "মা", "মায়ের"),
        setOf("papa", "pa", "dad", "daddy", "father", "baba", "appa", "nanna", "पापा", "पिता", "बाबा", "அப்பா", "నాన్న", "బాబా", "বাবা"),
    )
    private val SPLIT = Regex("[^\\p{L}\\p{M}]+")
    private val CIRCLES = listOf(30, 50, 100, 200)

    fun parse(text: String, names: List<String>): VoiceIntent {
        val t = text.lowercase().trim()
        if (t.isEmpty()) return VoiceIntent.Unknown
        fun has(words: List<String>) = words.any { t.contains(it) }
        if (has(HELP_ME)) return VoiceIntent.HelpMe
        if (has(SOS)) return VoiceIntent.Sos
        MESSAGE.firstOrNull { t.startsWith(it) }?.let { trigger ->
            val rest = text.trim().substring(trigger.length).trim()
            if (rest.length >= 2) return VoiceIntent.Message(rest)
        }
        if (has(SAVER)) return VoiceIntent.BatterySaver(!has(OFF))
        if (has(DAY)) return VoiceIntent.NightMode(false)
        if (has(NIGHT)) return VoiceIntent.NightMode(!has(OFF))
        if (has(CIRCLE)) Regex("\\d+").find(t)?.value?.toIntOrNull()?.let { n -> return VoiceIntent.Circle(CIRCLES.minBy { kotlin.math.abs(it - n) }) }
        val tokens = t.split(SPLIT).filter { it.isNotBlank() }
        val member = matchName(tokens, names)
        if (has(OPEN)) TARGETS.firstOrNull { (_, words) -> words.any { t.contains(it) } }?.let { return VoiceIntent.Open(it.first) }
        val light = has(LIGHT)
        if (light && has(STOP)) return VoiceIntent.StopLight
        if (light) return VoiceIntent.Light(member)
        if (has(WHO_NEAR)) return VoiceIntent.WhoNear
        if (member != null) return when {
            has(BATTERY) -> VoiceIntent.Battery(member)
            has(WHERE) -> VoiceIntent.Where(member)
            else -> VoiceIntent.Find(member)
        }
        if (has(STOP)) return VoiceIntent.StopLight
        return VoiceIntent.Unknown
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
