package com.compressor.audio

import android.content.Context
import java.io.File
import java.util.concurrent.CancellationException

/**
 * Cloud speech-to-text via the thorfin-stt-cloud repo's Actions
 * (faster-whisper base multilingual, free on public repos).
 *
 * Flow per file: upload original audio -> dispatch stt.yml with language ->
 * poll -> download transcript-<jobId> artifact (transcript.txt) -> text.
 * Needs the user's GitHub PAT. No chunking: the runner handles long files.
 */
object CloudStt {
    private const val OWNER = "yamenfhamsy-member"
    private const val REPO = "thorfin-stt-cloud"
    private const val WORKFLOW = "stt.yml"

    /**
     * Transcribe [file] (any audio/video; the runner decodes it).
     * [language] defaults to Arabic. Returns the transcript text.
     */
    fun transcribe(
        context: Context,
        file: File,
        language: String = "ar",
        jobId: String,
        onProgress: (Float, String) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): String {
        val pat = GhActions.getPat(context)
        require(pat.isNotBlank()) { "github token missing" }
        fun check() {
            if (isCancelled()) throw CancellationException("cloud stt cancelled")
        }
        check()
        onProgress(0.02f, "upload")
        val url = GhActions.uploadTemp(file)
        check()
        onProgress(0.08f, "dispatch")
        val since = System.currentTimeMillis()
        GhActions.dispatch(OWNER, REPO, WORKFLOW, pat,
            mapOf("audio_url" to url, "job_id" to jobId, "language" to language))
        var runId = -1L
        var waited = 0
        while (runId < 0 && waited < 180_000) {
            check()
            Thread.sleep(10_000)
            waited += 10_000
            runId = GhActions.findRun(OWNER, REPO, WORKFLOW, pat, since)
            onProgress(0.08f, "queued")
        }
        require(runId >= 0) { "run not found" }
        while (true) {
            check()
            val (status, conclusion) = GhActions.runStatus(OWNER, REPO, pat, runId)
            if (status == "completed") {
                require(conclusion == "success") { "run $conclusion" }
                break
            }
            onProgress(0.1f, "working")
            Thread.sleep(20_000)
        }
        check()
        onProgress(0.92f, "download")
        val artifactId = GhActions.findArtifact(OWNER, REPO, pat, runId, "transcript-$jobId")
            ?: throw IllegalStateException("artifact missing")
        val zip = GhActions.downloadZip(OWNER, REPO, pat, artifactId)
        val text = unzipTranscript(zip) ?: throw IllegalStateException("transcript missing")
        runCatching { GhActions.deleteArtifact(OWNER, REPO, pat, artifactId) }
        onProgress(1f, "done")
        return text.trim()
    }

    private fun unzipTranscript(zip: ByteArray): String? {
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            val buf = ByteArray(64 * 1024)
            while (entry != null) {
                if (entry.name.substringAfterLast('/').equals("transcript.txt", true)) {
                    val bos = java.io.ByteArrayOutputStream()
                    while (true) {
                        val n = zis.read(buf)
                        if (n < 0) break
                        bos.write(buf, 0, n)
                    }
                    return bos.toString(Charsets.UTF_8.name())
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return null
    }
}
