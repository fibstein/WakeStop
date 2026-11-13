package com.fibstein.wakestop.alarm

import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmRouterTest {

    private fun settings(
        silent: SilentModeBehavior = SilentModeBehavior.SOUND,
        fallback: Boolean = true,
        delay: Int = 30,
    ) = AlarmRoutingSettings(silent, fallback, delay)

    private fun route(headphones: Boolean, ringerSilent: Boolean, s: AlarmRoutingSettings) =
        AlarmRouter.route(AlarmEnvironment(headphones, ringerSilent), s)

    @Test
    fun headphonesIgnoreSilentModeAndFallBackToSpeaker() {
        for (silent in listOf(true, false)) {
            val r = route(true, silent, settings(SilentModeBehavior.SOUND))
            assertEquals(AlarmOutput.HEADPHONES, r.output)
            assertEquals(30, r.speakerFallbackAfterSec)
        }
        assertEquals(AlarmOutput.HEADPHONES, route(true, true, settings(SilentModeBehavior.VIBRATE_ONLY)).output)
    }

    @Test
    fun headphonesNoFallbackWhenDisabled() {
        assertEquals(null, route(true, false, settings(fallback = false)).speakerFallbackAfterSec)
    }

    @Test
    fun headphonesNoFallbackWhenSilentAndVibrateOnly() {
        val r = route(true, true, settings(SilentModeBehavior.VIBRATE_ONLY))
        assertEquals(AlarmOutput.HEADPHONES, r.output)
        assertEquals(null, r.speakerFallbackAfterSec)
        // Ringer normal -> fallback allowed even with VIBRATE_ONLY preference.
        assertEquals(30, route(true, false, settings(SilentModeBehavior.VIBRATE_ONLY)).speakerFallbackAfterSec)
    }

    @Test
    fun noHeadphonesNormalRingerUsesSpeaker() {
        for (b in SilentModeBehavior.entries) {
            assertEquals(AlarmRoute(AlarmOutput.SPEAKER, null), route(false, false, settings(b)))
        }
    }

    @Test
    fun noHeadphonesSilentRingerFollowsPreference() {
        assertEquals(AlarmOutput.VIBRATE_ONLY, route(false, true, settings(SilentModeBehavior.VIBRATE_ONLY)).output)
        assertEquals(AlarmOutput.SPEAKER, route(false, true, settings(SilentModeBehavior.SOUND)).output)
    }

    @Test
    fun vibrationAlwaysOn() {
        for (h in listOf(true, false)) for (s in listOf(true, false)) for (b in SilentModeBehavior.entries) {
            assertEquals(true, route(h, s, settings(b)).vibrate)
        }
    }
}
