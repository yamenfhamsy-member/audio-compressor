package com.compressor.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.use
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder

/** Compression presets. All output is Opus-in-Ogg (.opus). */
enum class Preset(
    val title: String,
    val subtitle: String,
    val channels: Int,
    val bitrate: Int,
    val application: OpusApplication,
) {
    MUSIC("Music", "Opus 96k stereo", 2, 96000, OpusApplication.OPUS_APPLICATION_AUDIO),
    BALANCED("Balanced", "Opus 64k stereo", 2, 64000, OpusApplication.OPUS_APPLICATION_AUDIO),
    VOICE("Voice", "Opus 24k mono", 1, 24000, OpusApplication.OPUS_APPLICATION_VOIP),
}

fun outputExtension(@Suppress("UNUSED_PARAMETER") preset: Preset): String = "opus"

/** Opus encoder always runs at 48 kHz; input is resampled to match. */
private const val ENCODER_RATE = 48000
private const val FRAME_MS = 20
private const val FRAME_SAMPLES = ENCODER_RATE * FRAME_MS / 1000 // 960

/**
 * MP3/M4A/WAV/FLAC -> Opus-in-Ogg, fully on-device with zero native dependencies:
 * MediaExtractor + MediaCodec (decode) -> linear resample to 48 kHz ->
 * Concentus OpusEncoder -> hand-rolled Ogg muxer.
 *
 * Must be called off the main thread. [onProgress] is invoked with 0..1 fraction.
 */
fun convertToOpus(
    context: Context,
    source: Uri,
    dest: File,
    preset: Preset,
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
        extractor.selectTrack(track)

        val durationUs = inFormat.getLong(MediaFormat.KEY_DURATION, 0L)

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(inFormat, null, null, 0)
        decoder.start()
        try {
            val encoder = OpusEncoder(ENCODER_RATE, preset.channels, preset.application)
            encoder.bitrate = preset.bitrate

            OggOpusWriter(dest, preset.channels, inRate).use { ogg ->
                val resampler = LinearResampler(inRate, ENCODER_RATE, preset.channels)
                val mapper = ChannelMapper(inChannels, preset.channels)
                // Accumulates encoder-ready (48 kHz, target channels) samples.
                var pending = ShortArray(FRAME_SAMPLES * preset.channels * 4)
                var pendingCount = 0 // samples per channel
                val frame = ShortArray(FRAME_SAMPLES * preset.channels)
                var totalEncoded = 0L // 48 kHz samples per channel fed to encoder
                var inputDone = false
                var decoderDone = false
                val info = MediaCodec.BufferInfo()

                fun flushFrames(final: Boolean) {
                    while (pendingCount >= FRAME_SAMPLES || (final && pendingCount > 0)) {
                        val take = minOf(pendingCount, FRAME_SAMPLES)
                        frame.fill(0)
                        System.arraycopy(pending, 0, frame, 0, take * preset.channels)
                        // shift remainder
                        val remain = pendingCount - take
                        if (remain > 0) {
                            System.arraycopy(pending, take * preset.channels, pending, 0, remain * preset.channels)
                        }
                        pendingCount = remain
                        ogg.writeAudioFrame(encoder, frame, FRAME_SAMPLES)
                        totalEncoded += FRAME_SAMPLES
                        if (take < FRAME_SAMPLES) break // padded final frame consumed everything
                    }
                }

                fun appendPending(out: ShortArray) {
                    if (out.isEmpty()) return
                    val need = pendingCount + out.size / preset.channels
                    if (need * preset.channels > pending.size) {
                        var ns = pending.size * 2
                        while (ns < need * preset.channels) ns *= 2
                        pending = pending.copyOf(ns)
                    }
                    System.arraycopy(out, 0, pending, pendingCount * preset.channels, out.size)
                    pendingCount += out.size / preset.channels
                }

                fun feedPcm16(raw: ByteBuffer, bytes: Int) {
                    raw.order(ByteOrder.LITTLE_ENDIAN)
                    val shorts = bytes / 2
                    val perChannel = shorts / inChannels
                    var inBuf = ShortArray(maxOf(1024, perChannel * inChannels))
                    if (inBuf.size < shorts) inBuf = ShortArray(shorts)
                    for (i in 0 until shorts) inBuf[i] = raw.short
                    // channel map to interleaved target layout in encoder-rate domain
                    val mapped = mapper.map(inBuf, perChannel)
                    val out = resampler.process(mapped, perChannel)
                    appendPending(out)
                    flushFrames(false)
                }

                while (!decoderDone) {
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
                    val outIdx = decoder.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIdx >= 0 -> {
                            val buf = decoder.getOutputBuffer(outIdx)!!
                            if (info.size > 0) feedPcm16(buf, info.size)
                            decoder.releaseOutputBuffer(outIdx, false)
                            if (durationUs > 0) {
                                onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f) * 0.9f)
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decoderDone = true
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* ignore */ }
                    }
                }
                // Flush resampler tail, then the final (possibly partial, zero-padded) frame.
                appendPending(resampler.drain())
                flushFrames(true)
                ogg.finish()
                onProgress(1f)
            }
        } finally {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
        }
    } finally {
        runCatching { extractor.release() }
    }
}

private fun MediaFormat.getLong(key: String, default: Long): Long =
    runCatching { getLong(key) }.getOrDefault(default)

/** Maps interleaved input channels to the encoder channel layout. */
private class ChannelMapper(private val inCh: Int, private val outCh: Int) {
    fun map(interleaved: ShortArray, perChannel: Int): ShortArray {
        if (inCh == outCh) return interleaved.copyOf(perChannel * inCh)
        val out = ShortArray(perChannel * outCh)
        for (i in 0 until perChannel) {
            when {
                outCh == 1 -> {
                    // average all input channels to mono
                    var sum = 0
                    for (c in 0 until inCh) sum += interleaved[i * inCh + c]
                    out[i] = (sum / inCh).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                inCh == 1 -> {
                    // duplicate mono to stereo
                    val s = interleaved[i]
                    out[i * 2] = s
                    out[i * 2 + 1] = s
                }
                else -> {
                    // take first two channels
                    out[i * 2] = interleaved[i * inCh]
                    out[i * 2 + 1] = interleaved[i * inCh + 1]
                }
            }
        }
        return out
    }
}

/**
 * Streaming linear-interpolation resampler from [inRate] to 48000 Hz.
 * Input and output channel count is always [outCh] (mapping happens before).
 */
private class LinearResampler(inRate: Int, private val outRate: Int, private val channels: Int) {
    private val step: Double = inRate.toDouble() / outRate
    private var pos = 0.0
    // leftover input samples carried between calls (per channel interleave tail)
    private var carry: ShortArray = ShortArray(0)
    private var carryFrames = 0

    fun process(input: ShortArray, frames: Int): ShortArray {
        if (step == 1.0 && carryFrames == 0) {
            pos += frames
            return input.copyOf(frames * channels)
        }
        // prepend carry
        val total = carryFrames + frames
        val buf = ShortArray(total * channels)
        System.arraycopy(carry, 0, buf, 0, carryFrames * channels)
        System.arraycopy(input, 0, buf, carryFrames * channels, frames * channels)

        val outFrames = ((total - pos) / step).toInt().coerceAtLeast(0)
        val out = ShortArray(outFrames * channels)
        var oi = 0
        while (oi < outFrames) {
            val idx = pos.toInt()
            val frac = (pos - idx).toFloat()
            val i0 = idx.coerceIn(0, total - 1)
            val i1 = (idx + 1).coerceIn(0, total - 1)
            for (c in 0 until channels) {
                val a = buf[i0 * channels + c].toInt()
                val b = buf[i1 * channels + c].toInt()
                out[oi * channels + c] = (a + (b - a) * frac).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            pos += step
            oi++
        }
        // keep unconsumed tail as carry; rebase pos
        val consumed = pos.toInt()
        val keepFrom = consumed.coerceIn(0, total)
        val keep = total - keepFrom
        carry = ShortArray(keep * channels)
        if (keep > 0) {
            System.arraycopy(buf, keepFrom * channels, carry, 0, keep * channels)
        }
        carryFrames = keep
        pos -= consumed
        return out
    }

    /** Flush leftover input through zero-padding. Call once at end of stream. */
    fun drain(): ShortArray {
        if (carryFrames == 0) return ShortArray(0)
        val out = process(ShortArray(carryFrames * channels), carryFrames)
        carryFrames = 0
        carry = ShortArray(0)
        pos = 0.0
        return out
    }
}
