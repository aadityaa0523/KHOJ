package app.linkfill.core.find

import kotlin.math.abs

enum class Level { LOW, MEDIUM, HIGH }

/** Where a number came from. The UI shows this next to every reading. */
enum class Source(val label: String) {
    UWB("Ultra-wideband"),
    BLE_CHANNEL_SOUNDING("Bluetooth Channel Sounding"),
    WIFI_RTT("Wi-Fi round-trip time"),
    BLE_RSSI_RANGING("Bluetooth signal (Android ranging)"),
    GPS("GPS positions"),
    BLE_SCAN_RSSI("Bluetooth signal strength"),
    RANGE_WALK("Walking + measured distances"),
    BLE_TURN("Bluetooth signal while turning"),
    BLE_TREND("Bluetooth signal getting stronger or weaker as you walk"),
    PREDICTED("Predicted from their last movements"),
}

/**
 * One real measurement from Android's ranging module. [azimuthDeg] is relative to the front of the measuring phone
 * (positive to the right), null when the hardware did not provide an angle. [headingAtMs] is the phone's true heading when
 * the measurement was taken, used to keep the arrow pointing right while the phone turns between measurements.
 */
data class RangingFix(
    val source: Source,
    val distanceM: Double,
    val distanceConfidence: Level,
    val azimuthDeg: Double?,
    val azimuthConfidence: Level?,
    val atMs: Long,
    val headingTrueAtMeasurementDeg: Double?,
)

data class HeadingFix(val trueDeg: Double, val uncertaintyDeg: Double, val atMs: Long)

enum class FindMode {
    /** Real hardware range and angle. */
    PRECISION_DIRECTION,
    /** Real hardware range, no angle. */
    PRECISION_DISTANCE,
    /** Real hardware distances measured from several places while walking give the direction (estimated, with uncertainty). */
    RANGE_WALK,
    /** Direction computed from two GPS fixes and the phone's heading. */
    LOCATION_BEARING,
    /** Bluetooth signal strength: closeness band and trend; an arrow only from a completed turn (see [SpinFinder]). */
    BLE_PROXIMITY,
    /** Their positions stopped arriving: where they probably are now, from their last movements (see [Predict]). */
    PREDICTED,
    UNAVAILABLE,
}

enum class Freshness { LIVE, RECENT, STALE }

enum class Note {
    RANGING_STALE, NO_ANGLE_FROM_HARDWARE, ANGLE_UNTRUSTED, GPS_TOO_CLOSE_FOR_DIRECTION, GPS_DIRECTION_UNCERTAIN, HEADING_UNRELIABLE,
    MY_LOCATION_MISSING, MY_LOCATION_OLD, TARGET_POSITION_OLD, RSSI_LOW_CONFIDENCE, BLE_NOT_HEARD, SIGNAL_IMPROVING, SIGNAL_WORSENING,
    WALK_MORE, WALK_SIDEWAYS, WALK_AMBIGUOUS, WALK_POOR_FIT,
    /** No arrow yet: turn slowly all the way round with the phone at your chest. */
    TURN_TO_FIND,
}

/** Direction to the target found by walking while ranging, valid at [atMs]. [bearingTrueDeg] is the bearing from where I am NOW. */
data class WalkFix(val bearingTrueDeg: Double, val uncertaintyDeg: Double, val distanceM: Double, val atMs: Long)

data class FindInputs(
    val now: Long,
    val ranging: RangingFix?,
    val rssi: RssiSnapshot?,
    val me: LocFix?,
    val target: LocFix?,
    val heading: HeadingFix?,
    /** Set only after the ranging angle's units and sign were validated on real hardware (see FIND_MEMBER_TEST_PLAN). */
    val trustRangingAngle: Boolean = false,
    val walk: WalkFix? = null,
    /** Why no walking direction exists yet, to tell the user what to do. */
    val walkIssue: WalkIssue? = null,
    /** Direction from a completed turn (body shadow on Bluetooth signal); bearing is true north. */
    val turn: WalkFix? = null,
    /** Their recent positions, oldest first, for predicting where they are when updates stop. */
    val targetTrack: List<LocFix> = emptyList(),
)

data class FindState(
    val mode: FindMode,
    val distanceM: Double?,
    /** Metres of uncertainty when it can be stated honestly (GPS-derived); null when the source gives only a confidence level. */
    val distanceUncertaintyM: Double?,
    val distanceSource: Source?,
    /** 0 = straight ahead, +right. Null whenever no trustworthy direction exists. */
    val arrowDeg: Double?,
    val arrowUncertaintyDeg: Double?,
    val arrowSource: Source?,
    val proximity: Proximity?,
    val trend: Trend,
    val targetFreshness: Freshness?,
    val targetFixAgeMs: Long?,
    val rangingConfidence: Level?,
    val notes: Set<Note>,
    /** True-north bearing to the predicted position, for words like "north-east". */
    val lastKnownBearingTrueDeg: Double? = null,
)

/**
 * Chooses what the UI may claim, from the best source down, and withholds anything the data cannot support.
 * Pure function of its inputs so the policy is unit-tested (see FindResolverTest).
 */
object FindResolver {
    const val RANGING_FRESH_MS = 3_000L
    const val TARGET_LIVE_MS = 10_000L
    const val TARGET_RECENT_MS = 30_000L
    const val ME_FRESH_MS = 10_000L
    const val HEADING_FRESH_MS = 2_000L
    const val MAX_ARROW_UNCERTAINTY_DEG = 45.0
    /** GPS and predicted arrows may be wider: the wedge drawn around the arrow shows exactly how unsure it is. */
    const val MAX_WIDE_ARROW_DEG = 90.0
    /** Positions older than this are not used even for a prediction. */
    const val PREDICT_MAX_MS = 10 * 60_000L
    /** Wedge for the warmer/colder arrow: it says ahead or behind, not an exact bearing. */
    const val TREND_ARROW_DEG = 60.0
    const val MAX_HEADING_UNCERTAINTY_DEG = 40.0
    const val MIN_RSSI_CONFIDENCE = 0.35
    /** A turn result stays usable this long; after that the person may have moved. */
    const val TURN_FRESH_MS = 45_000L
    /** A heading this recent may turn the compass direction into an arrow, however rough the compass says it is. */
    const val COMPASS_FRESH_MS = 5_000L

    fun resolve(i: FindInputs): FindState = withCompassArrow(i, resolveBest(i))

    /**
     * The compass word ("12 m NE") as an arrow. When no better source gave a direction but both positions and a compass
     * heading exist, point at their position (predicted forward if it is a little old) relative to where the phone faces.
     * The wedge carries the GPS and compass error, so a rough direction looks rough; it is never hidden for being rough.
     */
    private fun withCompassArrow(i: FindInputs, s: FindState): FindState {
        if (s.arrowDeg != null) return s
        val h = i.heading?.takeIf { i.now - it.atMs <= COMPASS_FRESH_MS } ?: return s
        val me = i.me ?: return s
        val t = i.target?.takeIf { i.now - it.atMs <= PREDICT_MAX_MS } ?: return s
        val p = Predict.position(i.targetTrack.ifEmpty { listOf(t) }, i.now) ?: return s
        val unc = (Bearing.uncertaintyDeg(Bearing.distanceM(me, p), me.accuracyM, p.accuracyM) + h.uncertaintyDeg).coerceAtMost(180.0)
        return s.copy(arrowDeg = Angles.relative(Bearing.trueBearing(me, p), h.trueDeg), arrowUncertaintyDeg = unc,
            arrowSource = if (i.now - t.atMs <= TARGET_RECENT_MS) Source.GPS else Source.PREDICTED)
    }

    private fun resolveBest(i: FindInputs): FindState {
        val notes = mutableSetOf<Note>()
        // Android's BLE-RSSI "ranging" is only a signal-strength estimate; it must never be presented as precise hardware ranging.
        val ranging = i.ranging?.takeIf { i.now - it.atMs <= RANGING_FRESH_MS && it.source != Source.BLE_RSSI_RANGING }
        if (i.ranging != null && ranging == null) notes += Note.RANGING_STALE

        val tFix = i.target
        val tAge = tFix?.let { i.now - it.atMs }
        val freshness = tAge?.let { if (it <= TARGET_LIVE_MS) Freshness.LIVE else if (it <= TARGET_RECENT_MS) Freshness.RECENT else Freshness.STALE }
        val rssi = i.rssi?.takeIf { it.proximity != null && it.confidence >= MIN_RSSI_CONFIDENCE }
        if (i.rssi != null && rssi == null && i.rssi.ageMs <= 10_000) notes += Note.RSSI_LOW_CONFIDENCE
        val headingOk = i.heading != null && i.now - i.heading.atMs <= HEADING_FRESH_MS && i.heading.uncertaintyDeg <= MAX_HEADING_UNCERTAINTY_DEG
        if (i.heading != null && !headingOk) notes += Note.HEADING_UNRELIABLE

        // GPS-derived direction, if both fixes and the heading allow a defensible one.
        val me = i.me?.takeIf { i.now - it.atMs <= ME_FRESH_MS }
        if (i.me == null) notes += Note.MY_LOCATION_MISSING else if (me == null) notes += Note.MY_LOCATION_OLD
        var gpsDistance: Double? = null; var gpsUncertaintyM: Double? = null; var gpsArrow: Double? = null; var gpsArrowUnc: Double? = null
        if (me != null && tFix != null) {
            gpsDistance = Bearing.distanceM(me, tFix)
            gpsUncertaintyM = kotlin.math.sqrt(me.accuracyM * me.accuracyM + tFix.accuracyM * tFix.accuracyM)
            val unc = Bearing.uncertaintyDeg(gpsDistance, me.accuracyM, tFix.accuracyM)
            val live = freshness == Freshness.LIVE || freshness == Freshness.RECENT
            if (!live) notes += Note.TARGET_POSITION_OLD
            when {
                !live -> Unit
                unc >= 180.0 -> notes += Note.GPS_TOO_CLOSE_FOR_DIRECTION
                unc > MAX_WIDE_ARROW_DEG -> notes += Note.GPS_DIRECTION_UNCERTAIN
                !headingOk -> Unit
                else -> {
                    gpsArrow = Angles.relative(Bearing.trueBearing(me, tFix), i.heading!!.trueDeg)
                    gpsArrowUnc = unc + i.heading.uncertaintyDeg // errors add; do not pretend they cancel
                }
            }
        } else if (tFix != null && tAge != null && tAge > TARGET_RECENT_MS) notes += Note.TARGET_POSITION_OLD

        // Tier 1: real hardware ranging.
        if (ranging != null) {
            val az = ranging.azimuthDeg
            if (az != null && ranging.azimuthConfidence != null && ranging.azimuthConfidence != Level.LOW && i.trustRangingAngle) {
                // Keep the arrow right while the phone turns between the measurement and now.
                val turned = if (headingOk && ranging.headingTrueAtMeasurementDeg != null) Angles.diff(i.heading!!.trueDeg, ranging.headingTrueAtMeasurementDeg) else 0.0
                return FindState(FindMode.PRECISION_DIRECTION, ranging.distanceM, null, ranging.source, Angles.normalize180(az - turned), null, ranging.source,
                    rssi?.proximity, rssi?.trend ?: Trend.UNKNOWN, freshness, tAge, ranging.distanceConfidence, notes)
            }
            notes += if (az == null) Note.NO_ANGLE_FROM_HARDWARE else Note.ANGLE_UNTRUSTED
            // Distances measured while walking can supply a direction when the hardware does not.
            val walk = i.walk?.takeIf { i.now - it.atMs <= RANGING_FRESH_MS }
            if (walk != null && headingOk && walk.uncertaintyDeg <= MAX_ARROW_UNCERTAINTY_DEG) {
                return FindState(FindMode.RANGE_WALK, ranging.distanceM, null, ranging.source, Angles.relative(walk.bearingTrueDeg, i.heading!!.trueDeg),
                    walk.uncertaintyDeg + i.heading.uncertaintyDeg, Source.RANGE_WALK, rssi?.proximity, rssi?.trend ?: Trend.UNKNOWN, freshness, tAge, ranging.distanceConfidence, notes)
            }
            when (i.walkIssue) {
                WalkIssue.WALK_MORE, WalkIssue.NOT_ENOUGH_SAMPLES -> notes += Note.WALK_MORE
                WalkIssue.WALK_SIDEWAYS -> notes += Note.WALK_SIDEWAYS
                WalkIssue.AMBIGUOUS -> notes += Note.WALK_AMBIGUOUS
                WalkIssue.POOR_FIT, WalkIssue.TOO_CLOSE_TO_TELL -> notes += Note.WALK_POOR_FIT
                null -> Unit
            }
            // Distance is real; any direction must come from GPS or a turn, and is labelled as such.
            val (arrow, unc, src) = turnArrow(i, headingOk)?.takeIf { gpsArrow == null } ?: Triple(gpsArrow, gpsArrowUnc, if (gpsArrow != null) Source.GPS else null)
            return FindState(FindMode.PRECISION_DISTANCE, ranging.distanceM, null, ranging.source, arrow, unc, src,
                rssi?.proximity, rssi?.trend ?: Trend.UNKNOWN, freshness, tAge, ranging.distanceConfidence, notes)
        }

        // Tier 2: location bearing, unless Bluetooth says we are already right next to them (GPS cannot resolve that).
        val veryClose = rssi?.proximity == Proximity.VERY_CLOSE
        if (gpsArrow != null && !veryClose) {
            return FindState(FindMode.LOCATION_BEARING, gpsDistance, gpsUncertaintyM, Source.GPS, gpsArrow, gpsArrowUnc, Source.GPS,
                rssi?.proximity, rssi?.trend ?: Trend.UNKNOWN, freshness, tAge, null, notes)
        }

        val predicted = predictedArrow(i, headingOk)

        // Tier 3: Bluetooth closeness. Arrow from a completed turn; else warmer/colder as an arrow relative to the way
        // you are walking (stronger: keep going, weaker: turn round); else the predicted position's arrow.
        if (rssi != null) {
            if (rssi.trend == Trend.IMPROVING) notes += Note.SIGNAL_IMPROVING
            if (rssi.trend == Trend.WORSENING) notes += Note.SIGNAL_WORSENING
            val t = turnArrow(i, headingOk)
                ?: when (rssi.trend) {
                    Trend.IMPROVING -> Triple(0.0, TREND_ARROW_DEG, Source.BLE_TREND)
                    Trend.WORSENING -> Triple(180.0, TREND_ARROW_DEG, Source.BLE_TREND)
                    // GPS cannot resolve a few metres, so a position-based arrow only while Bluetooth says medium or far.
                    else -> predicted?.takeIf { rssi.proximity == Proximity.MEDIUM || rssi.proximity == Proximity.FAR }?.let { Triple(it.first, it.second, Source.PREDICTED) }
                }
            if (t == null) notes += Note.TURN_TO_FIND
            return FindState(FindMode.BLE_PROXIMITY, null, null, null, t?.first, t?.second, t?.third, rssi.proximity, rssi.trend, freshness, tAge, null, notes)
        }
        if (i.rssi == null || i.rssi.ageMs > 10_000) notes += Note.BLE_NOT_HEARD

        // Tier 4: their positions stopped: point at where they probably are now, wedge widening with the gap.
        if (tFix != null && tAge != null && tAge <= PREDICT_MAX_MS) {
            val p = Predict.position(i.targetTrack.ifEmpty { listOf(tFix) }, i.now)!!
            val dist = i.me?.let { Bearing.distanceM(it, p) }
            return FindState(FindMode.PREDICTED, dist, dist?.let { kotlin.math.sqrt(i.me!!.accuracyM * i.me.accuracyM + p.accuracyM * p.accuracyM) },
                if (dist != null) Source.PREDICTED else null, predicted?.first, predicted?.second, predicted?.let { Source.PREDICTED },
                null, Trend.UNKNOWN, freshness, tAge, null, notes, lastKnownBearingTrueDeg = i.me?.let { Bearing.trueBearing(it, p) })
        }
        return FindState(FindMode.UNAVAILABLE, null, null, null, null, null, null, null, Trend.UNKNOWN, null, null, null, notes)
    }

    /** Arrow to the predicted position, if my fix and compass allow one and the wedge stays under [MAX_WIDE_ARROW_DEG]. */
    private fun predictedArrow(i: FindInputs, headingOk: Boolean): Pair<Double, Double>? {
        val tFix = i.target ?: return null
        if (!headingOk || i.now - tFix.atMs > PREDICT_MAX_MS) return null
        val me = i.me?.takeIf { i.now - it.atMs <= ME_FRESH_MS } ?: return null
        val p = Predict.position(i.targetTrack.ifEmpty { listOf(tFix) }, i.now) ?: return null
        val d = Bearing.distanceM(me, p)
        val unc = Bearing.uncertaintyDeg(d, me.accuracyM, p.accuracyM) + i.heading!!.uncertaintyDeg
        if (unc > MAX_WIDE_ARROW_DEG) return null
        return Angles.relative(Bearing.trueBearing(me, p), i.heading.trueDeg) to unc
    }

    private fun turnArrow(i: FindInputs, headingOk: Boolean): Triple<Double?, Double?, Source?>? {
        val t = i.turn?.takeIf { i.now - it.atMs <= TURN_FRESH_MS } ?: return null
        if (!headingOk) return null
        return Triple(Angles.relative(t.bearingTrueDeg, i.heading!!.trueDeg), t.uncertaintyDeg + i.heading.uncertaintyDeg, Source.BLE_TURN)
    }

    /** Uncertainty-widening helper for tests and UI: angular difference that should be treated as "pointing at it". */
    fun isOnTarget(arrowDeg: Double?, toleranceDeg: Double = 15.0) = arrowDeg != null && abs(Angles.normalize180(arrowDeg)) <= toleranceDeg
}
