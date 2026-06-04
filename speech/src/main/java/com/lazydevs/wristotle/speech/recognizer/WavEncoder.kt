package com.lazydevs.wristotle.speech.recognizer

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RIFF/WAV framing for a PCM-16 mono utterance. Allocates one
 * `ByteBuffer` sized header + body and returns its backing array; the
 * caller owns the result (multipart upload via [HttpRecognizer], etc.).
 */
internal object WavEncoder {

    private const val PCM_FORMAT: Short = 1
    private const val BITS_PER_SAMPLE: Short = 16
    private const val HEADER_BYTES = 44

    /**
     * @param samples 16-bit signed PCM, one channel.
     * @param sampleRate sample rate in Hz (16000 for the Wristotle pipeline).
     */
    fun encode(samples: ShortArray, sampleRate: Int): ByteArray {
        val dataBytes = samples.size * 2
        val buf = ByteBuffer.allocate(HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataBytes)             // ChunkSize = 36 + Subchunk2Size
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))

        // fmt sub-chunk (PCM)
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)                          // Subchunk1Size for PCM
        buf.putShort(PCM_FORMAT)
        buf.putShort(1)                         // NumChannels = mono
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2)              // ByteRate = SampleRate * NumChannels * BitsPerSample/8
        buf.putShort(2)                         // BlockAlign = NumChannels * BitsPerSample/8
        buf.putShort(BITS_PER_SAMPLE)

        // data sub-chunk
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataBytes)

        // PCM body — little-endian shorts via the same buffer.
        for (s in samples) buf.putShort(s)

        return buf.array()
    }
}
