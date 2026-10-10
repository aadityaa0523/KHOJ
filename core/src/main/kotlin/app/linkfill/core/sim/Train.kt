package app.linkfill.core.sim

import app.linkfill.core.policy.ExplorationPolicy
import app.linkfill.core.policy.Mlp
import app.linkfill.core.policy.ModelMetadata
import app.linkfill.core.policy.RecoveryAction
import app.linkfill.core.policy.RecoveryFeatures
import app.linkfill.core.reliability.ChannelConfig
import app.linkfill.core.reliability.DecisionRecord
import java.io.File
import java.time.LocalDate
import kotlin.math.sqrt
import kotlin.random.Random

/** One logged decision: inputs, the action that was (randomly) taken, and what it cost afterwards. */
class Sample(val x: DoubleArray, val action: Int, val cost: Double)

object TrainingData {
    /** Seconds charged per extra byte, so the model does not buy small freshness gains with lots of traffic. */
    const val SECONDS_PER_BYTE = 0.0005
    private const val NEVER_S = 6.0

    /** Approximate extra bytes each action causes (location message + its acknowledgement, or piggybacked copies). */
    fun extraBytes(a: RecoveryAction, config: ChannelConfig): Int = when (a) {
        RecoveryAction.RETRY_NOW, RecoveryAction.WAIT_RETRY -> 200
        RecoveryAction.ADD_REDUNDANCY -> 48 * config.redundancyDepth * (config.redundancyHoldMs / 1000).toInt()
        RecoveryAction.MARK_STALE -> 0
    }

    /**
     * Runs simulations with uniformly random actions (so every action is observed in every situation)
     * and scores each decision by how long the receiver then waited for this fix or a newer one.
     */
    fun collect(runs: Int, seed: Long, durationMs: Long = 300_000): List<Sample> {
        val r = Random(seed)
        val config = ChannelConfig()
        val out = ArrayList<Sample>()
        repeat(runs) { run ->
            val scenario = Scenario.random(r)
            val records = ArrayList<DecisionRecord>()
            val arrivals = HashMap<Long, Long>()
            val sim = LinkSimulator(scenario, seed * 1000 + run, durationMs)
            sim.run(ExplorationPolicy(Random(seed * 7 + run)), config, records::add, arrivals)
            val epoch = 1_700_000_000_000L
            val fixTimes = arrivals.keys.sorted()
            // suffix minimum of arrival time over fixes generated at or after a given fix time
            val suffixMin = LongArray(fixTimes.size + 1) { Long.MAX_VALUE }
            for (i in fixTimes.indices.reversed()) suffixMin[i] = minOf(suffixMin[i + 1], epoch + arrivals.getValue(fixTimes[i]))
            for (rec in records) {
                val idx = fixTimes.binarySearch(rec.fixTimeMs).let { if (it < 0) -it - 1 else it }
                val firstArrival = suffixMin[idx]
                val waitS = if (firstArrival == Long.MAX_VALUE) NEVER_S else ((firstArrival - rec.now) / 1000.0).coerceIn(0.0, NEVER_S)
                val a = rec.decision.action
                out += Sample(rec.features.vector(), a.ordinal, waitS + SECONDS_PER_BYTE * extraBytes(a, config))
            }
        }
        return out
    }
}

/** Trains a small MLP that predicts the cost of each action (only the taken action's output gets a loss). */
class CostModelTrainer(private val hidden: Int = 24, private val seed: Long = 1) {
    /** Returns the model, its validation MSE, and the MSE of always predicting each action's average cost. */
    fun train(samples: List<Sample>, epochs: Int = 40, lr: Double = 0.003, batch: Int = 64): Triple<Mlp, Double, Double> {
        val r = Random(seed)
        val shuffled = samples.shuffled(r)
        val nVal = shuffled.size / 10
        val valSet = shuffled.take(nVal)
        val trainSet = shuffled.drop(nVal)
        val nIn = trainSet[0].x.size
        val nOut = RecoveryAction.entries.size
        val mean = DoubleArray(nIn) { i -> trainSet.sumOf { it.x[i] } / trainSet.size }
        val std = DoubleArray(nIn) { i -> sqrt(trainSet.sumOf { (it.x[i] - mean[i]).let { d -> d * d } } / trainSet.size).coerceAtLeast(1e-3) }
        fun norm(x: DoubleArray) = DoubleArray(nIn) { (x[it] - mean[it]) / std[it] }

        val w1 = DoubleArray(hidden * nIn) { r.nextDouble(-1.0, 1.0) * sqrt(1.0 / nIn) }
        val b1 = DoubleArray(hidden)
        val w2 = DoubleArray(nOut * hidden) { r.nextDouble(-1.0, 1.0) * sqrt(1.0 / hidden) }
        val b2 = DoubleArray(nOut) { trainSet.filter { s -> s.action == it }.map { s -> s.cost }.average().takeIf { v -> !v.isNaN() } ?: 1.0 }
        val params = listOf(w1, b1, w2, b2)
        val m = params.map { DoubleArray(it.size) }
        val v = params.map { DoubleArray(it.size) }
        var step = 0

        val xs = trainSet.map { norm(it.x) }
        repeat(epochs) {
            val idx = xs.indices.shuffled(r)
            for (start in idx.indices step batch) {
                val grads = params.map { DoubleArray(it.size) }
                val end = minOf(start + batch, idx.size)
                for (bi in start until end) {
                    val i = idx[bi]
                    val x = xs[i]
                    val s = trainSet[i]
                    val h = DoubleArray(hidden) { j -> var z = b1[j]; for (k in 0 until nIn) z += w1[j * nIn + k] * x[k]; kotlin.math.tanh(z) }
                    val a = s.action
                    var y = b2[a]; for (j in 0 until hidden) y += w2[a * hidden + j] * h[j]
                    val dy = (y - s.cost)
                    grads[3][a] += dy
                    for (j in 0 until hidden) {
                        grads[2][a * hidden + j] += dy * h[j]
                        val dh = dy * w2[a * hidden + j] * (1 - h[j] * h[j])
                        grads[1][j] += dh
                        for (k in 0 until nIn) grads[0][j * nIn + k] += dh * x[k]
                    }
                }
                step++
                val n = (end - start).toDouble()
                for (p in params.indices) adam(params[p], grads[p], m[p], v[p], lr, step, n)
            }
        }
        val model = Mlp(
            RecoveryFeatures.NAMES, RecoveryAction.entries.map { it.name }, mean, std,
            listOf(Mlp.Layer(nIn, hidden, true, w1, b1), Mlp.Layer(hidden, nOut, false, w2, b2)),
            ModelMetadata(emptyMap()),
        )
        val valMse = valSet.sumOf { s -> (model.forward(s.x)[s.action] - s.cost).let { it * it } } / valSet.size.coerceAtLeast(1)
        val actionMean = DoubleArray(nOut) { a -> trainSet.filter { it.action == a }.map { it.cost }.average() }
        val baselineMse = valSet.sumOf { s -> (actionMean[s.action] - s.cost).let { it * it } } / valSet.size.coerceAtLeast(1)
        return Triple(model, valMse, baselineMse)
    }

    private fun adam(p: DoubleArray, g: DoubleArray, m: DoubleArray, v: DoubleArray, lr: Double, t: Int, n: Double) {
        val b1 = 0.9; val b2 = 0.999
        val c1 = 1 - Math.pow(b1, t.toDouble()); val c2 = 1 - Math.pow(b2, t.toDouble())
        for (i in p.indices) {
            val gi = g[i] / n
            m[i] = b1 * m[i] + (1 - b1) * gi
            v[i] = b2 * v[i] + (1 - b2) * gi * gi
            p[i] -= lr * (m[i] / c1) / (sqrt(v[i] / c2) + 1e-8)
        }
    }
}

/** Entry point for `./gradlew :core:trainPolicy`. Writes the model the app bundles. */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "recovery_policy_v1.mlp")
    val seed = 20261010L
    val runs = 400
    val started = System.currentTimeMillis()
    val samples = TrainingData.collect(runs, seed)
    println("collected ${samples.size} decisions from $runs simulated links in ${System.currentTimeMillis() - started} ms")
    val (model, valMse, baselineMse) = CostModelTrainer(seed = seed).train(samples)
    val meta = linkedMapOf(
        "trained_at" to LocalDate.now().toString(),
        "samples" to samples.size.toString(),
        "seed" to seed.toString(),
        "simulated_links" to runs.toString(),
        "validation_mse_s2" to "%.4f".format(valMse),
        "baseline_mse_s2_per_action_mean" to "%.4f".format(baselineMse),
        "seconds_per_byte" to TrainingData.SECONDS_PER_BYTE.toString(),
        "target" to "seconds_until_receiver_has_this_or_newer_fix_plus_byte_cost",
        "data" to "simulated_gilbert_elliott_links_not_real_measurements",
    )
    val withMeta = Mlp(model.featureNames, model.outputNames, model.mean, model.std, model.layers, ModelMetadata(meta))
    out.parentFile?.mkdirs()
    out.writeText(withMeta.write())
    println("validation MSE %.4f s^2 (per-action-average baseline %.4f); wrote ${out.absolutePath}".format(valMse, baselineMse))
}
