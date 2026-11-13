package com.fibstein.wakestop.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fibstein.wakestop.alarm.AlarmRoutingSettings
import com.fibstein.wakestop.alarm.SilentModeBehavior
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class AppSettings(
    val defaultRadiusM: Int = 500,
    /** 0 disables the ETA trigger. */
    val etaThresholdMin: Int = 3,
    val silentModeBehavior: SilentModeBehavior = SilentModeBehavior.SOUND,
    val headphoneFallbackToSpeaker: Boolean = true,
    val headphoneFallbackDelaySec: Int = 30,
    val onboardingDone: Boolean = false,
) {
    val routing get() = AlarmRoutingSettings(silentModeBehavior, headphoneFallbackToSpeaker, headphoneFallbackDelaySec)
}

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsStore(context: Context) {
    private val store = context.applicationContext.settingsDataStore

    private object Keys {
        val radius = intPreferencesKey("default_radius_m")
        val eta = intPreferencesKey("eta_threshold_min")
        val silent = stringPreferencesKey("silent_mode_behavior")
        val fallback = booleanPreferencesKey("headphone_fallback_to_speaker")
        val fallbackDelay = intPreferencesKey("headphone_fallback_delay_sec")
        val onboarding = booleanPreferencesKey("onboarding_done")
    }

    val settings: Flow<AppSettings> = store.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val s = transform(prefs.toSettings())
            prefs[Keys.radius] = s.defaultRadiusM
            prefs[Keys.eta] = s.etaThresholdMin
            prefs[Keys.silent] = s.silentModeBehavior.name
            prefs[Keys.fallback] = s.headphoneFallbackToSpeaker
            prefs[Keys.fallbackDelay] = s.headphoneFallbackDelaySec
            prefs[Keys.onboarding] = s.onboardingDone
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            defaultRadiusM = this[Keys.radius] ?: d.defaultRadiusM,
            etaThresholdMin = this[Keys.eta] ?: d.etaThresholdMin,
            silentModeBehavior = this[Keys.silent]
                ?.let { runCatching { SilentModeBehavior.valueOf(it) }.getOrNull() }
                ?: d.silentModeBehavior,
            headphoneFallbackToSpeaker = this[Keys.fallback] ?: d.headphoneFallbackToSpeaker,
            headphoneFallbackDelaySec = this[Keys.fallbackDelay] ?: d.headphoneFallbackDelaySec,
            onboardingDone = this[Keys.onboarding] ?: d.onboardingDone,
        )
    }
}
