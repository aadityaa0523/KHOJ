package app.linkfill.core.sim

import app.linkfill.core.mesh.CrewKey
import app.linkfill.core.mesh.MeshConfig
import app.linkfill.core.mesh.MeshListener
import app.linkfill.core.mesh.MeshMessage
import app.linkfill.core.mesh.MeshPacket
import app.linkfill.core.mesh.MeshRouter
import app.linkfill.core.mesh.RelayRule
import java.security.SecureRandom
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.random.Random

data class CrowdMetrics(
    val crowdSize: Int,
    val ruleName: String,
    val deliveryRate: Double,
    val medianLatencyS: Double,
    val p95LatencyS: Double,
    val txPerDelivered: Double,
    val totalDuplicates: Double,
)

object CrowdRoutingExperiment {

    fun runTrial(
        nodes: Int,
        rule: RelayRule,
        useAdaptiveDensity: Boolean,
        seed: Long,
    ): CrowdMetrics {
        val r = Random(seed)
        val crew = CrewKey.create(SecureRandom.getInstance("SHA1PRNG").apply { setSeed(seed) })
        val areaSide = when {
            nodes <= 5 -> 30.0
            nodes <= 20 -> 50.0
            nodes <= 100 -> 100.0
            else -> 220.0
        }
        val xs = DoubleArray(nodes) { r.nextDouble(0.0, areaSide) }
        val ys = DoubleArray(nodes) { r.nextDouble(0.0, areaSide) }
        xs[0] = 5.0; ys[0] = 5.0

        // Spatial grid with 100 m cell size so each transmission only tests nearby nodes
        val cellSize = 100.0
        fun cellKey(x: Double, y: Double): Long = ((x / cellSize).toLong() shl 20) or (y / cellSize).toLong()
        val grid = HashMap<Long, MutableList<Int>>()
        for (i in 0 until nodes) {
            grid.getOrPut(cellKey(xs[i], ys[i])) { mutableListOf() } += i
        }

        val firstRx = LongArray(nodes) { -1 }
        var now = 1_000_000L
        val config = if (useAdaptiveDensity) {
            MeshConfig(suppression = true, relayRule = rule)
        } else {
            object : MeshConfig(suppression = true, relayRule = rule, defaultTtl = 6) {
                override fun densityPolicy(neighbors5s: Int) = app.linkfill.core.mesh.DensityPolicy(
                    routineTtl = 6,
                    urgentTtl = 6,
                    relayProbability = 1.0,
                    suppressAfter = 3,
                    beaconPeriodMs = 3_000L
                )
            }
        }

        val routers = Array(nodes) { i ->
            MeshRouter(1000 + i, crew, config, Random(seed * 31 + i), object : MeshListener {
                override fun onMessage(originId: Int, message: MeshMessage, hops: Int, rssi: Int, ageS: Long) {
                    if (firstRx[i] < 0) firstRx[i] = now
                }
            })
        }
        firstRx[0] = now

        // Pre-warm local density with spatial grid
        for (i in 0 until nodes) {
            val cx = (xs[i] / cellSize).toLong()
            val cy = (ys[i] / cellSize).toLong()
            for (dx in -1L..1L) for (dy in -1L..1L) {
                val neighbors = grid[((cx + dx) shl 20) or (cy + dy)] ?: continue
                for (j in neighbors) {
                    if (i == j) continue
                    val d = hypot(xs[i] - xs[j], ys[i] - ys[j])
                    if (d > cellSize) continue
                    val rssi = -40.0 - 10 * 3.2 * log10(d.coerceAtLeast(1.0))
                    if (rssi > -95.0) {
                        routers[i].links.onFrame(1000 + j, 1, rssi.toInt(), false, now - 1_000L)
                    }
                }
            }
        }

        routers[0].originate(MeshMessage.Sos(25.3, 82.9, "EMERGENCY"), now)

        val maxRange = 100.0
        var totalTransmissions = 0
        val end = now + 15_000L
        var quietSlots = 0

        while (now <= end) {
            val sending = ArrayList<Pair<Int, ByteArray>>()
            for (i in 0 until nodes) {
                val f = routers[i].nextFrame(now, allowFiller = false) ?: continue
                sending += i to f
            }
            totalTransmissions += sending.size

            if (sending.isEmpty()) {
                quietSlots++
                if (quietSlots >= 4 && routers.all { it.queueSize == 0 }) break
            } else {
                quietSlots = 0
                val collision = exp(-0.01 * (sending.size - 1).coerceAtMost(200))
                for ((tx, bytes) in sending) {
                    val cx = (xs[tx] / cellSize).toLong()
                    val cy = (ys[tx] / cellSize).toLong()
                    for (dx in -1L..1L) for (dy in -1L..1L) {
                        val receivers = grid[((cx + dx) shl 20) or (cy + dy)] ?: continue
                        for (rx in receivers) {
                            if (rx == tx) continue
                            val d = hypot(xs[tx] - xs[rx], ys[tx] - ys[rx])
                            if (d > maxRange) continue
                            val shadow = r.nextGaussian() * 6.0
                            val rssi = -40.0 - 10 * 3.2 * log10(d.coerceAtLeast(1.0)) + shadow + 2 * r.nextGaussian()
                            val pDecode = RadioEnv.decodeProbability(rssi, coded = false) * collision * 0.95
                            if (r.nextDouble() < pDecode) {
                                routers[rx].onFrame(bytes, rssi.toInt(), false, now)
                            }
                        }
                    }
                }
            }

            for (i in 0 until nodes) routers[i].tick(now)
            now += 250L
        }

        val deliveredCount = (1 until nodes).count { firstRx[it] >= 0 }
        val deliveryRate = if (nodes > 1) deliveredCount.toDouble() / (nodes - 1) else 1.0
        val latencies = (1 until nodes).filter { firstRx[it] >= 0 }.map { (firstRx[it] - 1_000_000L) / 1000.0 }.sorted()
        val medLat = if (latencies.isEmpty()) 0.0 else latencies[latencies.size / 2]
        val p95Lat = if (latencies.isEmpty()) 0.0 else latencies[(latencies.size * 0.95).toInt().coerceAtMost(latencies.size - 1)]
        val txPerDelivered = if (deliveredCount > 0) totalTransmissions.toDouble() / deliveredCount else 0.0
        val totalDuplicates = routers.sumOf { it.stats.duplicates }.toDouble()

        return CrowdMetrics(
            crowdSize = nodes,
            ruleName = if (useAdaptiveDensity) "Phase 4 (${rule.name})" else "Old (${rule.name})",
            deliveryRate = deliveryRate,
            medianLatencyS = medLat,
            p95LatencyS = p95Lat,
            txPerDelivered = txPerDelivered,
            totalDuplicates = totalDuplicates,
        )
    }

    private fun Random.nextGaussian(): Double {
        var u: Double
        var v: Double
        var s: Double
        do {
            u = nextDouble() * 2.0 - 1.0
            v = nextDouble() * 2.0 - 1.0
            s = u * u + v * v
        } while (s >= 1.0 || s == 0.0)
        return u * Math.sqrt(-2.0 * Math.log(s) / s)
    }

    fun runComparison(trials: Int = 3): List<CrowdMetrics> {
        val sizes = listOf(5, 20, 100, 500)
        val results = ArrayList<CrowdMetrics>()

        for (n in sizes) {
            val oldRuns = (1..trials).map { runTrial(n, RelayRule.RSSI_FAR_FIRST, useAdaptiveDensity = false, seed = 1000L + it * 17) }
            val newRandomRuns = (1..trials).map { runTrial(n, RelayRule.RANDOM_SLOT, useAdaptiveDensity = true, seed = 1000L + it * 17) }
            val newFarRuns = (1..trials).map { runTrial(n, RelayRule.RSSI_FAR_FIRST, useAdaptiveDensity = true, seed = 1000L + it * 17) }

            fun avg(label: String, runs: List<CrowdMetrics>) = CrowdMetrics(
                crowdSize = n,
                ruleName = label,
                deliveryRate = runs.map { it.deliveryRate }.average(),
                medianLatencyS = runs.map { it.medianLatencyS }.average(),
                p95LatencyS = runs.map { it.p95LatencyS }.average(),
                txPerDelivered = runs.map { it.txPerDelivered }.average(),
                totalDuplicates = runs.map { it.totalDuplicates }.average(),
            )

            results += avg("Old (RSSI_FAR_FIRST, fixed)", oldRuns)
            results += avg("New (RANDOM_SLOT, adaptive)", newRandomRuns)
            results += avg("New (RSSI_FAR_FIRST, adaptive)", newFarRuns)
            println("Completed crowd size $n")
        }
        return results
    }
}

fun main() {
    println("Running Phase 4 crowd simulation comparison (uncalibrated simulation, directional only)...")
    val res = CrowdRoutingExperiment.runComparison(trials = 3)
    println()
    println("| Crowd Size | Strategy | Delivery % | Median Latency (s) | p95 Latency (s) | Tx / Delivered | Duplicates |")
    println("|---|---|---|---|---|---|---|")
    for (m in res) {
        println("| ${m.crowdSize} | ${m.ruleName} | ${"%.1f".format(m.deliveryRate * 100)}% | ${"%.2f".format(m.medianLatencyS)} s | ${"%.2f".format(m.p95LatencyS)} s | ${"%.2f".format(m.txPerDelivered)} | ${"%.0f".format(m.totalDuplicates)} |")
    }
}
