package com.compressor.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

/**
 * On-device vocal/instrumental split with UVR-MDX-NET-Voc_FT (ONNX, MIT weights).
 *
 * Pipeline: MediaCodec decode -> 44.1 kHz stereo float -> Hann-windowed chunks
 * (261120 samples, 25% overlap) -> host STFT -> ORT inference -> host iSTFT ->
 * overlap-add -> vocals; instrumental = mix - vocals.
 *
 * Runs fully offline once the model file is present (see ModelManager).
 * Call off the main thread. [onProgress] gets 0..1; throw [CancellationException]
 * from [isCancelled] to abort between chunks.
 */
object StemSplit {
    const val MIX_RATE = 44100
    private const val N_FFT = 6144
    private const val HOP = 1024
    private const val DIM_F = 3072
    private const val DIM_T = 256
    private const val CHUNK = HOP * (DIM_T - 1) // 261120 samples (~5.9 s)
    private const val TRIM = N_FFT / 2 // 3072
    private const val OVERLAP = 0.25f

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null
    private val lock = Any()

    data class Stems(
        val vocals: FloatArray, // interleaved stereo, 44.1 kHz
        val instrumental: FloatArray,
        val frames: Int,
    )

    private fun sessionFor(modelPath: String): OrtSession {
        synchronized(lock) {
            val s = session
            if (s != null) return s
            val e = OrtEnvironment.getEnvironment()
            env = e
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 8))
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val created = e.createSession(modelPath, opts)
            session = created
            return created
        }
    }

    /** Decode any audio/video Uri to interleaved stereo float at 44.1 kHz. */
    fun decodeMix(context: Context, source: Uri): Pair<FloatArray, Int> {
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
                val resampler = LinearResampler(inRate, MIX_RATE, 2)
                val mapper = ChannelMapper(inChannels, 2)
                // Growable primitive buffer — never box Shorts (a 3-min song is ~23M samples).
                var pcm = ShortArray(1 shl 20)
                var pcmLen = 0
                fun appendShorts(data: ShortArray) {
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
                            val out = resampler.process(mapper.map(raw, perChannel), perChannel)
                            appendShorts(out)
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decoderDone = true
                    }
                }
                appendShorts(resampler.drain())
                val interleaved = FloatArray(pcmLen) { i -> pcm[i] / 32768f }
                return Pair(interleaved, pcmLen / 2)
            } finally {
                runCatching { decoder.stop() }
                runCatching { decoder.release() }
            }
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Split interleaved stereo [mix] ([frames] per channel) into vocals/instrumental.
     * [modelPath] must point at UVR-MDX-NET-Voc_FT.onnx.
     */
    fun split(
        mix: FloatArray,
        frames: Int,
        modelPath: String,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Stems {
        require(mix.size == frames * 2) { "mix size mismatch" }
        val session = sessionFor(modelPath)
        val stft = MdxStft(N_FFT, HOP, DIM_F)
        val gen = CHUNK - 2 * TRIM
        val padEnd = gen + TRIM - (frames % gen)
        val total = TRIM + frames + padEnd
        val mixL = FloatArray(total)
        val mixR = FloatArray(total)
        for (i in 0 until frames) {
            mixL[TRIM + i] = mix[i * 2]
            mixR[TRIM + i] = mix[i * 2 + 1]
        }
        val hopStep = ((1f - OVERLAP) * CHUNK).toInt()
        val resultL = FloatArray(total)
        val resultR = FloatArray(total)
        val divider = FloatArray(total)
        val chunkStarts = (0 until total step hopStep).toList()
        val totalChunks = chunkStarts.size

        val inBuf = ByteBuffer.allocateDirect(1 * 4 * DIM_F * DIM_T * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()

        chunkStarts.forEachIndexed { ci, start ->
            if (isCancelled()) throw CancellationException("split cancelled")
            val actual = minOf(CHUNK, total - start)
            val partL = FloatArray(CHUNK)
            val partR = FloatArray(CHUNK)
            System.arraycopy(mixL, start, partL, 0, actual)
            System.arraycopy(mixR, start, partR, 0, actual)
            val window = stft.hannSymmetric(actual)
            val specL = stft.forward(partL, CHUNK, DIM_T)
            val specR = stft.forward(partR, CHUNK, DIM_T)

            inBuf.clear()
            // planes: L_re, L_im, R_re, R_im — flat index ((plane*DIM_F)+bin)*DIM_T+frame
            for (b in 0 until DIM_F) {
                for (f in 0 until DIM_T) {
                    val o = (b * DIM_T + f) * 2
                    inBuf.put(specL[o])
                }
            }
            for (b in 0 until DIM_F) {
                for (f in 0 until DIM_T) {
                    val o = (b * DIM_T + f) * 2
                    inBuf.put(specL[o + 1])
                }
            }
            for (b in 0 until DIM_F) {
                for (f in 0 until DIM_T) {
                    val o = (b * DIM_T + f) * 2
                    inBuf.put(specR[o])
                }
            }
            for (b in 0 until DIM_F) {
                for (f in 0 until DIM_T) {
                    val o = (b * DIM_T + f) * 2
                    inBuf.put(specR[o + 1])
                }
            }
            inBuf.flip()
            val outL: FloatArray
            val outR: FloatArray
            val inputTensor = OnnxTensor.createTensor(
                OrtEnvironment.getEnvironment(), inBuf,
                longArrayOf(1, 4, DIM_F.toLong(), DIM_T.toLong()),
            )
            try {
                val result = session.run(mapOf("input" to inputTensor))
                try {
                    @Suppress("UNCHECKED_CAST")
                    val out = (result[0].value as Array<Array<Array<FloatArray>>>)[0]
                    // out[plane][bin][frame]
                    val planeLre = flattenPlane(out[0])
                    val planeLim = flattenPlane(out[1])
                    val planeRre = flattenPlane(out[2])
                    val planeRim = flattenPlane(out[3])
                    outL = stft.inverse(interleave(planeLre, planeLim), CHUNK, DIM_T)
                    outR = stft.inverse(interleave(planeRre, planeRim), CHUNK, DIM_T)
                } finally {
                    result.close()
                }
            } finally {
                inputTensor.close()
            }
            for (i in 0 until actual) {
                resultL[start + i] += outL[i] * window[i]
                resultR[start + i] += outR[i] * window[i]
                divider[start + i] += window[i]
            }
            onProgress((ci + 1).toFloat() / totalChunks)
        }

        val vocals = FloatArray(frames * 2)
        val instrumental = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val d = divider[TRIM + i].coerceAtLeast(1e-6f)
            val vl = resultL[TRIM + i] / d
            val vr = resultR[TRIM + i] / d
            vocals[i * 2] = vl
            vocals[i * 2 + 1] = vr
            instrumental[i * 2] = mix[i * 2] - vl
            instrumental[i * 2 + 1] = mix[i * 2 + 1] - vr
        }
        return Stems(vocals, instrumental, frames)
    }

    /** [DIM_F][DIM_T] nested -> flat row-major. */
    private fun flattenPlane(plane: Array<FloatArray>): FloatArray {
        val flat = FloatArray(DIM_F * DIM_T)
        for (b in 0 until DIM_F) {
            System.arraycopy(plane[b], 0, flat, b * DIM_T, DIM_T)
        }
        return flat
    }

    /** Two [DIM_F*DIM_T] flats (re, im) -> forward()/inverse() layout. */
    private fun interleave(re: FloatArray, im: FloatArray): FloatArray {
        val out = FloatArray(DIM_F * DIM_T * 2)
        for (i in re.indices) {
            out[i * 2] = re[i]
            out[i * 2 + 1] = im[i]
        }
        return out
    }
}
