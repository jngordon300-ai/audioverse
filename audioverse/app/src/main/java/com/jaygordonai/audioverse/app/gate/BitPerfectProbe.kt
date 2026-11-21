package com.jaygordonai.audioverse.app.gate

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build

/**
 * Read-only check: does this phone's audio HAL offer bit-perfect USB output?
 * It only QUERIES. It never enables bit-perfect, because bit-perfect mode turns
 * off software volume and a DAC without hardware volume would play at full
 * scale (Council ruling U23).
 */
object BitPerfectProbe {

    data class Result(val supportedOs: Boolean, val dacAttached: Boolean, val summary: String, val bitPerfectFormats: Int)

    fun run(context: Context): Result {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return Result(false, false, "Android 14+ required for the bit-perfect API.", 0)
        }
        val am = context.getSystemService(AudioManager::class.java)
        val usb = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        if (usb.isEmpty()) {
            return Result(true, false, "No USB DAC attached. Plug one in and run again.", 0)
        }
        var total = 0
        val lines = usb.map { device ->
            val bp = am.getSupportedMixerAttributes(device)
                .filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
            total += bp.size
            val formats = bp.joinToString { "${it.format.sampleRate / 1000.0} kHz ${encodingName(it.format.encoding)}" }
            "${device.productName}: ${bp.size} bit-perfect format(s)" + if (formats.isNotEmpty()) " [$formats]" else ""
        }
        return Result(true, true, lines.joinToString("\n"), total)
    }

    private fun encodingName(e: Int) = when (e) {
        AudioFormat.ENCODING_PCM_16BIT -> "16-bit"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24-bit"
        AudioFormat.ENCODING_PCM_32BIT -> "32-bit"
        AudioFormat.ENCODING_PCM_FLOAT -> "float"
        else -> "enc $e"
    }
}
