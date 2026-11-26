package com.focusstudy.poc

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Where the adhan is heard: the speaker, or a headset that is actually connected.
 *
 * Android routes alarm-stream audio (USAGE_ALARM) to the device speaker even when a
 * Bluetooth A2DP headset is connected — alarms are meant to wake the person, not to
 * whisper into headphones. The media stream, by contrast, follows the connected
 * output. So the service plays on the alarm stream when nothing is plugged in
 * (loud, survives silent mode) and on the media stream when a headset is connected
 * (the adhan reaches the ears it is already in).
 *
 * The choice itself is pure so it stays unit-testable; only the device query
 * touches Android.
 */
internal object AdhanAudioRouting {

    /** Output device types that mean "a headset is on the person's ears right now". */
    private val HEADSET_TYPES = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_USB_HEADSET
    )

    /** Pure predicate over output device types, so the routing rule is unit-testable. */
    fun hasHeadsetDevice(deviceTypes: Collection<Int>): Boolean =
        deviceTypes.any { it in HEADSET_TYPES }

    /** True when a wired, USB or Bluetooth-A2DP headset is connected right now. */
    fun hasConnectedHeadset(audioManager: AudioManager): Boolean = runCatching {
        val types = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        hasHeadsetDevice(types)
    }.getOrDefault(false)

    /** Alarm stream for the speaker (loud, survives silent mode); media stream for a headset. */
    fun usageFor(headsetConnected: Boolean): Int =
        if (headsetConnected) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ALARM

    fun contentTypeFor(headsetConnected: Boolean): Int =
        if (headsetConnected) AudioAttributes.CONTENT_TYPE_MUSIC
        else AudioAttributes.CONTENT_TYPE_SONIFICATION

    fun attributesFor(headsetConnected: Boolean): AudioAttributes = AudioAttributes.Builder()
        .setUsage(usageFor(headsetConnected))
        .setContentType(contentTypeFor(headsetConnected))
        .build()
}
