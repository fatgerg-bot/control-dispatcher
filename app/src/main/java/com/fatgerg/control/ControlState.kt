package com.fatgerg.control

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Status(val label: String) {
    OFF("Off shift"),
    LOADING("Getting ready"),
    LISTENING("Listening"),
    HEARING("Hearing an over"),
    THINKING("Thinking"),
    TRANSMITTING("Transmitting"),
    SAFETY("SAFETY STOP"),
}

enum class Who { OFFICER, CONTROL, SYSTEM }

data class LogLine(val time: String, val who: Who, val text: String)

/** Shared between the radio service and the screen. */
object ControlState {
    private val _status = MutableStateFlow(Status.OFF)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _log = MutableStateFlow<List<LogLine>>(emptyList())
    val log: StateFlow<List<LogLine>> = _log.asStateFlow()

    /** Input loudness 0-100, for the level meter. */
    private val _level = MutableStateFlow(0)
    val level: StateFlow<Int> = _level.asStateFlow()

    private val _audioRoute = MutableStateFlow("No cable detected")
    val audioRoute: StateFlow<String> = _audioRoute.asStateFlow()

    /** True while the Hold to talk button is pressed. */
    private val _pttHeld = MutableStateFlow(false)
    val pttHeld: StateFlow<Boolean> = _pttHeld.asStateFlow()

    /** True while the listener thinks someone is talking (drives the level meter colour). */
    private val _speech = MutableStateFlow(false)
    val speech: StateFlow<Boolean> = _speech.asStateFlow()

    fun setPtt(held: Boolean) { _pttHeld.value = held }
    fun setSpeech(v: Boolean) { if (_speech.value != v) _speech.value = v }

    fun setStatus(s: Status) { _status.value = s }
    fun setLevel(l: Int) { _level.value = l }
    fun setRoute(r: String) { _audioRoute.value = r }

    fun add(who: Who, text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.UK).format(Date())
        _log.update { (it + LogLine(time, who, text)).takeLast(300) }
    }

    fun clearLog() { _log.value = emptyList() }

    fun logAsText(): String = _log.value.joinToString("\n") { l ->
        val who = when (l.who) {
            Who.OFFICER -> "OFFICER"
            Who.CONTROL -> "CONTROL"
            Who.SYSTEM -> "APP"
        }
        "${l.time}  $who: ${l.text}"
    }
}
