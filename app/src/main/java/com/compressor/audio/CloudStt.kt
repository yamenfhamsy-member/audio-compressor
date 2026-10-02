package com.compressor.audio

import android.content.Context
import java.io.File
import java.util.concurrent.CancellationException

/**
 * Cloud speech-to-text via free GitHub Actions runners (faster-whisper),
 * driven by our always-on Cloudflare relay.
 *
 * Flow per file: upload original audio -> relay dispatch with language ->
 * poll -> download transcript artifact -> text. No chunking: the runner
 * handles long files. The app holds no keys — the relay owns the token.
 */
object CloudStt {

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
        fun check() {
            if (isCancelled()) throw CancellationException("cloud stt cancelled")
        }
        check()
        val url = GhActions.uploadTemp(file) { f ->
            onProgress(0.02f + 0.06f * f, "upload")
        }
        check()
        onProgress(0.08f, "dispatch")
        val since = GhActions.dispatch("stt", url, jobId, mapOf("language" to language))
        var runId = -1L
        var waited = 0
        while (runId < 0 && waited < 180_000) {
            check()
            Thread.sleep(10_000)
            waited += 10_000
            runId = GhActions.pollRun("stt", since)?.runId ?: -1L
            onProgress(0.08f, "queued")
        }
        require(runId >= 0) { "run not found" }
        val deadline = System.currentTimeMillis() + 30 * 60_000L
        while (true) {
            check()
            if (System.currentTimeMillis() > deadline) throw IllegalStateException("run timeout")
            val run = GhActions.pollRun("stt", since)
                ?: throw IllegalStateException("run lost")
            if (run.status == "completed") {
                require(run.conclusion == "success") { "run ${run.conclusion}" }
                break
            }
            onProgress(0.1f, "working")
            Thread.sleep(20_000)
        }
        check()
        val zip = GhActions.fetchArtifactPatiently("stt", runId, jobId, isCancelled) { f ->
            onProgress(0.86f + 0.13f * f, "download")
        } ?: throw IllegalStateException("artifact missing")
        val text = unzipTranscript(zip) ?: throw IllegalStateException("transcript missing")
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
