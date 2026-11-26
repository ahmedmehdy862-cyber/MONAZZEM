package com.focusstudy.poc

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The adhan routing rule (AdhanAudioRouting), without a device. */
class AdhanAudioRoutingTest {

    @Test fun noHeadsetUsesTheAlarmStream() {
        assertEquals(AudioAttributes.USAGE_ALARM, AdhanAudioRouting.usageFor(false))
        assertEquals(
            AudioAttributes.CONTENT_TYPE_SONIFICATION,
            AdhanAudioRouting.contentTypeFor(false)
        )
    }

    @Test fun aHeadsetUsesTheMediaStream() {
        assertEquals(AudioAttributes.USAGE_MEDIA, AdhanAudioRouting.usageFor(true))
        assertEquals(AudioAttributes.CONTENT_TYPE_MUSIC, AdhanAudioRouting.contentTypeFor(true))
    }

    @Test fun wiredUsbAndBluetoothHeadsetsCountAsHeadsets() {
        assertTrue(
            AdhanAudioRouting.hasHeadsetDevice(listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        )
        assertTrue(
            AdhanAudioRouting.hasHeadsetDevice(listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        )
        assertTrue(
            AdhanAudioRouting.hasHeadsetDevice(listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        )
        assertTrue(
            AdhanAudioRouting.hasHeadsetDevice(listOf(AudioDeviceInfo.TYPE_USB_HEADSET))
        )
    }

    @Test fun speakerAndOtherOutputsDoNotCountAsHeadsets() {
        assertFalse(
            AdhanAudioRouting.hasHeadsetDevice(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        )
        assertFalse(
            AdhanAudioRouting.hasHeadsetDevice(listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        )
        assertFalse(AdhanAudioRouting.hasHeadsetDevice(emptyList()))
    }

    @Test fun aHeadsetAmongOtherOutputsStillCounts() {
        assertTrue(
            AdhanAudioRouting.hasHeadsetDevice(
                listOf(
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                )
            )
        )
    }
}
