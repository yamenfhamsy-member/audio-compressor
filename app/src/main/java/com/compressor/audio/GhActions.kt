package com.compressor.audio

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Backend client for Thorfin Audio's cloud features.
 *
 * Heavy work runs on free GitHub Actions runners, but the app never talks
 * to GitHub directly: it talks to our always-on Cloudflare relay, which
 * holds the GitHub token as a server-side secret. No keys live in the app,
 * so there is nothing to type, hide, or leak.
 *
 * Uploads still go straight from the phone to keyless temp hosts
 * (litterbox 72h, uguu.se fallback) — the relay only passes small JSON.
 */
object GhActions {
    private const val RELAY = "https://thorfin-relay.img-api.workers.dev"

    data class RelayRun(val runId: Long, val status: String, val conclusion: String?)

    /** Upload [file] keylessly; returns a public URL valid for days. */
    fun uploadTemp(file: File): String {
        require(file.exists() && file.length() in 1..209_715_200L) { "file size not supported" }
        try {
            return uploadLitterbox(file)
        } catch (e: Exception) {
            return uploadUguu(file)
        }
    }

    private fun postMultipart(url: String, fields: Map<String, String>, fileField: String, file: File): String {
        val boundary = "----Thorfin${System.currentTimeMillis()}"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("User-Agent", "Mozilla/5.0")
            connectTimeout = 30_000
            readTimeout = 300_000
            doOutput = true
            setChunkedStreamingMode(256 * 1024)
        }
        try {
            conn.outputStream.buffered().use { outs ->
                for ((k, v) in fields) {
                    outs.write("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n".toByteArray())
                }
                outs.write("--$boundary\r\nContent-Disposition: form-data; name=\"$fileField\"; filename=\"${file.name}\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
                file.inputStream().buffered().use { it.copyTo(outs) }
                outs.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText()?.trim() ?: ""
            require(code in 200..299 && body.startsWith("http")) { "upload $code" }
            return body
        } finally {
            conn.disconnect()
        }
    }

    private fun uploadLitterbox(file: File): String {
        // Temp host (same infra as catbox), files expire in 72h — plenty for a job.
        return postMultipart(
            "https://litterbox.catbox.moe/resources/internals/api.php",
            mapOf("reqtype" to "fileupload", "time" to "72h"),
            "fileToUpload", file,
        )
    }

    private fun uploadUguu(file: File): String {
        val resp = postMultipart("https://uguu.se/upload.php", mapOf(), "files[]", file)
        val url = JSONObject(resp).optJSONArray("files")
            ?.optJSONObject(0)?.optString("url", "") ?: ""
        require(url.startsWith("http")) { "uguu parse failed" }
        return url
    }

    private fun relayPost(path: String, body: JSONObject): Pair<Int, String> {
        val conn = (URL("$RELAY$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
        }
        try {
            conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
            val code = conn.responseCode
            val resp = try {
                conn.inputStream.bufferedReader().readText()
            } catch (e: Exception) {
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            }
            return Pair(code, resp)
        } finally {
            conn.disconnect()
        }
    }

    private fun relayGetBytes(path: String): Pair<Int, ByteArray> {
        val conn = (URL("$RELAY$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 300_000
        }
        try {
            val code = conn.responseCode
            val bytes = try {
                conn.inputStream.buffered().readBytes()
            } catch (e: Exception) {
                conn.errorStream?.buffered()?.readBytes() ?: ByteArray(0)
            }
            return Pair(code, bytes)
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /**
     * Ask the relay to start a cloud job ([target] is "stems" or "stt").
     * Returns the server-side marker used for polling.
     */
    fun dispatch(target: String, audioUrl: String, jobId: String, extra: Map<String, String> = emptyMap()): String {
        val jo = JSONObject()
            .put("target", target)
            .put("audio_url", audioUrl)
            .put("job_id", jobId)
        for ((k, v) in extra) jo.put(k, v)
        val (code, resp) = relayPost("/api/dispatch", jo)
        require(code == 200) { "cloud busy ($code)" }
        return JSONObject(resp).optString("since", "").also {
            require(it.isNotBlank()) { "cloud busy" }
        }
    }

    /** Newest matching run, or null while it is still queuing. */
    fun pollRun(target: String, since: String): RelayRun? {
        val (code, bytes) = relayGetBytes("/api/run?target=${enc(target)}&since=${enc(since)}")
        require(code == 200) { "cloud busy ($code)" }
        val o = JSONObject(bytes.toString(Charsets.UTF_8))
        if (!o.optBoolean("found")) return null
        return RelayRun(
            o.getLong("runId"),
            o.optString("status", ""),
            o.optString("conclusion", "").ifBlank { null },
        )
    }

    /**
     * Result zip bytes, or null while not ready yet. The relay deletes the
     * file server-side right after serving it, so nothing lingers.
     */
    fun fetchArtifact(target: String, runId: Long, name: String): ByteArray? {
        val (code, bytes) = relayGetBytes(
            "/api/artifact?target=${enc(target)}&run_id=$runId&name=${enc(name)}",
        )
        if (code == 404) return null
        require(code == 200) { "cloud busy ($code)" }
        return bytes
    }
}
