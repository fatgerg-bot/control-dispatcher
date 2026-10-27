package com.fatgerg.control

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Listens to the radio's speaker line and cuts it into "overs".
 * The radio squelch gives clean silence between transmissions, so a loudness check is enough.
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

    private val frame = SAMPLE_RATE / 50 // 20 ms
    private val preRoll = ArrayDeque<FloatArray>()
    private val segment = ArrayList<FloatArray>()
    private var inSpeech = false
    private var loudFrames = 0
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

    @SuppressLint("MissingPermission")
    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, frame * 2 * 10),
        )
        AudioRoutes.cableInput(context)?.let { record.setPreferredDevice(it) }
        val buf = ShortArray(frame)
        try {
            record.startRecording()
            while (running) {
                val n = record.read(buf, 0, frame)
                if (n <= 0) continue
                val samples = FloatArray(n) { buf[it] / 32768f }
                val level = levelOf(samples)
                ControlState.setLevel(level)
                if (muted) continue
                process(samples, level)
            }
        } catch (e: Exception) {
            ControlState.add(Who.SYSTEM, "Listening stopped: ${e.message}")
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
            ControlState.setLevel(0)
        }
    }

    private fun process(samples: FloatArray, level: Int) {
        val loud = level >= settings.talkThreshold
        val endFrames = (settings.endOfOverSeconds * 50).toInt().coerceAtLeast(10)

        if (!inSpeech) {
            preRoll.addLast(samples)
            while (preRoll.size > 15) preRoll.removeFirst() // 300 ms before speech starts
            loudFrames = if (loud) loudFrames + 1 else 0
            if (loudFrames >= 3) {
                inSpeech = true
                segment.clear()
                segment.addAll(preRoll)
                preRoll.clear()
                quietFrames = 0
                speechFrames = loudFrames
                onOverStarted()
            }
            return
        }

        segment.add(samples)
        if (loud) {
            quietFrames = 0
            speechFrames++
        } else {
            quietFrames++
        }

        val tooLong = segment.size > 50 * 30 // 30 s cap
        if (quietFrames >= endFrames || tooLong) {
            val keep = if (tooLong) segment.size else segment.size - quietFrames + 10
            val total = segment.take(keep.coerceAtMost(segment.size))
            val enough = speechFrames >= 20 // at least 0.4 s of talking
            resetSegment()
            if (enough) {
                val out = FloatArray(total.sumOf { it.size })
                var i = 0
                for (chunk in total) { chunk.copyInto(out, i); i += chunk.size }
                onOver(out)
            } else {
                onDiscard()
            }
        }
    }

    private fun resetSegment() {
        inSpeech = false
        segment.clear()
        preRoll.clear()
        loudFrames = 0
        quietFrames = 0
        speechFrames = 0
    }

    companion object {
        const val SAMPLE_RATE = 16000

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
