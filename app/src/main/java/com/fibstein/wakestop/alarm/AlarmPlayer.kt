package com.fibstein.wakestop.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Plays the arrival alarm according to [AlarmRouter]: headphones only (USAGE_MEDIA + preferred device),
 * loudspeaker (USAGE_ALARM) or vibration only. Vibration always runs. Must be used from the main thread.
 */
class AlarmPlayer(
    context: Context,
    private val settings: AlarmRoutingSettings,
) {
    private val context = context.applicationContext
    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator = Haptics.vibrator(context)
    private val handler = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var output: AlarmOutput? = null
    private var focusRequest: AudioFocusRequest? = null
    private var savedMusicVolume: Int? = null
    private var rampStartMs = 0L
    private var running = false

    private val fallbackRunnable = Runnable { switchToSpeaker() }

    private val rampRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            val elapsed = System.currentTimeMillis() - rampStartMs
            val v = min(1f, 0.1f + 0.9f * elapsed / RAMP_MS.toFloat())
            runCatching { p.setVolume(v, v) }
            if (v < 1f) handler.postDelayed(this, 1000)
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (running && output == AlarmOutput.HEADPHONES && headphoneDevice() == null) {
                Log.i(TAG, "Headphones disconnected during alarm, re-routing")
                handler.removeCallbacks(fallbackRunnable)
                stopPlayback()
                apply(AlarmRouter.route(environment(), settings))
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        startVibration()
        audio.registerAudioDeviceCallback(deviceCallback, handler)
        apply(AlarmRouter.route(environment(), settings))
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacksAndMessages(null)
        audio.unregisterAudioDeviceCallback(deviceCallback)
        stopPlayback()
        vibrator.cancel()
    }

    private fun environment() = AlarmEnvironment(
        headphonesConnected = headphoneDevice() != null,
        ringerSilent = audio.ringerMode != AudioManager.RINGER_MODE_NORMAL,
    )

    private fun apply(route: AlarmRoute) {
        output = route.output
        Log.i(TAG, "Alarm route: $route")
        when (route.output) {
            AlarmOutput.HEADPHONES -> {
                playOnHeadphones(headphoneDevice())
                route.speakerFallbackAfterSec?.let { handler.postDelayed(fallbackRunnable, it * 1000L) }
            }
            AlarmOutput.SPEAKER -> playOnSpeaker()
            AlarmOutput.VIBRATE_ONLY -> Unit
        }
    }

    private fun switchToSpeaker() {
        if (!running || output == AlarmOutput.SPEAKER) return
        Log.i(TAG, "Alarm not dismissed, falling back to speaker")
        stopPlayback()
        output = AlarmOutput.SPEAKER
        playOnSpeaker()
    }

    private fun playOnHeadphones(device: AudioDeviceInfo?) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        requestFocus(attrs)
        raiseMusicVolume()
        startPlayer(attrs, device)
    }

    private fun playOnSpeaker() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        requestFocus(attrs)
        startPlayer(attrs, null)
    }

    private fun startPlayer(attrs: AudioAttributes, device: AudioDeviceInfo?) {
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(attrs)
            p.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            if (!setSource(p)) {
                p.release()
                return
            }
            p.isLooping = true
            if (device != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) p.setPreferredDevice(device)
            p.setVolume(0.1f, 0.1f)
            p.prepare()
            p.start()
            player = p
            rampStartMs = System.currentTimeMillis()
            handler.post(rampRunnable)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to play alarm sound", e)
            p.release()
        }
    }

    /** Default alarm tone, falling back to other system tones, then to a generated beep. */
    private fun setSource(p: MediaPlayer): Boolean {
        val candidates = listOfNotNull(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        )
        for (uri in candidates) {
            try {
                p.setDataSource(context, uri)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Tone $uri unusable", e)
                p.reset()
            }
        }
        return try {
            p.setDataSource(context, Uri.fromFile(fallbackBeep()))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Fallback beep unusable", e)
            false
        }
    }

    private fun stopPlayback() {
        handler.removeCallbacks(rampRunnable)
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        focusRequest = null
        restoreMusicVolume()
    }

    private fun requestFocus(attrs: AudioAttributes) {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { }
            .build()
        audio.requestAudioFocus(req)
        focusRequest = req
    }

    /** A muted media stream would make the headphone alarm inaudible; bump it for the alarm's duration. */
    private fun raiseMusicVolume() {
        val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val minimum = max(1, (maxVol * MIN_MEDIA_VOLUME).roundToInt())
        if (current < minimum) {
            runCatching {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, minimum, 0)
                if (savedMusicVolume == null) savedMusicVolume = current
            }.onFailure { Log.w(TAG, "Cannot raise media volume", it) }
        }
    }

    private fun restoreMusicVolume() {
        savedMusicVolume?.let { runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) } }
        savedMusicVolume = null
    }

    private fun startVibration() {
        if (!vibrator.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        @Suppress("DEPRECATION")
        vibrator.vibrate(effect, attrs)
    }

    private fun headphoneDevice(): AudioDeviceInfo? =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in headphoneTypes }

    private fun fallbackBeep(): File {
        val file = File(context.cacheDir, "wakestop_beep.wav")
        if (file.exists()) return file
        val rate = 22_050
        val samples = rate // 1 s: 0.5 s tone + 0.5 s silence
        val pcm = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) {
            val s = if (i < samples / 2) (sin(2 * PI * 880 * i / rate) * 0.8 * Short.MAX_VALUE).toInt() else 0
            pcm.putShort(s.toShort())
        }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2)
            putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
        }
        FileOutputStream(file).use { it.write(header.array()); it.write(pcm.array()) }
        return file
    }

    private companion object {
        const val TAG = "AlarmPlayer"
        const val RAMP_MS = 30_000L
        const val MIN_MEDIA_VOLUME = 0.6

        val headphoneTypes: Set<Int> = buildSet {
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
            // A2DP can't be told apart from car kits / speakers reliably; treated as headphones.
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }
}
