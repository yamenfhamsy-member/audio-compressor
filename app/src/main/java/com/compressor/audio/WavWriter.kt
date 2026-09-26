package com.compressor.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal 16-bit PCM WAV writer (stereo/mono, any sample rate). */
object WavWriter {
    /**
     * Write interleaved float samples (-1..1) as 16-bit PCM.
     * [channels] is 1 or 2; data length must be frames*channels.
     */
    fun write(dest: File, samples: FloatArray, sampleRate: Int, channels: Int) {
        require(channels == 1 || channels == 2) { "channels must be 1 or 2" }
        val frames = samples.size / channels
        val dataBytes = frames * channels * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataBytes)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // PCM header size
        header.putShort(1) // PCM format
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * channels * 2) // byte rate
        header.putShort((channels * 2).toShort()) // block align
        header.putShort(16) // bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes)
        BufferedOutputStream(FileOutputStream(dest)).use { outs ->
            outs.write(header.array())
            val bb = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < samples.size) {
                bb.clear()
                while (i < samples.size && bb.remaining() >= 2) {
                    val s = (samples[i] * 32767f).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    bb.putShort(s.toShort())
                    i++
                }
                outs.write(bb.array(), 0, bb.position())
            }
        }
    }
}
