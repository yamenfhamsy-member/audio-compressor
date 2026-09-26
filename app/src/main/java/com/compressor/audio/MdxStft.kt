package com.compressor.audio

import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.PI
import kotlin.math.cos

/**
 * STFT / iSTFT matching torch.stft(center=True) semantics used by UVR MDX-Net:
 * periodic Hann window, reflect padding of n_fft/2 on both sides, unnormalized,
 * keeping bins [0, dimF) — the Nyquist bin is dropped on the way in and
 * re-attached as zeros on the way out.
 *
 * Voc_FT params: n_fft=6144, hop=1024, dimF=3072, frames per chunk=256.
 */
class MdxStft(
    private val nFft: Int = 6144,
    private val hop: Int = 1024,
    private val dimF: Int = 3072,
) {
    private val fft = DoubleFFT_1D(nFft.toLong())
    private val pad = nFft / 2

    /** Periodic Hann window, length [size]. */
    fun hannPeriodic(size: Int): FloatArray =
        FloatArray(size) { n -> (0.5 - 0.5 * cos(2.0 * PI * n / size)).toFloat() }

    /** Symmetric Hann window (numpy.hanning), for chunk-level overlap-add. */
    fun hannSymmetric(size: Int): FloatArray =
        if (size <= 1) FloatArray(size) { 1f }
        else FloatArray(size) { n -> (0.5 - 0.5 * cos(2.0 * PI * n / (size - 1))).toFloat() }

    /**
     * Forward STFT of one channel. Input length must be exactly [chunkLen];
     * returns [dimF][frames] of interleaved (re,im) pairs flattened as
     * FloatArray(dimF * frames * 2).
     */
    fun forward(x: FloatArray, chunkLen: Int, frames: Int): FloatArray {
        // reflect-pad pad samples on both sides (torch center=True)
        val padded = FloatArray(chunkLen + nFft)
        for (i in 0 until pad) {
            padded[i] = x[(pad - i).coerceIn(0, chunkLen - 1)]
            padded[chunkLen + pad + i] = x[(chunkLen - 2 - i).coerceIn(0, chunkLen - 1)]
        }
        System.arraycopy(x, 0, padded, pad, chunkLen)
        val window = hannPeriodic(nFft)
        val out = FloatArray(dimF * frames * 2)
        val buf = DoubleArray(nFft * 2)
        for (f in 0 until frames) {
            val off = f * hop
            for (n in 0 until nFft) {
                buf[2 * n] = padded[off + n] * window[n]
                buf[2 * n + 1] = 0.0
            }
            fft.complexForward(buf)
            for (b in 0 until dimF) {
                val o = (b * frames + f) * 2
                out[o] = buf[2 * b].toFloat()
                out[o + 1] = buf[2 * b + 1].toFloat()
            }
        }
        return out
    }

    /**
     * Inverse STFT of one channel spec [dimF][frames] (same layout as forward).
     * Returns the center-trimmed waveform of length [chunkLen].
     */
    fun inverse(spec: FloatArray, chunkLen: Int, frames: Int): FloatArray {
        val nBins = nFft / 2 + 1
        val y = DoubleArray(chunkLen + nFft)
        val wsum = DoubleArray(chunkLen + nFft)
        val window = hannPeriodic(nFft)
        val buf = DoubleArray(nFft * 2)
        for (f in 0 until frames) {
            for (b in 0 until nBins) {
                val v0: Double
                val v1: Double
                if (b < dimF) {
                    val o = (b * frames + f) * 2
                    v0 = spec[o].toDouble()
                    v1 = spec[o + 1].toDouble()
                } else {
                    v0 = 0.0 // Nyquist re-attached as zero
                    v1 = 0.0
                }
                buf[2 * b] = v0
                buf[2 * b + 1] = v1
            }
            fft.complexInverse(buf, true)
            val off = f * hop
            for (n in 0 until nFft) {
                val w = window[n].toDouble()
                y[off + n] += buf[2 * n] * w
                wsum[off + n] += w * w
            }
        }
        val out = FloatArray(chunkLen)
        for (i in 0 until chunkLen) {
            val v = y[pad + i] / wsum[pad + i].coerceAtLeast(1e-8)
            out[i] = v.toFloat()
        }
        return out
    }
}
