package com.fuad.offlinemeasure.measurement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccuracyMetricsTest {
    @Test fun exactMeasurementHasZeroError() {
        val result = AccuracyMetrics.calculate(0.30, 0.30)!!
        assertEquals(0.0, result.absoluteErrorCm, 1e-9)
        assertEquals(0.0, result.percentError, 1e-9)
    }

    @Test fun overMeasurementProducesPositiveSignedError() {
        val result = AccuracyMetrics.calculate(1.0, 1.03)!!
        assertEquals(3.0, result.signedErrorCm, 1e-9)
        assertEquals(3.0, result.percentError, 1e-9)
    }

    @Test fun underMeasurementProducesNegativeSignedError() {
        val result = AccuracyMetrics.calculate(2.0, 1.96)!!
        assertEquals(-4.0, result.signedErrorCm, 1e-9)
        assertEquals(2.0, result.percentError, 1e-9)
    }

    @Test fun invalidReferencesAreRejected() {
        assertNull(AccuracyMetrics.calculate(0.0, 0.2))
        assertNull(AccuracyMetrics.calculate(-0.1, 0.2))
        assertNull(AccuracyMetrics.calculate(Double.NaN, 0.2))
        assertNull(AccuracyMetrics.calculate(0.2, Double.POSITIVE_INFINITY))
    }
}
