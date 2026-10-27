package com.fatgerg.control

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** Finds the earphone cable going to the radio, so audio never leaks to the phone's own mic or speaker. */
object AudioRoutes {
    private val CABLE_TYPES = listOf(
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    )

    fun cableInput(context: Context): AudioDeviceInfo? = find(context, AudioManager.GET_DEVICES_INPUTS)

    fun cableOutput(context: Context): AudioDeviceInfo? = find(context, AudioManager.GET_DEVICES_OUTPUTS)

    private fun find(context: Context, flag: Int): AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = am.getDevices(flag)
        for (type in CABLE_TYPES) {
            devices.firstOrNull { it.type == type }?.let { return it }
        }
        return null
    }

    fun describe(context: Context): String {
        val input = cableInput(context)
        val output = cableOutput(context)
        return when {
            input != null && output != null -> "Cable connected: ${output.productName}"
            output != null -> "Cable output only, no mic line detected"
            else -> "No cable detected, using phone mic and speaker"
        }
    }
}
