package com.fatgerg.control

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Listens to the radio's speaker line (or the phone mic) and cuts it into "overs".
 *
 * A frame counts as talking only when it is loud enough AND the voice detector thinks it's
 * a human voice, so traffic, wind, the TV or radio hiss don't keep an over open.
 * While the Hold to talk button is pressed, everything is recorded and letting go ends the over.
 */
class RadioListener(
    private val context: Context,
    private val settings: Settings,
    private val onOverStarted: () -> Unit,
    private val onOver: (FloatArray) -> Unit,
    private val onDiscard: () -> Unit = {},
) {
    @Volatile private var running = false
    @Volatile var muted = false
        set(value) {
            field = value
            if (value) resetSegment()
        }

    private var thread: Thread? = null
    private var vad: Vad? = null

    private val preRoll = ArrayDeque<FloatArray>()
    private val segment = ArrayList<FloatArray>()
    private var inSpeech = false
    private var fromButton = false
    private var speechRun = 0
    private var quietFrames = 0
    private var speechFrames = 0

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "radio-listener").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }

    private fun loadVad() {
        if (vad != null) return
        try {
            vad = Vad(
                assetManager = context.assets,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = "vad/silero_vad.onnx",
                        windowSize = FRAME,
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                ),
            )
        } catch (e: Throwable) {
            ControlState.add(Who.SYSTEM, "Voice detector unavailable, using loudness only")
            vad = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        loadVad()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, FRAME * 2 * 10),
        )
        val cable = AudioRoutes.cableInput(context)
        if (cable != null) {
            record.setPreferredDevice(cable)
        } else if (NoiseSuppressor.isAvailable()) {
            // Phone mic only: let Android take the edge off background noise.
            try { NoiseSuppressor.create(record.audioSessionId)?.enabled = true } catch (_: Exception) {}
        }
        val buf = ShortArray(FRAME)
        try {
            record.startRecording()
            while (running) {
                var got = 0
                while (got < FRAME && running) {
                    val n = record.read(buf, got, FRAME - got)
                    if (n <= 0) break
                    got += n
                }
                if (got < FRAME) continue
                val samples = FloatArray(FRAME) { buf[it] / 32768f }
                val level = levelOf(samples)
                ControlState.setLevel(level)
                if (muted) {
                    ControlState.setSpeech(false)
                    continue
                }
                process(samples, level)
            }
        } catch (e: Exception) {
            ControlState.add(Who.SYSTEM, "Listening stopped: ${e.message}")
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
            ControlState.setLevel(0)
            ControlState.setSpeech(false)
            vad?.release()
            vad = null
        }
    }

    private fun isVoice(samples: FloatArray, level: Int): Boolean {
        if (level < settings.talkThreshold) return false
        val v = vad ?: return true
        return try { v.compute(samples) >= VOICE_PROBABILITY } catch (_: Throwable) { true }
    }

    private fun process(samples: FloatArray, level: Int) {
        val held = ControlState.pttHeld.value

        // ---- Hold to talk: record everything while held, finish the moment it's released ----
        if (held) {
            ControlState.setSpeech(true)
            if (!inSpeech || !fromButton) {
                beginSegment(button = true)
            }
            segment.add(samples)
            speechFrames++
            if (segment.size > FPS * 30) finishSegment(trailingQuiet = 0, minFrames = 1)
            return
        }
        if (inSpeech && fromButton) {
            finishSegment(trailingQuiet = 0, minFrames = (FPS * 0.3f).toInt())
            ControlState.setSpeech(false)
            return
        }
        if (settings.holdToTalkOnly) {
            ControlState.setSpeech(false)
            return
        }

        // ---- Automatic: voice detector plus loudness ----
        val voice = isVoice(samples, level)
        ControlState.setSpeech(voice)
        val endFrames = (settings.endOfOverSeconds * FPS).toInt().coerceAtLeast(8)

        if (!inSpeech) {
            preRoll.addLast(samples)
            while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
            speechRun = if (voice) speechRun + 1 else 0
            if (speechRun >= START_FRAMES) beginSegment(button = false)
            return
        }

        segment.add(samples)
        if (voice) {
            quietFrames = 0
            speechFrames++
        } else {
            quietFrames++
        }

        if (quietFrames >= endFrames || segment.size > FPS * 30) {
            finishSegment(trailingQuiet = quietFrames, minFrames = (FPS * 0.4f).toInt())
        }
    }

    private fun beginSegment(button: Boolean) {
        inSpeech = true
        fromButton = button
        segment.clear()
        segment.addAll(preRoll)
        preRoll.clear()
        quietFrames = 0
        speechFrames = if (button) 0 else speechRun
        onOverStarted()
    }

    private fun finishSegment(trailingQuiet: Int, minFrames: Int) {
        val keep = (segment.size - trailingQuiet + 6).coerceIn(0, segment.size)
        val total = segment.take(keep)
        val enough = speechFrames >= minFrames
        resetSegment()
        if (enough && total.isNotEmpty()) {
            val out = FloatArray(total.sumOf { it.size })
            var i = 0
            for (chunk in total) { chunk.copyInto(out, i); i += chunk.size }
            onOver(out)
        } else {
            onDiscard()
        }
    }

    private fun resetSegment() {
        inSpeech = false
        fromButton = false
        segment.clear()
        preRoll.clear()
        speechRun = 0
        quietFrames = 0
        speechFrames = 0
    }

    companion object {
        const val SAMPLE_RATE = 16000
        /** 512 samples = 32 ms, the window size the voice detector expects. */
        const val FRAME = 512
        const val FPS = SAMPLE_RATE / FRAME.toFloat()
        private const val VOICE_PROBABILITY = 0.5f
        private const val START_FRAMES = 3      // ~0.1 s of voice to start an over
        private const val PRE_ROLL_FRAMES = 10  // ~0.3 s kept from before the start

        /** Maps loudness from -60 dBFS..0 dBFS to 0..100. */
        fun levelOf(samples: FloatArray): Int {
            var sum = 0.0
            for (s in samples) sum += s * s
            val rms = sqrt(sum / samples.size.coerceAtLeast(1))
            if (rms <= 1e-6) return 0
            val db = 20 * log10(rms)
            return (((db + 60) / 60) * 100).toInt().coerceIn(0, 100)
        }
    }
}
