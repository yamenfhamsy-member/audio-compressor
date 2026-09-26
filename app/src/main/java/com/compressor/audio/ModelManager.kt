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

}
