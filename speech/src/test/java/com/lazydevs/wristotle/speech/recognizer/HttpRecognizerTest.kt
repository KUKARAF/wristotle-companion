package com.lazydevs.wristotle.speech.recognizer

import android.speech.SpeechRecognizer
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/**
 * Wire-shape tests for [HttpRecognizer] against a tiny embedded
 * `com.sun.net.httpserver.HttpServer` (JDK stdlib — no extra deps).
 *
 * Direct against [HttpRecognizer.postTranscription] so the test doesn't
 * need an `AudioSource` or coroutine machinery — we're verifying the
 * HTTP layer in isolation. The end-to-end audio→flow path is exercised
 * by the on-device smoke test (Phase D).
 */
class HttpRecognizerTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    /** Captures every request received so assertions can inspect headers
     *  + bodies after the call returns. */
    private val received = mutableListOf<CapturedRequest>()

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
        baseUrl = "http://127.0.0.1:${server.address.port}/v1"
    }

    @After
    fun stop() {
        server.stop(0)
    }

    @Test
    fun `success path returns transcript text`() {
        respond { exchange ->
            received += CapturedRequest.from(exchange)
            sendJson(exchange, 200, """{"text":"hello world"}""")
        }
        val text = newRecognizer().postTranscription(SAMPLE_WAV)
        assertEquals("hello world", text)
    }

    @Test
    fun `request hits the audio transcriptions path under the configured base`() {
        respond { exchange ->
            received += CapturedRequest.from(exchange)
            sendJson(exchange, 200, """{"text":""}""")
        }
        newRecognizer().runCatching { postTranscription(SAMPLE_WAV) }
        assertEquals(1, received.size)
        assertEquals("/v1/audio/transcriptions", received[0].path)
        assertEquals("POST", received[0].method)
    }

    @Test
    fun `multipart body carries the wav under a 'file' part with audio-wav content-type`() {
        respond { exchange ->
            received += CapturedRequest.from(exchange)
            sendJson(exchange, 200, """{"text":""}""")
        }
        newRecognizer().postTranscription(SAMPLE_WAV)
        val body = received[0].bodyAsText
        assertTrue("has file part", body.contains("name=\"file\""))
        assertTrue("has wav content-type", body.contains("Content-Type: audio/wav"))
        assertTrue("has model part", body.contains("name=\"model\""))
        assertTrue("model name", body.contains("test-model"))
        assertTrue("response_format part", body.contains("name=\"response_format\""))
        assertTrue("language part", body.contains("name=\"language\""))
    }

    @Test
    fun `language part is omitted when language is blank`() {
        respond { exchange ->
            received += CapturedRequest.from(exchange)
            sendJson(exchange, 200, """{"text":""}""")
        }
        newRecognizer(language = "").postTranscription(SAMPLE_WAV)
        assertTrue("no language part", !received[0].bodyAsText.contains("name=\"language\""))
    }

    @Test
    fun `authorization header is added when key is non-blank`() {
        respond { exchange ->
            received += CapturedRequest.from(exchange)
            sendJson(exchange, 200, """{"text":""}""")
        }
        newRecognizer(apiKey = "sk-test-123").postTranscription(SAMPLE_WAV)
        assertEquals("Bearer sk-test-123", received[0].headers["Authorization"]?.firstOrNull())
    }

    @Test
    fun `authorization header is omitted for self-hosted (blank key)`() {
        respond { exchange ->
            received += CapturedRequest.from(exchange)
            sendJson(exchange, 200, """{"text":""}""")
        }
        newRecognizer(apiKey = "").postTranscription(SAMPLE_WAV)
        assertNull(received[0].headers["Authorization"])
    }

    @Test
    fun `401 surfaces as ERROR_CLIENT with provider message`() {
        respond { exchange -> sendJson(exchange, 401, """{"error":{"message":"bad token"}}""") }
        val e = assertThrowsHre { newRecognizer().postTranscription(SAMPLE_WAV) }
        assertEquals(SpeechRecognizer.ERROR_CLIENT, e.errorCode)
        assertTrue(e.message!!.contains("401"))
    }

    @Test
    fun `429 surfaces as ERROR_NETWORK with provider message`() {
        respond { exchange -> sendJson(exchange, 429, """{"error":{"message":"slow down"}}""") }
        val e = assertThrowsHre { newRecognizer().postTranscription(SAMPLE_WAV) }
        assertEquals(SpeechRecognizer.ERROR_NETWORK, e.errorCode)
        assertEquals("slow down", e.message)
    }

    @Test
    fun `500 surfaces as ERROR_SERVER`() {
        respond { exchange -> sendJson(exchange, 500, """{"error":{"message":"upstream down"}}""") }
        val e = assertThrowsHre { newRecognizer().postTranscription(SAMPLE_WAV) }
        assertEquals(SpeechRecognizer.ERROR_SERVER, e.errorCode)
    }

    @Test
    fun `unparseable success body surfaces ERROR_NETWORK`() {
        respond { exchange -> sendJson(exchange, 200, "<html>not json</html>") }
        val e = assertThrowsHre { newRecognizer().postTranscription(SAMPLE_WAV) }
        assertEquals(SpeechRecognizer.ERROR_NETWORK, e.errorCode)
    }

    @Test
    fun `blank base url is rejected before the network call`() {
        val e = assertThrowsHre {
            HttpRecognizer(baseUrl = "", apiKey = "", model = "x").postTranscription(SAMPLE_WAV)
        }
        assertEquals(SpeechRecognizer.ERROR_CLIENT, e.errorCode)
    }

    // ── helpers ────────────────────────────────────────────────────────

    private fun newRecognizer(
        apiKey: String = "sk-test",
        language: String = "en",
    ) = HttpRecognizer(
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = "test-model",
        language = language,
    )

    private fun respond(handler: HttpHandler) {
        server.createContext("/v1/audio/transcriptions", handler)
    }

    private fun sendJson(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders["Content-Type"] = listOf("application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private inline fun assertThrowsHre(block: () -> Unit): HttpRecognizerException {
        try {
            block()
            fail("expected HttpRecognizerException")
            error("unreachable")
        } catch (e: HttpRecognizerException) {
            return e
        }
    }

    /**
     * Snapshot of an inbound HTTP request captured at the handler. Holds
     * the bytes so assertions can run after [HttpExchange] is closed.
     */
    private data class CapturedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, List<String>>,
        val body: ByteArray,
    ) {
        val bodyAsText: String get() = body.toString(Charsets.UTF_8)

        companion object {
            fun from(exchange: HttpExchange): CapturedRequest {
                val bytes = exchange.requestBody.readBytes()
                val hdrs = exchange.requestHeaders.toMap()
                return CapturedRequest(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.path,
                    headers = hdrs,
                    body = bytes,
                )
            }
        }
    }

    private companion object {
        /** Two-sample WAV — enough to verify the body shape without
         *  generating tens of KB per test. */
        private val SAMPLE_WAV = WavEncoder.encode(shortArrayOf(0, 1), sampleRate = 16_000)
    }
}
