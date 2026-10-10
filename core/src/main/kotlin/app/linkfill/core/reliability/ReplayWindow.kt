package app.linkfill.core.reliability

/**
 * Sliding window over the peer's reliable sequence numbers (like the IPsec anti-replay window).
 * Rejects duplicates and replays, and reports which application messages are missing from our view.
 * A missing sequence number does not prove a radio packet was lost; only that we have not seen that message.
 */
class ReplayWindow(private val size: Int = 64) {
    init { require(size in 1..64) }

    enum class Status { NEW, DUPLICATE, TOO_OLD }

    var highest = 0
        private set
    private var mask = 0UL // bit i set => seq (highest - i) received

    fun status(seq: Int): Status {
        require(seq > 0)
        if (seq > highest) return Status.NEW
        val d = highest - seq
        if (d >= size) return Status.TOO_OLD
        return if (mask and (1UL shl d) != 0UL) Status.DUPLICATE else Status.NEW
    }

    fun mark(seq: Int) {
        require(seq > 0)
        if (seq > highest) {
            val shift = seq - highest
            mask = if (shift >= size) 0UL else mask shl shift
            mask = mask or 1UL
            highest = seq
        } else {
            val d = highest - seq
            if (d < size) mask = mask or (1UL shl d)
        }
    }

    fun isReceived(seq: Int): Boolean = seq in 1..highest && highest - seq < size && mask and (1UL shl (highest - seq)) != 0UL

    /** Sequence numbers below [highest] still inside the window and not yet received, at or above [from]. */
    fun missing(from: Int = 1): List<Int> =
        (maxOf(from, highest - size + 1, 1) until highest).filter { !isReceived(it) }
}
