package app.linkfill.core

import app.linkfill.core.voice.VoiceIntent
import app.linkfill.core.voice.VoiceIntentParser
import app.linkfill.core.voice.VoiceTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceIntentTest {
    private val names = listOf("Maa", "Papa", "Gopi", "Aarav")
    private fun parse(s: String) = VoiceIntentParser.parse(s, names)

    @Test fun englishFindAndLight() {
        assertEquals(VoiceIntent.Find(1), parse("find Papa"))
        assertEquals(VoiceIntent.Where(3), parse("where is Aarav"))
        assertEquals(VoiceIntent.Light(1), parse("light up papa"))
        assertEquals(VoiceIntent.Light(null), parse("turn on my torch"))
    }

    @Test fun sosInManyLanguages() {
        for (s in listOf("SOS", "help me", "मुझे मदद चाहिए", "बचाओ", "உதவி", "காப்பாற்றுங்கள்", "సహాయం", "বাঁচাও")) assertEquals(s, VoiceIntent.Sos, parse(s))
    }

    @Test fun hindiNamesInDevanagariMatchLatinNames() {
        assertEquals(VoiceIntent.Find(1), parse("पापा को खोजो"))
        assertEquals(VoiceIntent.Where(2), parse("गोपी कहाँ है"))
        assertEquals(VoiceIntent.Where(3), parse("आरव कहाँ है"))
        assertEquals(VoiceIntent.Light(2), parse("गोपी की रोशनी करो"))
    }

    @Test fun tamilNamesMatch() {
        assertEquals(VoiceIntent.Find(2), parse("கோபியைத் தேடு"))
        assertEquals(VoiceIntent.Where(0), parse("அம்மா எங்கே"))
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

    @Test fun answersSettingsAndNavigation() {
        assertEquals(VoiceIntent.Where(2), parse("where is Gopi"))
        assertEquals(VoiceIntent.Where(2), parse("गोपी कहाँ है"))
        assertEquals(VoiceIntent.Where(3), parse("ஆரவ் எங்கே"))
        assertEquals(VoiceIntent.Battery(1), parse("papa battery"))
        assertEquals(VoiceIntent.WhoNear, parse("who is nearby"))
        assertEquals(VoiceIntent.HelpMe, parse("what can I say"))
        assertEquals(VoiceIntent.Open(VoiceTarget.GROUP), parse("open group"))
        assertEquals(VoiceIntent.Open(VoiceTarget.SETTINGS), parse("सेटिंग खोलो"))
        assertEquals(VoiceIntent.Circle(50), parse("set circle to 45 metres"))
        assertEquals(VoiceIntent.NightMode(true), parse("night mode"))
        assertEquals(VoiceIntent.NightMode(false), parse("night mode off"))
        assertEquals(VoiceIntent.BatterySaver(true), parse("battery saver"))
        assertEquals(VoiceIntent.BatterySaver(false), parse("battery saver off"))
        assertEquals(VoiceIntent.StopLight, parse("turn off the light"))
        assertEquals(VoiceIntent.StopLight, parse("ஒளியை நிறுத்து"))
        assertEquals(VoiceIntent.Message("I am at gate 4"), parse("tell family I am at gate 4"))
    }
}
