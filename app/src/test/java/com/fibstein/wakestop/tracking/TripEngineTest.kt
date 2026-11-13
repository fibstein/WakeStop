package com.fibstein.wakestop.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripEngineTest {

    private val destination = GeoPoint(41.0, 29.0)

    /** Meters per degree of latitude (close enough for synthetic tracks). */
    private val mPerDegLat = 111_195.0

    /** A point [metersSouth] meters south of the destination. */
    private fun fixAt(metersSouth: Double, timeMs: Long, accuracy: Double = 10.0, speed: Double? = null) =
        LocationFix(destination.lat - metersSouth / mPerDegLat, destination.lon, accuracy, speed, timeMs)

    private fun engine(
        radius: Double = 500.0,
        eta: Int? = 180,
        start: Long = 0,
    ) = TripEngine(TripConfig(destination, radius, etaThresholdSec = eta), startTimeMs = start)

    /** Drives toward the destination at [speed] m/s, one fix every [stepSec]. Returns all events with time. */
    private fun drive(
        engine: TripEngine,
        fromM: Double,
        speed: Double,
        stepSec: Int = 5,
        reportSpeed: Boolean = true,
    ): List<Pair<Double, TripEvent>> {
        val out = mutableListOf<Pair<Double, TripEvent>>()
        var t = 0L
        var d = fromM
        while (d > 0) {
            val events = engine.onFix(fixAt(d, t, speed = if (reportSpeed) speed else null))
            events.forEach { out += d to it }
            if (events.any { it is TripEvent.Alarm }) break
            d -= speed * stepSec
            t += stepSec * 1000L
        }
        return out
    }

    @Test
    fun distanceIsComputedCorrectly() {
        val d = Geo.distanceM(fixAt(1000.0, 0).point, destination)
        assertEquals(1000.0, d, 1.0)
    }

    @Test
    fun alarmWhenEnteringRadius_noEtaTrigger() {
        val events = drive(engine(radius = 500.0, eta = null), fromM = 3000.0, speed = 10.0)
        val alarm = events.single { it.second is TripEvent.Alarm }
        assertEquals(TripEvent.Alarm(com.fibstein.wakestop.tracking.AlarmReason.DISTANCE), alarm.second)
        // distance - accuracy(10) <= 500
        assertTrue(alarm.first <= 510.0 && alarm.first > 450.0)
        val pre = events.single { it.second is TripEvent.PreAlert }
        assertTrue(pre.first <= 1010.0 && pre.first > 950.0)
        assertTrue(events.indexOf(pre) < events.indexOf(alarm))
    }

    @Test
    fun etaTriggersBeforeRadiusAtHighSpeed() {
        // 20 m/s, 3 min threshold -> alarm around 3600 m, well before the 500 m radius.
        val events = drive(engine(radius = 500.0, eta = 180), fromM = 15_000.0, speed = 20.0)
        val alarm = events.single { it.second is TripEvent.Alarm }
        assertEquals(TripEvent.Alarm(AlarmReason.ETA), alarm.second)
        assertEquals(3600.0, alarm.first, 150.0)
        val pre = events.single { it.second is TripEvent.PreAlert }
        assertEquals(7200.0, pre.first, 150.0)
    }

    @Test
    fun speedDerivedFromPositionsWhenProviderHasNone() {
        val events = drive(engine(radius = 200.0, eta = 180), fromM = 15_000.0, speed = 20.0, reportSpeed = false)
        val alarm = events.single { it.second is TripEvent.Alarm }
        assertEquals(TripEvent.Alarm(AlarmReason.ETA), alarm.second)
        assertEquals(3600.0, alarm.first, 300.0)
    }

    @Test
    fun waitingAtStopDoesNotTriggerEtaAlarm() {
        val e = engine(radius = 300.0, eta = 180)
        // Approach at 10 m/s to 2500 m (ETA 250 s > 180 s).
        var t = 0L
        var d = 4000.0
        while (d > 2500) {
            assertFalse(e.onFix(fixAt(d, t, speed = 10.0)).any { it is TripEvent.Alarm })
            d -= 50; t += 5000
        }
        // Stand still for 10 minutes.
        repeat(120) {
            val ev = e.onFix(fixAt(2500.0, t, speed = 0.0)) + e.onTick(t)
            assertFalse(ev.any { it is TripEvent.Alarm })
            t += 5000
        }
        assertNull("standing still -> ETA undefined", e.snapshot.etaSec)
    }

    @Test
    fun inaccurateFixIsNotTrustedForEarlyAlarm() {
        val e = engine(radius = 500.0, eta = null)
        // 600 m away with 300 m accuracy: distance - accuracy = 300 <= 500, but it must not alarm.
        assertTrue(e.onFix(fixAt(600.0, 0, accuracy = 300.0)).none { it is TripEvent.Alarm })
        // Accurate fix at the same spot doesn't alarm either (600 - 10 > 500).
        assertTrue(e.onFix(fixAt(600.0, 1000, accuracy = 10.0)).none { it is TripEvent.Alarm })
        // Accurate fix inside -> alarm.
        assertEquals(listOf<TripEvent>(TripEvent.Alarm(AlarmReason.DISTANCE)), e.onFix(fixAt(450.0, 2000)))
    }

    @Test
    fun inaccurateFixFullyInsideRadiusStillAlarms() {
        val e = engine(radius = 500.0, eta = null)
        val ev = e.onFix(fixAt(100.0, 0, accuracy = 300.0))
        assertEquals(listOf<TripEvent>(TripEvent.Alarm(AlarmReason.DISTANCE)), ev)
    }

    @Test
    fun alarmFiresOnlyOnce() {
        val e = engine()
        assertEquals(1, e.onFix(fixAt(100.0, 0)).size)
        assertTrue(e.onFix(fixAt(50.0, 1000)).isEmpty())
        assertTrue(e.onTick(1_000_000).isEmpty())
        assertTrue(e.snapshot.alarmed)
    }

    @Test
    fun signalLostAndRestored() {
        val e = engine(radius = 300.0, eta = null)
        e.onFix(fixAt(10_000.0, 0, speed = 0.0))
        assertTrue(e.onTick(59_000).isEmpty())
        assertEquals(listOf<TripEvent>(TripEvent.SignalLost), e.onTick(60_000))
        assertTrue("SignalLost only once", e.onTick(70_000).isEmpty())
        assertEquals(listOf<TripEvent>(TripEvent.SignalRestored), e.onFix(fixAt(10_000.0, 80_000, speed = 0.0)))
        assertFalse(e.snapshot.signalLost)
    }

    @Test
    fun signalLostWithoutAnyFixSinceStart() {
        val e = engine(start = 1_000)
        assertEquals(listOf<TripEvent>(TripEvent.SignalLost), e.onTick(61_000))
        // No speed known -> no fail-safe, ever.
        assertTrue(e.onTick(10_000_000).isEmpty())
    }

    @Test
    fun failSafeAlarmInTunnel() {
        val e = engine(radius = 500.0, eta = 180)
        // 15 m/s, last fix at 6000 m. Time to ETA threshold: 6000/15 - 180 = 220 s.
        // Time to radius: 5500/15 = 366 s. Fail-safe at the earlier one -> 220 s after last fix.
        var t = 0L
        var d = 7000.0
        while (d >= 6000) {
            e.onFix(fixAt(d, t, speed = 15.0))
            d -= 75; t += 5000
        }
        val lastFixT = t - 5000
        val lostAt = lastFixT + 60_000
        assertEquals(listOf<TripEvent>(TripEvent.SignalLost), e.onTick(lostAt))
        val snapshotSpeed = e.snapshot.speedMps!!
        val expected = lastFixT + ((e.snapshot.distanceM!! / snapshotSpeed - 180) * 1000).toLong()
        assertTrue(e.onTick(expected - 1000).isEmpty())
        assertEquals(listOf<TripEvent>(TripEvent.Alarm(AlarmReason.SIGNAL_LOST_FAILSAFE)), e.onTick(expected))
    }

    @Test
    fun failSafeFiresImmediatelyIfAlreadyOverdue() {
        val e = engine(radius = 500.0, eta = null)
        e.onFix(fixAt(1200.0, 0, speed = 20.0)) // 700 m to radius at 20 m/s = 35 s < 60 s loss window
        val ev = e.onTick(60_000)
        assertEquals(listOf(TripEvent.SignalLost, TripEvent.Alarm(AlarmReason.SIGNAL_LOST_FAILSAFE)), ev)
    }

    @Test
    fun restoredSignalCancelsFailSafe() {
        val e = engine(radius = 500.0, eta = null)
        e.onFix(fixAt(5000.0, 0, speed = 10.0)) // fail-safe would be at 450 s
        e.onTick(60_000)
        e.onFix(fixAt(5000.0, 100_000, speed = 0.0)) // bus was actually standing still
        assertTrue(e.onTick(500_000).none { it is TripEvent.Alarm })
    }

    @Test
    fun preAlertNotRepeatedWhenRestoredFromState() {
        val e = TripEngine(TripConfig(destination, 500.0, etaThresholdSec = null), 0, preAlerted = true)
        assertTrue(e.onFix(fixAt(800.0, 0)).isEmpty())
    }

    @Test
    fun samplingPolicyModes() {
        val cfg = TripConfig(destination, 500.0, etaThresholdSec = 180)
        fun snap(d: Double?, eta: Double?) = TripSnapshot(d, 10.0, null, eta, 0, false, false, false)
        assertEquals(SamplingPolicy.Mode.FAR, SamplingPolicy.modeFor(snap(20_000.0, 2000.0), cfg))
        assertEquals(SamplingPolicy.Mode.FAR, SamplingPolicy.modeFor(snap(20_000.0, null), cfg))
        assertEquals(SamplingPolicy.Mode.MID, SamplingPolicy.modeFor(snap(20_000.0, 600.0), cfg))
        assertEquals(SamplingPolicy.Mode.MID, SamplingPolicy.modeFor(snap(3000.0, null), cfg))
        assertEquals(SamplingPolicy.Mode.NEAR, SamplingPolicy.modeFor(snap(1400.0, null), cfg))
        assertEquals(SamplingPolicy.Mode.NEAR, SamplingPolicy.modeFor(snap(8000.0, 400.0), cfg))
        assertEquals(SamplingPolicy.Mode.MID, SamplingPolicy.modeFor(snap(null, null), cfg))
    }

    @Test
    fun circleHasRadius() {
        val ring = Geo.circle(destination, 500.0, steps = 32)
        assertEquals(33, ring.size)
        assertEquals(ring.first(), ring.last())
        ring.forEach { assertEquals(500.0, Geo.distanceM(destination, it), 5.0) }
    }
}
