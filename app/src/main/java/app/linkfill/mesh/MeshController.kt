package app.linkfill.mesh

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import app.linkfill.LinkFillApp
import app.linkfill.R
import app.linkfill.core.find.HeadingFix
import app.linkfill.find.FindMemberSession
import app.linkfill.find.FindUi
import app.linkfill.find.HeadingProvider
import app.linkfill.core.estimate.GapEstimator
import app.linkfill.core.mesh.Band
import app.linkfill.core.mesh.CombineMode
import app.linkfill.core.mesh.CrewKey
import app.linkfill.core.mesh.FoundReport
import app.linkfill.core.mesh.LinkFeatures
import app.linkfill.core.mesh.LinkMode
import app.linkfill.core.mesh.MeshConfig
import app.linkfill.core.mesh.MeshListener
import app.linkfill.core.mesh.MeshMessage
import app.linkfill.core.mesh.MeshPacket
import app.linkfill.core.mesh.MeshRouter
import app.linkfill.core.mesh.MeshType
import app.linkfill.core.mesh.PublicMessage
import app.linkfill.core.mesh.Role
import app.linkfill.core.mesh.SosKind
import app.linkfill.core.mesh.TransportKind
import app.linkfill.core.mesh.TransportSelector
import app.linkfill.core.protocol.LocationFix
import app.linkfill.core.security.PublicCrypto
import app.linkfill.core.sim.LinkLabeler
import app.linkfill.location.LocationProvider
import app.linkfill.session.Phase
import app.linkfill.service.MeshService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.util.Base64
import kotlin.random.Random
import kotlin.random.asKotlinRandom

data class CrewMember(
    val originId: Int,
    val name: String,
    val lat: Double,
    val lon: Double,
    val accuracyM: Int,
    val battery: Int,
    val heardAt: Long,
    val hops: Int,
    val rssi: Int,
    /** This member allows crew members to start a precise-finding session with them. */
    val findable: Boolean = false,
)

data class FeedItem(val at: Long, val fromName: String, val text: String, val kind: String, val hops: Int, val mine: Boolean, val packetId: Int = 0, val ackedBy: List<String> = emptyList())

data class NeighborView(val id: Int, val rssi: Int, val pdr2s: Double, val risk: Double?)

/** One radio in the multi-transport view. */
data class TransportView(
    val kind: TransportKind,
    val supported: Boolean,
    val enabled: Boolean,
    val up: Boolean,
    val peers: Int,
    val quality: Double,
    val sent: Int,
    val delivered: Int,
    val failed: Int,
    val received: Int,
    val note: String,
)

/** Someone who asked for help, as seen by volunteers and police. */
data class SosAlert(val originId: Int, val packetId: Int, val kind: SosKind, val lat: Double, val lon: Double, val label: String, val at: Long, val responders: List<String>, val iResponded: Boolean)

/** A "found" report this volunteer sent, and whether the family's phone has received it. */
data class SentReport(val reportId: Long, val packetId: Int, val name: String, val at: Long, val familyReceived: Boolean)

data class Notice(val at: Long, val lat: Double, val lon: Double, val text: String)

/** The family's current meeting point (the most recent rally, from anyone in the crew or from me). */
data class MeetPoint(val lat: Double, val lon: Double, val label: String, val from: String, val at: Long)

/** Someone in the group asked this phone to light up: who, which of LIGHT_TORCH/SCREEN/SOUND, and when it switches itself off. */
data class LitState(val by: String, val modes: Int, val endsAt: Long)

/** Big-banner alerts on the home screen. */
data class HomeAlert(val key: String, val title: String, val body: String, val kind: String, val lat: Double? = null, val lon: Double? = null, val at: Long)

data class MeshUiState(
    val running: Boolean = false,
    val name: String = "",
    val crewCode: String? = null,
    val error: String? = null,
    val extendedSupported: Boolean = false,
    val codedSupported: Boolean = false,
    val maxAdvertisingBytes: Int = 0,
    val coded: Boolean = false,
    val mode: LinkMode = LinkMode.NORMAL,
    val risk: Double? = null,
    val predictorStatus: String = "",
    val members: List<CrewMember> = emptyList(),
    val feed: List<FeedItem> = emptyList(),
    val neighbors: List<NeighborView> = emptyList(),
    val myFix: LocationFix? = null,
    val stats: String = "",
    val logRows: Int = 0,
    val logPath: String = "",
    val transports: List<TransportView> = emptyList(),
    val combineMode: CombineMode = CombineMode.AUTO,
    val relayUrl: String = "",
    val lastChoice: String = "",
    val combinedEstimate: Double? = null,
    val batteryUnrestricted: Boolean = true,
    // Safety and roles
    val role: Role = Role.MEMBER,
    val policeUnlocked: Boolean = false,
    val bands: List<Band> = emptyList(),
    val sosBoard: List<SosAlert> = emptyList(),
    val sentReports: List<SentReport> = emptyList(),
    val notices: List<Notice> = emptyList(),
    val officials: List<Notice> = emptyList(),
    val mySosResponders: List<String> = emptyList(),
    val pendant: Boolean = false,
    val countdown: SosKind? = null,
    val countdownEndsAt: Long = 0,
    val rewindFrames: Int = 0,
    val rewindError: String? = null,
    val setupDone: Boolean = false,
    val headingDeg: Float? = null,
    val beingFoundBy: String? = null,
    /** Crew members looking for me right now (node id to name): the two-way navigator offers to find them back. */
    val seekers: Map<Int, String> = emptyMap(),
    val trustAngle: Boolean = false,
    val meetPoint: MeetPoint? = null,
    val alerts: List<HomeAlert> = emptyList(),
    val lastSosAt: Long = 0,
    /** The SOS siren is sounding on this phone. */
    val sirenOn: Boolean = false,
    /** Family may light this phone up (torch, bright screen, sound). On by default; only the crew can ask, and the owner can stop it at once. */
    val allowLight: Boolean = true,
    val lit: LitState? = null,
    /** Alarm when a group member is farther than this (GPS error allowed for). */
    val circleM: Int = app.linkfill.core.find.Circle.DEFAULT_M,
    /** User asked for battery-saver membership; [lowPower] is whether it is in effect now (also automatic on low battery). */
    val batterySaver: Boolean = false,
    val lowPower: Boolean = false,
    /** My last SOS went out without a position; it is re-sent as soon as GPS gives one. */
    val sosWaitingForGps: Boolean = false,
    val now: Long = System.currentTimeMillis(),
)

/**
 * KHOJ on this phone: runs [MeshRouter] over several radios, sends a position beacon every few seconds,
 * keeps the crew list and feed, handles wristbands, volunteer and police roles, the pendant safety
 * monitor (fall / panic shake) and the Rewind camera buffer.
 */
class MeshController(private val context: Context, private val scope: CoroutineScope) : MeshListener {
    private val prefs = context.getSharedPreferences("mesh", Context.MODE_PRIVATE)
    private val random: Random = SecureRandom().asKotlinRandom()
    private val radio = BleMeshRadio(context, ::onFrame, { msg -> _state.update { it.copy(error = msg) } }, { bytes, rssi -> onOtherFrame(bytes, TransportKind.GATT, rssi) })
    private val aware = WifiAwareTransport(context, { onOtherFrame(it, TransportKind.WIFI_AWARE, -60) }) { msg -> _state.update { it.copy(error = msg) } }
    private var nearby: NearbyMeshTransport? = null
    /** Point-to-point Nearby link for a Find pair; can upgrade to Wi-Fi Direct. The cluster link pauses while it runs. */
    private var pair: NearbyPairLink? = null
    /** Nearby is expensive (measured: about 3x the Bluetooth stack's CPU on an iQOO SM8850), so members run it on demand. */
    private var nearbyUntil = 0L
    private var lastNeighborMs = 0L
    private var codedBurstUntilMs = 0L
    private var beaconPeriodMs = 3_000L
    private var lastBeaconSentMs = 0L
    private var lastBeaconFix: LocationFix? = null
    private var lastNeighborCount = 0
    private val internet = InternetRelay(context, scope) { onOtherFrame(it, TransportKind.INTERNET, -100) }
    private val bleHealth = app.linkfill.core.mesh.TransportHealth(TransportKind.BLE)
    // Default radios: Wi-Fi Aware where the phone has it; otherwise Nearby, which reaches Wi-Fi Direct/hotspot underneath.
    private val enabled = ((prefs.getStringSet("transports", null) ?: setOf("BLE", "INTERNET", if (aware.supported) "WIFI_AWARE" else "NEARBY")) + "NEARBY")
        .mapNotNull { n -> TransportKind.entries.firstOrNull { it.name == n } }.toMutableSet()
    private val location = LocationProvider(context)
    private val heading = HeadingProvider(context)
    private val findHost: FindMemberSession.Host = object : FindMemberSession.Host {
        override val nodeId: Int get() = router?.nodeId ?: 0
        override fun crew() = router?.crew
        override fun myFix() = _state.value.myFix
        override fun member(id: Int) = members[id]
        override fun heading() = heading.latest
        override fun declinationKnown() = heading.declinationKnown
        override fun sendTunnel(to: Int, frame: ByteArray) = pair?.sendTunnel(to, frame) == true || nearby?.sendTunnel(to, frame) == true
        override fun hasDirectLink(to: Int) = pair?.hasLinkTo(to) == true || nearby?.hasLinkTo(to) == true
        override fun linkMedium(to: Int): String? = if (pair?.hasLinkTo(to) == true) pair?.medium else if (nearby?.hasLinkTo(to) == true) "Bluetooth (mesh link)" else null
        override fun keepNearby(on: Boolean) { findKeepsNearby = on; applyTransports() }
        // Same group = consent; the person being found is told and can pause it (pauseBeingFound).
        override fun isFindable(): Boolean = find.rangeable() && System.currentTimeMillis() >= findPausedUntil
        override fun beingFound(by: String?) { _state.update { it.copy(beingFoundBy = by) }; if (by != null) notify(context.getString(R.string.being_found), context.getString(R.string.being_found_by, by)) }
        override fun vibrate() {
            // A haptic cue must never be able to take the app down.
            try { context.getSystemService(android.os.Vibrator::class.java)?.vibrate(android.os.VibrationEffect.createOneShot(60, android.os.VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: SecurityException) { }
        }
        override fun trustAngle() = _state.value.trustAngle
    }
    private var findKeepsNearby = false
    private var findPausedUntil = 0L
    val find: FindMemberSession = FindMemberSession(context, findHost)
    val findUi: StateFlow<FindUi?> get() = find.ui
    private var meetPoint: MeetPoint? = null
    private val alerts = LinkedHashMap<String, HomeAlert>()
    private val safety = SafetyMonitor(context, { kind, endsAt -> _state.update { it.copy(countdown = kind, countdownEndsAt = endsAt) }; refreshService() }, { kind -> sendSos(kind) })
    val rewind = RewindCamera(context, { _state.value.myFix })
    private val siren = Siren(context)
    private var pendingSos: SosKind? = null
    /** After an SOS the phone stays at full power for 10 minutes, whatever the battery saver says. */
    private var fullPowerUntil = 0L
    private var router: MeshRouter? = null
    private var labeler: LinkLabeler? = null
    private var loop: Job? = null
    private val members = LinkedHashMap<Int, CrewMember>()
    private val lostNotified = mutableSetOf<Int>()
    private val outsideCircle = mutableSetOf<Int>()
    /** node id of someone looking for me -> when their latest LOOKING arrived. */
    private val seekers = LinkedHashMap<Int, Long>()
    private val feed = ArrayDeque<FeedItem>()
    private val sosBoard = LinkedHashMap<Long, SosAlert>()
    private val sentReports = ArrayList<SentReport>()
    private val notices = ArrayDeque<Notice>()
    private val officials = ArrayDeque<Notice>()
    private val myPublicSos = mutableSetOf<Int>()
    private val mySosResponders = ArrayList<String>()
    private val authority: PublicKey? = MeshController::class.java.getResourceAsStream("/app/linkfill/core/mesh/authority_public.txt")
        ?.bufferedReader()?.use { it.readText().trim() }?.let { PublicCrypto.decompress(Base64.getDecoder().decode(it)) }
    private val logFile = File(context.getExternalFilesDir(null) ?: context.filesDir, "link_log.csv")
    private var logRows = 0
    private var startedAt = 0L

    // shortcut: crew code, wristband keys and the police key sit in app-private preferences; move them to the Android Keystore before real deployment.
    private val _state = MutableStateFlow(
        MeshUiState(
            name = prefs.getString("name", null) ?: android.os.Build.MODEL,
            crewCode = prefs.getString("crew", null),
            predictorStatus = LINK_AI_PAUSED,
            logPath = logFile.absolutePath,
            combineMode = prefs.getString("combine", null)?.let { m -> CombineMode.entries.firstOrNull { it.name == m } } ?: CombineMode.AUTO,
            relayUrl = prefs.getString("relay", "") ?: "",
            role = Role.of(prefs.getInt("role", 0)),
            pendant = prefs.getBoolean("pendant", true),
            setupDone = prefs.getBoolean("setup_done", false),
            trustAngle = prefs.getBoolean("trust_angle", false),
            circleM = prefs.getInt("circle_m", app.linkfill.core.find.Circle.DEFAULT_M),
            batterySaver = prefs.getBoolean("battery_saver", false),
            allowLight = prefs.getBoolean("allow_light", true),
        ),
    )
    val state: StateFlow<MeshUiState> = _state.asStateFlow()

    init {
        _state.update {
            it.copy(
                extendedSupported = radio.extendedSupported, codedSupported = radio.codedSupported, maxAdvertisingBytes = radio.maxAdvertisingBytes,
                bands = loadBands().map { b -> b.first }, policeUnlocked = policeKey() != null,
            )
        }
        publishTransports()
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(ALERTS, "KHOJ alerts", NotificationManager.IMPORTANCE_HIGH))
    }

    // ---------------------------------------------------------------- settings

    /** Turns one radio on or off for the mesh. Bluetooth stays on: it is how phones discover each other. */
    fun setTransport(kind: TransportKind, on: Boolean) {
        if (kind == TransportKind.BLE || kind == TransportKind.NEARBY) return // Find's direct link needs Nearby
        if (on) enabled += kind else enabled -= kind
        prefs.edit().putStringSet("transports", enabled.map { it.name }.toSet()).apply()
        if (router != null) applyTransports()
        publishTransports()
    }

    fun setCombineMode(mode: CombineMode) { prefs.edit().putString("combine", mode.name).apply(); _state.update { it.copy(combineMode = mode) } }

    fun setRelayUrl(url: String) {
        prefs.edit().putString("relay", url.trim()).apply()
        _state.update { it.copy(relayUrl = url.trim()) }
        if (router != null) applyTransports()
    }

    fun setName(name: String) { prefs.edit().putString("name", name.take(12)).apply(); _state.update { it.copy(name = name.take(12)) } }

    fun createCrew(): String { val k = CrewKey.create(); saveCrew(k.code); return k.code }

    fun completeSetup() { prefs.edit().putBoolean("setup_done", true).apply(); _state.update { it.copy(setupDone = true) } }

    /** Family QR shown on the setup and settings screens; other members scan it instead of typing the code. */
    fun crewQr(): String? = _state.value.crewCode?.let { CREW_PREFIX + it }

    fun joinFromQr(text: String): Boolean = text.startsWith(CREW_PREFIX) && joinCrew(text.removePrefix(CREW_PREFIX))

    fun dismissAlert(key: String) { alerts.remove(key); _state.update { it.copy(alerts = alerts.values.sortedByDescending { a -> a.at }) } }

    /** Shares my current position as the family meeting point. */
    fun meetHere() {
        sendRally(context.getString(R.string.meet_point), 0)
        _state.value.myFix?.let { f -> meetPoint = MeetPoint(f.latitude, f.longitude, context.getString(R.string.meet_point), context.getString(R.string.you), System.currentTimeMillis()) }
    }

    fun joinCrew(code: String): Boolean { val k = CrewKey.fromCode(code) ?: return false; saveCrew(k.code); return true }

    fun leaveCrew() { stop(); prefs.edit().remove("crew").remove("bands").apply(); members.clear(); feed.clear(); _state.update { it.copy(crewCode = null, members = emptyList(), feed = emptyList(), bands = emptyList()) } }

    private fun saveCrew(code: String) { prefs.edit().putString("crew", code).apply(); _state.update { it.copy(crewCode = code, error = null) } }

    /** Police needs the authority key; volunteers do not (anyone can volunteer to relay and scan bands). */
    fun setRole(role: Role) {
        if (role == Role.POLICE && policeKey() == null) return _state.update { it.copy(error = "Scan the police key QR to unlock police mode.") }
        prefs.edit().putInt("role", role.code).apply()
        _state.update { it.copy(role = role, error = null) }
        if (router != null) applyTransports()
    }

    /** Accepts the police key QR only if it matches the authority key bundled in every app. */
    fun unlockPolice(qr: String): Boolean {
        if (!qr.startsWith(POLICE_PREFIX)) return false
        val bytes = try { Base64.getUrlDecoder().decode(qr.removePrefix(POLICE_PREFIX)) } catch (_: IllegalArgumentException) { return false }
        val key = PublicCrypto.privateFromPkcs8(bytes) ?: return false
        val auth = authority ?: return false
        if (!PublicCrypto.verify(auth, "check".toByteArray(), PublicCrypto.sign(key, "check".toByteArray()))) return false
        prefs.edit().putString("police", Base64.getEncoder().encodeToString(bytes)).apply()
        _state.update { it.copy(policeUnlocked = true, error = null) }
        setRole(Role.POLICE)
        return true
    }

    private fun policeKey(): PrivateKey? = prefs.getString("police", null)?.let { PublicCrypto.privateFromPkcs8(Base64.getDecoder().decode(it)) }

    /** Pendant mode: fall and panic-shake detection, plus the Rewind camera buffer (if camera permission is granted). */
    fun setPendant(on: Boolean) {
        prefs.edit().putBoolean("pendant", on).apply()
        _state.update { it.copy(pendant = on) }
        if (router == null) return
        if (on) startPendant() else { safety.stop(); rewind.stop() }
        refreshService()
    }

    private fun cameraAllowed() = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startPendant() {
        safety.start()
        if (cameraAllowed()) rewind.start() else _state.update { it.copy(rewindError = "Allow the camera to use Rewind.") }
    }

    fun cancelCountdown() = safety.cancel()

    // ---------------------------------------------------------------- Find Member

    /** Starts looking for a crew member. Needs the mesh running; the Find screen shows the resulting state. */
    fun startFind(memberId: Int) {
        val m = members[memberId] ?: return
        if (router == null) return
        find.start(memberId, m.name, System.currentTimeMillis())
        applyTransports()
    }

    fun stopFind() = find.stop(System.currentTimeMillis())

    /** The person being found taps "Pause" on the notice: ends the running session and declines new ones for 30 minutes. */
    fun pauseBeingFound() {
        val now = System.currentTimeMillis()
        findPausedUntil = now + 30 * 60_000
        find.findableChanged(false, now)
    }

    /** Developer switch: use the ranging module's azimuth for the arrow. Leave off until units and sign are validated (docs/FIND_MEMBER_TEST_PLAN.md). */
    fun setTrustAngle(on: Boolean) { prefs.edit().putBoolean("trust_angle", on).apply(); _state.update { it.copy(trustAngle = on) } }

    fun setGroundTruth(m: Double?) = find.setGroundTruth(m)

    fun setBatterySaver(on: Boolean) { prefs.edit().putBoolean("battery_saver", on).apply(); _state.update { it.copy(batterySaver = on) }; router?.let { updatePower(it, System.currentTimeMillis()) } }

    /**
     * Low-power member: chosen in Settings, or automatic at 15 % battery when not charging. Helpers never go low power
     * (they are the backbone), and an SOS forces full power for 10 minutes.
     */
    private fun updatePower(r: MeshRouter, now: Long) {
        val bm = context.getSystemService(BatteryManager::class.java)
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        val low = _state.value.role == Role.MEMBER && now >= fullPowerUntil &&
            (_state.value.batterySaver || level in 1..15 && bm?.isCharging == false)
        if (low == r.lowPower) return
        r.lowPower = low
        radio.setSlowAdvertising(low)
        BleMeshRadio.log("low-power member: $low (battery $level%)")
        _state.update { it.copy(lowPower = low) }
    }

    fun setCircle(m: Int) { prefs.edit().putInt("circle_m", m).apply(); _state.update { it.copy(circleM = m) } }

    // ---------------------------------------------------------------- wristbands (family side)

    /** Creates a band for a child or elder. Its private key stays on this phone; only this phone can read "found" reports. */
    fun createBand(name: String, age: Int, phone: String, meetingPoint: String): Band? {
        val crew = _state.value.crewCode?.let(CrewKey::fromCode) ?: return null.also { _state.update { it.copy(error = "Create or join a crew first.") } }
        val kp = PublicCrypto.newKeyPair()
        val band = Band(crew.crewId, random.nextInt(), PublicCrypto.compress(kp.public), name.trim(), age, phone, meetingPoint.trim())
        val entries = (prefs.getStringSet("bands", emptySet()) ?: emptySet()) + (band.toQrText() + "|" + Base64.getEncoder().encodeToString(kp.private.encoded))
        prefs.edit().putStringSet("bands", entries).apply()
        _state.update { it.copy(bands = loadBands().map { b -> b.first }) }
        return band
    }

    fun deleteBand(bandId: Int) {
        val keep = (prefs.getStringSet("bands", emptySet()) ?: emptySet()).filter { Band.parse(it.substringBefore('|'))?.bandId != bandId }.toSet()
        prefs.edit().putStringSet("bands", keep).apply()
        _state.update { it.copy(bands = loadBands().map { b -> b.first }) }
    }

    private fun loadBands(): List<Pair<Band, PrivateKey>> = (prefs.getStringSet("bands", emptySet()) ?: emptySet()).mapNotNull { e ->
        val b = Band.parse(e.substringBefore('|')) ?: return@mapNotNull null
        val k = PublicCrypto.privateFromPkcs8(Base64.getDecoder().decode(e.substringAfter('|'))) ?: return@mapNotNull null
        b to k
    }.sortedBy { it.first.name }

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        if (router != null) return
        val role = _state.value.role
        val crew = _state.value.crewCode?.let(CrewKey::fromCode)
        if (crew == null && role == Role.MEMBER) return _state.update { it.copy(error = "Create or join a crew first.") }
        val id = random.nextInt()
        val err = radio.start(id)
        if (err != null) return _state.update { it.copy(error = err) }
        BleMeshRadio.log("capabilities: ${radio.capabilities()} wifiAware=${aware.supported} " +
            "wifiDirect=${context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)} " +
            "channelSounding=${context.packageManager.hasSystemFeature("android.hardware.bluetooth_le.channel_sounding")} " +
            "batteryUnrestricted=${batteryUnrestricted()} role=$role")
        // A fresh node id per run keeps the AES-GCM nonces (node id, packet counter) unique across restarts.
        // Link AI is set aside: the bundled model was trained on 250 ms-dwell logs, and its inputs mean something else under
        // the 1 s dwell. Risk falls back to 1 - pdr(2 s) until it is retrained on link_log.csv rows from this build.
        val r = MeshRouter(id, crew, MeshConfig(codedSupported = radio.codedSupported), random, this, predictor = null)
        router = r
        startedAt = System.currentTimeMillis()
        labeler = LinkLabeler(r.links)
        val myKey = crew
        // With mobile data: upload our crew's packets and every public message (found reports, public SOS, official alerts).
        // Crew headers carry a rotating tag, not the crew id, so membership is checked with the key and uploads go under our
        // stable crew id. Other crews' packets are not uploaded: their crew id cannot be recovered from the tag.
        r.bridge = { frame, p ->
            val mine = myKey != null && p.type !in PublicMessage.TYPES && p.type != MeshType.SUMMARY && myKey.matchesTag(p.crewTag, p.originTimeS)
            if (p.type != MeshType.NEIGHBOR && (mine || p.type in PublicMessage.TYPES)) internet.upload(frame, if (mine) myKey!!.crewId else p.crewId)
        }
        r.rssiObserver = { relayId, rssi, coded, now -> find.onBluetoothFrame(relayId, rssi, coded, now) }
        pair = NearbyPairLink(context, NearbyMeshTransport.nameFor(r.nodeId), { onOtherFrame(it, TransportKind.NEARBY, -60) },
            { frame -> find.onTunnel(frame, System.currentTimeMillis()) }, BleMeshRadio::log)
        nearby = NearbyMeshTransport(context, NearbyMeshTransport.nameFor(r.nodeId), { onOtherFrame(it, TransportKind.NEARBY, -60) }, { msg -> _state.update { it.copy(error = msg) } },
            onTunnel = { frame -> find.onTunnel(frame, System.currentTimeMillis()) },
            permissionHint = context.getString(R.string.nearby_needs_location),
            onRecovered = { _state.update { s -> if (s.error?.startsWith("Nearby") == true || s.error == context.getString(R.string.nearby_needs_location)) s.copy(error = null) else s } })
        bleHealth.up = true
        applyTransports()
        startLocation()
        heading.onHeading = { h -> _state.update { it.copy(headingDeg = h.trueDeg.toFloat()) }; find.evaluate(System.currentTimeMillis()) }
        heading.start()
        find.refreshCapabilities()
        if (_state.value.pendant) startPendant()
        refreshService()
        _state.update { it.copy(running = true, error = null) }
        loop = scope.launch {
            var n = 0L
            var lastFrameSentMs = 0L
            var lastUrgentPreemptMs = 0L
            while (isActive) {
                val now = System.currentTimeMillis()

                // Phase 3: LE Coded emergency burst control
                val lowDensity = r.links.neighbors.values.count { now - it.lastRxMs <= 10_000 } < 10
                val urgentDue = r.hasUrgentDue(now)
                val shouldBurstCoded = radio.codedSupported && (
                    (urgentDue && r.is1mHealthPoor(now) && lowDensity) ||
                    r.mode == LinkMode.HIGH
                )
                if (shouldBurstCoded) {
                    if (codedBurstUntilMs <= now) {
                        codedBurstUntilMs = now + 10_000L
                        radio.setCodedAdvertising(true)
                    }
                } else if (now >= codedBurstUntilMs) {
                    radio.setCodedAdvertising(false)
                }

                // Phase 3: Frame dwell (1000 ms routine, 3000 ms low-power, urgent pre-empt >= 250 ms)
                val dwell = if (r.lowPower) r.config.lowPowerDwellMs else r.config.dwellMs
                val dwellExpired = (now - lastFrameSentMs) >= dwell
                val urgentPreempt = urgentDue && ((now - lastFrameSentMs) >= 250) && ((now - lastUrgentPreemptMs) >= 250)

                if (dwellExpired || urgentPreempt) {
                    r.nextFrame(now, allowFiller = dwellExpired)?.let { frame ->
                        transmit(r, frame)
                        lastFrameSentMs = now
                        if (urgentPreempt) lastUrgentPreemptMs = now
                    }
                }
                r.tick(now)
                safety.tick(now)
                find.evaluate(now)
                if (rewind.running) rewind.tick(now)
                if (n % 20 == 0L) applyTransports() // every 5 s: follow Wi-Fi, data and direct-sharing changes
                // Phase 4: Trickle-like beacons (binary exponential growth up to density cap, resets on event)
                val currentDensity = r.links.density(now)
                val densityPolicy = r.config.densityPolicy(currentDensity)
                // Lost-contact fires after 60 s of silence, so no beacon period may exceed 20 s (3 chances); a low-power member
                // keeps its 15 s period.
                val densityCap = if (r.lowPower) 15_000L else densityPolicy.beaconPeriodMs

                val activeNeighbors = r.links.neighbors.values.count { now - it.lastRxMs <= 5_000 }
                if (activeNeighbors > lastNeighborCount) beaconPeriodMs = 3_000L
                lastNeighborCount = activeNeighbors

                val curFix = _state.value.myFix
                val prevFix = lastBeaconFix
                if (curFix != null && prevFix != null) {
                    val dist = GapEstimator.distanceM(curFix.latitude, curFix.longitude, prevFix.latitude, prevFix.longitude)
                    val maxAcc = maxOf(curFix.accuracyM, prevFix.accuracyM).toDouble()
                    if (dist > maxAcc) beaconPeriodMs = 3_000L
                }

                val effectivePeriod = (if (r.hasPendingUrgent()) (beaconPeriodMs * 2).coerceAtLeast(6_000L) else beaconPeriodMs).coerceAtMost(20_000L)
                if (now - lastBeaconSentMs >= effectivePeriod) {
                    sendBeacon(r, now)
                    lastBeaconSentMs = now
                    lastBeaconFix = curFix
                    beaconPeriodMs = (beaconPeriodMs * 2).coerceAtMost(densityCap)
                }
                if (n % 40 == 0L) updatePower(r, now)
                // A location request made while Android briefly refused permission (screen off at the wrong moment) never
                // delivers; with no fresh fix for 30 s, ask again (measured: the arrow had no position until a restart).
                if (n % 120 == 60L && _state.value.myFix.let { it == null || now - it.fixTimeMs > 30_000 }) { location.stop(); startLocation() }
                // Two-way navigator: while I am finding someone, tell them every 10 s so they can navigate back to me.
                if (n % 40 == 20L && r.crew != null) find.activeTarget?.let { r.originate(MeshMessage.Looking(it), now) }
                if (n % 4 == 0L) { sampleLinks(r, now); checkLostContact(now); _state.value.lit?.let { if (now >= it.endsAt) stopLight() } } // every 1 s
                if (n % 2 == 0L) publish(r, now)
                n++
                delay(250)
            }
        }
    }

    private fun startLocation() = location.start(onFix = { fix ->
        heading.setLocation(fix.latitude, fix.longitude, 0.0, fix.fixTimeMs); _state.update { it.copy(myFix = fix) }
        pendingSos?.let { k -> pendingSos = null; broadcastSos(k) }
    }, onError = { msg -> _state.update { it.copy(error = msg) } })

    fun stop() {
        loop?.cancel(); loop = null
        codedBurstUntilMs = 0L
        beaconPeriodMs = 3_000L
        lastBeaconSentMs = 0L
        lastBeaconFix = null
        lastNeighborCount = 0
        radio.setCodedAdvertising(false)
        radio.stop()
        aware.stop(); nearby?.stop(); pair?.stop(); internet.stop()
        safety.stop(); rewind.stop(); siren.stop(); pendingSos = null
        bleHealth.up = false
        location.stop()
        find.shutdown(System.currentTimeMillis())
        heading.stop()
        router = null; labeler = null
        context.stopService(Intent(context, MeshService::class.java))
        _state.update { it.copy(running = false, countdown = null, rewindFrames = 0, sirenOn = false, lit = null) }
    }

    /** (Re)starts the foreground service with the right types: camera only while Rewind is actually running. */
    private fun refreshService() {
        if (router == null) return
        val i = Intent(context, MeshService::class.java)
            .putExtra(MeshService.EXTRA_CAMERA, rewind.running && cameraAllowed())
            .putExtra(MeshService.EXTRA_COUNTDOWN, _state.value.countdown?.label)
        context.startForegroundService(i)
    }

    // ---------------------------------------------------------------- sending

    /**
     * SOS: sounds the siren at once, then sends to the crew (encrypted) and, so helpers can come, a public SOS with
     * location and kind. It never refuses: the mesh is started if it is off, and with no GPS fix yet it goes out as
     * "location unknown" and is sent again with the position as soon as one arrives.
     */
    fun sendSos(kind: SosKind = SosKind.MANUAL) {
        siren.start()
        buzz(SOS_PATTERN)
        fullPowerUntil = System.currentTimeMillis() + 10 * 60_000
        if (router == null) start()
        router?.let { updatePower(it, System.currentTimeMillis()) }
        broadcastSos(kind)
    }

    private var lastLightAt = 0L

    fun setAllowLight(on: Boolean) { prefs.edit().putBoolean("allow_light", on).apply(); _state.update { it.copy(allowLight = on) }; if (!on) stopLight() }

    /** Asks the crew member [targetId] to light up. The target obeys only if it allowed it; there is no reply, so the screen says "sent", not "lit". */
    fun sendLight(targetId: Int, modes: Int, durS: Int, name: String) {
        val r = router ?: return _state.update { it.copy(error = context.getString(R.string.sos_no_mesh)) }
        if (r.crew == null) return _state.update { it.copy(error = "Join a crew first.") }
        val now = System.currentTimeMillis()
        r.originate(MeshMessage.Light(targetId, modes and 7, durS.coerceIn(MeshMessage.LIGHT_MIN_S, MeshMessage.LIGHT_MAX_S)), now)
        addFeed(FeedItem(now, "You", context.getString(R.string.light_sent_feed, name), "TEXT", 0, mine = true))
        applyTransports() // an urgent request needs the fast radios now
    }

    private fun startLight(by: String, modes: Int, durS: Int, now: Long) {
        lastLightAt = now
        val torch = modes and MeshMessage.LIGHT_TORCH != 0
        val sound = modes and MeshMessage.LIGHT_SOUND != 0
        if (!_state.value.sirenOn && (torch || sound)) { siren.stop(); siren.start(sound = sound, flash = torch, simpleBlink = true) }
        buzz(SOS_PATTERN)
        val title = context.getString(R.string.light_lit_title, by)
        notify(title, context.getString(R.string.light_lit_body))
        _state.update { it.copy(lit = LitState(by, modes, now + durS * 1000L)) }
    }

    fun stopLight() {
        if (!_state.value.sirenOn) siren.stop()
        _state.update { it.copy(lit = null) }
    }

    fun stopSiren() { siren.stop(); _state.update { it.copy(sirenOn = false) } }

    private fun broadcastSos(kind: SosKind) {
        val now = System.currentTimeMillis()
        val r = router ?: return _state.update { it.copy(sirenOn = siren.playing, error = context.getString(R.string.sos_no_mesh)) }
        val f = _state.value.myFix
        if (f == null) pendingSos = kind
        val lat = f?.latitude ?: 0.0
        val lon = f?.longitude ?: 0.0 // 0,0 = position unknown (no one is in the Gulf of Guinea)
        if (r.crew != null) {
            val id = r.originate(MeshMessage.Sos(lat, lon, "${_state.value.name}: ${kind.label}"), now)
            addFeed(FeedItem(now, "You", "SOS sent (${kind.label})", "SOS", 0, mine = true, packetId = id))
        }
        myPublicSos += r.originatePublic(PublicMessage.PublicSos(kind, lat, lon, _state.value.name), now)
        mySosResponders.clear()
        _state.update { it.copy(lastSosAt = now, sirenOn = siren.playing, sosWaitingForGps = f == null, error = null) }
        applyTransports() // an urgent message needs Nearby and fast scanning right now, not in 5 s
    }

    /** Strong vibration for alarms; a haptic failure must never take the app down. */
    private fun buzz(pattern: LongArray) {
        try { context.getSystemService(android.os.Vibrator::class.java)?.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1)) } catch (_: SecurityException) { }
    }

    fun sendRally(label: String, inMinutes: Int) {
        val at = System.currentTimeMillis() / 1000 + inMinutes * 60L
        send(MeshMessage.Rally(myLat(), myLon(), at, label.take(40)), "RALLY", "Meet here: $label, in $inMinutes min")
    }

    fun sendText(text: String) { if (text.isNotBlank()) send(MeshMessage.Text(text.take(100)), "TEXT", text.take(100)) }

    private fun send(m: MeshMessage, kind: String, shown: String) {
        val r = router ?: return _state.update { it.copy(error = "Start the mesh first.") }
        if (r.crew == null) return _state.update { it.copy(error = "Join a crew to message it.") }
        if (m !is MeshMessage.Text && _state.value.myFix == null) return _state.update { it.copy(error = "Waiting for your GPS position.") }
        val id = r.originate(m, System.currentTimeMillis())
        applyTransports()
        addFeed(FeedItem(System.currentTimeMillis(), "You", shown, kind, 0, mine = true, packetId = id))
    }

    // ---------------------------------------------------------------- volunteer and police actions

    /** A volunteer scanned a band: send a "found" report only that family's phone can read. */
    fun reportFound(band: Band, note: String) {
        val r = router ?: return _state.update { it.copy(error = "Start the mesh first.") }
        val f = _state.value.myFix ?: return _state.update { it.copy(error = "Waiting for your GPS position.") }
        val now = System.currentTimeMillis()
        val report = FoundReport(random.nextLong(), band.bandId, f.latitude, f.longitude, now / 1000, _state.value.name, note.take(40))
        val msg = PublicMessage.foundFor(band, report) ?: return _state.update { it.copy(error = "This band's key is damaged.") }
        val id = r.originatePublic(msg, now, crewId = band.crewId)
        sentReports.add(0, SentReport(report.reportId, id, band.name, now, familyReceived = false))
        applyTransports()
    }

    /** Someone was found with no band: broadcast a short description so their family can recognise them. */
    fun registerFound(description: String) {
        val r = router ?: return
        val f = _state.value.myFix ?: return _state.update { it.copy(error = "Waiting for your GPS position.") }
        r.originatePublic(PublicMessage.FoundNotice(f.latitude, f.longitude, "${_state.value.name}: ${description.take(60)}"), System.currentTimeMillis())
    }

    fun respond(alert: SosAlert) {
        val r = router ?: return
        r.originatePublic(PublicMessage.Respond(alert.originId, alert.packetId, _state.value.role, _state.value.name), System.currentTimeMillis())
        sosBoard[key(alert.originId, alert.packetId)] = alert.copy(iResponded = true, responders = alert.responders + "You")
    }

    fun sendOfficial(text: String) {
        val r = router ?: return
        val k = policeKey() ?: return _state.update { it.copy(error = "Police key missing.") }
        if (text.isBlank()) return
        r.originatePublic(PublicMessage.official(text.trim(), k), System.currentTimeMillis())
        officials.addFirst(Notice(System.currentTimeMillis(), myLat(), myLon(), "You: ${text.trim()}"))
    }

    // ---------------------------------------------------------------- receiving

    override fun onPublic(packet: MeshPacket, message: PublicMessage, hops: Int, rssi: Int, ageS: Long) {
        val now = System.currentTimeMillis()
        val r = router ?: return
        when (message) {
            is PublicMessage.Found -> {
                if (packet.crewId != r.crew?.crewId) return
                val (band, key) = loadBands().firstOrNull { it.first.bandId == message.bandId } ?: return
                val report = PublicCrypto.openWith(key, message.sealed, FoundReport.aad(band.crewId, band.bandId))?.let(FoundReport::decode) ?: return
                val title = context.getString(R.string.found_alert, band.name)
                val body = context.getString(R.string.found_alert_body, report.desk, where(report.lat, report.lon)) + if (report.note.isNotBlank()) " · ${report.note}" else ""
                val text = "$title. $body"
                addFeed(FeedItem(now, report.desk, text, "FOUND", hops, mine = false))
                alert(HomeAlert("found-${report.reportId}", title, body, "FOUND", report.lat, report.lon, now))
                notify(title, body)
                r.originatePublic(PublicMessage.FoundAck(report.reportId), now, crewId = band.crewId)
                // Tell the rest of the crew too: their phones do not hold this band's key.
                r.originate(MeshMessage.Text(text.take(100)), now)
            }
            is PublicMessage.FoundAck -> {
                val i = sentReports.indexOfFirst { it.reportId == message.reportId }
                if (i >= 0 && !sentReports[i].familyReceived) { sentReports[i] = sentReports[i].copy(familyReceived = true); r.cancelRepeats(sentReports[i].packetId) }
            }
            is PublicMessage.PublicSos -> {
                if (packet.originId == r.nodeId) return
                val k = key(packet.originId, packet.packetId)
                if (k !in sosBoard) {
                    sosBoard[k] = SosAlert(packet.originId, packet.packetId, message.kind, message.lat, message.lon, message.label, now - ageS * 1000, emptyList(), false)
                    if (_state.value.role != Role.MEMBER) notify("${message.kind.label}: ${message.label}", context.getString(R.string.needs_help, where(message.lat, message.lon)))
                }
            }
            is PublicMessage.Respond -> {
                sosBoard[key(message.sosOrigin, message.sosPacket)]?.let { a ->
                    if (message.name !in a.responders) sosBoard[key(message.sosOrigin, message.sosPacket)] = a.copy(responders = a.responders + "${message.role.label} ${message.name}")
                }
                if (message.sosOrigin == r.nodeId && message.sosPacket in myPublicSos) {
                    r.cancelRepeats(message.sosPacket)
                    val who = "${message.role.label} ${message.name}"
                    if (who !in mySosResponders) {
                        mySosResponders += who
                        val body = context.getString(R.string.help_coming_body, who)
                        notify(context.getString(R.string.help_coming), body)
                        alert(HomeAlert("help-$who", context.getString(R.string.help_coming), body, "HELP", at = now))
                        addFeed(FeedItem(now, who, body, "RESPOND", hops, mine = false))
                    }
                }
            }
            is PublicMessage.Official -> {
                val auth = authority ?: return
                if (!message.verify(auth)) return // forged "official" alerts are dropped silently
                if (officials.none { it.text == message.text }) {
                    officials.addFirst(Notice(now, 0.0, 0.0, message.text))
                    notify(context.getString(R.string.official_alert), message.text)
                    alert(HomeAlert("official-${message.text.hashCode()}", context.getString(R.string.official_alert), message.text, "OFFICIAL", at = now))
                }
            }
            is PublicMessage.FoundNotice -> {
                notices.addFirst(Notice(now, message.lat, message.lon, message.description))
                while (notices.size > 30) notices.removeLast()
            }
        }
    }

    override fun onMessage(originId: Int, message: MeshMessage, hops: Int, rssi: Int, ageS: Long) {
        val now = System.currentTimeMillis()
        val name = members[originId]?.name ?: "Crew ${Integer.toHexString(originId).takeLast(4)}"
        when (message) {
            is MeshMessage.Beacon -> {
                // A phone takes a fresh node id each time KHOJ restarts. The same name now speaking under a new id while
                // the old id is silent is the same person: drop the ghost and its alarms instead of reporting it lost.
                // shortcut: matches by name, so two silent-then-restarted members with the same name merge; a stable member id in the beacon would fix it.
                members.values.filter { it.originId != originId && it.name == message.name && now - it.heardAt > 10_000 }.forEach { g ->
                    members.remove(g.originId); lostNotified -= g.originId; outsideCircle -= g.originId
                    alerts.remove("lost-${g.originId}"); alerts.remove("circle-${g.originId}")
                }
                members[originId] = CrewMember(originId, message.name, message.lat, message.lon, message.accuracyM, message.battery, now - ageS * 1000, hops, rssi, message.findable); lostNotified -= originId
            }
            is MeshMessage.Sos -> {
                addFeed(FeedItem(now, name, "SOS: ${message.note} ${where(message.lat, message.lon)}", "SOS", hops, mine = false))
                val title = context.getString(R.string.sos_from, name)
                notify(title, message.note)
                buzz(SOS_PATTERN)
                val known = hasPosition(message.lat, message.lon)
                alert(HomeAlert("sos-$originId", title, context.getString(R.string.needs_help, where(message.lat, message.lon)), "SOS", message.lat.takeIf { known }, message.lon.takeIf { known }, now))
            }
            is MeshMessage.Rally -> {
                addFeed(FeedItem(now, name, "Meet: ${message.label} ${where(message.lat, message.lon)} at ${clock(message.meetAtS * 1000)}", "RALLY", hops, mine = false))
                meetPoint = MeetPoint(message.lat, message.lon, message.label, name, now)
            }
            is MeshMessage.Text -> addFeed(FeedItem(now, name, message.text, "TEXT", hops, mine = false))
            is MeshMessage.Ack -> Unit
            is MeshMessage.Light -> if (message.targetId == router?.nodeId && _state.value.allowLight && ageS <= 60 && now - lastLightAt > 3_000) startLight(name, message.modes, message.durS, now)
            is MeshMessage.Looking -> if (message.targetId == router?.nodeId) {
                beaconPeriodMs = 3_000L
                if (seekers.put(originId, now) == null) { notify(context.getString(R.string.looking_for_you, name), context.getString(R.string.looking_for_you_body)); applyTransports() }
            }
        }
    }

    override fun onAcked(packetId: Int, byOriginId: Int) {
        val who = members[byOriginId]?.name ?: "a crew member"
        val i = feed.indexOfFirst { it.mine && it.packetId == packetId }
        if (i >= 0) feed[i] = feed[i].copy(ackedBy = feed[i].ackedBy + who)
    }

    /**
     * Two ways a member leaves the group, both with a strong vibration and a notification on every phone in the group
     * (each phone runs this check for every member, so the person who walked off is warned too):
     * out of the circle (GPS, see [app.linkfill.core.find.Circle]), or out of radio range (silent for 60 s, 3x density cap).
     */
    private fun checkLostContact(now: Long) {
        val me = _state.value.myFix
        val lostTimeoutMs = 60_000L
        for (m in members.values) {
            if (now - m.heardAt in lostTimeoutMs..600_000 && lostNotified.add(m.originId)) {
                val title = context.getString(R.string.lost_contact, m.name)
                val body = context.getString(R.string.last_seen, where(m.lat, m.lon))
                addFeed(FeedItem(now, m.name, "$title. $body", "LOST", m.hops, mine = false))
                notify(title, body)
                buzz(LEFT_PATTERN)
                alert(HomeAlert("lost-${m.originId}", title, body, "LOST", m.lat.takeIf { hasPosition(m.lat, m.lon) }, m.lon.takeIf { hasPosition(m.lat, m.lon) }, now))
            }
            if (now - m.heardAt < lostTimeoutMs && alerts.remove("lost-${m.originId}") != null) lostNotified -= m.originId
            if (me == null || now - m.heardAt >= lostTimeoutMs || now - me.fixTimeMs > 30_000 || !hasPosition(m.lat, m.lon)) continue
            val d = GapEstimator.distanceM(me.latitude, me.longitude, m.lat, m.lon)
            val circle = _state.value.circleM.toDouble()
            val out = app.linkfill.core.find.Circle.outside(d, me.accuracyM.toDouble(), m.accuracyM.toDouble(), circle, m.originId in outsideCircle)
            if (out && outsideCircle.add(m.originId)) {
                val title = context.getString(R.string.left_circle, m.name)
                val body = context.getString(R.string.left_circle_body, where(m.lat, m.lon), circle.toInt())
                addFeed(FeedItem(now, m.name, "$title. $body", "LOST", m.hops, mine = false))
                notify(title, body)
                buzz(LEFT_PATTERN)
                alert(HomeAlert("circle-${m.originId}", title, body, "LOST", m.lat, m.lon, now))
            } else if (!out && outsideCircle.remove(m.originId)) alerts.remove("circle-${m.originId}")
        }
    }

    private fun alert(a: HomeAlert) { alerts[a.key] = a; while (alerts.size > 6) alerts.remove(alerts.keys.first()) }

    private fun notify(title: String, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val n = android.app.Notification.Builder(context, ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title).setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(android.app.PendingIntent.getActivity(context, 0, Intent(context, app.linkfill.MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE))
            .build()
        try { nm.notify((title + text).hashCode(), n) } catch (_: SecurityException) { } // notifications not allowed: the in-app feed still shows it
    }

    private fun key(origin: Int, packet: Int) = (origin.toLong() shl 32) or (packet.toLong() and 0xFFFFFFFFL)

    private fun myLat() = _state.value.myFix?.latitude ?: 0.0
    private fun myLon() = _state.value.myFix?.longitude ?: 0.0

    private fun sendBeacon(r: MeshRouter, now: Long) {
        if (r.crew == null) return
        val f = _state.value.myFix
        val lat = f?.latitude ?: 0.0
        val lon = f?.longitude ?: 0.0
        val acc = f?.accuracyM?.toInt() ?: 0
        val battery = context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
        r.originate(MeshMessage.Beacon(lat, lon, acc, battery.coerceIn(0, 100), _state.value.name, find.rangeable()), now)
    }

    private fun onFrame(bytes: ByteArray, rssi: Int, coded: Boolean) {
        bleHealth.received++
        bleHealth.lastRxMs = System.currentTimeMillis()
        router?.onFrame(bytes, rssi, coded, System.currentTimeMillis())
    }

    /** Frames from Wi-Fi Aware, Nearby or the relay. RSSI is unknown there; a nominal value sets the relay delay. */
    private fun onOtherFrame(bytes: ByteArray, via: TransportKind, nominalRssi: Int) {
        router?.onFrame(bytes, nominalRssi, false, System.currentTimeMillis(), via)
    }

    /**
     * Sends one frame on the radios the selector picks. Bluetooth always advertises something each slot,
     * because that is how neighbours discover us and measure the link.
     */
    private fun transmit(r: MeshRouter, frame: ByteArray) {
        val type = TransportSelector.typeOf(frame) ?: return
        val healths = listOf(bleHealth, aware.health, nearby?.health, internet.health).filterNotNull()
        val kinds = TransportSelector.choose(type, r.risk, healths, _state.value.combineMode, risky = r.riskHigh)
        radio.send(frame) // Bluetooth advertising carries every frame; other radios only when chosen
        bleHealth.sent++
        if (TransportKind.WIFI_AWARE in kinds) aware.send(frame)
        if (TransportKind.NEARBY in kinds) nearby?.send(frame)
        if (type != MeshType.NEIGHBOR) pair?.send(frame) // the Find pair's own link carries every real packet
        if (type != MeshType.NEIGHBOR) {
            _state.update {
                it.copy(
                    lastChoice = "${type.name.lowercase()} via ${kinds.joinToString(" + ") { k -> k.label }}" + if (internet.health.up) " (+ data bridge)" else "",
                    combinedEstimate = TransportSelector.combinedDelivery(kinds, healths),
                )
            }
        }
    }

    /** Starts or stops each radio to match the switches, role, Wi-Fi state, data connection and direct sharing. */
    private fun applyTransports() {
        val r = router ?: return
        if (TransportKind.WIFI_AWARE in enabled && aware.supported) { if (!aware.health.up) aware.start() } else aware.stop()
        val directSharing = (context.applicationContext as LinkFillApp).session.state.value.phase.let { it != Phase.IDLE && it != Phase.ENDED }
        val now = System.currentTimeMillis()
        val neighbors = r.links.neighbors.values.count { now - it.lastRxMs <= 10_000 }
        if (neighbors > 0) lastNeighborMs = now
        val responder = _state.value.role != Role.MEMBER
        // Volunteers and police are the backbone: Nearby and fast scanning always on. Members: on demand only
        // when Bluetooth already hears KHOJ phones and something needs a stronger link.
        val needed = responder || neighbors > 0 && (r.pendingUrgent > 0 || (r.risk ?: 0.0) >= r.riskHigh || _state.value.combineMode == CombineMode.ALL)
        if (needed || findKeepsNearby) nearbyUntil = now + 60_000
        // A Find pair gets its own point-to-point link (Wi-Fi Direct capable): the finder dials, the person being found
        // advertises; when both are finding each other the lower node id advertises so they meet in the middle.
        val target = find.activeTarget
        val seeker = seekers.entries.firstOrNull { now - it.value <= 30_000 }?.key
        val pairWant = when {
            directSharing -> null
            target != null && seekers[target]?.let { now - it <= 30_000 } == true && r.nodeId < target -> NearbyPairLink.Role.ADVERTISE to target
            target != null -> NearbyPairLink.Role.DISCOVER to target
            seeker != null -> NearbyPairLink.Role.ADVERTISE to seeker
            else -> null
        }
        if (pairWant != null) { nearby?.stop(); pair?.start(pairWant.first, NearbyMeshTransport.nameFor(pairWant.second)) }
        else {
            pair?.stop()
            nearby?.let { if (TransportKind.NEARBY in enabled && !directSharing && now < nearbyUntil) { it.start(); it.ensure() } else it.stop() }
        }
        radio.setLowLatencyScan(!r.lowPower && (responder || now - lastNeighborMs <= 60_000 || r.pendingUrgent > 0 || lastNeighborMs == 0L && now - startedAt < 60_000), now)
        val url = _state.value.relayUrl
        val relayCrew = r.crew?.crewId ?: 0
        if (TransportKind.INTERNET in enabled && url.isNotEmpty()) { if (!internet.health.up && internet.online()) internet.start(url, relayCrew) } else internet.stop()
    }

    private fun where(lat: Double, lon: Double): String {
        if (!hasPosition(lat, lon)) return context.getString(R.string.position_unknown)
        val me = _state.value.myFix ?: return ""
        val d = GapEstimator.distanceM(me.latitude, me.longitude, lat, lon)
        val b = GapEstimator.bearingDeg(me.latitude, me.longitude, lat, lon)
        return context.getString(R.string.metres_dir, d.toInt(), compassWord(b))
    }

    private fun compassWord(b: Double) = context.resources.getStringArray(R.array.compass)[(((b + 22.5) % 360) / 45).toInt()]

    private fun clock(ms: Long) = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

    private fun addFeed(item: FeedItem) { feed.addFirst(item); while (feed.size > 50) feed.removeLast() }

    /** Records one labelled row per active neighbour per second, for retraining on real phones. */
    private fun sampleLinks(r: MeshRouter, now: Long) {
        val l = labeler ?: return
        val powerMgr = context.getSystemService(android.os.PowerManager::class.java)
        val screenOn = powerMgr?.isInteractive == true
        val batterySaver = powerMgr?.isPowerSaveMode == true
        val thermal = if (android.os.Build.VERSION.SDK_INT >= 29) powerMgr?.currentThermalStatus ?: 0 else 0
        val density = r.links.density(now)
        val gattCount = radio.gatt.linkCount
        val nearbySt = if (nearby?.isRunning == true) 1 else 0

        for (n in r.links.neighbors.values) if (now - n.lastRxMs <= 10_000) {
            val f = r.links.features(
                n.id, now, r.queueSize, radio.isCoded,
                distanceM = Double.NaN,
                advUpdateAgeS = 0.0,
                activeAdvSets = 1,
                nearbyState = nearbySt,
                gattLinkCount = gattCount,
                screenOn = screenOn,
                batterySaver = batterySaver,
                thermalStatus = thermal,
                localDensity = density,
                peerBeaconPeriodS = 3.0,
            )
            l.sample(n.id, now, f)
        }
        val rows = StringBuilder()
        l.finalize(now) { id, t, f, loss, lost -> rows.append("$t,$id,${f.joinToString(",")},$loss,$lost\n"); logRows++ }
        if (rows.isNotEmpty()) {
            if (!logFile.exists()) logFile.writeText("time_ms,neighbor,${LinkFeatures.ALL.joinToString(",")},loss_next_0_6s,lost_5s\n")
            logFile.appendText(rows.toString())
        }
    }

    private fun publishTransports() {
        val views = listOf(
            TransportView(TransportKind.BLE, radio.extendedSupported, true, bleHealth.up, _state.value.neighbors.size, bleHealth.quality(), bleHealth.sent, 0, 0, bleHealth.received,
                radio.activeSets.ifEmpty { if (radio.codedSupported) "1M + LE Coded (long range) when on" else "1M only (no LE Coded)" } +
                    if (bleHealth.up) " · scan ${if (radio.lowLatencyScan) "fast" else "power-saving"}" else ""),
            TransportView(TransportKind.GATT, true, true, radio.gatt.linkCount > 0, radio.gatt.linkCount, if (radio.gatt.linkCount > 0) 1.0 else 0.0, 0, 0, 0, radio.gatt.framesIn,
                if (radio.extendedSupported) "opens only for older phones and iPhones nearby" else "this phone joins the mesh through these links"),
            TransportView(TransportKind.WIFI_AWARE, aware.supported, TransportKind.WIFI_AWARE in enabled, aware.health.up, aware.health.peers, aware.health.quality(),
                aware.health.sent, aware.health.delivered, aware.health.failed, aware.health.received, if (aware.supported) "needs Wi-Fi on, no network" else "not on this phone"),
            TransportView(TransportKind.NEARBY, true, TransportKind.NEARBY in enabled, nearby?.health?.up == true, nearby?.health?.peers ?: 0, nearby?.health?.quality() ?: 0.0,
                nearby?.health?.sent ?: 0, nearby?.health?.delivered ?: 0, nearby?.health?.failed ?: 0, nearby?.health?.received ?: 0,
                if (nearby?.isRunning == true) "running" else "starts on demand (urgent or weak link with phones nearby)"),
            TransportView(TransportKind.INTERNET, true, TransportKind.INTERNET in enabled, internet.health.up, 0, internet.health.quality(),
                internet.health.sent, internet.health.delivered, internet.health.failed, internet.health.received,
                when { _state.value.relayUrl.isEmpty() -> "set a relay server URL"; !internet.online() -> "no mobile data now"; else -> internet.lastError ?: "bridging" }),
        )
        _state.update { it.copy(transports = views, batteryUnrestricted = batteryUnrestricted()) }
    }

    /** OriginOS and other OEM power managers may throttle a background mesh unless the app is exempt. */
    fun batteryUnrestricted(): Boolean =
        context.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    private fun publish(r: MeshRouter, now: Long) {
        val risks = r.neighborRisks(now)
        bleHealth.peers = risks.size
        bleHealth.externalQuality = r.links.neighbors.values.filter { now - it.lastRxMs <= 10_000 }.map { r.links.pdr(it.id, 2_000, now) }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        publishTransports()
        // Old SOS alerts drop off the board after 30 minutes.
        sosBoard.values.removeAll { now - it.at > 30 * 60_000 }
        val s = r.stats
        _state.update {
            it.copy(
                now = now, coded = radio.isCoded, mode = r.mode, risk = r.risk, logRows = logRows,
                members = members.values.sortedBy { m -> m.name },
                feed = feed.toList(),
                sosBoard = sosBoard.values.sortedByDescending { a -> a.at },
                sentReports = sentReports.toList(),
                notices = notices.toList(),
                officials = officials.toList(),
                mySosResponders = mySosResponders.toList(),
                rewindFrames = rewind.snapshot.size,
                meetPoint = meetPoint,
                alerts = alerts.values.sortedByDescending { a -> a.at },
                seekers = seekers.apply { values.removeAll { now - it > 30_000 } }.keys.associateWith { id -> members[id]?.name ?: "Crew" },
                rewindError = rewind.error ?: it.rewindError.takeIf { _ -> !rewind.running },
                neighbors = r.links.neighbors.values.filter { n -> now - n.lastRxMs <= 10_000 }
                    .map { n -> NeighborView(n.id, n.lastRssi, r.links.pdr(n.id, 2_000, now), risks[n.id]) }.sortedByDescending { n -> n.rssi },
                stats = "frames in ${s.framesIn}, out ${s.framesOut} (radio ${radio.framesSent}); relayed ${s.relayed}, suppressed ${s.suppressed}, " +
                    "duplicates ${s.duplicates}; other crews relayed ${s.foreignRelayed}; delivered ${s.delivered}; public ${s.publicDelivered} " +
                    "(rate-limited ${s.publicRateLimited}); forged/corrupt ${s.authFailed}; malformed ${s.malformed}; queue ${r.queueSize}; advertise errors ${radio.advertiseErrors}",
            )
        }
    }

    companion object {
        /** Shown on the mesh screen; see the comment where the router is created. LinkAi stays in the app for the retrained model. */
        private const val LINK_AI_PAUSED = "Link AI paused (model predates the 1 s dwell); using 1 - delivery ratio over 2 s"
        private const val ALERTS = "alerts"
        private val SOS_PATTERN = longArrayOf(0, 600, 200, 600, 200, 600)
        private val LEFT_PATTERN = longArrayOf(0, 400, 150, 400, 150, 400, 150, 400)
        /** Senders use 0,0 for "no position yet". */
        fun hasPosition(lat: Double, lon: Double) = lat != 0.0 || lon != 0.0
        const val POLICE_PREFIX = "KHOJ-POLICE1:"
        const val CREW_PREFIX = "KHOJ-CREW1:"
    }
}
