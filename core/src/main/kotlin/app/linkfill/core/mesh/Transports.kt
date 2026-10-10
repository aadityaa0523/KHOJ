package app.linkfill.core.mesh

/**
 * The radios one mesh can use at the same time, like Google Nearby Connections hides Bluetooth and
 * Wi-Fi behind one API, plus a mobile-data relay. [cost] is a rough energy/data weight for choosing.
 */
enum class TransportKind(val label: String, val cost: Double) {
    BLE("Bluetooth LE", 1.0),
    WIFI_AWARE("Wi-Fi Aware", 2.0),
    NEARBY("Nearby (Bluetooth + Wi-Fi)", 3.0),
    INTERNET("Mobile data relay", 1.5),
    GATT("Bluetooth GATT link (older phones, iPhone)", 1.2),
}

enum class CombineMode(val label: String) {
    AUTO("Auto: add a better link when needed, all links when risky or urgent"),
    BEST_ONLY("Best link only (Bluetooth always advertises)"),
    ALL("Every available link"),
}

/**
 * Delivery record of one transport. Transports with link-layer acknowledgements (Wi-Fi Aware message
 * success, Nearby transfer status, HTTP status) report sends; Bluetooth advertising has no acknowledgement,
 * so its quality comes from the delivery ratio measured on incoming frames instead ([externalQuality]).
 */
class TransportHealth(val kind: TransportKind) {
    var up = false
    var peers = 0
    var sent = 0
    var delivered = 0
    var failed = 0
    var received = 0
    var lastRxMs = 0L
    var externalQuality: Double? = null
    private val outcomes = ArrayDeque<Boolean>()

    fun recordSend(success: Boolean) {
        if (success) delivered++ else failed++
        outcomes.addLast(success)
        if (outcomes.size > 50) outcomes.removeFirst()
    }

    /** Estimated chance a frame sent now reaches at least one peer (Laplace-smoothed recent success rate). */
    fun quality(): Double = externalQuality ?: ((outcomes.count { it } + 1.0) / (outcomes.size + 2.0))

    /** Worth sending on: up, and either has peers or reaches peers it cannot count (broadcast, relay server). */
    val usable get() = up && (peers > 0 || kind == TransportKind.BLE || kind == TransportKind.INTERNET)
}

object TransportSelector {
    const val RISKY = 0.6

    /**
     * Which local radios carry this frame. The router's duplicate filter makes sending on several safe.
     * Mobile data is not chosen here: it works as a bridge ([MeshRouter.bridge]), uploading each packet once.
     */
    fun choose(type: MeshType, risk: Double?, health: Collection<TransportHealth>, mode: CombineMode, risky: Double = RISKY): List<TransportKind> {
        val usable = health.filter { it.usable && it.kind != TransportKind.INTERNET }
        if (usable.isEmpty()) return emptyList()
        if (type == MeshType.NEIGHBOR) return usable.filter { it.kind == TransportKind.BLE }.map { it.kind } // link measurement only
        val best = usable.maxBy { it.quality() / it.kind.cost }
        val urgent = type == MeshType.SOS || type == MeshType.ACK || type == MeshType.RALLY ||
            type == MeshType.PUBLIC_SOS || type == MeshType.OFFICIAL || type == MeshType.FOUND || type == MeshType.FOUND_ACK
        return when (mode) {
            CombineMode.ALL -> usable.map { it.kind }
            CombineMode.BEST_ONLY -> listOf(best.kind)
            CombineMode.AUTO -> when {
                urgent || (risk ?: 1.0) >= risky -> usable.map { it.kind }
                else -> {
                    // Bluetooth advertising always runs (it is how new phones discover us); add the best other link
                    // when Bluetooth alone is not good enough or the other link is clearly better.
                    val ble = usable.firstOrNull { it.kind == TransportKind.BLE }
                    val other = usable.filter { it.kind != TransportKind.BLE }.maxByOrNull { it.quality() / it.kind.cost }
                    buildList {
                        if (ble != null) add(ble.kind)
                        if (other != null && (ble == null || ble.quality() < 0.8 || other.quality() > ble.quality())) add(other.kind)
                        if (isEmpty()) add(best.kind)
                    }
                }
            }
        }
    }

    /** Chance at least one chosen link delivers, assuming their failures are independent (optimistic). */
    fun combinedDelivery(chosen: List<TransportKind>, health: Collection<TransportHealth>): Double =
        1 - chosen.fold(1.0) { acc, k -> acc * (1 - (health.firstOrNull { it.kind == k }?.quality() ?: 0.0)) }

    /** Frame type without decoding the whole packet (byte 2 of the header). */
    fun typeOf(frame: ByteArray): MeshType? = if (frame.size > 2) MeshType.of(frame[2].toInt() and 0xFF) else null
}
