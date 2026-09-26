package com.compressor.audio

import android.content.Context
import android.util.Base64
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import org.json.JSONObject

/**
 * Cloud speech-to-text via the user's own thorfin-stt Cloudflare Worker
 * (Whisper large-v3-turbo, multilingual incl. Arabic).
 *
 * Audio is chunked into 25 s WAV windows, POSTed as base64 JSON, and the
 * texts are concatenated. No audio stays on any server (Workers AI inference).
 *
 * Settings (SharedPreferences "settings"): worker_url, worker_key, stt_engine.
 */
object CloudStt {
    const val ENGINE_DEVICE = "device"
    const val ENGINE_CLOUD = "cloud"
    const val DEFAULT_URL = "https://thorfin-stt.img-api.workers.dev"

    private const val CHUNK_SEC = 25
    private const val CHUNK_SAMPLES = 16000 * CHUNK_SEC

    fun getEngine(context: Context): String =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("stt_engine", ENGINE_DEVICE) ?: ENGINE_DEVICE

    fun setEngine(context: Context, engine: String) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putString("stt_engine", engine).apply()
    }

    fun getUrl(context: Context): String =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("worker_url", DEFAULT_URL) ?: DEFAULT_URL

    fun setUrl(context: Context, url: String) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putString("worker_url", url.trim().trimEnd('/')).apply()
    }

    fun getKey(context: Context): String =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("worker_key", "") ?: ""

    fun setKey(context: Context, key: String) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putString("worker_key", key.trim()).apply()
    }

    /**
     * Transcribe 16 kHz mono [pcm] through the cloud worker.
     * Throws on auth/network/server errors (message is user-safe).
     */
    fun transcribe(
        context: Context,
        pcm: ShortArray,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): String {
        val base = getUrl(context)
        val key = getKey(context)
        require(base.isNotBlank()) { "worker url missing" }
        require(key.isNotBlank()) { "api key missing" }
        val total = pcm.size
        val nChunks = (total + CHUNK_SAMPLES - 1) / CHUNK_SAMPLES
        val parts = StringBuilder()
        var offset = 0
        var ci = 0
        while (offset < total) {
            if (isCancelled()) throw CancellationException("cloud stt cancelled")
            val n = minOf(CHUNK_SAMPLES, total - offset)
            val wav = chunkWav(context, pcm.copyOfRange(offset, offset + n))
            try {
                val text = postChunk(base, key, wav)
                if (text.isNotBlank()) {
                    if (parts.isNotEmpty()) parts.append(' ')
                    parts.append(text)
                }
            } finally {
                runCatching { wav.delete() }
            }
            offset += n
            ci++
            onProgress(ci.toFloat() / nChunks)
        }
        onProgress(1f)
        return parts.toString().trim()
    }

    private fun chunkWav(context: Context, pcm: ShortArray): File {
        val floats = FloatArray(pcm.size) { i -> pcm[i] / 32768f }
        val f = File.createTempFile("stt_chunk", ".wav", context.cacheDir)
        WavWriter.write(f, floats, 16000, 1)
        return f
    }

    private fun postChunk(baseUrl: String, key: String, wav: File): String {
        val b64 = Base64.encodeToString(wav.readBytes(), Base64.NO_WRAP)
        val body = JSONObject().put("audio", b64).toString()
        val conn = (URL("$baseUrl/stt").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $key")
            connectTimeout = 20_000
            readTimeout = 90_000
            doOutput = true
        }
        try {
            conn.outputStream.bufferedWriter().use { it.write(body) }
            val code = conn.responseCode
            val resp = try {
                conn.inputStream.bufferedReader().readText()
            } catch (e: Exception) {
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            }
            if (code == 401) throw IllegalStateException("unauthorized (check api key)")
            if (code == 413) throw IllegalStateException("chunk too large")
            if (code != 200) throw IllegalStateException("server $code")
            return JSONObject(resp).optString("text", "").trim()
        } finally {
            conn.disconnect()
        }
    }
}
