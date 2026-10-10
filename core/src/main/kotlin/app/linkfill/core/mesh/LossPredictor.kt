package app.linkfill.core.mesh

import java.io.InputStream
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Calibrated logistic regression: P(at least one frame from this neighbour is missed in the next window).
 * Deliberately simple (as the research brief recommends for a first prototype): easy to debug, cheap on a
 * phone, and its probabilities can be checked for calibration.
 */
class LossPredictor(
    val featureNames: List<String>,
    val columns: List<Int>,
    private val mean: DoubleArray,
    private val std: DoubleArray,
    private val weights: DoubleArray, // weights[0] is the bias
    val meta: Map<String, String> = emptyMap(),
) {
    fun predict(all: DoubleArray): Double {
        var z = weights[0]
        for ((i, c) in columns.withIndex()) z += weights[i + 1] * (all[c] - mean[i]) / std[i]
        return 1.0 / (1.0 + exp(-z.coerceIn(-30.0, 30.0)))
    }

    fun withMeta(m: Map<String, String>) = LossPredictor(featureNames, columns, mean, std, weights, m)

    fun write(): String = buildString {
        appendLine("linkfill-logreg 1")
        for ((k, v) in meta) appendLine("meta $k=$v")
        appendLine("columns ${columns.joinToString(" ")}")
        appendLine("features ${featureNames.joinToString(" ")}")
        appendLine("mean ${mean.joinToString(" ")}")
        appendLine("std ${std.joinToString(" ")}")
        appendLine("weights ${weights.joinToString(" ")}")
    }

    companion object {
        /** v2: trained on real iQOO field logs to predict "link drops for 5 s" (training/train_link_ai.py); v1 was simulated. */
        const val RESOURCE = "/app/linkfill/core/mesh/loss_predictor_v2.txt"

        /** Fits by full-batch gradient descent with L2 regularisation. Labels are 0/1. */
        fun train(x: List<DoubleArray>, y: List<Int>, columns: List<Int>, l2: Double = 1e-3, iterations: Int = 400, lr: Double = 0.5): LossPredictor {
            val n = x.size
            val k = columns.size
            val mean = DoubleArray(k) { j -> x.sumOf { it[columns[j]] } / n }
            val std = DoubleArray(k) { j -> sqrt(x.sumOf { (it[columns[j]] - mean[j]).let { d -> d * d } } / n).coerceAtLeast(1e-6) }
            val z = Array(n) { i -> DoubleArray(k) { j -> (x[i][columns[j]] - mean[j]) / std[j] } }
            val w = DoubleArray(k + 1)
            val prior = y.average().coerceIn(1e-4, 1 - 1e-4)
            w[0] = ln(prior / (1 - prior))
            repeat(iterations) {
                val g = DoubleArray(k + 1)
                for (i in 0 until n) {
                    var s = w[0]
                    for (j in 0 until k) s += w[j + 1] * z[i][j]
                    val err = 1.0 / (1.0 + exp(-s.coerceIn(-30.0, 30.0))) - y[i]
                    g[0] += err
                    for (j in 0 until k) g[j + 1] += err * z[i][j]
                }
                for (j in 0..k) w[j] -= lr * (g[j] / n + if (j > 0) l2 * w[j] else 0.0)
            }
            return LossPredictor(LinkFeatures.ALL, columns, mean, std, w)
        }

        fun parse(text: String): LossPredictor? {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.firstOrNull() != "linkfill-logreg 1") return null
            fun nums(key: String) = lines.firstOrNull { it.startsWith("$key ") }?.substring(key.length + 1)?.split(' ')?.mapNotNull { it.toDoubleOrNull() }
            val meta = lines.filter { it.startsWith("meta ") }.mapNotNull { l -> l.substring(5).split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val features = lines.firstOrNull { it.startsWith("features ") }?.substring(9)?.split(' ') ?: return null
            if (features != LinkFeatures.ALL) return null
            val columns = nums("columns")?.map { it.toInt() } ?: return null
            val mean = nums("mean")?.toDoubleArray() ?: return null
            val std = nums("std")?.toDoubleArray() ?: return null
            val w = nums("weights")?.toDoubleArray() ?: return null
            if (columns.any { it !in features.indices } || mean.size != columns.size || std.size != columns.size || w.size != columns.size + 1 || std.any { it <= 0 }) return null
            return LossPredictor(features, columns, mean, std, w, meta)
        }

        fun read(stream: InputStream?): LossPredictor? = stream?.bufferedReader()?.use { parse(it.readText()) }

        fun loadBundled(): LossPredictor? = read(LossPredictor::class.java.getResourceAsStream(RESOURCE))
    }
}

/** Evaluation metrics the brief asks for: PR-AUC, Brier score, calibration, recall at a false-alarm budget. */
object Metrics {
    fun brier(p: List<Double>, y: List<Int>) = p.indices.sumOf { (p[it] - y[it]).let { d -> d * d } } / p.size

    /** Area under the precision-recall curve (average precision). */
    fun prAuc(p: List<Double>, y: List<Int>): Double {
        val order = p.indices.sortedByDescending { p[it] }
        val positives = y.count { it == 1 }
        if (positives == 0) return 0.0
        var tp = 0; var ap = 0.0
        for ((rank, i) in order.withIndex()) if (y[i] == 1) { tp++; ap += tp.toDouble() / (rank + 1) }
        return ap / positives
    }

    /** Recall when the threshold is set so that at most [maxFpr] of negatives raise an alarm. */
    fun recallAtFpr(p: List<Double>, y: List<Int>, maxFpr: Double): Double {
        val negatives = p.indices.filter { y[it] == 0 }.map { p[it] }.sortedDescending()
        if (negatives.isEmpty()) return 0.0
        val threshold = negatives[(negatives.size * maxFpr).toInt().coerceAtMost(negatives.size - 1)]
        val pos = p.indices.filter { y[it] == 1 }
        return if (pos.isEmpty()) 0.0 else pos.count { p[it] > threshold }.toDouble() / pos.size
    }

    /** Ten equal-width bins: (mean predicted, observed rate, count). */
    fun calibration(p: List<Double>, y: List<Int>): List<Triple<Double, Double, Int>> =
        (0 until 10).mapNotNull { b ->
            val idx = p.indices.filter { (p[it] * 10).toInt().coerceAtMost(9) == b }
            if (idx.isEmpty()) null else Triple(idx.sumOf { p[it] } / idx.size, idx.sumOf { y[it] }.toDouble() / idx.size, idx.size)
        }
}
