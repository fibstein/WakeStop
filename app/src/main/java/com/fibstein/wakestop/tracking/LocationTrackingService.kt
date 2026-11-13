package com.fibstein.wakestop.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.fibstein.wakestop.alarm.AlarmNotifier
import com.fibstein.wakestop.alarm.AlarmPlayer
import com.fibstein.wakestop.alarm.Haptics
import com.fibstein.wakestop.appContainer
import com.fibstein.wakestop.data.prefs.AppSettings
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that owns a trip: streams locations into [TripEngine], keeps the
 * notification up to date and plays the alarm. Survives the UI; restarted by the system
 * (START_STICKY) from the persisted [ActiveTrip].
 */
class LocationTrackingService : LifecycleService() {

    private val container by lazy { appContainer }
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private var trip: ActiveTrip? = null
    private var engine: TripEngine? = null
    private var settings = AppSettings()
    private var mode: SamplingPolicy.Mode? = null
    private var tickJob: Job? = null
    private var player: AlarmPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var starting = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::onLocation)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP, ACTION_DISMISS_ALARM -> {
                finishTrip()
                return START_NOT_STICKY
            }
        }
        // ACTION_START, or a null intent when the system restarts us after a kill.
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (engine == null && !starting) {
            starting = true
            lifecycleScope.launch {
                val saved = container.trips.load()
                starting = false
                if (saved == null) finishTrip() else begin(saved)
            }
        }
        return START_STICKY
    }

    private fun enterForeground(): Boolean {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission missing, not starting")
            return false
        }
        val notification = AlarmNotifier.trackingNotification(this, "WakeStop", "Konum bekleniyor…")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(this, AlarmNotifier.ID_TRACKING, notification, type)
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "startForeground denied", e)
            false
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+): restarted from the background.
            Log.e(TAG, "startForeground not allowed", e)
            false
        }
    }

    private suspend fun begin(t: ActiveTrip) {
        settings = container.settings.current()
        trip = t
        val e = TripEngine(t.toConfig(), SystemClock.elapsedRealtime(), preAlerted = t.preAlerted)
        engine = e
        acquireWakeLock()
        publishTracking()
        requestUpdates(SamplingPolicy.modeFor(e.snapshot, e.config))
        tickJob = lifecycleScope.launch {
            while (isActive) {
                delay(TICK_MS)
                val eng = engine ?: break
                handle(eng.onTick(SystemClock.elapsedRealtime()))
                if (engine != null && player == null) publishTracking()
            }
        }
    }

    private fun onLocation(location: Location) {
        val e = engine ?: return
        val fix = LocationFix(
            lat = location.latitude,
            lon = location.longitude,
            accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else 999.0,
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
            timeMs = location.elapsedRealtimeNanos / 1_000_000,
        )
        handle(e.onFix(fix))
        if (engine == null || player != null) return
        val newMode = SamplingPolicy.modeFor(e.snapshot, e.config)
        if (newMode != mode) requestUpdates(newMode)
        publishTracking()
    }

    private fun handle(events: List<TripEvent>) {
        val t = trip ?: return
        for (event in events) {
            Log.i(TAG, "Trip event: $event")
            when (event) {
                TripEvent.PreAlert -> {
                    val snap = engine?.snapshot
                    AlarmNotifier.showPreAlert(
                        this,
                        "${t.name.ifBlank { "Varış" }}: ${Format.distance(snap?.distanceM)} kaldı",
                    )
                    Haptics.pulse(this, Haptics.PRE_ALERT)
                    lifecycleScope.launch { container.trips.save(t.copy(preAlerted = true)) }
                }
                TripEvent.SignalLost -> {
                    AlarmNotifier.showSignalLost(this)
                    Haptics.pulse(this, Haptics.SIGNAL_LOST)
                }
                TripEvent.SignalRestored -> AlarmNotifier.cancel(this, AlarmNotifier.ID_SIGNAL)
                is TripEvent.Alarm -> startAlarm(t, event.reason)
            }
        }
    }

    private fun startAlarm(t: ActiveTrip, reason: AlarmReason) {
        if (player != null) return
        fused.removeLocationUpdates(callback)
        tickJob?.cancel()
        AlarmNotifier.cancel(this, AlarmNotifier.ID_SIGNAL)
        AlarmNotifier.cancel(this, AlarmNotifier.ID_PRE_ALERT)
        container.trips.publish(TripStatus.Alarming(t, reason))
        player = AlarmPlayer(this, settings.routing).also { it.start() }
        AlarmNotifier.post(this, AlarmNotifier.ID_ALARM, AlarmNotifier.alarmNotification(this, t.name))
    }

    private fun finishTrip() {
        player?.stop()
        player = null
        tickJob?.cancel()
        fused.removeLocationUpdates(callback)
        engine = null
        trip = null
        releaseWakeLock()
        listOf(AlarmNotifier.ID_ALARM, AlarmNotifier.ID_PRE_ALERT, AlarmNotifier.ID_SIGNAL)
            .forEach { AlarmNotifier.cancel(this, it) }
        container.trips.publish(TripStatus.Idle)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        lifecycleScope.launch {
            container.trips.clear()
            stopSelf()
        }
    }

    private fun publishTracking() {
        val t = trip ?: return
        val snap = engine?.snapshot
        val locationOn = LocationManagerCompat.isLocationEnabled(getSystemService(LocationManager::class.java))
        container.trips.publish(TripStatus.Tracking(t, snap, locationOn))
        val text = when {
            !locationOn -> "Konum servisi kapalı!"
            snap?.signalLost == true -> "GPS sinyali yok · son: ${Format.distance(snap.distanceM)}"
            snap?.distanceM == null -> "Konum bekleniyor…"
            else -> "${Format.distance(snap.distanceM)} kaldı · tahmini ${Format.eta(snap.etaSec)}"
        }
        AlarmNotifier.post(
            this, AlarmNotifier.ID_TRACKING,
            AlarmNotifier.trackingNotification(this, t.name.ifBlank { "Varış noktası" }, text),
        )
    }

    private fun requestUpdates(newMode: SamplingPolicy.Mode) {
        if (!hasLocationPermission()) return
        mode = newMode
        val priority = if (newMode.highAccuracy) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val request = LocationRequest.Builder(priority, newMode.intervalMs)
            .setMinUpdateIntervalMillis(newMode.intervalMs / 2)
            .setWaitForAccurateLocation(false)
            .build()
        Log.i(TAG, "Location mode -> $newMode")
        try {
            fused.removeLocationUpdates(callback)
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission revoked", e)
        }
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Keeps the tick loop (signal-loss detection / fail-safe) running while the screen is off. */
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WakeStop:trip")
            .apply {
                setReferenceCounted(false)
                acquire(MAX_TRIP_MS)
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        player?.stop()
        fused.removeLocationUpdates(callback)
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TrackingService"
        const val ACTION_START = "com.fibstein.wakestop.START"
        const val ACTION_STOP = "com.fibstein.wakestop.STOP"
        const val ACTION_DISMISS_ALARM = "com.fibstein.wakestop.DISMISS_ALARM"
        private const val TICK_MS = 5_000L
        private const val MAX_TRIP_MS = 6 * 60 * 60 * 1000L

        /** Saves [trip] and starts tracking it. Call while the app is in the foreground. */
        suspend fun start(context: Context, trip: ActiveTrip) {
            context.appContainer.trips.save(trip)
            resume(context)
        }

        /** Builds a trip from current settings and starts it. */
        suspend fun startNew(context: Context, name: String, destination: GeoPoint, radiusM: Int) {
            val s = context.appContainer.settings.current()
            start(
                context,
                ActiveTrip(
                    name = name,
                    destination = destination,
                    radiusM = radiusM,
                    etaThresholdSec = s.etaThresholdMin.takeIf { it > 0 }?.times(60),
                    startedAtMs = System.currentTimeMillis(),
                ),
            )
        }

        /** Starts the service for an already persisted trip. */
        fun resume(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LocationTrackingService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LocationTrackingService::class.java).setAction(ACTION_STOP))
        }

        fun dismissAlarm(context: Context) {
            context.startService(Intent(context, LocationTrackingService::class.java).setAction(ACTION_DISMISS_ALARM))
        }
    }
}
