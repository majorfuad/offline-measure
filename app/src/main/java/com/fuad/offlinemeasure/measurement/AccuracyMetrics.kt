package com.fuad.offlinemeasure.measurement

import kotlin.math.abs

/** Comparison against a known reference length, not a sensor accuracy guarantee. */
data class AccuracyMetrics(
    val referenceMeters: Double,
    val measuredMeters: Double,
    val signedErrorCm: Double,
    val absoluteErrorCm: Double,
    val percentError: Double
) {
    companion object {
        fun calculate(referenceMeters: Double, measuredMeters: Double): AccuracyMetrics? {
            if (!referenceMeters.isFinite() || referenceMeters <= 0.0 ||
                !measuredMeters.isFinite() || measuredMeters < 0.0) return null
            val signedErrorCm = (measuredMeters - referenceMeters) * 100.0
            return AccuracyMetrics(
                referenceMeters,
                measuredMeters,
                signedErrorCm,
                abs(signedErrorCm),
                abs(measuredMeters - referenceMeters) / referenceMeters * 100.0
            )
        }
    }
}
