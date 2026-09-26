package com.compressor.audio

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File

/**
 * One-time download of the vocal-separation ONNX model. The model is NOT
 * bundled in the APK (it would triple its size); after this download the
 * split feature works fully offline.
 *
 * Model: UVR-MDX-NET-Voc_FT (MIT, Ultimate Vocal Remover project),
 * mirrored by k2-fsa/sherpa-onnx on GitHub Releases.
 */
object ModelManager {
    private const val MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
            "source-separation-models/UVR-MDX-NET-Voc_FT.onnx"
    private const val MODEL_NAME = "UVR-MDX-NET-Voc_FT.onnx"

    /** ~63.7 MB on disk; ±3 MB tolerance for container variance. */
    private const val EXPECTED_BYTES = 63_700_000L
    private const val TOLERANCE = 3_000_000L

    // ---- Speech-to-text (Vosk Arabic, one-time download, unzipped on first use) ----
    private const val STT_URL =
        "https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip"
    private const val STT_ZIP = "vosk-model-ar-mgb2-0.4.zip"
    private const val STT_DIR = "vosk-model-ar-mgb2-0.4"

    /** ~333 MB download; ±10 MB tolerance. */
    private const val STT_EXPECTED_BYTES = 333_241_610L
    private const val STT_TOLERANCE = 10_000_000L

    sealed interface Status {
        data object Missing : Status
        data class Downloading(val fraction: Float) : Status
        data object Ready : Status
        data object Failed : Status
    }

    fun modelFile(context: Context): File =
        File(context.getExternalFilesDir(null), "models/$MODEL_NAME")

    fun isReady(context: Context): Boolean {
        val f = modelFile(context)
        return f.exists() && kotlin.math.abs(f.length() - EXPECTED_BYTES) <= TOLERANCE
    }

    /** Enqueue the download; returns the DownloadManager id (or -1 if already ready). */
    fun enqueue(context: Context): Long {
        if (isReady(context)) return -1
        val dest = modelFile(context)
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()
        val req = DownloadManager.Request(Uri.parse(MODEL_URL)).apply {
            setTitle("Thorfin Audio World — split model")
            setDescription(MODEL_NAME)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setDestinationInExternalFilesDir(context, null, "models/$MODEL_NAME")
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
        }
        val dm = context.getSystemService(DownloadManager::class.java)
        return dm.enqueue(req)
    }

    /** Poll the download state for [downloadId]. */
    fun query(context: Context, downloadId: Long): Status {
        if (downloadId < 0) return if (isReady(context)) Status.Ready else Status.Missing
        val dm = context.getSystemService(DownloadManager::class.java)
        val q = DownloadManager.Query().setFilterById(downloadId)
        dm.query(q)?.use { c ->
            if (!c.moveToFirst()) {
                return if (isReady(context)) Status.Ready else Status.Failed
            }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL ->
                    if (isReady(context)) Status.Ready else Status.Failed
                DownloadManager.STATUS_FAILED -> Status.Failed
                else -> {
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val frac = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                    Status.Downloading(frac)
                }
            }
        }
        return Status.Failed
    }

    fun cancel(context: Context, downloadId: Long) {
        if (downloadId >= 0) {
            runCatching {
                context.getSystemService(DownloadManager::class.java).remove(downloadId)
            }
        }
    }

    // ---- STT model (Vosk Arabic) ----

    fun sttZipFile(context: Context): File =
        File(context.getExternalFilesDir(null), "models/$STT_ZIP")

    fun sttDir(context: Context): File =
        File(context.getExternalFilesDir(null), "models/$STT_DIR")

    /** Ready = extracted model dir present. */
    fun isSttReady(context: Context): Boolean =
        File(sttDir(context), "am/final.mdl").exists() ||
            File(sttDir(context), "conf/model.conf").exists()

    fun isSttZipReady(context: Context): Boolean {
        val f = sttZipFile(context)
        return f.exists() && kotlin.math.abs(f.length() - STT_EXPECTED_BYTES) <= STT_TOLERANCE
    }

    fun enqueueStt(context: Context): Long {
        if (isSttZipReady(context) || isSttReady(context)) return -1
        val dest = sttZipFile(context)
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()
        val req = DownloadManager.Request(Uri.parse(STT_URL)).apply {
            setTitle("Thorfin Audio World — Arabic STT model")
            setDescription(STT_ZIP)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setDestinationInExternalFilesDir(context, null, "models/$STT_ZIP")
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
        }
        return context.getSystemService(DownloadManager::class.java).enqueue(req)
    }

    /** STT status: Missing / Downloading(zip) / Ready(dir) / Failed. */
    fun sttStatus(context: Context, downloadId: Long): Status {
        if (isSttReady(context)) return Status.Ready
        if (downloadId < 0) {
            return if (isSttZipReady(context)) Status.Downloading(1f) else Status.Missing
        }
        val dm = context.getSystemService(DownloadManager::class.java)
        val q = DownloadManager.Query().setFilterById(downloadId)
        dm.query(q)?.use { c ->
            if (!c.moveToFirst()) {
                return if (isSttZipReady(context)) Status.Downloading(1f) else Status.Failed
            }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL ->
                    if (isSttZipReady(context)) Status.Downloading(1f) else Status.Failed
                DownloadManager.STATUS_FAILED -> Status.Failed
                else -> {
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    // Zip download is ~85% of the work; extraction is the rest.
                    val frac = if (total > 0) {
                        (done.toFloat() / total).coerceIn(0f, 1f) * 0.85f
                    } else 0f
                    Status.Downloading(frac)
                }
            }
        }
        return Status.Failed
    }

    /**
     * Stream-extract the STT zip into the models dir. Returns the model dir,
     * or null on failure. Deletes the zip afterwards to reclaim space.
     */
    fun extractStt(
        context: Context,
        onProgress: (Float) -> Unit = {},
    ): File? {
        val zip = sttZipFile(context)
        val destDir = File(context.getExternalFilesDir(null), "models")
        return try {
            val total = zip.length().coerceAtLeast(1L)
            var read = 0L
            var lastPosted = 0f
            java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                val buf = ByteArray(256 * 1024)
                while (entry != null) {
                    val out = File(destDir, entry.name)
                    // Zip-slip guard: stay inside the models dir.
                    if (!out.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) {
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().buffered().use { outs ->
                            while (true) {
                                val n = zis.read(buf)
                                if (n < 0) break
                                outs.write(buf, 0, n)
                                read += n
                                val f = 0.85f + 0.15f * (read.toFloat() / total).coerceIn(0f, 1f)
                                if (f - lastPosted > 0.02f) {
                                    lastPosted = f
                                    onProgress(f)
                                }
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            onProgress(1f)
            if (isSttReady(context)) {
                runCatching { zip.delete() }
                sttDir(context)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
