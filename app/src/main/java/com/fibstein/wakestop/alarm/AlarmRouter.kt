package com.fibstein.wakestop.alarm

enum class SilentModeBehavior { VIBRATE_ONLY, SOUND }

enum class AlarmOutput {
    /** USAGE_MEDIA routed to the headset only. */
    HEADPHONES,

    /** USAGE_ALARM (loudspeaker, bypasses silent mode). */
    SPEAKER,

    /** No sound at all. */
    VIBRATE_ONLY,
}

data class AlarmEnvironment(
    val headphonesConnected: Boolean,
    /** ringerMode != RINGER_MODE_NORMAL */
    val ringerSilent: Boolean,
)

data class AlarmRoutingSettings(
    val silentModeBehavior: SilentModeBehavior,
    val headphoneFallbackToSpeaker: Boolean,
    val headphoneFallbackDelaySec: Int,
)

data class AlarmRoute(
    val output: AlarmOutput,
    /** If non-null, also switch to the speaker after this many seconds without dismissal. */
    val speakerFallbackAfterSec: Int?,
) {
    /** Vibration always runs, whatever the output. */
    val vibrate: Boolean get() = true
}

/** Pure decision table for where the alarm sound goes. */
object AlarmRouter {
    fun route(env: AlarmEnvironment, settings: AlarmRoutingSettings): AlarmRoute {
        if (env.headphonesConnected) {
            val userWantsSilence =
                env.ringerSilent && settings.silentModeBehavior == SilentModeBehavior.VIBRATE_ONLY
            val fallback = if (settings.headphoneFallbackToSpeaker && !userWantsSilence) {
                settings.headphoneFallbackDelaySec.coerceAtLeast(0)
            } else {
                null
            }
            return AlarmRoute(AlarmOutput.HEADPHONES, fallback)
        }
        if (!env.ringerSilent) return AlarmRoute(AlarmOutput.SPEAKER, null)
        return when (settings.silentModeBehavior) {
            SilentModeBehavior.VIBRATE_ONLY -> AlarmRoute(AlarmOutput.VIBRATE_ONLY, null)
            SilentModeBehavior.SOUND -> AlarmRoute(AlarmOutput.SPEAKER, null)
        }
    }
}
