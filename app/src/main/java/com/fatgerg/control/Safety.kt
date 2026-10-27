package com.fatgerg.control

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.telephony.SmsManager

/** The safety net: trip words checked before Claude sees anything, plus Claude's own SAFETY_STOP. */
class Safety(private val context: Context, private val settings: Settings) {
    private var ringtone: Ringtone? = null

    /** Returns the trip phrase that matched, or null. */
    fun check(transcript: String): String? {
        val text = " " + transcript.lowercase()
            .replace("’", "'")
            .replace(Regex("[^a-z0-9' ]"), " ")
            .replace(Regex("\\s+"), " ") + " "
        return settings.tripWordList().firstOrNull { word -> text.contains(" $word ") }
    }

    fun message(): String =
        "This is not the game. Stop playing now and go and find a grown-up straight away. " +
            "This is not the game. Go and find a grown-up now."

    /** Rings the phone loudly and texts the alert number, so Dad knows even from another room. */
    fun alertGrownUp(reason: String) {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        } catch (e: Exception) {
            ControlState.add(Who.SYSTEM, "Couldn't sound the alarm: ${e.message}")
        }

        val number = settings.alertNumber
        if (number.isNotBlank()) {
            try {
                val sms = if (Build.VERSION.SDK_INT >= 31) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION") SmsManager.getDefault()
                }
                sms.sendTextMessage(
                    number, null,
                    "Control radio app: SAFETY STOP. Heard: \"${reason.take(100)}\". Game stopped.",
                    null, null
                )
                ControlState.add(Who.SYSTEM, "Alert text sent to $number")
            } catch (e: Exception) {
                ControlState.add(Who.SYSTEM, "Couldn't send alert text: ${e.message}")
            }
        }
    }

    fun silenceAlarm() {
        try { ringtone?.stop() } catch (_: Exception) {}
        ringtone = null
    }
}
