package com.fatgerg.control

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Control's voice. Text is turned into audio with the phone's British voice, then played
 * down the cable with a short tone in front so the radio's VOX keys up on the tone, not the words.
 */
class RadioVoice(private val context: Context, private val settings: Settings) {
    private var tts: TextToSpeech? = null
    private val ready = CountDownLatch(1)
    @Volatile private var ok = false
    @Volatile private var currentTrack: AudioTrack? = null

    fun init() {
        tts = TextToSpeech(context) { status ->
            ok = status == TextToSpeech.SUCCESS
            if (ok) applyVoice()
            ready.countDown()
        }
    }

    fun awaitReady(): Boolean {
        ready.await(15, TimeUnit.SECONDS)
        return ok
    }

    /** Every downloaded English voice on the phone: British, Irish, Australian, Indian, American and so on. */
    fun englishVoices(): List<Voice> {
        val t = tts ?: return emptyList()
        return try {
            val en = t.voices.orEmpty()
                .filter { it.locale.language == "en" }
                .filter { !it.isNetworkConnectionRequired }
            // Voices listed but not downloaded fall back to the default voice, so they all sound the same.
            val installed = en.filter {
                !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }
            installed.ifEmpty { en }
                .sortedWith(compareBy<Voice>({ countryOrder(it.locale.country) }, { it.name }))
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Voices with friendly labels like "British 2" or "Irish 1", for the drop-down. */
    fun labelledVoices(): List<Pair<String, String>> {
        val counts = HashMap<String, Int>()
        return englishVoices().map { v ->
            val accent = accentName(v.locale.country)
            val n = (counts[accent] ?: 0) + 1
            counts[accent] = n
            v.name to "$accent $n"
        }
    }

    private fun britishVoices(): List<Voice> =
        englishVoices().filter { it.locale.country == "GB" }

    fun applyVoice() {
        val t = tts ?: return
        t.setLanguage(Locale.UK)
        val wanted = settings.voiceName
        val voice = englishVoices().firstOrNull { it.name == wanted } ?: britishVoices().firstOrNull()
        if (voice != null) t.setVoice(voice)
        t.setSpeechRate(settings.speechRate)
    }

    /** Plays a sample of a voice straight away, for choosing one in Settings. */
    fun preview(voiceName: String, text: String) {
        val t = tts ?: return
        t.setLanguage(Locale.UK)
        val voice = englishVoices().firstOrNull { it.name == voiceName } ?: britishVoices().firstOrNull()
        if (voice != null) t.setVoice(voice)
        t.setSpeechRate(settings.speechRate)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview")
    }

    /** Speaks over the radio and returns once the audio has finished playing. */
    fun transmit(text: String) {
        val speech = synthesize(text) ?: return
        val rate = speech.second
        val lead = tone(rate, settings.leadInMs, 1000.0, 0.45f)
        val tail = ShortArray(rate * 150 / 1000)
        val beep = if (settings.endBeep) ShortArray(rate * 80 / 1000) + endBeep(rate) else ShortArray(0)
        play(lead + gain(speech.first) + gain(beep) + tail, rate)
    }

    /** A steady tone for setting VOX sensitivity and audio levels. */
    fun testTone(seconds: Int = 3) {
        val rate = 24000
        play(gain(tone(rate, seconds * 1000, 1000.0, 0.6f)), rate)
    }

    /** Stops whatever is playing right now. */
    fun stopNow() {
        try { currentTrack?.pause(); currentTrack?.flush() } catch (_: Exception) {}
    }

    fun shutdown() {
        stopNow()
        tts?.shutdown()
        tts = null
    }

    private fun synthesize(text: String): Pair<ShortArray, Int>? {
        val t = tts ?: return null
        applyVoice()
        val file = File(context.cacheDir, "say.wav")
        val id = UUID.randomUUID().toString()
        val done = CountDownLatch(1)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id) done.countDown() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { done.countDown() }
            override fun onError(utteranceId: String?, errorCode: Int) { done.countDown() }
        })
        file.delete()
        val result = t.synthesizeToFile(text, Bundle(), file, id)
        if (result != TextToSpeech.SUCCESS) return null
        done.await(30, TimeUnit.SECONDS)
        if (!file.exists() || file.length() < 64) return null
        return readWav(file.readBytes())
    }

    private fun play(pcm: ShortArray, rate: Int) {
        if (pcm.isEmpty()) return
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        AudioRoutes.cableOutput(context)?.let { track.setPreferredDevice(it) }
        currentTrack = track
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            val millis = pcm.size * 1000L / rate
            // Wait for playback to finish (or be stopped).
            val end = System.currentTimeMillis() + millis + 300
            while (System.currentTimeMillis() < end) {
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) break
                if (track.playbackHeadPosition >= pcm.size) break
                Thread.sleep(20)
            }
        } finally {
            currentTrack = null
            try { track.stop() } catch (_: Exception) {}
            track.release()
        }
    }

    private fun gain(pcm: ShortArray): ShortArray {
        val g = settings.outputLevel.coerceIn(10, 100) / 100f
        return ShortArray(pcm.size) { (pcm[it] * g).toInt().toShort() }
    }

    companion object {
        private val ACCENTS = listOf(
            "GB" to "British", "IE" to "Irish", "AU" to "Australian", "NZ" to "New Zealand",
            "IN" to "Indian", "ZA" to "South African", "NG" to "Nigerian", "CA" to "Canadian",
            "US" to "American",
        )

        fun accentName(country: String): String =
            ACCENTS.firstOrNull { it.first == country }?.second ?: "English ($country)"

        fun countryOrder(country: String): Int =
            ACCENTS.indexOfFirst { it.first == country }.let { if (it < 0) 99 else it }

        fun tone(rate: Int, millis: Int, freq: Double, amp: Float): ShortArray {
            val n = rate * millis / 1000
            val fade = rate / 100 // 10 ms fades stop clicks
            return ShortArray(n) { i ->
                var a = amp
                if (i < fade) a *= i / fade.toFloat()
                if (i > n - fade) a *= (n - i) / fade.toFloat()
                (sin(2 * PI * freq * i / rate) * a * Short.MAX_VALUE).toInt().toShort()
            }
        }

        /** The "bleep-bloop" at the end of a transmission: a high tone dropping to a lower one. */
        fun endBeep(rate: Int): ShortArray =
            tone(rate, 90, 1250.0, 0.4f) + ShortArray(rate * 15 / 1000) + tone(rate, 120, 850.0, 0.4f)

        /** Reads a 16-bit PCM WAV, mixing down to mono if needed. */
        fun readWav(bytes: ByteArray): Pair<ShortArray, Int>? {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (bytes.size < 44) return null
            var pos = 12
            var rate = 22050
            var channels = 1
            var bits = 16
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4, Charsets.US_ASCII)
                val size = bb.getInt(pos + 4)
                val body = pos + 8
                if (id == "fmt ") {
                    channels = bb.getShort(body + 2).toInt()
                    rate = bb.getInt(body + 4)
                    bits = bb.getShort(body + 14).toInt()
                } else if (id == "data") {
                    if (bits != 16) return null
                    val len = min(size.takeIf { it > 0 } ?: (bytes.size - body), bytes.size - body)
                    val frames = len / 2 / channels.coerceAtLeast(1)
                    val out = ShortArray(frames) { f ->
                        var sum = 0
                        for (c in 0 until channels) sum += bb.getShort(body + (f * channels + c) * 2)
                        (sum / channels).toShort()
                    }
                    return out to rate
                }
                pos = body + size + (size and 1)
            }
            return null
        }
    }
}
