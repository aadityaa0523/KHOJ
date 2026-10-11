package app.linkfill.core

import app.linkfill.core.voice.VoiceIntent
import app.linkfill.core.voice.VoiceIntentParser
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceIntentTest {
    private val names = listOf("Maa", "Papa", "Gopi", "Aarav")
    private fun parse(s: String) = VoiceIntentParser.parse(s, names)

    @Test fun englishFindAndLight() {
        assertEquals(VoiceIntent.Find(1), parse("find Papa"))
        assertEquals(VoiceIntent.Find(3), parse("where is Aarav"))
        assertEquals(VoiceIntent.Light(1), parse("light up papa"))
        assertEquals(VoiceIntent.Light(null), parse("turn on my torch"))
    }

    @Test fun sosInManyLanguages() {
        for (s in listOf("SOS", "help me", "मुझे मदद चाहिए", "बचाओ", "உதவி", "காப்பாற்றுங்கள்", "సహాయం", "বাঁচাও")) assertEquals(s, VoiceIntent.Sos, parse(s))
    }

    @Test fun hindiNamesInDevanagariMatchLatinNames() {
        assertEquals(VoiceIntent.Find(1), parse("पापा को खोजो"))
        assertEquals(VoiceIntent.Find(2), parse("गोपी कहाँ है"))
        assertEquals(VoiceIntent.Find(3), parse("आरव कहाँ है"))
        assertEquals(VoiceIntent.Light(2), parse("गोपी की रोशनी करो"))
    }

    @Test fun tamilNamesMatch() {
        assertEquals(VoiceIntent.Find(2), parse("கோபியைத் தேடு"))
        assertEquals(VoiceIntent.Find(0), parse("அம்மா எங்கே"))
        assertEquals(VoiceIntent.Light(null), parse("என் டார்ச் எரி"))
    }

    @Test fun nothingMatchesNothing() {
        assertEquals(VoiceIntent.Unknown, parse("what time is it"))
        assertEquals(VoiceIntent.Unknown, parse("मैं घर में हूँ"))
        assertEquals(VoiceIntent.Unknown, parse(""))
    }

    @Test fun twoPeopleWithTheSameSoundAreNotGuessed() {
        assertEquals(VoiceIntent.Unknown, VoiceIntentParser.parse("find Gopi", listOf("Gopi", "Kopi")))
    }
}
