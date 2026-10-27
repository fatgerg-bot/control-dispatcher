package com.fatgerg.control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Runs a shift: listens to the radio, transcribes each over, asks Claude what Control says,
 * and transmits the reply. Runs as a foreground service so the Pixel doesn't put it to sleep.
 */
class ControlService : Service() {
    private lateinit var settings: Settings
    private lateinit var voice: RadioVoice
    private lateinit var brain: Brain
    private lateinit var safety: Safety
    private var listener: RadioListener? = null
    private var transcriber: Transcriber? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // One worker, so overs, replies and transmissions happen strictly one at a time.
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var watchdog: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var savedDnd: Int? = null

    @Volatile private var onShift = false
    @Volatile private var paused = false // after a safety stop, until Dad presses Resume
    @Volatile private var busy = false
    @Volatile private var lastActivity = 0L
    @Volatile private var lastTransmit = 0L
    @Volatile private var awaitingReply = false
    @Volatile private var missedReplies = 0
    private var voiceReady = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        voice = RadioVoice(this, settings).also { it.init() }
        brain = Brain(settings)
        safety = Safety(this, settings)
        goForeground("Control ready")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startShift()
            ACTION_END -> endShift()
            ACTION_SAY -> intent.getStringExtra(EXTRA_TEXT)?.let { say(it) }
            ACTION_TEST_TONE -> testTone()
            ACTION_STOP_NOW -> stopNow()
            ACTION_RESUME -> resume()
        }
        return START_NOT_STICKY
    }

    // ---- Shift control -------------------------------------------------------------------

    private fun startShift() {
        if (onShift) return
        onShift = true
        paused = false
        ControlState.setStatus(Status.LOADING)
        ControlState.add(Who.SYSTEM, "Start of shift")
        goForeground("Control on shift")
        acquireWakeLock()
        quietPhone()

        scope.launch(worker) {
            try {
                ensureVoice()
                if (transcriber == null) {
                    ControlState.add(Who.SYSTEM, "Loading speech recognition…")
                    transcriber = Transcriber(assets)
                }
            } catch (e: Throwable) {
                ControlState.add(Who.SYSTEM, "Couldn't load speech recognition: ${e.message}")
                withContext(Dispatchers.Main) { endShift() }
                return@launch
            }
            if (settings.apiKey.isBlank()) {
                ControlState.add(Who.SYSTEM, "No Claude API key set. Add it in Settings.")
            }
            ControlState.setRoute(AudioRoutes.describe(this@ControlService))
            brain.reset()
            missedReplies = 0
            lastActivity = System.currentTimeMillis()
            listener = RadioListener(
                this@ControlService, settings,
                onOverStarted = {
                    lastActivity = System.currentTimeMillis()
                    if (!busy && !paused) ControlState.setStatus(Status.HEARING)
                },
                onOver = { samples -> scope.launch(worker) { handleOver(samples) } },
            ).also { it.start() }
            ControlState.setStatus(Status.LISTENING)
            think("[Start of shift]")
        }
        startWatchdog()
    }

    private fun endShift() {
        if (!onShift) {
            stopSelfIfIdle()
            return
        }
        onShift = false
        watchdog?.cancel()
        scope.launch(worker) {
            if (!paused && settings.apiKey.isNotBlank()) think("[End of shift]", force = true)
            listener?.stop()
            listener = null
            restorePhone()
            releaseWakeLock()
            saveLog()
            safety.silenceAlarm()
            paused = false
            ControlState.add(Who.SYSTEM, "End of shift")
            ControlState.setStatus(Status.OFF)
            withContext(Dispatchers.Main) { stopSelfIfIdle() }
        }
    }

    private fun resume() {
        safety.silenceAlarm()
        if (!paused) return
        paused = false
        listener?.muted = false
        lastActivity = System.currentTimeMillis()
        ControlState.add(Who.SYSTEM, "Game resumed by a grown-up")
        ControlState.setStatus(if (onShift) Status.LISTENING else Status.OFF)
    }

    private fun stopNow() {
        voice.stopNow()
        safety.silenceAlarm()
        ControlState.add(Who.SYSTEM, "Stopped transmitting")
    }

    // ---- One over at a time --------------------------------------------------------------

    private suspend fun handleOver(samples: FloatArray) {
        if (!onShift || paused) return
        busy = true
        try {
            ControlState.setStatus(Status.THINKING)
            val text = try {
                transcriber?.transcribe(samples).orEmpty()
            } catch (e: Throwable) {
                ControlState.add(Who.SYSTEM, "Speech recognition error: ${e.message}")
                ""
            }
            if (text.isBlank()) {
                ControlState.add(Who.SYSTEM, "Heard something but couldn't make out any words")
                return
            }
            ControlState.add(Who.OFFICER, text)
            awaitingReply = false
            missedReplies = 0
            lastActivity = System.currentTimeMillis()

            val tripped = safety.check(text)
            if (tripped != null) {
                safetyStop("Trip words \"$tripped\" in: $text")
                return
            }
            think(text)
        } finally {
            busy = false
            if (onShift && !paused) ControlState.setStatus(Status.LISTENING)
        }
    }

    /** Asks Claude for Control's reply and transmits it. Must run on the worker. */
    private suspend fun think(input: String, force: Boolean = false) {
        if ((!onShift && !force) || paused) return
        if (settings.apiKey.isBlank()) return
        busy = true
        try {
            ControlState.setStatus(Status.THINKING)
            if (input.startsWith("[")) ControlState.add(Who.SYSTEM, input.trim('[', ']'))
            val reply = try {
                withContext(Dispatchers.IO) { brain.reply(input) }
            } catch (e: Exception) {
                ControlState.add(Who.SYSTEM, e.message ?: "Couldn't reach Claude")
                transmit("All stations, Control, stand by, over.", expectReply = false)
                return
            }
            if (reply.contains(Prompt.SAFETY_TOKEN)) {
                safetyStop("Control thought this sounded real: $input")
                return
            }
            if (reply.isBlank()) return
            val callsigns = settings.officerList().map { it.callsign.lowercase() }
            transmit(reply, expectReply = callsigns.any { reply.lowercase().contains(it) })
        } finally {
            busy = false
            if (onShift && !paused) ControlState.setStatus(Status.LISTENING)
        }
    }

    private fun transmit(text: String, expectReply: Boolean) {
        listener?.muted = true
        ControlState.setStatus(Status.TRANSMITTING)
        ControlState.add(Who.CONTROL, text)
        try {
            voice.transmit(text)
            Thread.sleep(500) // let the radio drop back to receive before listening again
        } finally {
            listener?.muted = paused
            lastTransmit = System.currentTimeMillis()
            lastActivity = lastTransmit
            awaitingReply = expectReply
        }
    }

    private fun safetyStop(reason: String) {
        paused = true
        listener?.muted = true
        ControlState.setStatus(Status.SAFETY)
        ControlState.add(Who.SYSTEM, "SAFETY STOP. $reason")
        safety.alertGrownUp(reason)
        val msg = safety.message()
        ControlState.add(Who.CONTROL, msg)
        try { voice.transmit(msg) } catch (_: Exception) {}
    }

    // ---- Missed replies and quiet channel ------------------------------------------------

    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (isActive && onShift) {
                delay(5000)
                if (busy || paused || listener == null) continue
                val now = System.currentTimeMillis()
                if (awaitingReply && now - lastTransmit > 30_000 && lastActivity <= lastTransmit) {
                    awaitingReply = false
                    missedReplies++
                    if (missedReplies <= 2) {
                        val who = settings.officerList().joinToString(" or ") { it.callsign }
                        scope.launch(worker) { think("[No reply from $who after 30 seconds]") }
                    }
                } else if (!awaitingReply && now - lastActivity > settings.idleMinutes * 60_000L) {
                    lastActivity = now
                    scope.launch(worker) { think("[Channel quiet for ${settings.idleMinutes} minutes]") }
                }
            }
        }
    }

    // ---- One-off commands from the screen ------------------------------------------------

    private fun say(text: String) {
        scope.launch(worker) {
            ensureVoice()
            listener?.muted = true
            ControlState.add(Who.CONTROL, text)
            ControlState.setStatus(Status.TRANSMITTING)
            voice.transmit(text)
            listener?.muted = paused
            ControlState.setStatus(if (onShift) Status.LISTENING else Status.OFF)
            withContext(Dispatchers.Main) { stopSelfIfIdle() }
        }
    }

    private fun testTone() {
        scope.launch(worker) {
            ensureVoice()
            listener?.muted = true
            ControlState.setRoute(AudioRoutes.describe(this@ControlService))
            ControlState.add(Who.SYSTEM, "Test tone, 3 seconds")
            ControlState.setStatus(Status.TRANSMITTING)
            voice.testTone(3)
            listener?.muted = paused
            ControlState.setStatus(if (onShift) Status.LISTENING else Status.OFF)
            withContext(Dispatchers.Main) { stopSelfIfIdle() }
        }
    }

    private fun ensureVoice() {
        if (!voiceReady) {
            voiceReady = voice.awaitReady()
            if (!voiceReady) ControlState.add(Who.SYSTEM, "The phone's text-to-speech isn't working")
        }
    }

    private fun stopSelfIfIdle() {
        if (!onShift) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ---- Keeping the phone awake and quiet -----------------------------------------------

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "control:shift").apply {
            acquire(6 * 60 * 60 * 1000L) // at most 6 hours
        }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    /** Do Not Disturb (alarms only), so notification pings don't go out over VOX. */
    private fun quietPhone() {
        if (!settings.useDnd) return
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            ControlState.add(Who.SYSTEM, "Do Not Disturb not allowed yet, phone pings could go out over the radio")
            return
        }
        savedDnd = nm.currentInterruptionFilter
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
    }

    private fun restorePhone() {
        val nm = getSystemService(NotificationManager::class.java)
        savedDnd?.let { if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(it) }
        savedDnd = null
    }

    private fun saveLog() {
        try {
            val dir = getExternalFilesDir("logs") ?: return
            dir.mkdirs()
            val name = "shift-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.UK).format(Date()) + ".txt"
            File(dir, name).writeText(ControlState.logAsText())
            ControlState.add(Who.SYSTEM, "Shift log saved as $name")
        } catch (_: Exception) {}
    }

    private fun goForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Control on shift", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle("Control")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    override fun onDestroy() {
        onShift = false
        listener?.stop()
        releaseWakeLock()
        restorePhone()
        safety.silenceAlarm()
        voice.shutdown()
        transcriber?.release()
        scope.cancel()
        worker.close()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "shift"
        const val ACTION_START = "start"
        const val ACTION_END = "end"
        const val ACTION_SAY = "say"
        const val ACTION_TEST_TONE = "tone"
        const val ACTION_STOP_NOW = "stop"
        const val ACTION_RESUME = "resume"
        const val EXTRA_TEXT = "text"

        fun send(context: Context, action: String, text: String? = null) {
            val i = Intent(context, ControlService::class.java).setAction(action)
            if (text != null) i.putExtra(EXTRA_TEXT, text)
            context.startForegroundService(i)
        }
    }
}
