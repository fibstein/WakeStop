package com.fibstein.wakestop.tracking

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lon: Double)

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle (haversine) distance in meters. */
    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Polygon ring approximating a circle; first point is repeated at the end. */
    fun circle(center: GeoPoint, radiusM: Double, steps: Int = 64): List<GeoPoint> {
        val latRad = Math.toRadians(center.lat)
        val dLat = Math.toDegrees(radiusM / EARTH_RADIUS_M)
        val dLon = Math.toDegrees(radiusM / (EARTH_RADIUS_M * cos(latRad)))
        return (0..steps).map { i ->
            val t = 2 * Math.PI * (i % steps) / steps
            GeoPoint(center.lat + dLat * sin(t), center.lon + dLon * cos(t))
        }
    }
}
