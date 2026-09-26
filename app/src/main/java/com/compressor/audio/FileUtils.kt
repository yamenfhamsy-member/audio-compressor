package com.compressor.audio

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File

data class AudioItem(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val isVideo: Boolean = false,
    val hasAudio: Boolean = true,
    val audioMime: String? = null,
) {
    val isMp3Audio: Boolean get() = hasAudio && audioMime == "audio/mpeg"
}

/** Probe the audio track of an audio/video Uri: (hasAudio, mime). */
fun queryAudioTrack(context: Context, uri: Uri): Pair<Boolean, String?> {
    val extractor = android.media.MediaExtractor()
    return runCatching {
        extractor.setDataSource(context, uri, null)
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i)
                .getString(android.media.MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) return Pair(true, mime)
        }
        Pair(false, null)
    }.getOrDefault(Pair(false, null)).also { runCatching { extractor.release() } }
}

fun queryDisplayName(resolver: ContentResolver, uri: Uri): String {
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0) return c.getString(i) ?: "audio"
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "audio"
}

fun querySize(resolver: ContentResolver, uri: Uri): Long {
    resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val i = c.getColumnIndex(OpenableColumns.SIZE)
            if (i >= 0) return c.getLong(i)
        }
    }
    return runCatching {
        resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
    }.getOrDefault(0L)
}

fun queryDurationMs(context: Context, uri: Uri): Long {
    val r = MediaMetadataRetriever()
    return runCatching {
        r.setDataSource(context, uri)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    }.getOrDefault(0L).also { runCatching { r.release() } }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var v = bytes.toDouble()
    var u = 0
    while (v >= 1024 && u < units.size - 1) { v /= 1024; u++ }
    return if (u == 0) "$bytes B" else String.format("%.1f %s", v, units[u])
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "--:--"
    val s = ms / 1000
    return String.format("%d:%02d", s / 60, s % 60)
}

fun savedPercent(before: Long, after: Long): Int {
    if (before <= 0) return 0
    return ((1 - after.toDouble() / before) * 100).toInt().coerceIn(0, 99)
}

/** Publish a finished file to Download/ThorfinAudioWorld so the user keeps it. */
fun publishToDownloads(
    context: Context,
    file: File,
    displayName: String,
    mime: String = "audio/ogg",
): Uri? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        // Legacy path: MediaStore.Files needs an absolute DATA path pre-Q.
        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "ThorfinAudioWorld",
        )
        if (!dir.mkdirs() && !dir.isDirectory) return null
        val dest = File(dir, displayName)
        runCatching { file.copyTo(dest, overwrite = true) }.onFailure { return null }
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
        return Uri.fromFile(dest)
    }
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, displayName)
        put(MediaStore.Downloads.MIME_TYPE, mime)
        put(
            MediaStore.Downloads.RELATIVE_PATH,
            Environment.DIRECTORY_DOWNLOADS + "/ThorfinAudioWorld",
        )
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val dest = resolver.insert(collection, values) ?: return null
    val outs = resolver.openOutputStream(dest)
    if (outs == null) {
        resolver.delete(dest, null, null)
        return null
    }
    runCatching {
        outs.use { file.inputStream().use { ins -> ins.copyTo(it) } }
    }.onFailure {
        resolver.delete(dest, null, null)
        return null
    }
    values.clear()
    values.put(MediaStore.Downloads.IS_PENDING, 0)
    resolver.update(dest, values, null, null)
    return dest
}
