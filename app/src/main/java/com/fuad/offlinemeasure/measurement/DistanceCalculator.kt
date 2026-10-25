package com.fuad.offlinemeasure.measurement

import com.fuad.offlinemeasure.ar.MeasurementPoint
import kotlin.math.sqrt

object DistanceCalculator {

    fun distanceMeters(
        a: MeasurementPoint,
        b: MeasurementPoint
    ): Float {

        val dx = b.x - a.x
        val dy = b.y - a.y
        val dz = b.z - a.z

        return sqrt(
            dx * dx +
                    dy * dy +
                    dz * dz
        )
    }
}