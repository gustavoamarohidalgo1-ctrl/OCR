package com.facturaclara.app

import kotlin.math.abs
import kotlin.math.roundToInt

/** Angles wrap at +/-180 degrees; those observations vote for the same rotation. */
object OcrOrientation {
    fun quarterTurn(angles: List<Float>): Int {
        if (angles.size < 3) return 0
        val rotations = angles.filter { it.isFinite() }.mapNotNull { angle ->
            val nearest = (angle / 90f).roundToInt() * 90
            if (abs(angle - nearest) <= 15f) ((nearest % 360) + 360) % 360 else null
        }
        val common = rotations.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return 0
        if (common.key == 0 || common.value * 3 < angles.size * 2) return 0
        return when (common.key) { 270 -> 90; 180 -> 180; else -> -common.key }
    }

    /** Smaller tilts are left alone: resampling a slightly tilted photo costs more than it gains. */
    const val MIN_DESKEW_DEGREES = 5f

    /**
     * Clockwise degrees that straighten a clearly tilted, upright page, or 0. Requires
     * most lines to agree on one angle; quarter turns are handled by [quarterTurn].
     */
    fun deskew(angles: List<Float>): Float {
        val finite = angles.filter { it.isFinite() }
        val usable = finite.filter { abs(it) <= 45f }.sorted()
        if (usable.size < 5) return 0f
        val median = usable[usable.size / 2]
        val inliers = usable.filter { abs(it - median) <= 8f }
        // Agreement is measured against every line, including steep ones left out above.
        if (inliers.size * 5 < finite.size * 3) return 0f
        val angle = inliers[inliers.size / 2]
        return if (abs(angle) in MIN_DESKEW_DEGREES..45f) -angle else 0f
    }
}
