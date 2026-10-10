package app.linkfill.find

import android.content.Context
import android.os.Build
import app.linkfill.core.find.AngleSmoother
import app.linkfill.core.find.FindInputs
import app.linkfill.core.find.FindMode
import app.linkfill.core.find.FindResolver
import app.linkfill.core.find.FindState
import app.linkfill.core.find.HeadingFix
import app.linkfill.core.find.ImprovementCue
import app.linkfill.core.find.LocFix
import app.linkfill.core.find.OwnPdrTracker
import app.linkfill.core.find.RangeBearingEstimator
import app.linkfill.core.find.RangeSample
import app.linkfill.core.find.StepIntegrator
import app.linkfill.core.find.WalkFix
import app.linkfill.core.find.WalkIssue
import app.linkfill.core.find.RangingFix
import app.linkfill.core.find.RssiSnapshot
import app.linkfill.core.find.RssiTracker
import app.linkfill.core.find.Tunnel
import app.linkfill.core.mesh.CrewKey
import app.linkfill.core.protocol.LocationFix
import app.linkfill.mesh.CrewMember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.SecureRandom
import java.util.Locale

/** Where the secure link to the target stands. */
enum class LinkPhase { IDLE, WAITING_FOR_RANGE, NO_DIRECT_LINK, REQUESTING, NEGOTIATING, RANGING, DECLINED, NO_RESPONSE, UNSUPPORTED, ENDED }

/** Everything the Find screen draws. Built from real inputs only; see FindResolver for what may be claimed. */
data class FindUi(
    val targetId: Int,
    val targetName: String,
    val targetFindable: Boolean,
    val state: FindState,
    val arrowSmoothedDeg: Double?,
    val rssi: RssiSnapshot?,
    val phase: LinkPhase,
    val phaseDetail: String,
    val caps: RangingCaps,
    val rangingTech: String?,
    val bluetoothHeard: Boolean,
    val directLink: Boolean,
    val headingTrue: Double?,
    val headingUncertainty: Double?,
    val declinationKnown: Boolean,
    val myFixAccuracyM: Double?,
    val trustAngle: Boolean,
    val groundTruthM: Double?,
    val logPath: String,
    val sessionId: String,
    val beingFoundBy: String?,
    val now: Long,
    val linkMedium: String? = null,
)

/**
 * Orchestrates one Find Member session and the opposite side of it (being found).
 *
 * Finder: collects real inputs (signal strength from the target's own Bluetooth frames, GPS fixes from crew beacons, the
 * phone's heading, and ranging measurements from Android's Ranging module when both phones support it), resolves what may be
 * claimed with [FindResolver], smooths the arrow, and exposes [ui].
 *
 * Target: if the user allowed it, accepts a request from a crew member, starts the responder half of the ranging session, and
 * shows a visible "X is finding you" prompt.
 *
 * Not thread-safe; everything runs on the main thread like the rest of the mesh.
 */
class FindMemberSession(private val context: Context, private val host: Host) {
    interface Host {
        val nodeId: Int
        fun crew(): CrewKey?
        fun myFix(): LocationFix?
        fun member(id: Int): CrewMember?
        fun heading(): HeadingFix?
        fun declinationKnown(): Boolean
        fun sendTunnel(to: Int, frame: ByteArray): Boolean
        fun hasDirectLink(to: Int): Boolean
        /** Which radio the direct link to [to] is on now ("Wi-Fi Direct / hotspot", "Bluetooth"), or null. */
        fun linkMedium(to: Int): String? = null
        /** Ask the mesh to keep Nearby connected (needed for the tunnel). */
        fun keepNearby(on: Boolean)
        fun isFindable(): Boolean
        fun beingFound(by: String?)
        fun vibrate()
        fun trustAngle(): Boolean
    }

    private val random = SecureRandom()
    private val _ui = MutableStateFlow<FindUi?>(null)
    val ui: StateFlow<FindUi?> = _ui.asStateFlow()
    var caps: RangingCaps = RangingCaps.NONE
        private set
    companion object {
        /** Channel Sounding and UWB are short-range; ask for a session only inside this. */
        const val PRECISE_RANGE_M = 40.0
        /** Filtered 1M RSSI that counts as "close" for starting a session (uncalibrated default). */
        const val MIN_RANGE_RSSI = -85.0
    }

    private val probe = RangingCapabilityProbe(context) { c -> caps = c; android.util.Log.i("KHOJ", "ranging capabilities: ${c.summary()} | queried=${c.queried} permission=${c.permissionGranted} azimuth=${c.uwbAzimuth} elevation=${c.uwbElevation} background=${c.uwbBackground}") }

    private var targetId = 0
    private var targetName = ""
    private var active = false
    /** The member being found right now, or null. */
    val activeTarget: Int? get() = if (active) targetId else null
    private var sessionId = ""
    private var phase = LinkPhase.IDLE
    private var phaseDetail = ""
    private var requestsSent = 0
    private var lastRequestMs = 0L
    private var lastRanging: RangingFix? = null
    private var rangingTech: String? = null
    private val rssi = RssiTracker()
    private val spin = app.linkfill.core.find.SpinFinder()
    private var turnFix: WalkFix? = null
    /** Their recent positions, for predicting where they are when updates stop. */
    private val track = ArrayDeque<LocFix>()
    private var onTarget = false
    private val smoother = AngleSmoother(250.0)
    private val cue = ImprovementCue()
    private var lastEval = 0L
    private var lastLogMs = 0L
    private var lastMode: FindMode? = null
    private var groundTruth: Double? = null
    private var beingFoundBy: String? = null
    private var framesHeard1m = 0
    private var framesHeardCoded = 0
    private var lastHeardMs = 0L

    // Ranging halves; the session class only exists on Android 16+, so it is held as Any? and cast behind a version check.
    // Direction from walking: steps -> path, distances along the path -> target position.
    private val steps = StepIntegrator()
    private val walker = RangeBearingEstimator()
    private var walkIssue: WalkIssue? = null
    private var walkFix: WalkFix? = null
    private var lastSolveMs = 0L
    private val ownPdr = OwnPdrTracker()
    private var lastMyFix: LocFix? = null
    private val motion = MotionTracker(context) { now, strideM -> onStep(now, strideM) }

    private var rangingSession: Any? = null
    private var transport: Any? = null
    private var responderPeer = 0
    private val logFile = File(context.getExternalFilesDir(null) ?: context.filesDir, "find_log.csv")

    init { probe.start() }

    fun refreshCapabilities() { probe.stop(); probe.start() }

    val permissionNeeded get() = Build.VERSION.SDK_INT >= 36 && !probe.permissionGranted()

    // ------------------------------------------------------------------ finder side

    fun start(id: Int, name: String, now: Long) {
        stop(now)
        targetId = id; targetName = name; active = true
        sessionId = ByteArray(3).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        rssi.reset(); smoother.reset(); cue.reset(); lastRanging = null; rangingTech = null
        spin.reset(); turnFix = null; track.clear(); onTarget = false
        steps.reset(); walker.reset(); walkFix = null; walkIssue = null; lastSolveMs = 0
        ownPdr.reset(); lastMyFix = null
        motion.start()
        framesHeard1m = 0; framesHeardCoded = 0; lastHeardMs = 0; lastMode = null; requestsSent = 0
        phase = LinkPhase.IDLE; phaseDetail = ""
        host.keepNearby(true)
        log(now, "start", "")
        phase = LinkPhase.WAITING_FOR_RANGE; phaseDetail = "Precise finding starts by itself within about ${PRECISE_RANGE_M.toInt()} m"
        evaluate(now, force = true)
    }

    /** Called when the user leaves the screen or taps Stop. */
    fun stop(now: Long) {
        if (active) { host.sendTunnel(targetId, seal(Tunnel.Kind.STOP, ByteArray(0), targetId, now)); log(now, "stop", "") }
        if (rangingSession != null && responderPeer == 0) endRanging()
        motion.stop()
        active = false
        host.keepNearby(false)
        phase = LinkPhase.IDLE
        _ui.value = null
    }

    private fun beginRanging(now: Long) {
        val m = host.member(targetId)
        if (m == null || !m.findable) { phase = LinkPhase.UNSUPPORTED; phaseDetail = "${targetName}'s phone cannot do precise ranging; using GPS and Bluetooth"; return }
        if (Build.VERSION.SDK_INT < 36) { phase = LinkPhase.UNSUPPORTED; phaseDetail = "This phone runs Android ${Build.VERSION.SDK_INT}; the Ranging module needs Android 16"; return }
        if (permissionNeeded) { phase = LinkPhase.UNSUPPORTED; phaseDetail = "Allow the Ranging permission to use hardware ranging"; return }
        if (caps.queried && !caps.anyPrecise) { phase = LinkPhase.UNSUPPORTED; phaseDetail = "No precise ranging hardware enabled on this phone"; return }
        sendRequest(now)
    }

    /** This phone can be measured by a crew member's phone: Android 16, permission, and ranging hardware switched on. */
    fun rangeable() = Build.VERSION.SDK_INT >= 36 && probe.permissionGranted() && caps.queried && caps.anyPrecise

    /**
     * Close enough for hardware ranging to work and be worth its power: the target's own Bluetooth frames are heard
     * directly and not weak, or both fresh GPS fixes put us within [PRECISE_RANGE_M].
     */
    private fun inPreciseRange(now: Long): Boolean {
        val f = rssi.snapshot(now).filtered
        if (now - lastHeardMs < 5_000 && lastHeardMs != 0L && f != null && f >= MIN_RANGE_RSSI) return true
        val m = host.member(targetId) ?: return false
        val me = host.myFix() ?: return false
        if (now - m.heardAt > 10_000 || now - me.fixTimeMs > 10_000) return false
        return app.linkfill.core.estimate.GapEstimator.distanceM(me.latitude, me.longitude, m.lat, m.lon) <= PRECISE_RANGE_M
    }

    private fun sendRequest(now: Long) {
        // Two-way finding: they already range with me as initiator; asking again would tear their session down.
        if (responderPeer == targetId && rangingSession != null) { phase = LinkPhase.NEGOTIATING; phaseDetail = "${targetName}'s phone is already measuring the distance to you"; return }
        if (!host.hasDirectLink(targetId)) { phase = LinkPhase.NO_DIRECT_LINK; phaseDetail = "No direct phone-to-phone link yet; connecting"; return }
        val ok = host.sendTunnel(targetId, seal(Tunnel.Kind.REQUEST, ByteArray(0), targetId, now))
        requestsSent++; lastRequestMs = now
        phase = if (ok) LinkPhase.REQUESTING else LinkPhase.NO_DIRECT_LINK
        phaseDetail = if (ok) "Asking ${targetName}'s phone to range (try $requestsSent)" else "Direct link lost"
    }

    /** Every Bluetooth frame heard from any phone; only the target's own, 1M-PHY frames feed the signal tracker. */
    fun onBluetoothFrame(relayId: Int, rssiDbm: Int, coded: Boolean, now: Long) {
        if (!active || relayId != targetId) return
        lastHeardMs = now
        if (coded) { framesHeardCoded++; return } // LE Coded RSSI is not comparable with 1M RSSI; do not mix them
        framesHeard1m++
        rssi.add(now, rssiDbm)
        // Turn-to-find: file each reading under the compass heading it was taken at, when the compass can be trusted.
        host.heading()?.takeIf { now - it.atMs <= FindResolver.HEADING_FRESH_MS && it.uncertaintyDeg <= FindResolver.MAX_HEADING_UNCERTAINTY_DEG }
            ?.let { spin.add(now, it.trueDeg, rssiDbm) }
    }

    fun setGroundTruth(m: Double?) { groundTruth = m }

    // ------------------------------------------------------------------ tunnel (both sides)

    fun onTunnel(frame: ByteArray, now: Long) {
        val key = host.crew() ?: return
        val msg = Tunnel.open(key, host.nodeId, frame, now) ?: return
        when (msg.kind) {
            Tunnel.Kind.REQUEST -> onRequest(msg.from, now)
            Tunnel.Kind.ACCEPT -> if (active && msg.from == targetId && phase != LinkPhase.RANGING) startInitiator(now)
            Tunnel.Kind.DECLINE -> if (active && msg.from == targetId) {
                phase = LinkPhase.DECLINED
                phaseDetail = when (msg.payload.firstOrNull()?.toInt()) {
                    1 -> "${targetName} paused precise finding"
                    2 -> "${targetName}'s phone has no ranging hardware or permission"
                    else -> "${targetName}'s phone declined"
                }
            }
            Tunnel.Kind.DATA -> deliverToTransport(msg.payload)
            Tunnel.Kind.STOP -> {
                if (responderPeer == msg.from) { endRanging(); responderPeer = 0; beingFoundBy = null; host.beingFound(null) }
                else if (active && msg.from == targetId) { phase = LinkPhase.ENDED; phaseDetail = "${targetName} ended the session" }
            }
        }
    }

    // --- target side
    private fun onRequest(from: Int, now: Long) {
        val name = host.member(from)?.name ?: "A crew member"
        if (!host.isFindable()) { host.sendTunnel(from, seal(Tunnel.Kind.DECLINE, byteArrayOf(1), from, now)); return }
        if (Build.VERSION.SDK_INT < 36 || !probe.permissionGranted() || (caps.queried && !caps.anyPrecise)) {
            host.sendTunnel(from, seal(Tunnel.Kind.DECLINE, byteArrayOf(2), from, now)); return
        }
        if (responderPeer == from && rangingSession != null) { host.sendTunnel(from, seal(Tunnel.Kind.ACCEPT, ByteArray(0), from, now)); return } // retry of the same request
        endRanging()
        responderPeer = from
        beingFoundBy = name
        host.beingFound(name)
        host.keepNearby(true)
        startSession(initiator = false, peer = from)
        host.sendTunnel(from, seal(Tunnel.Kind.ACCEPT, ByteArray(0), from, now))
    }

    // --- finder side
    private fun startInitiator(now: Long) {
        phase = LinkPhase.NEGOTIATING; phaseDetail = "Negotiating a ranging session"
        endRanging()
        startSession(initiator = true, peer = targetId)
    }

    private fun startSession(initiator: Boolean, peer: Int) {
        if (Build.VERSION.SDK_INT < 36) return
        startSessionApi36(initiator, peer)
    }

    @androidx.annotation.RequiresApi(36)
    private fun startSessionApi36(initiator: Boolean, peer: Int) {
        val executor = androidx.core.content.ContextCompat.getMainExecutor(context)
        val handle = TunnelTransportHandle(executor) { bytes -> host.sendTunnel(peer, seal(Tunnel.Kind.DATA, bytes, peer, System.currentTimeMillis())) }
        transport = handle
        val wantAngle = caps.uwbAzimuth && Tech.UWB in caps.usable
        val s = AndroidRangingSession(context, initiator, wantAngle, handle,
            onFix = { fix, rawTs, rawAz ->
                if (initiator) {
                    lastRanging = fix; rangingTech = fix.source.label; phase = LinkPhase.RANGING; phaseDetail = "Ranging with ${fix.source.label}"
                    // Remember where I was when this distance was measured; the walking solver needs the pair.
                    if (fix.distanceConfidence != app.linkfill.core.find.Level.LOW) walker.add(RangeSample(fix.atMs, steps.east, steps.north, fix.distanceM))
                }
                log(System.currentTimeMillis(), "ranging", "tech=${fix.source.name} d=${fix.distanceM} dc=${fix.distanceConfidence} az_raw=${rawAz} ts_raw=$rawTs")
            },
            onStatus = { t -> if (initiator) phaseDetail = t; log(System.currentTimeMillis(), "status", t) })
        s.headingProvider = { host.heading()?.trueDeg }
        rangingSession = s
        s.start()
    }

    /** One walking step: move along my current true heading, if the compass can be trusted right now. */
    private fun onStep(now: Long, strideM: Double = 0.70) {
        val h = host.heading() ?: return
        if (now - h.atMs > FindResolver.HEADING_FRESH_MS || h.uncertaintyDeg > FindResolver.MAX_HEADING_UNCERTAINTY_DEG) return
        steps.step(h.trueDeg)
        ownPdr.onStep(h.trueDeg, strideM)
    }

    private fun deliverToTransport(data: ByteArray) {
        if (Build.VERSION.SDK_INT < 36) return
        (transport as? TunnelTransportHandle)?.deliver(data)
    }

    private fun endRanging() {
        if (Build.VERSION.SDK_INT >= 36) (rangingSession as? AndroidRangingSession)?.stop()
        rangingSession = null; transport = null
    }

    /** Stops everything (mesh stopping, or the user turned finding off). */
    fun shutdown(now: Long) {
        if (responderPeer != 0) { host.sendTunnel(responderPeer, seal(Tunnel.Kind.STOP, ByteArray(0), responderPeer, now)); endRanging(); responderPeer = 0; beingFoundBy = null; host.beingFound(null) }
        stop(now)
        probe.stop()
    }

    /** The user allowed or disallowed being found while a session may be running. */
    fun findableChanged(allowed: Boolean, now: Long) {
        if (!allowed && responderPeer != 0) {
            host.sendTunnel(responderPeer, seal(Tunnel.Kind.STOP, ByteArray(0), responderPeer, now))
            endRanging(); responderPeer = 0; beingFoundBy = null; host.beingFound(null)
        }
    }

    private fun seal(kind: Tunnel.Kind, payload: ByteArray, to: Int, now: Long): ByteArray =
        Tunnel.seal(host.crew() ?: error("no crew"), host.nodeId, to, kind, payload, now)

    // ------------------------------------------------------------------ evaluation

    /** Drives timers and recomputes the UI. Call from the controller's 250 ms loop and on heading changes. */
    fun evaluate(now: Long, force: Boolean = false) {
        if (!active) {
            if (beingFoundBy != null && now % 1000 < 300) publishTargetSide(now)
            return
        }
        if (!force && now - lastEval < 60) return
        lastEval = now

        if (phase == LinkPhase.WAITING_FOR_RANGE && inPreciseRange(now)) beginRanging(now)
        if (phase == LinkPhase.REQUESTING && now - lastRequestMs > 4_000) { if (requestsSent < 3) sendRequest(now) else { phase = LinkPhase.NO_RESPONSE; phaseDetail = "${targetName}'s phone did not answer" } }
        if (phase == LinkPhase.NO_DIRECT_LINK && now - lastRequestMs > 2_000 && host.hasDirectLink(targetId) && host.member(targetId)?.findable == true && Build.VERSION.SDK_INT >= 36) sendRequest(now)
        if (phase == LinkPhase.RANGING && lastRanging != null && now - lastRanging!!.atMs > 8_000) { phase = LinkPhase.NEGOTIATING; phaseDetail = "Ranging results stopped; waiting" }

        val m = host.member(targetId)
        val mine = host.myFix()
        val rawMe = mine?.let { LocFix(it.latitude, it.longitude, it.accuracyM.toDouble(), it.fixTimeMs) }
        if (rawMe != null && (lastMyFix == null || rawMe.atMs > lastMyFix!!.atMs)) {
            lastMyFix = rawMe
            ownPdr.onFix(rawMe)
        }
        val me = if (rawMe != null && now - rawMe.atMs <= FindResolver.ME_FRESH_MS) {
            rawMe
        } else {
            ownPdr.position(now) ?: rawMe
        }
        val target = m?.takeIf { app.linkfill.mesh.MeshController.hasPosition(it.lat, it.lon) }?.let { LocFix(it.lat, it.lon, it.accuracyM.toDouble(), it.heardAt) }
        val snap = rssi.snapshot(now).takeIf { it.samples > 0 }
        val newest = lastRanging
        if (newest != null && newest.atMs > lastSolveMs && now - lastSolveMs >= 500) {
            lastSolveMs = newest.atMs
            val r = walker.solve()
            walkIssue = r.issue
            walkFix = r.solution?.let { sol -> walker.bearingFrom(sol, steps.east, steps.north).let { (b, d, u) -> WalkFix(b, u, d, newest.atMs) } }
        } else if (walkFix != null) {
            // Keep the bearing current as I keep walking: recompute from my newest position without re-solving.
            walkFix = walkFix?.let { old -> walker.solve().solution?.let { sol -> walker.bearingFrom(sol, steps.east, steps.north).let { (b, d, u) -> WalkFix(b, u, d, old.atMs) } } ?: old }
        }
        val turn = spin.solve(now)
        if (target != null && track.lastOrNull()?.atMs != target.atMs) { track.addLast(target); while (track.size > 20) track.removeFirst() }
        turn.bearingTrueDeg?.let { b -> turnFix = WalkFix(b, turn.uncertaintyDeg!!, Double.NaN, now) }
        val motionClass = motion.motionClass(now)
        val state = FindResolver.resolve(FindInputs(now, lastRanging, snap, me, target, host.heading(), host.trustAngle(), walkFix, walkIssue, turnFix, track.toList(), motionClass))
        // AirTag-style confirmation: one buzz when the arrow swings onto the person.
        val nowOn = FindResolver.isOnTarget(state.arrowDeg)
        if (nowOn && !onTarget) host.vibrate()
        onTarget = nowOn

        val arrow = state.arrowDeg?.let { smoother.update(it, now) } ?: run { smoother.reset(); null }
        if (cue.update(snap?.trend ?: app.linkfill.core.find.Trend.UNKNOWN, now) && state.mode == FindMode.BLE_PROXIMITY) host.vibrate()
        if (state.mode != lastMode) { log(now, "mode", state.mode.name); lastMode = state.mode }
        if (now - lastLogMs >= 1_000) { lastLogMs = now; logSample(now, state, snap) }

        val h = host.heading()
        _ui.value = FindUi(
            targetId, targetName, m?.findable == true, state, arrow, snap, phase, phaseDetail, caps, rangingTech,
            bluetoothHeard = now - lastHeardMs < 5_000 && lastHeardMs != 0L, directLink = host.hasDirectLink(targetId),
            headingTrue = h?.trueDeg, headingUncertainty = h?.uncertaintyDeg, declinationKnown = host.declinationKnown(),
            myFixAccuracyM = mine?.accuracyM?.toDouble(), trustAngle = host.trustAngle(), groundTruthM = groundTruth,
            logPath = logFile.absolutePath, sessionId = sessionId, beingFoundBy = null, now = now, linkMedium = host.linkMedium(targetId),
        )
    }

    private fun publishTargetSide(now: Long) { /* the banner is raised through Host.beingFound; nothing else to draw */ }

    // ------------------------------------------------------------------ anonymised log

    /** Rows hold measurements and a random session id only: no coordinates, no names. */
    private fun logSample(now: Long, s: FindState, r: RssiSnapshot?) {
        val row = listOf(
            now, sessionId, "sample", s.mode.name, s.distanceSource?.name ?: "", s.distanceM?.let { "%.2f".format(Locale.US, it) } ?: "", s.rangingConfidence?.name ?: "",
            s.arrowSource?.name ?: "", s.arrowDeg?.let { "%.1f".format(Locale.US, it) } ?: "", r?.rawLast ?: "", r?.filtered?.let { "%.1f".format(Locale.US, it) } ?: "",
            r?.let { "%.2f".format(Locale.US, it.stdDb) } ?: "", r?.trend?.name ?: "", s.proximity?.name ?: "", groundTruth?.let { "%.2f".format(Locale.US, it) } ?: "",
            phase.name, "",
        )
        append(row.joinToString(","))
    }

    private fun log(now: Long, kind: String, detail: String) = append(listOf(now, sessionId, kind, "", "", "", "", "", "", "", "", "", "", "", groundTruth ?: "", phase.name, detail.replace(',', ';')).joinToString(","))

    private fun append(line: String) {
        try {
            if (!logFile.exists()) logFile.writeText("time_ms,session,kind,mode,dist_source,distance_m,dist_conf,arrow_source,arrow_deg,rssi_raw,rssi_filtered,rssi_std,trend,proximity,ground_truth_m,phase,detail\n")
            logFile.appendText(line + "\n")
        } catch (_: java.io.IOException) { /* diagnostics only */ }
    }
}
