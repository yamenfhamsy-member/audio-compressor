package com.compressor.audio

import android.content.Context
import java.io.File
import java.util.concurrent.CancellationException

/**
 * Cloud stem separation via free GitHub Actions runners (Demucs),
 * driven by our always-on Cloudflare relay. Flow per file: upload ->
 * relay dispatch -> poll -> download stems zip -> unzip -> publish.
 * The app holds no keys at all — the relay owns the GitHub token.
 */
object CloudSplit {
    data class CloudStems(val vocals: File, val instrumental: File)

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
        fun check() {
            if (isCancelled()) throw CancellationException("cloud split cancelled")
        }
        check()
        onProgress(0.02f, "upload")
        val url = GhActions.uploadTemp(file)
        check()
        onProgress(0.08f, "dispatch")
        val since = GhActions.dispatch("stems", url, jobId, mapOf("ext" to ext))
        var runId = -1L
        var waited = 0
        while (runId < 0 && waited < 180_000) {
            check()
            Thread.sleep(10_000)
            waited += 10_000
            runId = GhActions.pollRun("stems", since)?.runId ?: -1L
            onProgress(0.08f, "queued")
        }
        require(runId >= 0) { "run not found" }
        while (true) {
            check()
            val run = GhActions.pollRun("stems", since)
                ?: throw IllegalStateException("run lost")
            if (run.status == "completed") {
                require(run.conclusion == "success") { "run ${run.conclusion}" }
                break
            }
            onProgress(0.1f, "working")
            Thread.sleep(20_000)
        }
        check()
        onProgress(0.92f, "download")
        val zip = GhActions.fetchArtifactPatiently("stems", runId, jobId, isCancelled)
            ?: throw IllegalStateException("artifact missing")
        val (vocals, instrumental) = unzipStems(context, zip, jobId)
        onProgress(1f, "done")
        return CloudStems(vocals, instrumental)
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
}
