package com.fibstein.wakestop.tracking

import java.util.Locale
import kotlin.math.roundToInt

object Format {
    private val tr = Locale.forLanguageTag("tr-TR")

    fun distance(m: Double?): String = when {
        m == null -> "—"
        m >= 10_000 -> String.format(tr, "%.0f km", m / 1000)
        m >= 1_000 -> String.format(tr, "%.1f km", m / 1000)
        else -> "${(m / 10).roundToInt() * 10} m"
    }

    fun eta(sec: Double?): String = when {
        sec == null -> "—"
        sec < 60 -> "< 1 dk"
        sec < 3600 -> "${(sec / 60).roundToInt()} dk"
        else -> "${(sec / 3600).toInt()} sa ${((sec % 3600) / 60).roundToInt()} dk"
    }

    fun speedKmh(mps: Double?): String = mps?.let { "${(it * 3.6).roundToInt()} km/s" } ?: "—"
}
