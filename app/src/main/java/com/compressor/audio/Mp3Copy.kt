package com.compressor.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Direct MP3 stream copy: when the source audio track is already MP3,
 * its access units are dumped verbatim into a .mp3 file. Instant, zero loss.
 * Throws if the source has no MP3 audio track.
 */
fun copyMp3Stream(
    context: Context,
    source: Uri,
    dest: File,
    onProgress: (Float) -> Unit = {},
): Long {
    val extractor = MediaExtractor()
    extractor.setDataSource(context, source, null)
    try {
        var track = -1
        var durationUs = 0L
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME) == "audio/mpeg") {
                track = i
                durationUs = runCatching { f.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
                break
            }
        }
        require(track >= 0) { "no MP3 audio track" }
        extractor.selectTrack(track)
        val buf = ByteBuffer.allocate(256 * 1024)
        var written = 0L
        FileOutputStream(dest).use { outs ->
            while (true) {
                buf.clear()
                val n = extractor.readSampleData(buf, 0)
                if (n < 0) break
                outs.write(buf.array(), 0, n)
                written += n
                if (durationUs > 0) {
                    onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                }
                extractor.advance()
            }
        }
        require(written > 0) { "empty MP3 stream" }
        onProgress(1f)
        return written
    } finally {
        runCatching { extractor.release() }
    }
}
