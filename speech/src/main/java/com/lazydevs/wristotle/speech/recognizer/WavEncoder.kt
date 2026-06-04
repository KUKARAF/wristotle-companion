package com.lazydevs.wristotle.speech.recognizer

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encode a buffered PCM-16 mono ShortArray into a complete WAV byte
 * array. Used by the HTTP recognizer family to package an utterance for
 * `multipart/form-data` upload to OpenAI-compatible `/audio/transcriptions`
 * endpoints. Whisper / Groq / Cloudflare / Speaches all accept WAV as
 * one of their canonical formats — no resampling or transcoding needed
 * because the rest of the speech pipeline is already 16 kHz mono 16-bit.
 *
 * The encoder is **synchronous + allocating** by design: an utterance is
 * at most ~8 seconds, so ~256 KB of audio + 44 bytes of header. Streamed
 * upload would save the allocation but complicate retry / failover, and
 * the savings don't matter at this size.
 */
internal object WavEncoder {

    private const val PCM_FORMAT: Short = 1
    private const val BITS_PER_SAMPLE: Short = 16
    private const val HEADER_BYTES = 44

    /**
     * Returns `samples` framed as a RIFF/WAV container.
     *
     * @param samples 16-bit signed PCM, one channel.
     * @param sampleRate sample rate in Hz (16000 for the Wristotle pipeline).
     */
    fun encode(samples: ShortArray, sampleRate: Int): ByteArray {
        val dataBytes = samples.size * 2
        val out = ByteArrayOutputStream(HEADER_BYTES + dataBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataBytes)             // ChunkSize = 36 + Subchunk2Size
        header.put("WAVE".toByteArray(Charsets.US_ASCII))

        // fmt sub-chunk (PCM)
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)                          // Subchunk1Size for PCM
        header.putShort(PCM_FORMAT)
        header.putShort(1)                         // NumChannels = mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)              // ByteRate = SampleRate * NumChannels * BitsPerSample/8
        header.putShort(2)                         // BlockAlign = NumChannels * BitsPerSample/8
        header.putShort(BITS_PER_SAMPLE)

        // data sub-chunk
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes)

        out.write(header.array())

        // PCM body — little-endian shorts.
        val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) body.putShort(s)
        out.write(body.array())

        return out.toByteArray()
    }
}
