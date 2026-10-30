package com.fuad.offlinemeasure.measurement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepeatabilityReportTest {
    @Test fun exactRepeatedReadings() {
        val report = RepeatabilityReport.calculate(0.30, listOf(0.30, 0.30, 0.30))!!
        assertEquals(3, report.count)
        assertEquals(0.0, report.biasCm, 1e-8)
        assertEquals(0.0, report.sampleStdDevCm, 1e-8)
    }

    @Test fun spreadAndBiasAreCalculatedSeparately() {
        val report = RepeatabilityReport.calculate(1.0, listOf(0.98, 1.00, 1.02))!!
        assertEquals(100.0, report.meanMeters * 100.0, 1e-8)
        assertEquals(0.0, report.biasCm, 1e-8)
        assertEquals(4.0 / 3.0, report.meanAbsoluteErrorCm, 1e-8)
        assertEquals(2.0, report.sampleStdDevCm, 1e-8)
    }

    @Test fun singleSampleHasZeroObservedVariation() {
        val report = RepeatabilityReport.calculate(2.0, listOf(2.02))!!
        assertEquals(0.0, report.sampleStdDevCm, 1e-8)
        assertEquals(2.0, report.biasCm, 1e-8)
    }

    @Test fun invalidDataRejected() {
        assertNull(RepeatabilityReport.calculate(0.0, listOf(1.0)))
        assertNull(RepeatabilityReport.calculate(1.0, emptyList()))
        assertNull(RepeatabilityReport.calculate(1.0, listOf(Double.NaN)))
        assertNull(RepeatabilityReport.calculate(1.0, listOf(-1.0)))
    }
}
