package app.linkfill.core.sim

import app.linkfill.core.fec.FecCodec
import app.linkfill.core.policy.MlRecoveryPolicy
import app.linkfill.core.policy.RecoveryPolicy
import java.io.File
import java.time.LocalDate
import kotlin.random.Random

private fun percentile(sorted: List<Long>, p: Double): Long =
    if (sorted.isEmpty()) 0 else sorted[((sorted.size - 1) * p).toInt()]

class StrategyRow(
    val strategy: Strategy,
    val deliveryRate: Double,
    val medianMs: Long,
    val p95Ms: Long,
    val retransmissionsPerMin: Double,
    val bytesPerFix: Double,
    val recoveryRate: Double,
    val meanStalenessS: Double,
    val p95StalenessS: Double,
    val mlRejectRate: Double?,
    val inferenceMicros: Double?,
)

object LocationExperiment {
    fun run(scenario: Scenario, strategy: Strategy, ml: RecoveryPolicy?, seeds: Int, durationMs: Long): StrategyRow {
        val runs = (1..seeds).map { s ->
            LinkSimulator(scenario, 900_000L + s, durationMs)
                .run(LinkSimulator.policyFor(strategy, ml), LinkSimulator.configFor(strategy))
        }
        val lat = runs.flatMap { it.latenciesMs }.sorted()
        val stale = runs.flatMap { it.stalenessSamplesMs }.sorted()
        val gen = runs.sumOf { it.generated }
        val decisions = runs.sumOf { it.decisions }
        val infCount = runs.sumOf { it.inferenceCount }
        return StrategyRow(
            strategy,
            deliveryRate = runs.sumOf { it.deliveredInTime }.toDouble() / gen,
            medianMs = percentile(lat, 0.5),
            p95Ms = percentile(lat, 0.95),
            retransmissionsPerMin = runs.sumOf { it.retransmissions } / (seeds * durationMs / 60_000.0),
            bytesPerFix = runs.sumOf { it.bytes }.toDouble() / gen,
            recoveryRate = runs.sumOf { it.recovered }.toDouble() / runs.sumOf { it.firstLost }.coerceAtLeast(1),
            meanStalenessS = stale.average() / 1000.0,
            p95StalenessS = percentile(stale, 0.95) / 1000.0,
            mlRejectRate = if (strategy == Strategy.ML && decisions > 0) runs.sumOf { it.mlRejected }.toDouble() / decisions else null,
            inferenceMicros = if (infCount > 0) runs.sumOf { it.inferenceNanosTotal } / 1000.0 / infCount else null,
        )
    }
}

class FecRow(val method: String, val successRate: Double, val bytesOverPayload: Double, val meanRounds: Double)

/**
 * Bulk-payload experiment: a 4 KB payload as 10 chunks over the same bursty channel, sent once with
 * 0, 2 or 4 Reed-Solomon parity chunks, versus resending missing chunks (up to 3 extra rounds).
 * Every successful decode is checked byte-for-byte against the original.
 */
object FecExperiment {
    fun run(scenario: Scenario, trials: Int): List<FecRow> {
        val r = Random(4242)
        val payload = ByteArray(4096).also(r::nextBytes)
        val k = 10
        return listOf(0, 2, 4).map { m -> fecRow(scenario, payload, k, m, trials) } + arqRow(scenario, k, trials)
    }

    private fun fecRow(sc: Scenario, payload: ByteArray, k: Int, m: Int, trials: Int): FecRow {
        val shards = FecCodec.split(payload, k, m)
        var ok = 0
        repeat(trials) { t ->
            val ge = GilbertElliott(sc.pGoodToBad, sc.pBadToGood, sc.lossGood, sc.lossBad, Random(77_000L + t))
            val got = shards.withIndex().filter { !ge.lost() }.associate { it.index to it.value }
            val joined = FecCodec.join(got, k, m, payload.size)
            if (joined != null) { check(joined.contentEquals(payload)) { "FEC decoded wrong bytes" }; ok++ }
        }
        return FecRow(if (m == 0) "Plain, sent once (10 chunks)" else "Reed-Solomon 10+$m, sent once", ok.toDouble() / trials, (k + m).toDouble() / k, 1.0)
    }

    private fun arqRow(sc: Scenario, k: Int, trials: Int): FecRow {
        var ok = 0; var sent = 0L; var rounds = 0L
        repeat(trials) { t ->
            val ge = GilbertElliott(sc.pGoodToBad, sc.pBadToGood, sc.lossGood, sc.lossBad, Random(77_000L + t))
            var missing = (0 until k).toMutableList()
            var round = 0
            while (missing.isNotEmpty() && round < 4) {
                sent += missing.size
                missing = missing.filter { ge.lost() }.toMutableList()
                round++
            }
            rounds += round
            if (missing.isEmpty()) ok++
        }
        return FecRow("Resend missing chunks (up to 3 extra rounds)", ok.toDouble() / trials, sent.toDouble() / (trials * k), rounds.toDouble() / trials)
    }
}

/** Entry point for `./gradlew :core:runExperiment`. Writes docs/RESULTS.md from an actual run. */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "RESULTS.md")
    val ml = when (val l = MlRecoveryPolicy.loadBundled()) {
        is MlRecoveryPolicy.Companion.Load.Ready -> l.policy
        is MlRecoveryPolicy.Companion.Load.Unavailable -> { println("ML model unavailable: ${l.reason}"); null }
    }
    val seeds = 20
    val durationMs = 300_000L
    val started = System.currentTimeMillis()
    val text = buildString {
        appendLine("# LinkFill AI: measured results")
        appendLine()
        appendLine("Generated by `./gradlew :core:runExperiment` on ${LocalDate.now()}. Do not edit by hand; rerun instead.")
        appendLine()
        appendLine("**What this is:** a simulation. Both phones run the app's real `ReliableChannel` code, but the radio link")
        appendLine("between them is a Gilbert-Elliott bursty-loss model with assumed parameters, not a measured Bluetooth link.")
        appendLine("Use it to compare strategies with each other, not to predict absolute performance on real phones.")
        appendLine()
        appendLine("Setup: one location fix per second, $seeds runs of ${durationMs / 1000} s per row, location expiry 5 s.")
        appendLine("Held-out seeds (the model was trained on different random links). Model: ${ml?.model?.metadata?.summary() ?: "not available"}.")
        appendLine()
        appendLine("Columns: **delivery** = fixes that reached the receiver within 5 s; **latency** = generation to first arrival;")
        appendLine("**recovered** = fixes whose first transmission was lost but still arrived in time; **staleness** = age of the newest")
        appendLine("position the receiver held, sampled every 100 ms; **bytes/fix** = all traffic both ways per generated fix.")
        appendLine()
        for (sc in Scenario.TEST) {
            appendLine("## ${sc.name}")
            appendLine()
            appendLine("Average loss implied by the model: ${"%.1f".format(sc.averageLoss * 100)}%, base delay ${sc.baseDelayMs.toInt()} ms, disconnects ${sc.disconnectsPerMin}/min.")
            appendLine()
            appendLine("| Strategy | Delivery | Median latency | p95 latency | Retransmits/min | Bytes/fix | Recovered | Mean staleness | p95 staleness | ML abstained | Inference |")
            appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
            for (st in Strategy.entries) {
                if (st == Strategy.ML && ml == null) { appendLine("| ${st.label} | not run: model unavailable |||||||||| "); continue }
                val row = LocationExperiment.run(sc, st, ml, seeds, durationMs)
                appendLine(
                    "| ${st.label} | ${"%.1f".format(row.deliveryRate * 100)}% | ${row.medianMs} ms | ${row.p95Ms} ms | " +
                        "${"%.1f".format(row.retransmissionsPerMin)} | ${"%.0f".format(row.bytesPerFix)} | ${"%.1f".format(row.recoveryRate * 100)}% | " +
                        "${"%.2f".format(row.meanStalenessS)} s | ${"%.2f".format(row.p95StalenessS)} s | " +
                        "${row.mlRejectRate?.let { "%.0f%%".format(it * 100) } ?: "-"} | ${row.inferenceMicros?.let { "%.1f µs".format(it) } ?: "-"} |",
                )
                println("${sc.name} / ${st.label} done")
            }
            appendLine()
        }
        appendLine("## Bulk payload: Reed-Solomon FEC vs resending")
        appendLine()
        appendLine("A 4,096-byte payload in 10 chunks, 5,000 trials per row. Success means the receiver rebuilt the exact bytes.")
        appendLine("FEC needs no return channel and no extra round trips; resending needs acknowledgements and more round trips.")
        appendLine()
        for (sc in Scenario.TEST.take(3)) {
            appendLine("### ${sc.name}")
            appendLine()
            appendLine("| Method | Success | Bytes sent / payload | Rounds |")
            appendLine("|---|---|---|---|")
            for (r in FecExperiment.run(sc, 5000)) {
                appendLine("| ${r.method} | ${"%.1f".format(r.successRate * 100)}% | ${"%.2f".format(r.bytesOverPayload)}x | ${"%.2f".format(r.meanRounds)} |")
            }
            appendLine()
        }
        appendLine("Inference times are measured on the JVM of the build machine. The app shows its own on-phone timing on the diagnostics screen.")
        appendLine("Total experiment time: ${(System.currentTimeMillis() - started) / 1000} s.")
    }
    out.parentFile?.mkdirs()
    out.writeText(text)
    println("wrote ${out.absolutePath}")
}
