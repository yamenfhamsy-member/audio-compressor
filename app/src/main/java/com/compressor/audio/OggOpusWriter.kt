package com.compressor.audio

import io.github.jaredmdobson.concentus.OpusEncoder
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Minimal Ogg container writer for Opus packets (RFC 3533 framing + RFC 7845 headers).
 * Pure JVM, no native code.
 *
 * Layout: BOS page with OpusHead, page with OpusTags, then audio pages with
 * granule positions in 48 kHz units. Encoder runs at 48 kHz so granule math is exact.
 * Pages that end mid-packet carry granule -1 per the Ogg spec.
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
    private var granule: Long = 0 // 48 kHz samples encoded so far (preskip added at write time)
    private var closed = false

    // Current page assembly
    private val segTable = ByteArray(MAX_SEGMENTS)
    private var segCount = 0
    private val pageData = mutableListOf<ByteArray>()

    // State for the page currently being assembled
    private var pageStartsWithContinuation = false
    private var pageIsBos = false
    private var lastGranule = 0L // granule of the last fully-buffered packet

    init {
        bufferPacket(opusHead())
        pageIsBos = true
        flushPage(endOfStream = false, granulePos = 0)
        bufferPacket(opusTags())
        flushPage(endOfStream = false, granulePos = 0)
    }

    private val packetBuf = ByteArray(4000)

    /** Encode one 20 ms frame and buffer its packet into the current page. */
    fun writeAudioFrame(encoder: OpusEncoder, pcm: ShortArray, frameSize: Int) {
        val n = encoder.encode(pcm, 0, frameSize, packetBuf, 0, packetBuf.size)
        require(n > 0) { "opus encode failed" }
        granule += frameSize
        bufferPacket(packetBuf.copyOf(n))
        lastGranule = granule + OPUS_PRESKIP
    }

    /**
     * Flush remaining audio and emit the EOS page. If the last audio page was
     * already flushed, an (spec-legal) empty EOS page carries the final granule.
     */
    fun finish() {
        flushPage(endOfStream = true, granulePos = lastGranule)
        out.flush()
    }

    override fun close() {
        if (!closed) {
            closed = true
            runCatching { out.close() }
        }
    }

    private fun bufferPacket(packet: ByteArray) {
        // Don't let a page grow unbounded: flush the previous page first,
        // stamped with the previous packet's granule.
        if (segCount >= FLUSH_THRESHOLD) {
            flushPage(endOfStream = false, granulePos = lastGranule)
        }
        var off = 0
        while (off < packet.size) {
            if (segCount >= MAX_SEGMENTS) {
                // Page ends mid-packet: granule -1, next page starts with continuation.
                flushPage(endOfStream = false, granulePos = -1)
                pageStartsWithContinuation = true
            }
            val chunk = minOf(255, packet.size - off)
            segTable[segCount++] = chunk.toByte()
            pageData.add(packet.copyOfRange(off, off + chunk))
            off += chunk
        }
        // Packet completed inside this page: the next page starts fresh.
        pageStartsWithContinuation = false
    }

    private fun flushPage(endOfStream: Boolean, granulePos: Long) {
        var flags = 0
        if (pageIsBos) flags = flags or 0x02
        if (pageStartsWithContinuation) flags = flags or 0x01
        if (endOfStream) flags = flags or 0x04
        val header = ByteBuffer.allocate(27 + segCount).order(ByteOrder.LITTLE_ENDIAN)
        header.put("OggS".toByteArray(Charsets.US_ASCII))
        header.put(0.toByte()) // version
        header.put(flags.toByte())
        header.putLong(granulePos)
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
        pageIsBos = false
        pageStartsWithContinuation = false
    }

    private fun opusHead(): ByteArray {
        val b = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        b.put("OpusHead".toByteArray(Charsets.US_ASCII))
        b.put(1.toByte()) // version
        b.put(channels.toByte())
        b.putShort(OPUS_PRESKIP.toShort())
        b.putInt(inputSampleRate) // informational original rate
        b.putShort(0) // output gain
        b.put(0.toByte()) // channel mapping family 0 (mono/stereo)
        return b.array()
    }

    private fun opusTags(): ByteArray {
        val vendor = "Thorfin Audio World".toByteArray(Charsets.UTF_8)
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
