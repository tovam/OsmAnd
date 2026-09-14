package net.osmand.plus.plugins.flightmode

import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/** Residuals are measured in the calibration image pixels, not geographic metres. */
data class FlightPhotoErrorMetrics(val cumulative: Double, val rms: Double) {
    companion object {
        fun of(errors: List<Double>) =
            FlightPhotoErrorMetrics(
                errors.sum(),
                if (errors.isEmpty()) 0.0 else sqrt(errors.sumOf { it * it } / errors.size),
            )
    }
}

data class FlightPhotoPointInfluence(
    /** Original zero-based control point index, including gaps from incomplete pairs. */
    val pointIndex: Int,
    /** Kept in the original retained-point order; null means the subset could not be fitted. */
    val remainingErrors: List<Double>?,
    val excludedError: Double?,
    val weak: Boolean,
) {
    val after: FlightPhotoErrorMetrics?
        get() = remainingErrors?.let(FlightPhotoErrorMetrics::of)

    fun before(fit: FlightPhotoFit) =
        FlightPhotoErrorMetrics.of(
            fit.errors.filterIndexed { i, _ -> fit.pointIndices[i] != pointIndex }
        )

    fun rmsReductionPercent(fit: FlightPhotoFit): Double? {
        val before = before(fit).rms
        // Percentage gains at numerical zero would turn round-off into a false warning.
        return after?.takeIf { before > 0.000001 }?.let { 100 * (before - it.rms) / before }
    }

    fun toJson() =
        JSONObject().apply {
            put("point", pointIndex)
            remainingErrors?.let { put("remainingErrors", JSONArray(it)) }
            put("excludedError", excludedError)
            put("weak", weak)
        }

    companion object {
        /**
         * Optional reports are parsed separately so corrupt diagnostics never erase a saved pose.
         */
        fun readReport(array: JSONArray?, indices: List<Int>): List<FlightPhotoPointInfluence>? =
            runCatching {
                    require(array != null && indices.size >= 5 && array.length() == indices.size)
                    List(array.length()) { i ->
                        val row = array.getJSONObject(i)
                        val index = row.getInt("point")
                        require(index == indices[i])
                        val errors =
                            row.optJSONArray("remainingErrors")?.let { values ->
                                require(values.length() == indices.size - 1)
                                List(values.length()) { k ->
                                    values.getDouble(k).also { require(it.isFinite() && it >= 0) }
                                }
                            }
                        val excluded =
                            if (row.isNull("excludedError")) null
                            else
                                row.getDouble("excludedError").also {
                                    require(it.isFinite() && it >= 0)
                                }
                        require(errors != null || excluded == null)
                        FlightPhotoPointInfluence(
                            index,
                            errors,
                            excluded,
                            row.optBoolean("weak", true),
                        )
                    }
                }
                .getOrNull()
    }
}

/** Rank by absolute RMS reduction on the same retained points, not by a smaller point count. */
fun FlightPhotoFit.rankedInfluences(): List<FlightPhotoPointInfluence> =
    influences.orEmpty().sortedByDescending { row ->
        row.after?.let { row.before(this).rms - it.rms } ?: Double.NEGATIVE_INFINITY
    }
