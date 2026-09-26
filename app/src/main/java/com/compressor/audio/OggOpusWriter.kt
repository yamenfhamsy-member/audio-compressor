package com.compressor.audio

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import org.concentus.OpusEncoder

/**
 * Minimal Ogg container writer for Opus packets (RFC 3533 framing + RFC 7845 headers).
 * Pure JVM, no native code.
 *
 * Layout: BOS page with OpusHead, page with OpusTags, then audio pages with
 * granule positions in 48 kHz units. Encoder runs at 48 kHz so granule math is exact.
 */
class OggOpusWriter(
    dest: File,
    private val channels: Int,
    private val inputSampleRate: Int,
) : Closeable {
    companion object {
        private const val OPUS_PRESKIP = 312 // libopus/CONCENTUS preskip at 48 kHz
        private const val MAX_SEGMENTS = 255
        private const val FLUSH_THRESHOLD = 200
    }

    private val out = BufferedOutputStream(FileOutputStream(dest))
    private val serial: Int = Random.nextInt()
    private var sequence = 0
    private var granule: Long = 0 // 48 kHz samples encoded so far (+ preskip below)
    private var closed = false

    // Current page assembly
    private val segTable = ByteArray(MAX_SEGMENTS)
    private var segCount = 0
    private val pageData = mutableListOf<ByteArray>()
    private var pageBytes = 0

    init {
        writeHeaderPacket(opusHead(), beginningOfStream = true, granulePos = 0)
        flushPage(continued = false, endOfStream = false, granulePos = 0)
        writeHeaderPacket(opusTags(), beginningOfStream = false, granulePos = 0)
        flushPage(continued = false, endOfStream = false, granulePos = 0)
    }

    private val packetBuf = ByteArray(4000)

    /** Encode one 20 ms frame and buffer its packet into the current page. */
    fun writeAudioFrame(encoder: OpusEncoder, pcm: ShortArray, frameSize: Int) {
        val n = encoder.encode(pcm, 0, frameSize, packetBuf, 0, packetBuf.size)
        require(n > 0) { "opus encode failed" }
        granule += frameSize
        bufferPacket(packetBuf.copyOf(n), granule + OPUS_PRESKIP, endOfStream = false)
    }

    /** Flush remaining pages and close. [totalEncoded] is informational. */
    fun finish(@Suppress("UNUSED_PARAMETER") totalEncoded: Long) {
        if (segCount > 0 || pageData.isEmpty()) {
            flushPage(continued = false, endOfStream = true, granulePos = granule + OPUS_PRESKIP)
        } else {
            // Mark EOS: rewrite is complex; instead emit an empty EOS page.
            flushPage(continued = false, endOfStream = true, granulePos = granule + OPUS_PRESKIP)
        }
        out.flush()
    }

    override fun close() {
        if (!closed) {
            closed = true
            runCatching { out.close() }
        }
    }

    private fun bufferPacket(packet: ByteArray, granulePos: Long, endOfStream: Boolean) {
        var off = 0
        var first = true
        while (off < packet.size) {
            val chunk = minOf(255, packet.size - off)
            if (segCount >= FLUSH_THRESHOLD && first) {
                flushPage(continued = false, endOfStream = false, granulePos = granulePos)
            }
            segTable[segCount++] = chunk.toByte()
            pageData.add(packet.copyOfRange(off, off + chunk))
            pageBytes += chunk
            off += chunk
            first = false
            if (segCount >= MAX_SEGMENTS) {
                flushPage(continued = off < packet.size, endOfStream = false, granulePos = granulePos)
            }
        }
        pendingEos = endOfStream
        pendingGranule = granulePos
        if (segCount >= FLUSH_THRESHOLD) {
            flushPage(continued = false, endOfStream = false, granulePos = granulePos)
        }
    }

    private var pendingEos = false
    private var pendingGranule = 0L

    private fun writeHeaderPacket(packet: ByteArray, beginningOfStream: Boolean, granulePos: Long) {
        pendingBos = beginningOfStream
        bufferPacketHeader(packet)
        pendingGranule = granulePos
    }

    private var pendingBos = false

    private fun bufferPacketHeader(packet: ByteArray) {
        var off = 0
        while (off < packet.size) {
            val chunk = minOf(255, packet.size - off)
            segTable[segCount++] = chunk.toByte()
            pageData.add(packet.copyOfRange(off, off + chunk))
            pageBytes += chunk
            off += chunk
        }
    }

    private fun flushPage(continued: Boolean, endOfStream: Boolean, granulePos: Long) {
        var flags = 0
        if (pendingBos) flags = flags or 0x02
        if (continued) flags = flags or 0x01
        if (endOfStream || pendingEos) flags = flags or 0x04
        val header = ByteBuffer.allocate(27 + segCount).order(ByteOrder.LITTLE_ENDIAN)
        header.put("OggS".toByteArray(Charsets.US_ASCII))
        header.put(0) // version
        header.put(flags.toByte())
        header.putLong(if (segCount == 0 && !pendingBos) granulePos else pendingGranuleFor(headerFlags = flags, granulePos))
        header.putInt(serial)
        header.putInt(sequence++)
        header.putInt(0) // checksum placeholder
        header.put(segCount.toByte())
        for (i in 0 until segCount) header.put(segTable[i])
        val headerBytes = header.array()
        val crc = oggChecksum(headerBytes, pageData)
        ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN).putInt(22, crc)
        out.write(headerBytes)
        for (d in pageData) out.write(d)
        segCount = 0
        pageData.clear()
        pageBytes = 0
        pendingEos = false
        pendingBos = false
    }

    private fun pendingGranuleFor(headerFlags: Int, granulePos: Long): Long {
        // BOS header pages carry granule 0; audio pages carry the last packet's granule.
        return if (headerFlags and 0x02 != 0) 0L else granulePos
    }

    private fun opusHead(): ByteArray {
        val b = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        b.put("OpusHead".toByteArray(Charsets.US_ASCII))
        b.put(1) // version
        b.put(channels.toByte())
        b.putShort(OPUS_PRESKIP.toShort())
        b.putInt(inputSampleRate) // informational original rate
        b.putShort(0) // output gain
        b.put(0) // channel mapping family 0 (mono/stereo)
        return b.array()
    }

    private fun opusTags(): ByteArray {
        val vendor = "AudioCompressor".toByteArray(Charsets.UTF_8)
        val b = ByteBuffer.allocate(8 + 4 + vendor.size + 4).order(ByteOrder.LITTLE_ENDIAN)
        b.put("OpusTags".toByteArray(Charsets.US_ASCII))
        b.putInt(vendor.size)
        b.put(vendor)
        b.putInt(0) // zero user comments
        return b.array()
    }

    // Ogg CRC32 (MSB-first, poly 0x04C11DB7) — NOT java.util.zip.CRC32.
    private fun oggChecksum(header: ByteArray, pages: List<ByteArray>): Int {
        var crc = 0
        for (byte in header) {
            crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor (byte.toInt() and 0xFF)) and 0xFF]
        }
        for (d in pages) {
            for (byte in d) {
                crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor (byte.toInt() and 0xFF)) and 0xFF]
            }
        }
        return crc
    }
}

private val CRC_TABLE = IntArray(256) { i ->
    var r = i shl 24
    repeat(8) {
        r = if (r and Int.MIN_VALUE != 0) (r shl 1) xor 0x04C11DB7 else r shl 1
    }
    r
}
