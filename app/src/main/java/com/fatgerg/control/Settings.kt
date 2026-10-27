package com.fatgerg.control

import android.content.Context
import android.content.SharedPreferences

/** All the knobs on the settings screen, stored on the phone. */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("control_settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) = prefs.edit().putString("api_key", v.trim()).apply()

    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = prefs.edit().putString("model", v.trim().ifEmpty { DEFAULT_MODEL }).apply()

    /** One officer per line: "Wolf One = Officer Wolf". */
    var officers: String
        get() = prefs.getString("officers", DEFAULT_OFFICERS) ?: DEFAULT_OFFICERS
        set(v) = prefs.edit().putString("officers", v).apply()

    var town: String
        get() = prefs.getString("town", "Ashbrook") ?: "Ashbrook"
        set(v) = prefs.edit().putString("town", v.trim().ifEmpty { "Ashbrook" }).apply()

    var voiceName: String
        get() = prefs.getString("voice", "") ?: ""
        set(v) = prefs.edit().putString("voice", v).apply()

    var speechRate: Float
        get() = prefs.getFloat("rate", 1.0f)
        set(v) = prefs.edit().putFloat("rate", v).apply()

    /** Seconds of quiet that count as the end of an over. */
    var endOfOverSeconds: Float
        get() = prefs.getFloat("eoo", 0.8f)
        set(v) = prefs.edit().putFloat("eoo", v).apply()

    /** Length of the tone played before Control speaks, so VOX keys up on it. */
    var leadInMs: Int
        get() = prefs.getInt("leadin", 400)
        set(v) = prefs.edit().putInt("leadin", v).apply()

    /** Loudness (0-100) above which input counts as someone talking. */
    var talkThreshold: Int
        get() = prefs.getInt("threshold", 12)
        set(v) = prefs.edit().putInt("threshold", v).apply()

    /** Output volume of Control's audio into the radio, 10-100. */
    var outputLevel: Int
        get() = prefs.getInt("outlevel", 70)
        set(v) = prefs.edit().putInt("outlevel", v).apply()

    var idleMinutes: Int
        get() = prefs.getInt("idle", 4)
        set(v) = prefs.edit().putInt("idle", v).apply()

    var tripWords: String
        get() = prefs.getString("trip", DEFAULT_TRIP_WORDS) ?: DEFAULT_TRIP_WORDS
        set(v) = prefs.edit().putString("trip", v).apply()

    var alertNumber: String
        get() = prefs.getString("alert_number", "") ?: ""
        set(v) = prefs.edit().putString("alert_number", v.trim()).apply()

    var useDnd: Boolean
        get() = prefs.getBoolean("dnd", true)
        set(v) = prefs.edit().putBoolean("dnd", v).apply()

    /** Empty means "use the built-in prompt". */
    var customPrompt: String
        get() = prefs.getString("prompt", "") ?: ""
        set(v) = prefs.edit().putString("prompt", v).apply()

    fun officerList(): List<Officer> = officers.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val parts = line.split("=", limit = 2)
            Officer(parts[0].trim(), parts.getOrNull(1)?.trim().orEmpty())
        }

    fun tripWordList(): List<String> = tripWords.split(",", "\n")
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }

    companion object {
        const val DEFAULT_MODEL = "claude-haiku-5-5"
        const val DEFAULT_OFFICERS = "Wolf One"
        const val DEFAULT_TRIP_WORDS =
            "i'm hurt, i am hurt, hurt myself, i'm bleeding, bleeding, help me, " +
                "a stranger, stranger, i'm scared, i am scared, i'm lost, i am lost, " +
                "can't breathe, cannot breathe, real emergency, not pretend, not a game, " +
                "for real, call an ambulance, need a grown up, i fell over, i've fallen"
    }
}

data class Officer(val callsign: String, val name: String)
