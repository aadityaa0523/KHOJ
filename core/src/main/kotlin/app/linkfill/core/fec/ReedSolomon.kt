package app.linkfill.core.fec

import java.security.MessageDigest

/** Arithmetic in GF(2^8) with the polynomial x^8 + x^4 + x^3 + x^2 + 1 (0x11d). */
object GF256 {
    private val exp = IntArray(512)
    private val log = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x
            log[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11d
        }
        for (i in 255 until 512) exp[i] = exp[i - 255]
    }

    fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]

    fun inv(a: Int): Int {
        require(a != 0) { "zero has no inverse" }
        return exp[255 - log[a]]
    }
}

/**
 * Systematic Reed-Solomon erasure code: k data shards plus m parity shards; any k of the k + m shards
 * rebuild the data exactly. The parity rows form a Cauchy matrix (1 / (x_i xor y_j) with
 * x_i = k + i, y_j = j), so every k-row subset of [identity; Cauchy] is invertible.
 * This recovers whole missing application-level chunks. It does not touch Bluetooth radio frames.
 */
class ReedSolomon(val k: Int, val m: Int) {
    init { require(k in 1..128 && m in 0..127 && k + m <= 256) }

    private val parity = Array(m) { i -> IntArray(k) { j -> GF256.inv((k + i) xor j) } }

    private fun row(r: Int): IntArray = if (r < k) IntArray(k) { if (it == r) 1 else 0 } else parity[r - k]

    /** Returns the m parity shards for k equally sized data shards. */
    fun encodeParity(data: List<ByteArray>): List<ByteArray> {
        require(data.size == k)
        val size = data[0].size
        require(data.all { it.size == size })
        return List(m) { i ->
            val out = ByteArray(size)
            for (j in 0 until k) {
                val c = parity[i][j]
                val d = data[j]
                for (b in 0 until size) out[b] = (out[b].toInt() xor GF256.mul(c, d[b].toInt() and 0xFF)).toByte()
            }
            out
        }
    }

    /** Rebuilds the k data shards from any k received shards (index -> bytes), or null if too few arrived. */
    fun decode(received: Map<Int, ByteArray>): List<ByteArray>? {
        if (received.size < k) return null
        val use = received.keys.sorted().take(k) // data shards first, as they need no arithmetic
        val size = received.getValue(use[0]).size
        val inverse = invert(Array(k) { row(use[it]) })
        return List(k) { j ->
            val out = ByteArray(size)
            for (l in 0 until k) {
                val c = inverse[j][l]
                if (c == 0) continue
                val s = received.getValue(use[l])
                for (b in 0 until size) out[b] = (out[b].toInt() xor GF256.mul(c, s[b].toInt() and 0xFF)).toByte()
            }
            out
        }
    }

    /** Gauss-Jordan inversion over GF(256). */
    private fun invert(a: Array<IntArray>): Array<IntArray> {
        val n = a.size
        val m = Array(n) { r -> a[r].copyOf() + IntArray(n) { if (it == r) 1 else 0 } }
        for (col in 0 until n) {
            val pivot = (col until n).firstOrNull { m[it][col] != 0 } ?: error("singular matrix")
            if (pivot != col) { val t = m[pivot]; m[pivot] = m[col]; m[col] = t }
            val invP = GF256.inv(m[col][col])
            for (c in 0 until 2 * n) m[col][c] = GF256.mul(m[col][c], invP)
            for (r in 0 until n) {
                if (r == col || m[r][col] == 0) continue
                val f = m[r][col]
                for (c in 0 until 2 * n) m[r][c] = m[r][c] xor GF256.mul(f, m[col][c])
            }
        }
        return Array(n) { r -> m[r].copyOfRange(n, 2 * n) }
    }
}

/** Splits a payload into k padded data shards plus m parity shards, and joins them back. */
object FecCodec {
    fun split(payload: ByteArray, k: Int, m: Int): List<ByteArray> {
        val shardSize = maxOf(1, (payload.size + k - 1) / k)
        val data = List(k) { i ->
            ByteArray(shardSize).also { s ->
                val from = i * shardSize
                if (from < payload.size) System.arraycopy(payload, from, s, 0, minOf(shardSize, payload.size - from))
            }
        }
        return data + ReedSolomon(k, m).encodeParity(data)
    }

    fun join(received: Map<Int, ByteArray>, k: Int, m: Int, totalLength: Int): ByteArray? {
        val data = ReedSolomon(k, m).decode(received) ?: return null
        val all = data.fold(ByteArray(0)) { acc, s -> acc + s }
        return if (totalLength <= all.size) all.copyOf(totalLength) else null
    }

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
}
