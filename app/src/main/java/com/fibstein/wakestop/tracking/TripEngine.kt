package com.fibstein.wakestop.tracking

import kotlin.math.max
import kotlin.math.min

/** A single location sample. [speedMps] is the provider-reported speed, if any. */
data class LocationFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Double,
    val speedMps: Double?,
    val timeMs: Long,
) {
    val point get() = GeoPoint(lat, lon)
}

data class TripConfig(
    val destination: GeoPoint,
    val radiusM: Double,
    /** Alarm when estimated time of arrival drops to this; null disables the ETA trigger. */
    val etaThresholdSec: Int? = 180,
    /** Pre-alert fires at threshold * factor (both distance and ETA). */
    val preAlertFactor: Double = 2.0,
    /** Fixes less accurate than this are not trusted for "distance - accuracy" triggering. */
    val maxAccuracyM: Double = 100.0,
    val signalLostAfterMs: Long = 60_000,
    /** Below this smoothed speed ETA is considered undefined (standing at a stop / light). */
    val minSpeedForEtaMps: Double = 2.0,
    val speedEmaAlpha: Double = 0.3,
)

enum class AlarmReason { DISTANCE, ETA, SIGNAL_LOST_FAILSAFE }

sealed interface TripEvent {
    data object PreAlert : TripEvent
    data class Alarm(val reason: AlarmReason) : TripEvent
    data object SignalLost : TripEvent
    data object SignalRestored : TripEvent
}

data class TripSnapshot(
    val distanceM: Double?,
    val accuracyM: Double?,
    val speedMps: Double?,
    val etaSec: Double?,
    val lastFixTimeMs: Long,
    val signalLost: Boolean,
    val preAlerted: Boolean,
    val alarmed: Boolean,
)

/**
 * Pure decision logic for a single trip. Feed it fixes ([onFix]) and periodic ticks ([onTick]);
 * it returns the events the caller has to act on. No Android dependencies.
 *
 * Distance is measured as the crow flies, not along the route.
 */
class TripEngine(
    val config: TripConfig,
    startTimeMs: Long,
    preAlerted: Boolean = false,
) {
    private var lastFixTimeMs = startTimeMs
    private var lastGoodFix: LocationFix? = null
    private var distanceM: Double? = null
    private var accuracyM: Double? = null
    private var speedEma: Double? = null
    private var signalLost = false
    private var preAlerted = preAlerted
    private var alarmed = false

    /** Estimated wall-clock time at which the alarm would have fired, captured when signal is lost. */
    private var failSafeAtMs: Long? = null

    val snapshot: TripSnapshot
        get() = TripSnapshot(
            distanceM = distanceM,
            accuracyM = accuracyM,
            speedMps = speedEma,
            etaSec = etaSec(),
            lastFixTimeMs = lastFixTimeMs,
            signalLost = signalLost,
            preAlerted = preAlerted,
            alarmed = alarmed,
        )

    fun onFix(fix: LocationFix): List<TripEvent> {
        if (alarmed) return emptyList()
        // Ignore out-of-order samples.
        if (fix.timeMs < lastFixTimeMs && lastGoodFix != null) return emptyList()

        val events = mutableListOf<TripEvent>()
        lastFixTimeMs = fix.timeMs
        if (signalLost) {
            signalLost = false
            failSafeAtMs = null
            events += TripEvent.SignalRestored
        }

        val distance = Geo.distanceM(fix.point, config.destination)
        distanceM = distance
        accuracyM = fix.accuracyM

        val trusted = fix.accuracyM <= config.maxAccuracyM
        if (trusted) {
            updateSpeed(fix)
            lastGoodFix = fix
        }

        // Distance trigger. A trusted fix may use its accuracy in our favour (alarm a bit early);
        // an untrusted fix only counts if even its worst case is inside the radius.
        val effective = if (trusted) distance - fix.accuracyM else distance + fix.accuracyM
        val eta = if (trusted) etaSec() else null

        when {
            effective <= config.radiusM -> events += alarm(AlarmReason.DISTANCE)
            eta != null && config.etaThresholdSec != null && eta <= config.etaThresholdSec ->
                events += alarm(AlarmReason.ETA)

            !preAlerted && (
                effective <= config.radiusM * config.preAlertFactor ||
                    (eta != null && config.etaThresholdSec != null &&
                        eta <= config.etaThresholdSec * config.preAlertFactor)
                ) -> {
                preAlerted = true
                events += TripEvent.PreAlert
            }
        }
        return events
    }

    fun onTick(nowMs: Long): List<TripEvent> {
        if (alarmed) return emptyList()
        val events = mutableListOf<TripEvent>()
        if (!signalLost && nowMs - lastFixTimeMs >= config.signalLostAfterMs) {
            signalLost = true
            failSafeAtMs = estimateAlarmTimeMs()
            events += TripEvent.SignalLost
        }
        val failSafe = failSafeAtMs
        if (signalLost && failSafe != null && nowMs >= failSafe) {
            events += alarm(AlarmReason.SIGNAL_LOST_FAILSAFE)
        }
        return events
    }

    private fun alarm(reason: AlarmReason): TripEvent {
        alarmed = true
        return TripEvent.Alarm(reason)
    }

    private fun updateSpeed(fix: LocationFix) {
        val prev = lastGoodFix
        val measured: Double? = when {
            fix.speedMps != null && fix.speedMps >= 0 -> fix.speedMps
            prev != null && fix.timeMs > prev.timeMs ->
                Geo.distanceM(prev.point, fix.point) / ((fix.timeMs - prev.timeMs) / 1000.0)
            else -> null
        }
        if (measured != null) {
            val ema = speedEma
            speedEma = if (ema == null) measured else ema + config.speedEmaAlpha * (measured - ema)
        }
    }

    private fun etaSec(): Double? {
        val d = distanceM ?: return null
        val v = speedEma ?: return null
        if (v < config.minSpeedForEtaMps) return null
        return d / v
    }

    /**
     * When the signal is lost, extrapolate from the last known distance and speed: the alarm
     * would have fired once we covered (distance - radius) or reached the ETA threshold,
     * whichever comes first. Unknown/low speed means we can't estimate -> no fail-safe.
     */
    private fun estimateAlarmTimeMs(): Long? {
        val fix = lastGoodFix ?: return null
        val d = distanceM ?: return null
        val v = speedEma ?: return null
        if (v < config.minSpeedForEtaMps) return null
        val toRadiusSec = max(0.0, d - config.radiusM) / v
        val toEtaSec = config.etaThresholdSec?.let { max(0.0, d / v - it) } ?: Double.MAX_VALUE
        return fix.timeMs + (min(toRadiusSec, toEtaSec) * 1000).toLong()
    }
}

/** Adaptive location sampling: sparse while far away, dense when getting close. */
object SamplingPolicy {
    enum class Mode(val intervalMs: Long, val highAccuracy: Boolean) {
        FAR(30_000, false),
        MID(10_000, true),
        NEAR(3_000, true),
    }

    fun modeFor(snapshot: TripSnapshot, config: TripConfig): Mode {
        val d = snapshot.distanceM ?: return Mode.MID
        val eta = snapshot.etaSec
        val etaThreshold = config.etaThresholdSec?.toDouble()
        val nearDistance = config.radiusM * config.preAlertFactor + 500
        if (d <= nearDistance || (eta != null && etaThreshold != null && eta <= etaThreshold * 3)) {
            return Mode.NEAR
        }
        val farDistance = max(config.radiusM * 6, 5_000.0)
        if (d >= farDistance && (eta == null || eta >= 15 * 60)) return Mode.FAR
        return Mode.MID
    }
}
