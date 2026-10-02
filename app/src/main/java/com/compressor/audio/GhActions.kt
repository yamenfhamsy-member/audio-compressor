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
    fun uploadTemp(file: File, onProgress: (Float) -> Unit = {}): String {
        require(file.exists() && file.length() in 1..209_715_200L) { "file size not supported" }
        try {
            return uploadLitterbox(file, onProgress)
        } catch (e: Exception) {
            return uploadUguu(file, onProgress)
        }
    }

    private fun postMultipart(
        url: String,
        fields: Map<String, String>,
        fileField: String,
        file: File,
        onProgress: (Float) -> Unit = {},
    ): String {
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
                // Stream the file in chunks with live progress so big
                // uploads never look frozen.
                val total = file.length().coerceAtLeast(1)
                var sent = 0L
                val buf = ByteArray(256 * 1024)
                file.inputStream().buffered().use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        outs.write(buf, 0, n)
                        sent += n
                        runCatching { onProgress(sent.toFloat() / total) }
                    }
                }
                outs.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText()?.trim() ?: ""
            // NOTE: return the raw body — each host has its own reply format
            // (litterbox returns a bare URL, uguu.se returns JSON).
            require(code in 200..299 && body.isNotEmpty()) { "upload $code" }
            return body
        } finally {
            conn.disconnect()
        }
    }

    private fun uploadLitterbox(file: File, onProgress: (Float) -> Unit = {}): String {
        // Temp host (same infra as catbox), files expire in 72h — plenty for a job.
        // Replies with the bare file URL as plain text.
        val body = postMultipart(
            "https://litterbox.catbox.moe/resources/internals/api.php",
            mapOf("reqtype" to "fileupload", "time" to "72h"),
            "fileToUpload", file, onProgress,
        )
        require(body.startsWith("http")) { "litterbox bad reply" }
        return body
    }

    private fun uploadUguu(file: File, onProgress: (Float) -> Unit = {}): String {
        val resp = postMultipart("https://uguu.se/upload.php", mapOf(), "files[]", file, onProgress)
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

    /**
     * Keep asking for the artifact, then stream it down with live progress.
     * [onProgress] gets 0 while waiting and 0..1 while bytes arrive, so the
     * UI never looks frozen on big files. Gives up after ~15 minutes total
     * instead of hanging forever. Returns null if cancelled or timed out.
     */
    fun fetchArtifactPatiently(
        target: String,
        runId: Long,
        name: String,
        isCancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {},
    ): ByteArray? {
        val path = "/api/artifact?target=${enc(target)}&run_id=$runId&name=${enc(name)}"
        val start = System.currentTimeMillis()
        var waited = 0
        while (System.currentTimeMillis() - start < 15 * 60_000L) {
            if (isCancelled()) return null
            val conn = (URL("$RELAY$path").openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
            }
            try {
                if (conn.responseCode == 404) {
                    conn.disconnect()
                    if (waited < 180_000) {
                        runCatching { onProgress(0f) }
                        Thread.sleep(10_000)
                        waited += 10_000
                        continue
                    }
                    return null
                }
                require(conn.responseCode == 200) { "cloud busy (${conn.responseCode})" }
                val total = conn.getHeaderFieldLong("Content-Length", -1)
                val bos = java.io.ByteArrayOutputStream()
                conn.inputStream.buffered().use { ins ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        if (isCancelled()) {
                            conn.disconnect()
                            return null
                        }
                        val n = ins.read(buf)
                        if (n < 0) break
                        bos.write(buf, 0, n)
                        done += n
                        if (total > 0) runCatching { onProgress(done.toFloat() / total) }
                    }
                }
                conn.disconnect()
                runCatching { onProgress(1f) }
                return bos.toByteArray()
            } catch (e: Exception) {
                runCatching { conn.disconnect() }
                // Mid-download failure: wait a bit, then resume from scratch.
                if (isCancelled()) return null
                Thread.sleep(10_000)
            }
        }
        return null
    }
}
