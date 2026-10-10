package app.linkfill.core.sim

import app.linkfill.core.mesh.CombineMode
import app.linkfill.core.mesh.CrewKey
import app.linkfill.core.mesh.MeshConfig
import app.linkfill.core.mesh.MeshListener
import app.linkfill.core.mesh.MeshMessage
import app.linkfill.core.mesh.MeshRouter
import app.linkfill.core.mesh.TransportHealth
import app.linkfill.core.mesh.TransportKind
import app.linkfill.core.mesh.TransportSelector
import java.io.File
import java.security.SecureRandom
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.random.Random

/** Crowd strip and radio assumptions for the range experiment. */
class RangeScenario(
    val lengthM: Double = 2000.0,
    val widthM: Double = 200.0,
    val peoplePerM2: Double = 1.0,
    val adoption: Double,
    val coded: Boolean,
    val suppression: Boolean,
    /** Dense crowds absorb 2.4 GHz strongly; assumed exponent. */
    val pathLossExponent: Double = 3.2,
    val shadowSigmaDb: Double = 6.0,
    /** Also send over Wi-Fi Aware (acknowledged unicast to discovered peers, up to 3 tries). */
    val wifiAware: Boolean = false,
    /** Share of app phones that have mobile data and use the relay server. */
    val internetShare: Double = 0.0,
    val internetLatencyMs: Long = 2_000,
) {
    val nodes get() = (lengthM * widthM * peoplePerM2 * adoption).toInt()
}

class RangeResult(val reachByBin: List<Double>, val medianHopsByBin: List<Double?>, val transmissions: Double, val medianLatencyFarS: Double?)

/**
 * Floods one encrypted crew message from one end of a crowd strip through phones running the app,
 * using the real [MeshRouter] (dedup, TTL, signal-weighted delays, suppression). People stand still for the
 * 60 s of the experiment. Every radio number is an assumption (see [RadioEnv]); the point is comparing
 * designs and adoption levels, not predicting exact metres.
 */
object MeshRange {
    val BINS = listOf(0.0, 250.0, 500.0, 1000.0, 1500.0, 2000.0)

    fun run(s: RangeScenario, seed: Long, ttl: Int = 8): RangeResult {
        val r = Random(seed)
        val crew = CrewKey.create(SecureRandom.getInstance("SHA1PRNG").apply { setSeed(seed) })
        val n = s.nodes
        val xs = DoubleArray(n) { r.nextDouble(0.0, s.lengthM) }
        val ys = DoubleArray(n) { r.nextDouble(0.0, s.widthM) }
        xs[0] = 20.0; ys[0] = s.widthM / 2
        val firstRx = LongArray(n) { -1 }
        val hops = IntArray(n)
        var now = 0L
        val config = MeshConfig(defaultTtl = ttl, suppression = s.suppression, codedSupported = s.coded)
        val routers = Array(n) { i ->
            MeshRouter(1000 + i, crew, config, Random(seed * 31 + i), object : MeshListener {
                override fun onMessage(originId: Int, message: MeshMessage, hops: Int, rssi: Int, ageS: Long) {
                    if (firstRx[i] < 0) { firstRx[i] = now; hopsOf(i, hops + 1) }
                }
                fun hopsOf(i: Int, h: Int) { hops[i] = h }
            })
        }
        firstRx[0] = 0
        // Spatial grid so each transmission only checks nearby phones.
        val sens = if (s.coded) RadioEnv.SENSITIVITY_CODED else RadioEnv.SENSITIVITY_1M
        val maxRange = Math.pow(10.0, (-40.0 - (sens - 3 * s.shadowSigmaDb)) / (10 * s.pathLossExponent))
        val cell = maxRange
        val grid = HashMap<Long, MutableList<Int>>()
        fun cellOf(x: Double, y: Double) = ((x / cell).toLong() shl 20) or (y / cell).toLong()
        for (i in 0 until n) grid.getOrPut(cellOf(xs[i], ys[i])) { mutableListOf() } += i

        val online = BooleanArray(n) { r.nextDouble() < s.internetShare }
        val health = Array(n) { i ->
            listOf(
                TransportHealth(TransportKind.BLE).apply { up = true },
                TransportHealth(TransportKind.WIFI_AWARE).apply { up = s.wifiAware; peers = 1 },
                TransportHealth(TransportKind.INTERNET).apply { up = online[i] },
            )
        }
        val serverQueue = ArrayList<Triple<Long, Int, ByteArray>>() // deliver-at, sender, frame
        var uploads = 0
        for (i in 0 until n) if (online[i]) routers[i].bridge = { f, _ -> serverQueue += Triple(now + s.internetLatencyMs, i, f); uploads++ }
        val awareMax = Math.pow(10.0, (15.0 - 40.0 - (AWARE_SENSITIVITY - 3 * s.shadowSigmaDb)) / (10 * s.pathLossExponent))
        routers[0].originate(MeshMessage.Text("Amma is at the blue tent, sector 4"), 1_000_000L)
        var transmissions = 0
        val slot = 250L
        now = 1_000_000L
        val end = now + 60_000
        while (now <= end) {
            val sending = ArrayList<Pair<Int, ByteArray>>()
            val awareSending = ArrayList<Pair<Int, ByteArray>>()
            for (i in 0 until n) {
                val f = routers[i].nextFrame(now, allowFiller = false) ?: continue
                val kinds = TransportSelector.choose(TransportSelector.typeOf(f)!!, 0.0, health[i], CombineMode.ALL)
                if (TransportKind.BLE in kinds) sending += i to f
                if (TransportKind.WIFI_AWARE in kinds) awareSending += i to f
            }
            transmissions += sending.size + awareSending.size
            // Relay server: online phones receive what other online phones uploaded, after polling latency.
            val due = serverQueue.filter { it.first <= now }
            serverQueue.removeAll(due.toSet())
            for ((_, from, f) in due) for (j in 0 until n) if (online[j] && j != from) routers[j].onFrame(f, -100, false, now, TransportKind.INTERNET)
            // Wi-Fi Aware: acknowledged unicast to every discovered peer in range, up to 3 tries each.
            for ((tx, f) in awareSending) {
                val cx = (xs[tx] / cell).toLong(); val cy = (ys[tx] / cell).toLong()
                for (dx in -2L..2L) for (dy in -2L..2L) {
                    val list = grid[((cx + dx) shl 20) or (cy + dy)] ?: continue
                    for (rx in list) {
                        if (rx == tx) continue
                        val d = hypot(xs[tx] - xs[rx], ys[tx] - ys[rx])
                        if (d > awareMax) continue
                        val rssi = 15.0 - 40.0 - 10 * s.pathLossExponent * log10(d.coerceAtLeast(1.0)) + pairShadow(tx, rx, seed xor 0x5A5A) * s.shadowSigmaDb + 2 * LinkState.gauss(r)
                        val p = 1.0 / (1.0 + exp(-(rssi - AWARE_SENSITIVITY) / 1.5))
                        if (r.nextDouble() < 1 - Math.pow(1 - p, 3.0)) routers[rx].onFrame(f, rssi.toInt(), false, now, TransportKind.WIFI_AWARE)
                    }
                }
            }
            // Crude collision model: each extra audible transmitter in the same slot costs a little reception.
            for ((tx, bytes) in sending) {
                val cx = (xs[tx] / cell).toLong(); val cy = (ys[tx] / cell).toLong()
                for (dx in -1L..1L) for (dy in -1L..1L) {
                    val list = grid[((cx + dx) shl 20) or (cy + dy)] ?: continue
                    for (rx in list) {
                        if (rx == tx) continue
                        val d = hypot(xs[tx] - xs[rx], ys[tx] - ys[rx])
                        if (d > maxRange) continue
                        val shadow = pairShadow(tx, rx, seed) * s.shadowSigmaDb
                        val rssi = -40.0 - 10 * s.pathLossExponent * log10(d.coerceAtLeast(1.0)) + shadow + 2 * LinkState.gauss(r)
                        val collision = exp(-0.01 * (sending.size - 1).coerceAtMost(200))
                        if (r.nextDouble() < RadioEnv.decodeProbability(rssi, s.coded) * collision * 0.95) {
                            routers[rx].onFrame(bytes, rssi.toInt(), s.coded, now)
                        }
                    }
                }
            }
            now += slot
        }
        val dist = DoubleArray(n) { hypot(xs[it] - xs[0], ys[it] - ys[0]) }
        val reach = BINS.zipWithNext().map { (a, b) -> (1 until n).filter { dist[it] >= a && dist[it] < b }.let { idx -> if (idx.isEmpty()) Double.NaN else idx.count { firstRx[it] >= 0 }.toDouble() / idx.size } }
        val medHops = BINS.zipWithNext().map { (a, b) -> (1 until n).filter { dist[it] >= a && dist[it] < b && firstRx[it] >= 0 }.map { hops[it].toDouble() }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] } }
        val far = (1 until n).filter { dist[it] >= 1500 && firstRx[it] >= 0 }.map { (firstRx[it] - 1_000_000L) / 1000.0 }.sorted()
        return RangeResult(reach, medHops, (transmissions + uploads).toDouble(), if (far.isEmpty()) null else far[far.size / 2])
    }

    /** Assumed Wi-Fi Aware link: +15 dBm transmit, -82 dBm sensitivity for low-rate frames (not measured). */
    const val AWARE_SENSITIVITY = -82.0

    /** Fixed per-pair shadowing (people stand still), symmetric, standard normal. */
    private fun pairShadow(a: Int, b: Int, seed: Long): Double {
        val lo = minOf(a, b).toLong(); val hi = maxOf(a, b).toLong()
        return LinkState.gauss(Random(seed xor (lo * 1_000_003L + hi)))
    }
}

/** Entry point for `./gradlew :core:runMeshRange`. Writes docs/MESH_RANGE_RESULTS.md. */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "MESH_RANGE_RESULTS.md")
    val trials = 4
    val started = System.currentTimeMillis()
    val text = buildString {
        appendLine("# Mesh range: measured in simulation")
        appendLine()
        appendLine("Generated by `./gradlew :core:runMeshRange` on ${LocalDate.now()}. Rerun instead of editing.")
        appendLine()
        appendLine("A 2,000 m x 200 m crowd strip (think of a ghat or a mela road) with 1 person per square metre, so 400,000 people.")
        appendLine("Only the given share of them run the app and relay. One crew message starts 20 m from one end; every relay runs the")
        appendLine("app's real `MeshRouter` code. People stand still for the 60 s run. $trials trials per row.")
        appendLine()
        appendLine("**Assumptions, not measurements:** path-loss exponent 3.2 for a dense crowd, 6 dB shadowing, BLE 1M sensitivity")
        appendLine("-94 dBm and LE Coded -104 dBm at the phone, a crude collision penalty. Real phones, bodies and interference will differ;")
        appendLine("use this to compare adoption levels and designs, then measure on real phones.")
        appendLine()
        appendLine("Reach = share of app phones in each distance band that received the message within 60 s.")
        appendLine()
        appendLine("| App adoption | Radio | Suppression | Phones | 0-250 m | 250-500 m | 500-1000 m | 1000-1500 m | 1500-2000 m | Median hops at 1-1.5 km | Median time at 1.5 km+ | Transmissions |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|")
        for (adoption in listOf(0.0025, 0.01, 0.03)) for (coded in listOf(false, true)) for (suppression in listOf(true, false)) {
            if (!suppression && adoption != 0.01) continue // the suppression comparison is shown at one adoption level
            val sc = RangeScenario(adoption = adoption, coded = coded, suppression = suppression)
            val runs = (1..trials).map { MeshRange.run(sc, 5_000L + it) }
            val reach = MeshRange.BINS.indices.toList().dropLast(1).map { b -> runs.map { it.reachByBin[b] }.filter { !it.isNaN() }.average() }
            val hops = runs.mapNotNull { it.medianHopsByBin[3] }.average()
            val lat = runs.mapNotNull { it.medianLatencyFarS }.let { if (it.isEmpty()) "-" else "%.1f s".format(it.average()) }
            append("| ${"%.2f".format(adoption * 100)}% | ${if (coded) "LE Coded" else "1M"} | ${if (suppression) "on" else "off"} | ${sc.nodes} | ")
            append(reach.joinToString(" | ") { if (it.isNaN()) "-" else "%.0f%%".format(it * 100) })
            appendLine(" | ${if (hops.isNaN()) "-" else "%.1f".format(hops)} | $lat | ${"%.0f".format(runs.map { it.transmissions }.average())} |")
            println("adoption=$adoption coded=$coded suppression=$suppression done")
        }
        appendLine()
        appendLine("## Combining transports")
        appendLine()
        appendLine("Same strip. Each phone sends every frame on every available transport (combine mode ALL); the router drops the")
        appendLine("duplicate copies. **Wi-Fi Aware** is modelled as acknowledged unicast to each peer in range with 3 tries, assuming")
        appendLine("+15 dBm transmit and -82 dBm sensitivity. **Mobile data relay**: the given share of app phones have signal; what")
        appendLine("one uploads reaches the others after about 2 s, and they re-broadcast it locally. Each online phone uploads a packet")
        appendLine("once, the first time it hears it, regardless of local relay suppression. Transmissions include uploads.")
        appendLine("All assumptions, not measurements.")
        appendLine()
        appendLine("| App adoption | Transports | Phones | 0-250 m | 250-500 m | 500-1000 m | 1000-1500 m | 1500-2000 m | Median hops at 1-1.5 km | Median time at 1.5 km+ | Transmissions |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
        for (adoption in listOf(0.0025, 0.01)) for ((label, sc) in listOf(
            "Bluetooth 1M" to RangeScenario(adoption = adoption, coded = false, suppression = true),
            "Bluetooth 1M + Wi-Fi Aware" to RangeScenario(adoption = adoption, coded = false, suppression = true, wifiAware = true),
            "Bluetooth 1M + Wi-Fi Aware + data relay (10% online)" to RangeScenario(adoption = adoption, coded = false, suppression = true, wifiAware = true, internetShare = 0.10),
            "Bluetooth Coded + Wi-Fi Aware + data relay (10% online)" to RangeScenario(adoption = adoption, coded = true, suppression = true, wifiAware = true, internetShare = 0.10),
        )) {
            val runs = (1..trials).map { MeshRange.run(sc, 7_000L + it) }
            val reach = MeshRange.BINS.indices.toList().dropLast(1).map { b -> runs.map { it.reachByBin[b] }.filter { !it.isNaN() }.average() }
            val hops = runs.mapNotNull { it.medianHopsByBin[3] }.average()
            val lat = runs.mapNotNull { it.medianLatencyFarS }.let { if (it.isEmpty()) "-" else "%.1f s".format(it.average()) }
            append("| ${"%.2f".format(adoption * 100)}% | $label | ${sc.nodes} | ")
            append(reach.joinToString(" | ") { if (it.isNaN()) "-" else "%.0f%%".format(it * 100) })
            appendLine(" | ${if (hops.isNaN()) "-" else "%.1f".format(hops)} | $lat | ${"%.0f".format(runs.map { it.transmissions }.average())} |")
            println("combined adoption=$adoption $label done")
        }
        appendLine()
        appendLine("Reading the table: messages carry a hop limit of 8 (TTL). On 1M each hop covers less ground, so the hop limit")
        appendLine("runs out around 1 km; LE Coded covers more ground per hop and crosses the strip in 5-6 hops. Turning relay")
        appendLine("suppression off roughly triples the transmissions and, through collisions, can reduce reach.")
        appendLine()
        appendLine("Total time: ${(System.currentTimeMillis() - started) / 1000} s.")
    }
    out.parentFile?.mkdirs()
    out.writeText(text)
    println("wrote ${out.absolutePath}")
}
