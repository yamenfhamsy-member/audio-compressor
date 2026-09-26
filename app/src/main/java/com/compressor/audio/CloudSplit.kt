package com.compressor.audio

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cloud stem separation through the repo's own GitHub Actions (public repo =
 * unlimited free runner minutes). Flow per file:
 *
 * 1. Upload audio to catbox.moe (keyless, ≤200 MB) -> public URL.
 * 2. Dispatch `separate.yml` with {audio_url, job_id, ext} (needs user PAT).
 * 3. Poll the workflow run until completed.
 * 4. Download the stems artifact zip, unzip, publish, delete artifact.
 *
 * Settings (SharedPreferences "settings"): split_engine, github_pat.
 */
object CloudSplit {
    const val ENGINE_DEVICE = "device"
    const val ENGINE_CLOUD = "cloud"

    private const val OWNER = "yamenfhamsy-member"
    private const val REPO = "thorfin-audio-world"
    private const val WORKFLOW = "separate.yml"
    private const val API = "https://api.github.com"

    fun getEngine(context: Context): String =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("split_engine", ENGINE_DEVICE) ?: ENGINE_DEVICE

    fun setEngine(context: Context, engine: String) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putString("split_engine", engine).apply()
    }

    fun getPat(context: Context): String =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("github_pat", "") ?: ""

    fun setPat(context: Context, pat: String) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putString("github_pat", pat.trim()).apply()
    }

    data class CloudStems(val vocals: File, val instrumental: File, val runId: Long)

    /**
     * Full cloud split for one cached audio/video [file] with extension [ext].
     * Returns the two stem files in cache. Throws on auth/network/run failure.
     */
    fun splitFile(
        context: Context,
        file: File,
        ext: String,
        jobId: String,
        onProgress: (Float, String) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): CloudStems {
        val pat = getPat(context)
        require(pat.isNotBlank()) { "github token missing" }
        fun check() {
            if (isCancelled()) throw CancellationException("cloud split cancelled")
        }
        check()
        onProgress(0.02f, "upload")
        val url = uploadCatbox(file)
        check()
        onProgress(0.08f, "dispatch")
        val since = System.currentTimeMillis()
        dispatch(pat, url, jobId, ext)
        // 1. find the run (allow ~2 min for GH to register it)
        var runId = -1L
        var waited = 0
        while (runId < 0 && waited < 120_000) {
            check()
            Thread.sleep(10_000)
            waited += 10_000
            runId = findRun(pat, since)
            onProgress(0.08f, "queued")
        }
        require(runId >= 0) { "run not found" }
        // 2. wait for completion
        while (true) {
            check()
            val (status, conclusion) = runStatus(pat, runId)
            if (status == "completed") {
                require(conclusion == "success") { "run $conclusion" }
                break
            }
            onProgress(0.1f, "working")
            Thread.sleep(20_000)
        }
        check()
        onProgress(0.92f, "download")
        val artifactId = findArtifact(pat, runId, "stems-$jobId")
            ?: throw IllegalStateException("artifact missing")
        val zip = downloadZip(pat, artifactId)
        val (vocals, instrumental) = unzipStems(context, zip, jobId)
        runCatching { deleteArtifact(pat, artifactId) }
        onProgress(1f, "done")
        return CloudStems(vocals, instrumental, runId)
    }

    private fun uploadCatbox(file: File): String {
        require(file.length() in 1..209_715_200L) { "file size not supported" }
        val boundary = "----Thorfin${System.currentTimeMillis()}"
        val conn = (URL("https://catbox.moe/user/api.php").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connectTimeout = 30_000
            readTimeout = 300_000
            doOutput = true
            setChunkedStreamingMode(256 * 1024)
        }
        try {
            conn.outputStream.buffered().use { outs ->
                fun field(name: String, value: String) {
                    outs.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                }
                field("reqtype", "fileupload")
                outs.write("--$boundary\r\nContent-Disposition: form-data; name=\"fileToUpload\"; filename=\"${file.name}\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
                file.inputStream().buffered().use { it.copyTo(outs) }
                outs.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = conn.responseCode
            val body = (if (code == 200) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText()?.trim() ?: ""
            require(code == 200 && body.startsWith("http")) { "upload failed ($code)" }
            return body
        } finally {
            conn.disconnect()
        }
    }

    private fun gh(path: String, pat: String, method: String = "GET", body: String? = null): Pair<Int, String> {
        val conn = (URL("$API$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $pat")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connectTimeout = 20_000
            readTimeout = 30_000
            if (body != null) {
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
        }
        try {
            if (body != null) conn.outputStream.bufferedWriter().use { it.write(body) }
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

    private fun dispatch(pat: String, audioUrl: String, jobId: String, ext: String) {
        val body = JSONObject()
            .put("ref", "main")
            .put(
                "inputs", JSONObject()
                    .put("audio_url", audioUrl)
                    .put("job_id", jobId)
                    .put("ext", ext),
            ).toString()
        val (code, resp) = gh("/repos/$OWNER/$REPO/actions/workflows/$WORKFLOW/dispatches", pat, "POST", body)
        require(code == 204) {
            if (code == 401 || code == 403) "unauthorized (check token)" else "dispatch $code"
        }
    }

    private fun findRun(pat: String, sinceMs: Long): Long {
        val (code, resp) = gh(
            "/repos/$OWNER/$REPO/actions/workflows/$WORKFLOW/runs?per_page=10",
            pat,
        )
        require(code == 200) { "runs api $code" }
        val runs = JSONObject(resp).optJSONArray("workflow_runs") ?: JSONArray()
        for (i in 0 until runs.length()) {
            val r = runs.getJSONObject(i)
            val created = r.optString("created_at", "")
            // ISO8601 compare as string is safe (same format, UTC).
            if (created >= isoOf(sinceMs)) return r.getLong("id")
        }
        return -1
    }

    private fun isoOf(ms: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date(ms))
    }

    private fun runStatus(pat: String, runId: Long): Pair<String, String?> {
        val (code, resp) = gh("/repos/$OWNER/$REPO/actions/runs/$runId", pat)
        require(code == 200) { "run api $code" }
        val o = JSONObject(resp)
        return Pair(o.optString("status", ""), o.optString("conclusion", "").ifBlank { null })
    }

    private fun findArtifact(pat: String, runId: Long, name: String): Long? {
        val (code, resp) = gh("/repos/$OWNER/$REPO/actions/runs/$runId/artifacts?per_page=20", pat)
        require(code == 200) { "artifacts api $code" }
        val list = JSONObject(resp).optJSONArray("artifacts") ?: JSONArray()
        for (i in 0 until list.length()) {
            val a = list.getJSONObject(i)
            if (a.optString("name") == name && !a.optBoolean("expired")) return a.getLong("id")
        }
        return null
    }

    private fun downloadZip(pat: String, artifactId: Long): ByteArray {
        val conn = (URL("$API/repos/$OWNER/$REPO/actions/artifacts/$artifactId/zip").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $pat")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connectTimeout = 20_000
            readTimeout = 300_000
        }
        try {
            require(conn.responseCode == 200) { "download ${conn.responseCode}" }
            val bos = ByteArrayOutputStream()
            conn.inputStream.buffered().use { it.copyTo(bos) }
            return bos.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    private fun unzipStems(context: Context, zip: ByteArray, jobId: String): Pair<File, File> {
        var vocals: File? = null
        var instrumental: File? = null
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            val buf = ByteArray(256 * 1024)
            while (entry != null) {
                val name = entry.name.substringAfterLast('/')
                val target = when {
                    name.equals("vocals.wav", true) ->
                        File(context.cacheDir, "cloud_${jobId}_vocals.wav").also { vocals = it }
                    name.equals("instrumental.wav", true) ->
                        File(context.cacheDir, "cloud_${jobId}_instrumental.wav").also { instrumental = it }
                    else -> null
                }
                if (target != null) {
                    target.outputStream().buffered().use { outs ->
                        while (true) {
                            val n = zis.read(buf)
                            if (n < 0) break
                            outs.write(buf, 0, n)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return Pair(
            vocals?.takeIf { it.exists() && it.length() > 0 }
                ?: throw IllegalStateException("vocals missing"),
            instrumental?.takeIf { it.exists() && it.length() > 0 }
                ?: throw IllegalStateException("instrumental missing"),
        )
    }

    private fun deleteArtifact(pat: String, artifactId: Long) {
        gh("/repos/$OWNER/$REPO/actions/artifacts/$artifactId", pat, "DELETE")
    }
}
