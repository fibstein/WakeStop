package com.fibstein.wakestop.tracking

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** What the user asked for; persisted so the service can resume after being killed. */
data class ActiveTrip(
    val name: String,
    val destination: GeoPoint,
    val radiusM: Int,
    /** null = ETA trigger disabled. */
    val etaThresholdSec: Int?,
    val startedAtMs: Long,
    val preAlerted: Boolean = false,
) {
    fun toConfig() = TripConfig(destination, radiusM.toDouble(), etaThresholdSec)
}

sealed interface TripStatus {
    data object Idle : TripStatus

    data class Tracking(
        val trip: ActiveTrip,
        val snapshot: TripSnapshot?,
        val locationEnabled: Boolean = true,
    ) : TripStatus

    data class Alarming(val trip: ActiveTrip, val reason: AlarmReason) : TripStatus
}

private val Context.tripDataStore by preferencesDataStore(name = "active_trip")

class TripRepository(context: Context) {
    private val store = context.applicationContext.tripDataStore

    private val _status = MutableStateFlow<TripStatus>(TripStatus.Idle)
    val status: StateFlow<TripStatus> = _status.asStateFlow()

    private object Keys {
        val name = stringPreferencesKey("name")
        val lat = doublePreferencesKey("lat")
        val lon = doublePreferencesKey("lon")
        val radius = intPreferencesKey("radius_m")
        val eta = intPreferencesKey("eta_threshold_sec")
        val started = longPreferencesKey("started_at")
        val preAlerted = booleanPreferencesKey("pre_alerted")
    }

    fun publish(status: TripStatus) {
        _status.value = status
    }

    suspend fun save(trip: ActiveTrip) {
        store.edit {
            it.clear()
            it[Keys.name] = trip.name
            it[Keys.lat] = trip.destination.lat
            it[Keys.lon] = trip.destination.lon
            it[Keys.radius] = trip.radiusM
            trip.etaThresholdSec?.let { eta -> it[Keys.eta] = eta }
            it[Keys.started] = trip.startedAtMs
            it[Keys.preAlerted] = trip.preAlerted
        }
    }

    suspend fun load(): ActiveTrip? = store.data.first().toTrip()

    suspend fun clear() {
        store.edit { it.clear() }
        _status.value = TripStatus.Idle
    }

    private fun Preferences.toTrip(): ActiveTrip? {
        val lat = this[Keys.lat] ?: return null
        val lon = this[Keys.lon] ?: return null
        return ActiveTrip(
            name = this[Keys.name] ?: "",
            destination = GeoPoint(lat, lon),
            radiusM = this[Keys.radius] ?: 500,
            etaThresholdSec = this[Keys.eta],
            startedAtMs = this[Keys.started] ?: System.currentTimeMillis(),
            preAlerted = this[Keys.preAlerted] ?: false,
        )
    }
}
