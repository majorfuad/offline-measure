package com.fuad.offlinemeasure.measurement

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Snapshot of repeated measurements of the same physical reference.
 * All samples use meters; no network access or calibration assumptions.
 */
data class RepeatabilityReport(
    val count: Int,
    val referenceMeters: Double,
    val meanMeters: Double,
    val biasCm: Double,
    val meanAbsoluteErrorCm: Double,
    val meanAbsolutePercentError: Double,
    val sampleStdDevCm: Double,
    val minMeters: Double,
    val maxMeters: Double
) {
    companion object {
        fun calculate(referenceMeters: Double, samplesMeters: List<Double>): RepeatabilityReport? {
            if (!referenceMeters.isFinite() || referenceMeters <= 0.0 ||
                samplesMeters.isEmpty() ||
                samplesMeters.any { !it.isFinite() || it < 0.0 }) return null
            val mean = samplesMeters.average()
            val deviations = samplesMeters.map { it - mean }
            val variance = if (samplesMeters.size > 1) {
                deviations.sumOf { it * it } / (samplesMeters.size - 1)
            } else 0.0
            return RepeatabilityReport(
                count = samplesMeters.size,
                referenceMeters = referenceMeters,
                meanMeters = mean,
                biasCm = (mean - referenceMeters) * 100.0,
                meanAbsoluteErrorCm = samplesMeters.sumOf { abs(it - referenceMeters) } * 100.0 / samplesMeters.size,
                meanAbsolutePercentError = samplesMeters.sumOf { abs(it - referenceMeters) / referenceMeters } * 100.0 / samplesMeters.size,
                sampleStdDevCm = sqrt(variance) * 100.0,
                minMeters = samplesMeters.min(),
                maxMeters = samplesMeters.max()
            )
        }
    }
}
