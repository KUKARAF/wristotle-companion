package com.lazydevs.wristotle.speech.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Direct tests for the shared HTTP helpers in [HttpUtil]. They're
 * exercised indirectly by `HttpRecognizerTest`'s status-code paths,
 * but with three callers across two modules (the LLM agent clients in
 * `:app/agent` and the speech-to-text [HttpRecognizer] in `:speech`)
 * the contract is worth pinning down explicitly.
 */
class HttpUtilTest {

    // ── bucketFor ─────────────────────────────────────────────────────

    @Test fun `401 and 403 map to Auth`() {
        assertEquals(HttpFailureBucket.Auth, bucketFor(401))
        assertEquals(HttpFailureBucket.Auth, bucketFor(403))
    }

    @Test fun `429 maps to RateLimit`() {
        assertEquals(HttpFailureBucket.RateLimit, bucketFor(429))
    }

    @Test fun `5xx range maps to Server`() {
        assertEquals(HttpFailureBucket.Server, bucketFor(500))
        assertEquals(HttpFailureBucket.Server, bucketFor(503))
        assertEquals(HttpFailureBucket.Server, bucketFor(599))
    }

    @Test fun `other 4xx codes fall into Other`() {
        // 400 / 404 / 422 are real failures but not Auth, RateLimit, or
        // Server — caller decides how to surface them.
        assertEquals(HttpFailureBucket.Other, bucketFor(400))
        assertEquals(HttpFailureBucket.Other, bucketFor(404))
        assertEquals(HttpFailureBucket.Other, bucketFor(422))
    }

    @Test fun `unusual codes fall into Other`() {
        // Defensive: callers pass whatever `HttpURLConnection.responseCode`
        // returns, including 0 (transport failure under some JDKs) and
        // 3xx (redirects we don't follow). Both should bucket safely.
        assertEquals(HttpFailureBucket.Other, bucketFor(0))
        assertEquals(HttpFailureBucket.Other, bucketFor(301))
    }

    // ── providerErrorMessage ──────────────────────────────────────────

    @Test fun `extracts nested error_message shape`() {
        // OpenAI / Groq / Anthropic all wrap their human-readable error
        // in `{"error":{"message":"..."}}`. This is the dominant shape.
        val payload = """{"error":{"message":"API key is invalid"}}"""
        assertEquals("API key is invalid", payload.providerErrorMessage())
    }

    @Test fun `falls back to flat error string`() {
        // Some OpenAI-compat self-hosted servers (e.g. older
        // llama.cpp-server builds) emit the error as a flat string at
        // `.error` rather than nesting under `.message`.
        val payload = """{"error":"rate limit exceeded"}"""
        assertEquals("rate limit exceeded", payload.providerErrorMessage())
    }

    @Test fun `returns null for null payload`() {
        // Transport-level failures hand us a null body — callers fall
        // back to their own generic message.
        assertNull((null as String?).providerErrorMessage())
    }

    @Test fun `returns null for empty payload`() {
        // Server returned an HTTP error code with no body. Same fallback
        // behaviour as the null case.
        assertNull("".providerErrorMessage())
    }

    @Test fun `returns null for malformed JSON`() {
        // Self-hosted misconfigurations sometimes emit an HTML error
        // page or a free-text 502 from the load balancer.
        assertNull("<html>502 Bad Gateway</html>".providerErrorMessage())
        assertNull("just text".providerErrorMessage())
    }

    @Test fun `returns null when error_message is blank`() {
        // Some providers populate the field but leave it empty. Treat
        // it as "no message" so the caller's fallback wins.
        val payload = """{"error":{"message":""}}"""
        assertNull(payload.providerErrorMessage())
    }

    @Test fun `returns null when neither nested nor flat error is present`() {
        // Well-formed JSON but the shape is unfamiliar — e.g. an envelope
        // we don't recognise. Stay null so the caller doesn't surface
        // misleading text.
        val payload = """{"some_other_field":"not an error"}"""
        assertNull(payload.providerErrorMessage())
    }

    @Test fun `prefers nested over flat when both are present`() {
        // Defensive: if a provider somehow returns BOTH `error.message`
        // (a string) and `error` (also a string, which it'd have to via
        // a separate envelope), the nested message wins because we
        // check it first. Mostly a doc test — this shape is unlikely.
        val payload = """{"error":{"message":"detailed reason","code":"X"}}"""
        assertEquals("detailed reason", payload.providerErrorMessage())
    }

    @Test fun `user agent constant is stable`() {
        // Pin the user-agent so a change to the constant doesn't
        // silently re-key provider rate-limit buckets keyed by UA.
        assertEquals("Wristotle/companion", WRISTOTLE_USER_AGENT)
    }
}
