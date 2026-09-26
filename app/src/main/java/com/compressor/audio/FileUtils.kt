package com.compressor.audio

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

data class AudioItem(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long,
)

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

/** Copy a SAF uri into app cache so FFmpeg gets a plain file path. */
fun copyToCache(context: Context, uri: Uri, name: String): File {
    val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
    val out = File(context.cacheDir, "in_$safe")
    context.contentResolver.openInputStream(uri)?.use { ins ->
        FileOutputStream(out).use { outs -> ins.copyTo(outs) }
    }
    return out
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

/** Publish a finished file to Download/AudioCompressor so the user keeps it. */
fun publishToDownloads(context: Context, file: File, displayName: String): Uri? {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, displayName)
        put(MediaStore.Downloads.MIME_TYPE, "audio/ogg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/AudioCompressor",
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Files.getContentUri("external")
    }
    val dest = resolver.insert(collection, values) ?: return null
    runCatching {
        resolver.openOutputStream(dest)?.use { outs -> file.inputStream().use { it.copyTo(outs) } }
    }.onFailure { resolver.delete(dest, null, null); return null }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(dest, values, null, null)
    }
    return dest
}
