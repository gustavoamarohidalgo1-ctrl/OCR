package com.facturaclara.app

/** Float sigmoid implementations can overshoot the mathematical bounds by a few ulps. */
internal object DetectorProbabilities {
    const val ROUNDING_TOLERANCE = 1e-6f
    data class Summary(val rawMinimum: Float, val rawMaximum: Float, val clampedCount: Int)

    /** Only correct bounded floating-point overshoot; reject nonfinite or invalid maps. */
    fun validateInPlace(values: FloatArray, checkpoint: () -> Unit = {}): Summary {
        require(values.isNotEmpty()) { "Empty detector probability map" }
        var minimum = Float.POSITIVE_INFINITY
        var maximum = Float.NEGATIVE_INFINITY
        var clamped = 0
        values.forEachIndexed { index, value ->
            if (index % 8192 == 0) checkpoint()
            require(value.isFinite() && value >= -ROUNDING_TOLERANCE && value <= 1f + ROUNDING_TOLERANCE) {
                "Invalid detector probability at $index: $value (rounding tolerance $ROUNDING_TOLERANCE)"
            }
            minimum = minOf(minimum, value); maximum = maxOf(maximum, value)
            if (value < 0f || value > 1f) { values[index] = value.coerceIn(0f, 1f); clamped++ }
        }
        return Summary(minimum, maximum, clamped)
    }
}
