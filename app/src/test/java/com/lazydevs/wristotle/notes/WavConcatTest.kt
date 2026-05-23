package com.lazydevs.wristotle.notes

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavConcatTest {

    @get:Rule val tmp = TemporaryFolder()

    /**
     * Write a minimal 16-bit mono PCM WAV with `sampleCount` distinct
     * sample values (so the test can verify concat order) at [sampleRate].
     */
    private fun writeWav(file: File, sampleRate: Int, samples: ShortArray): File {
        val dataSize = samples.size * 2
        val totalSize = WAV_HEADER_SIZE - 8 + dataSize
        val bytes = ByteBuffer.allocate(WAV_HEADER_SIZE + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray(Charsets.US_ASCII))
        bytes.putInt(totalSize)
        bytes.put("WAVE".toByteArray(Charsets.US_ASCII))
        bytes.put("fmt ".toByteArray(Charsets.US_ASCII))
        bytes.putInt(16)
        bytes.putShort(1)             // PCM
        bytes.putShort(1)             // mono
        bytes.putInt(sampleRate)
        bytes.putInt(sampleRate * 2)  // byte rate
        bytes.putShort(2)             // block align
        bytes.putShort(16)            // bits per sample
        bytes.put("data".toByteArray(Charsets.US_ASCII))
        bytes.putInt(dataSize)
        for (s in samples) bytes.putShort(s)
        file.writeBytes(bytes.array())
        return file
    }

    /** Read the RIFF chunk size (file size − 8) from a WAV. */
    private fun riffSize(file: File): Int =
        readLE(file, 4)

    /** Read the data subchunk size from a WAV. */
    private fun dataSize(file: File): Int =
        readLE(file, 40)

    private fun readLE(file: File, offset: Long): Int = RandomAccessFile(file, "r").use { raf ->
        raf.seek(offset)
        val b = ByteArray(4)
        raf.readFully(b)
        (b[0].toInt() and 0xFF) or
            ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or
            ((b[3].toInt() and 0xFF) shl 24)
    }

    private fun readSamples(file: File): ShortArray {
        val bytes = file.readBytes()
        val pcm = bytes.copyOfRange(WAV_HEADER_SIZE, bytes.size)
        val buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val out = ShortArray(pcm.size / 2)
        for (i in out.indices) out[i] = buf.short
        return out
    }

    @Test fun `append concatenates pcm and rewrites size fields`() {
        val a = writeWav(tmp.newFile("a.wav"), 16_000, shortArrayOf(1, 2, 3, 4))
        val b = writeWav(tmp.newFile("b.wav"), 16_000, shortArrayOf(5, 6, 7))
        val appended = appendWav(target = a, source = b)
        assertEquals(6L, appended) // 3 samples × 2 bytes
        assertEquals(shortArrayOf(1, 2, 3, 4, 5, 6, 7).toList(), readSamples(a).toList())
        // RIFF = 36 + dataSize ; dataSize = (4 + 3) samples × 2 = 14
        assertEquals(14, dataSize(a))
        assertEquals(14 + 36, riffSize(a))
        // File length matches header + dataSize.
        assertEquals((WAV_HEADER_SIZE + 14).toLong(), a.length())
    }

    @Test fun `multiple appends keep header sizes correct`() {
        val target = writeWav(tmp.newFile("t.wav"), 16_000, shortArrayOf(1, 2))
        val s1 = writeWav(tmp.newFile("s1.wav"), 16_000, shortArrayOf(3, 4))
        val s2 = writeWav(tmp.newFile("s2.wav"), 16_000, shortArrayOf(5, 6, 7))
        appendWav(target, s1)
        appendWav(target, s2)
        assertEquals(shortArrayOf(1, 2, 3, 4, 5, 6, 7).toList(), readSamples(target).toList())
        // 7 samples × 2 bytes = 14
        assertEquals(14, dataSize(target))
        assertEquals(50, riffSize(target)) // 14 + 36
    }

    @Test fun `sample-rate mismatch throws so caller can fall back`() {
        val a = writeWav(tmp.newFile("a.wav"), 16_000, shortArrayOf(1, 2, 3))
        val b = writeWav(tmp.newFile("b.wav"), 8_000, shortArrayOf(4, 5))
        assertThrows(IllegalArgumentException::class.java) { appendWav(a, b) }
    }

    @Test fun `non-WAV source throws`() {
        val a = writeWav(tmp.newFile("a.wav"), 16_000, shortArrayOf(1, 2, 3))
        val notWav = tmp.newFile("garbage.wav").apply {
            writeBytes(ByteArray(100) { 0xAB.toByte() })
        }
        assertThrows(IllegalArgumentException::class.java) { appendWav(a, notWav) }
    }

    @Test fun `non-WAV target throws`() {
        val notWav = tmp.newFile("garbage.wav").apply {
            writeBytes(ByteArray(100) { 0xAB.toByte() })
        }
        val source = writeWav(tmp.newFile("s.wav"), 16_000, shortArrayOf(1, 2))
        assertThrows(IllegalArgumentException::class.java) { appendWav(notWav, source) }
    }

    @Test fun `tiny source (under header size) throws`() {
        val target = writeWav(tmp.newFile("t.wav"), 16_000, shortArrayOf(1, 2))
        val tiny = tmp.newFile("tiny.wav").apply { writeBytes(ByteArray(10) { 0 }) }
        assertThrows(IllegalArgumentException::class.java) { appendWav(target, tiny) }
    }
}
