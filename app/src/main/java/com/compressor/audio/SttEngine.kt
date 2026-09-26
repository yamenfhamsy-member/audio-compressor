package com.compressor.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Offline speech-to-text with Vosk (Arabic model, one-time download).
 *
 * Pipeline: MediaCodec decode -> mono 16 kHz shorts -> Vosk Recognizer
 * fed in chunks -> concatenated final text.
 *
 * Call off the main thread. [onProgress] gets 0..1 fraction of the audio
 * consumed. Throw [CancellationException] from [isCancelled] to abort.
 */
object SttEngine {
    const val STT_RATE = 16000

    @Volatile private var model: Model? = null
    private val lock = Any()

    private fun modelFor(modelDir: String): Model {
        synchronized(lock) {
            val m = model
            if (m != null) return m
            val created = Model(modelDir)
            model = created
            return created
        }
    }

    /** Decode any audio/video Uri to mono 16-bit shorts at 16 kHz. */
    fun decodeMono16k(context: Context, source: Uri): ShortArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, source, null)
        try {
            var track = -1
            var mime: String? = null
            for (i in 0 until extractor.trackCount) {
                val m = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                if (m?.startsWith("audio/") == true) {
                    track = i
                    mime = m
                    break
                }
            }
            require(track >= 0 && mime != null) { "no audio track" }
            val inFormat = extractor.getTrackFormat(track)
            val inRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val inChannels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            extractor.selectTrack(track)
            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inFormat, null, null, 0)
            decoder.start()
            try {
                val resampler = LinearResampler(inRate, STT_RATE, 1)
                val mapper = ChannelMapper(inChannels, 1)
                var pcm = ShortArray(1 shl 18)
                var pcmLen = 0
                fun append(data: ShortArray) {
                    if (data.isEmpty()) return
                    val need = pcmLen + data.size
                    if (need > pcm.size) {
                        var ns = pcm.size * 2
                        while (ns < need) ns *= 2
                        pcm = pcm.copyOf(ns)
                    }
                    System.arraycopy(data, 0, pcm, pcmLen, data.size)
                    pcmLen += data.size
                }
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var decoderDone = false
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
                    if (outIdx >= 0) {
                        val buf = decoder.getOutputBuffer(outIdx)!!
                        if (info.size > 0) {
                            buf.order(ByteOrder.LITTLE_ENDIAN)
                            val count = info.size / 2
                            val raw = ShortArray(count)
                            for (i in 0 until count) raw[i] = buf.short
                            val perChannel = count / inChannels
                            append(resampler.process(mapper.map(raw, perChannel), perChannel))
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decoderDone = true
                    }
                }
                append(resampler.drain())
                return pcm.copyOf(pcmLen)
            } finally {
                runCatching { decoder.stop() }
                runCatching { decoder.release() }
            }
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Transcribe 16 kHz mono [pcm] with the model in [modelDir].
     * Returns the concatenated final text (may be empty for music-only audio).
     */
    fun transcribe(
        pcm: ShortArray,
        modelDir: String,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): String {
        val rec = Recognizer(modelFor(modelDir), STT_RATE.toFloat())
        try {
            rec.setWords(false)
            val parts = StringBuilder()
            var consumed = 0
            val chunk = 8000
            while (consumed < pcm.size) {
                if (isCancelled()) throw CancellationException("stt cancelled")
                val n = minOf(chunk, pcm.size - consumed)
                val data = ShortArray(n)
                System.arraycopy(pcm, consumed, data, 0, n)
                if (rec.acceptWaveForm(data, n)) {
                    val t = JSONObject(rec.result).optString("text", "")
                    if (t.isNotBlank()) {
                        if (parts.isNotEmpty()) parts.append(' ')
                        parts.append(t.trim())
                    }
                }
                consumed += n
                onProgress((consumed.toFloat() / pcm.size).coerceIn(0f, 1f))
            }
            val tail = JSONObject(rec.finalResult).optString("text", "")
            if (tail.isNotBlank()) {
                if (parts.isNotEmpty()) parts.append(' ')
                parts.append(tail.trim())
            }
            onProgress(1f)
            return parts.toString().trim()
        } finally {
            runCatching { rec.close() }
        }
    }
}
