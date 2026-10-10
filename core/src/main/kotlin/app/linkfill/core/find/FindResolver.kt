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
    LAST_KNOWN("Last known position"),
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
    /** Bluetooth signal strength only: closeness band and trend, no direction. */
    BLE_PROXIMITY,
    /** Only an old position is known. */
    LAST_KNOWN,
    UNAVAILABLE,
}

enum class Freshness { LIVE, RECENT, STALE }

enum class Note {
    RANGING_STALE, NO_ANGLE_FROM_HARDWARE, ANGLE_UNTRUSTED, GPS_TOO_CLOSE_FOR_DIRECTION, GPS_DIRECTION_UNCERTAIN, HEADING_UNRELIABLE,
    MY_LOCATION_MISSING, MY_LOCATION_OLD, TARGET_POSITION_OLD, RSSI_LOW_CONFIDENCE, BLE_NOT_HEARD, SIGNAL_IMPROVING, SIGNAL_WORSENING,
    WALK_MORE, WALK_SIDEWAYS, WALK_AMBIGUOUS, WALK_POOR_FIT,
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
    /** True-north bearing to the last known position, for words like "north-east"; set only for LAST_KNOWN, where no arrow may be drawn. */
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
    const val MAX_HEADING_UNCERTAINTY_DEG = 40.0
    const val MIN_RSSI_CONFIDENCE = 0.35

    fun resolve(i: FindInputs): FindState {
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
                unc > MAX_ARROW_UNCERTAINTY_DEG -> notes += Note.GPS_DIRECTION_UNCERTAIN
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
            // Distance is real; any direction must come from GPS and is labelled as such.
            return FindState(FindMode.PRECISION_DISTANCE, ranging.distanceM, null, ranging.source, gpsArrow, gpsArrowUnc, if (gpsArrow != null) Source.GPS else null,
                rssi?.proximity, rssi?.trend ?: Trend.UNKNOWN, freshness, tAge, ranging.distanceConfidence, notes)
        }

        // Tier 2: location bearing, unless Bluetooth says we are already right next to them (GPS cannot resolve that).
        val veryClose = rssi?.proximity == Proximity.VERY_CLOSE
        if (gpsArrow != null && !veryClose) {
            return FindState(FindMode.LOCATION_BEARING, gpsDistance, gpsUncertaintyM, Source.GPS, gpsArrow, gpsArrowUnc, Source.GPS,
                rssi?.proximity, rssi?.trend ?: Trend.UNKNOWN, freshness, tAge, null, notes)
        }

        // Tier 3: Bluetooth proximity guidance, never an arrow.
        if (rssi != null) {
            if (rssi.trend == Trend.IMPROVING) notes += Note.SIGNAL_IMPROVING
            if (rssi.trend == Trend.WORSENING) notes += Note.SIGNAL_WORSENING
            return FindState(FindMode.BLE_PROXIMITY, null, null, null, null, null, null, rssi.proximity, rssi.trend, freshness, tAge, null, notes)
        }
        if (i.rssi == null || i.rssi.ageMs > 10_000) notes += Note.BLE_NOT_HEARD

        // Tier 4: last known only.
        if (tFix != null) {
            val dist = i.me?.let { Bearing.distanceM(it, tFix) }
            return FindState(FindMode.LAST_KNOWN, dist, dist?.let { kotlin.math.sqrt((i.me!!.accuracyM * i.me.accuracyM) + tFix.accuracyM * tFix.accuracyM) },
                if (dist != null) Source.LAST_KNOWN else null, null, null, null, null, Trend.UNKNOWN, freshness, tAge, null, notes,
                lastKnownBearingTrueDeg = i.me?.let { Bearing.trueBearing(it, tFix) })
        }
        return FindState(FindMode.UNAVAILABLE, null, null, null, null, null, null, null, Trend.UNKNOWN, null, null, null, notes)
    }

    /** Uncertainty-widening helper for tests and UI: angular difference that should be treated as "pointing at it". */
    fun isOnTarget(arrowDeg: Double?, toleranceDeg: Double = 15.0) = arrowDeg != null && abs(Angles.normalize180(arrowDeg)) <= toleranceDeg
}
