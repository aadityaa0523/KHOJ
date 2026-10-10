package app.linkfill.core.policy

import java.io.InputStream
import kotlin.math.tanh

class ModelFormatException(message: String) : Exception(message)

/** Facts about a trained model, stored in the model file and shown on the diagnostics screen. */
class ModelMetadata(val values: Map<String, String>) {
    val formatVersion get() = values["format_version"]
    val trainedAt get() = values["trained_at"] ?: "unknown"
    val samples get() = values["samples"] ?: "unknown"
    fun summary() = "trained ${trainedAt}, ${samples} samples, seed ${values["seed"] ?: "?"}"
}

/**
 * A small fully connected network (inputs -> tanh hidden -> linear outputs), evaluated in plain Kotlin.
 * The file format is plain text so it diffs cleanly in git:
 *
 *   linkfill-mlp 1
 *   meta key=value            (any number)
 *   features name1 name2 ...
 *   outputs name1 name2 ...
 *   mean ... / std ...        (input normalisation)
 *   layer IN OUT tanh|linear
 *   w ...  (OUT*IN numbers, row-major by output) / b ... (OUT numbers)
 */
class Mlp(
    val featureNames: List<String>,
    val outputNames: List<String>,
    val mean: DoubleArray,
    val std: DoubleArray,
    val layers: List<Layer>,
    val metadata: ModelMetadata,
) {
    class Layer(val inputs: Int, val outputs: Int, val tanh: Boolean, val w: DoubleArray, val b: DoubleArray) {
        init { require(w.size == inputs * outputs && b.size == outputs) }

        fun forward(x: DoubleArray): DoubleArray = DoubleArray(outputs) { o ->
            var s = b[o]
            val base = o * inputs
            for (i in 0 until inputs) s += w[base + i] * x[i]
            if (tanh) tanh(s) else s
        }
    }

    fun normalise(x: DoubleArray) = DoubleArray(x.size) { (x[it] - mean[it]) / std[it] }

    fun forward(x: DoubleArray): DoubleArray {
        var h = normalise(x)
        for (l in layers) h = l.forward(h)
        return h
    }

    fun write(): String = buildString {
        appendLine("$MAGIC $FORMAT_VERSION")
        for ((k, v) in metadata.values) if (k != "format_version") appendLine("meta $k=$v")
        appendLine("features ${featureNames.joinToString(" ")}")
        appendLine("outputs ${outputNames.joinToString(" ")}")
        appendLine("mean ${mean.joinToString(" ")}")
        appendLine("std ${std.joinToString(" ")}")
        for (l in layers) {
            appendLine("layer ${l.inputs} ${l.outputs} ${if (l.tanh) "tanh" else "linear"}")
            appendLine("w ${l.w.joinToString(" ")}")
            appendLine("b ${l.b.joinToString(" ")}")
        }
    }

    companion object {
        const val MAGIC = "linkfill-mlp"
        const val FORMAT_VERSION = "1"

        fun parse(text: String): Mlp {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty() || lines[0] != "$MAGIC $FORMAT_VERSION") throw ModelFormatException("not a LinkFill model, or unsupported version")
            val meta = linkedMapOf("format_version" to FORMAT_VERSION)
            var features: List<String>? = null
            var outputs: List<String>? = null
            var mean: DoubleArray? = null
            var std: DoubleArray? = null
            val layers = mutableListOf<Layer>()
            var i = 1
            fun nums(line: String, key: String): DoubleArray {
                if (!line.startsWith("$key ")) throw ModelFormatException("expected '$key' line")
                return line.substring(key.length + 1).split(' ').map { it.toDoubleOrNull() ?: throw ModelFormatException("bad number in $key") }.toDoubleArray()
            }
            while (i < lines.size) {
                val l = lines[i]
                when {
                    l.startsWith("meta ") -> l.substring(5).split('=', limit = 2).let { if (it.size == 2) meta[it[0]] = it[1] }
                    l.startsWith("features ") -> features = l.substring(9).split(' ')
                    l.startsWith("outputs ") -> outputs = l.substring(8).split(' ')
                    l.startsWith("mean ") -> mean = nums(l, "mean")
                    l.startsWith("std ") -> std = nums(l, "std")
                    l.startsWith("layer ") -> {
                        val p = l.split(' ')
                        if (p.size != 4 || i + 2 >= lines.size) throw ModelFormatException("bad layer header")
                        val ins = p[1].toIntOrNull() ?: throw ModelFormatException("bad layer size")
                        val outs = p[2].toIntOrNull() ?: throw ModelFormatException("bad layer size")
                        val w = nums(lines[i + 1], "w")
                        val b = nums(lines[i + 2], "b")
                        if (w.size != ins * outs || b.size != outs) throw ModelFormatException("layer weights do not match its size")
                        layers += Layer(ins, outs, p[3] == "tanh", w, b)
                        i += 2
                    }
                    else -> throw ModelFormatException("unknown line: ${l.take(20)}")
                }
                i++
            }
            val f = features ?: throw ModelFormatException("missing features")
            val o = outputs ?: throw ModelFormatException("missing outputs")
            val mu = mean ?: throw ModelFormatException("missing mean")
            val sd = std ?: throw ModelFormatException("missing std")
            if (mu.size != f.size || sd.size != f.size || sd.any { it <= 0.0 }) throw ModelFormatException("bad normalisation")
            if (layers.isEmpty() || layers.first().inputs != f.size || layers.last().outputs != o.size ||
                layers.zipWithNext().any { (a, b) -> a.outputs != b.inputs }
            ) throw ModelFormatException("layer shapes do not chain")
            return Mlp(f, o, mu, sd, layers, ModelMetadata(meta))
        }

        fun read(stream: InputStream): Mlp = parse(stream.bufferedReader().use { it.readText() })
    }
}
