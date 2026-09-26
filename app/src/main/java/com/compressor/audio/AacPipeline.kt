package com.compressor.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val AAC_RATE = 48000
private const val AAC_MIME = "audio/mp4a-latm"

/**
 * Audio -> M4A/AAC using only built-in platform codecs: MediaCodec decodes the
 * source, PCM is resampled to 48 kHz, MediaCodec encodes AAC-LC, MediaMuxer
 * writes the .m4a container. Zero native dependencies.
 *
 * Must be called off the main thread. [onProgress] is invoked with 0..1 fraction.
 */
fun convertToM4a(
    context: Context,
    source: Uri,
    dest: File,
    channels: Int,
    bitrate: Int,
    onProgress: (Float) -> Unit = {},
) {
    val extractor = MediaExtractor()
    extractor.setDataSource(context, source, null)
    try {
        var track = -1
        var mime: String? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val m = f.getString(MediaFormat.KEY_MIME)
            if (m?.startsWith("audio/") == true) {
                track = i
                mime = m
                break
            }
        }
        require(track >= 0 && mime != null) { "no audio track found" }
        val inFormat = extractor.getTrackFormat(track)
        val inRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val inChannels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val durationUs = runCatching { inFormat.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
        extractor.selectTrack(track)

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(inFormat, null, null, 0)
        val encoderFormat = MediaFormat.createAudioFormat(AAC_MIME, AAC_RATE, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC,
            )
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        val encoder = MediaCodec.createEncoderByType(AAC_MIME)
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        decoder.start()
        encoder.start()
        var muxer: MediaMuxer? = null
        var muxTrack = -1
        try {
            val resampler = LinearResampler(inRate, AAC_RATE, channels)
            val mapper = ChannelMapper(inChannels, channels)
            var pcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT
            var inputDone = false
            var decoderDone = false
            var encoderDone = false
            var framesFed = 0L // 48 kHz frames per channel fed to the encoder
            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            // Queued encoder-ready PCM bytes (16-bit LE, interleaved target layout).
            var pending = ByteArray(64 * 1024)
            var pendingLen = 0

            fun appendPcm16LE(data: ShortArray) {
                val need = pendingLen + data.size * 2
                if (need > pending.size) {
                    var ns = pending.size * 2
                    while (ns < need) ns *= 2
                    pending = pending.copyOf(ns)
                }
                val bb = ByteBuffer.wrap(pending, pendingLen, data.size * 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                for (s in data) bb.putShort(s)
                pendingLen = need
            }

            fun drainResamplerTail() {
                val tail = resampler.drain()
                if (tail.isNotEmpty()) appendPcm16LE(tail)
            }

            // AAC-LC frame = 1024 samples per channel. Never hold a dequeued
            // input buffer without queueing it — check data availability first.
            fun feedEncoder(final: Boolean) {
                while (true) {
                    val frameBytes = 1024 * channels * 2
                    val partial = final && pendingLen in 1 until frameBytes
                    if (pendingLen < frameBytes && !partial) return
                    val inIdx = encoder.dequeueInputBuffer(0)
                    if (inIdx < 0) return
                    val buf = encoder.getInputBuffer(inIdx) ?: return
                    val take = minOf(pendingLen, frameBytes)
                    buf.clear()
                    buf.put(pending, 0, take)
                    val remain = pendingLen - take
                    System.arraycopy(pending, take, pending, 0, remain)
                    pendingLen = remain
                    val pts = framesFed * 1_000_000L / AAC_RATE
                    framesFed += take / (channels * 2)
                    encoder.queueInputBuffer(inIdx, 0, take, pts, 0)
                }
            }

            fun drainEncoder(): Boolean {
                while (true) {
                    val outIdx = encoder.dequeueOutputBuffer(encInfo, 5_000)
                    when {
                        outIdx >= 0 -> {
                            val buf = encoder.getOutputBuffer(outIdx)!!
                            if (encInfo.size > 0 && muxer != null && muxTrack >= 0) {
                                buf.position(encInfo.offset)
                                buf.limit(encInfo.offset + encInfo.size)
                                muxer!!.writeSampleData(muxTrack, buf, encInfo)
                            }
                            encoder.releaseOutputBuffer(outIdx, false)
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                return true
                            }
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            muxer = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                            muxTrack = muxer!!.addTrack(encoder.outputFormat)
                            muxer!!.start()
                        }
                        else -> return false
                    }
                }
            }

            var eosPushed = false

            while (!encoderDone) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                if (!decoderDone) {
                    val outIdx = decoder.dequeueOutputBuffer(decInfo, 10_000)
                    when {
                        outIdx >= 0 -> {
                            val buf = decoder.getOutputBuffer(outIdx)!!
                            if (decInfo.size > 0) {
                                val shorts = pcmToS16(buf, decInfo.size, pcmEncoding)
                                val perChannel = shorts.size / inChannels
                                appendPcm16LE(resampler.process(mapper.map(shorts, perChannel), perChannel))
                            }
                            decoder.releaseOutputBuffer(outIdx, false)
                            if (durationUs > 0) {
                                onProgress((decInfo.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f) * 0.85f)
                            }
                            if (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                drainResamplerTail()
                                decoderDone = true
                            }
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            pcmEncoding = decoder.outputFormat
                                .getInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                        }
                    }
                }
                feedEncoder(decoderDone)
                if (drainEncoder()) encoderDone = true
                if (decoderDone && pendingLen == 0 && !eosPushed) {
                    // Push EOS exactly once, then keep draining until it arrives.
                    var queued = false
                    while (!queued) {
                        val inIdx = encoder.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            encoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            queued = true
                        }
                    }
                    eosPushed = true
                }
            }
            onProgress(1f)
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
    } finally {
        runCatching { extractor.release() }
    }
    require(dest.exists() && dest.length() > 0) { "encoder produced no output" }
}

/** Convert a decoded PCM buffer (16-bit or float) to 16-bit shorts. */
private fun pcmToS16(buf: ByteBuffer, bytes: Int, encoding: Int): ShortArray {
    return if (encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
        val floats = bytes / 4
        val out = ShortArray(floats)
        val dup = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until floats) {
            out[i] = (dup.float * 32767f).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        out
    } else {
        val shorts = bytes / 2
        val out = ShortArray(shorts)
        val dup = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until shorts) out[i] = dup.short
        out
    }
}
