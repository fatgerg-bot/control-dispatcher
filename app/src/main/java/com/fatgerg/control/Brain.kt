package com.fatgerg.control

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Claude playing Control. Keeps the whole shift's conversation so it remembers each job. */
class Brain(private val settings: Settings) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val history = ArrayList<Pair<String, String>>() // role to text

    fun reset() = history.clear()

    /** Sends one officer transmission or app event and returns Control's spoken reply. */
    fun reply(input: String): String {
        addUser(input)
        val body = JSONObject()
            .put("model", settings.model)
            .put("max_tokens", 300)
            .put("system", Prompt.build(settings))
            .put("messages", JSONArray().apply {
                for ((role, text) in history) {
                    put(JSONObject().put("role", role).put("content", text))
                }
            })

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", settings.apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = try {
                    JSONObject(raw).getJSONObject("error").getString("message")
                } catch (_: Exception) { raw.take(200) }
                throw RuntimeException("Claude API ${resp.code}: $msg")
            }
            val content = JSONObject(raw).getJSONArray("content")
            val text = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") text.append(block.optString("text"))
            }
            val spoken = cleanForSpeech(text.toString())
            history.add("assistant" to spoken.ifEmpty { "Stand by, over." })
            trim()
            return spoken
        }
    }

    private fun addUser(text: String) {
        if (history.lastOrNull()?.first == "user") {
            val (_, prev) = history.removeAt(history.size - 1)
            history.add("user" to "$prev\n$text")
        } else {
            history.add("user" to text)
        }
    }

    private fun trim() {
        while (history.size > 60) history.removeAt(0)
        while (history.isNotEmpty() && history.first().first != "user") history.removeAt(0)
    }

    companion object {
        /** Removes anything a voice shouldn't read out: stage directions, markdown, emoji. */
        fun cleanForSpeech(s: String): String = s
            .replace(Regex("\\*[^*]*\\*"), " ")
            .replace(Regex("\\[[^\\]]*\\]"), " ")
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("[#*_`~>|]"), " ")
            .replace(Regex("[\\p{So}\\p{Cn}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
