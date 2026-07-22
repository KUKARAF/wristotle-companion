// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.homeassistant

import com.lazydevs.wristotle.speech.nlu.http.HttpClient
import com.lazydevs.wristotle.speech.nlu.http.HttpRequest
import com.lazydevs.wristotle.speech.nlu.http.HttpResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [HomeAssistantClient] — URL construction, header/body shape, response
 * parsing off a real HA conversation reply, and the failure taxonomy. The
 * fixtures use HA's actual `/api/conversation/process` response shape so a
 * parser regression can't hide behind invented JSON.
 */
class HomeAssistantClientTest {

    /** Captures the last request and returns a canned response. */
    private class FakeHttp(
        private val status: Int,
        private val body: String,
        private val error: String? = null,
    ) : HttpClient {
        var lastRequest: HttpRequest? = null
        override suspend fun request(request: HttpRequest): HttpResponse {
            lastRequest = request
            return HttpResponse(status = status, body = body, error = error)
        }
    }

    // A genuine action_done reply (lights turned off).
    private val actionDoneBody = """
        {
          "response": {
            "response_type": "action_done",
            "speech": {"plain": {"speech": "Turned off the lights", "extra_data": null}},
            "language": "en",
            "data": {"targets": [], "success": [], "failed": []}
          },
          "conversation_id": null
        }
    """.trimIndent()

    @Test fun success_parsesSpeechFromRealShape() = runTest {
        val http = FakeHttp(200, actionDoneBody)
        val client = HomeAssistantClient(http, "http://ha.local:8123", "tok")
        val result = client.process("turn off the lights")
        assertEquals(HaResult.Success("Turned off the lights"), result)
    }

    @Test fun request_targetsConversationEndpointWithBearerAndJsonBody() = runTest {
        val http = FakeHttp(200, actionDoneBody)
        val client = HomeAssistantClient(http, "http://ha.local:8123", "secret-token", language = "en")
        client.process("turn on the lamp")
        val req = http.lastRequest!!
        assertEquals("POST", req.method)
        assertEquals("http://ha.local:8123/api/conversation/process", req.url)
        assertEquals("Bearer secret-token", req.headers["Authorization"])
        assertEquals("application/json", req.headers["Content-Type"])
        val sentBody = req.body!!.decodeToString()
        assertTrue(sentBody.contains("\"text\":\"turn on the lamp\""), "text field: $sentBody")
        assertTrue(sentBody.contains("\"language\":\"en\""), "language field: $sentBody")
    }

    @Test fun baseUrl_trailingSlashesAreTrimmed() = runTest {
        val http = FakeHttp(200, actionDoneBody)
        val client = HomeAssistantClient(http, "http://ha.local:8123///", "tok")
        client.process("status")
        assertEquals("http://ha.local:8123/api/conversation/process", http.lastRequest!!.url)
    }

    @Test fun blankBaseUrl_isNotConfigured_andMakesNoRequest() = runTest {
        val http = FakeHttp(200, actionDoneBody)
        val client = HomeAssistantClient(http, "   ", "tok")
        assertEquals(HaResult.NotConfigured, client.process("x"))
        assertEquals(null, http.lastRequest, "must not hit the network when unconfigured")
    }

    @Test fun blankToken_isNotConfigured() = runTest {
        val http = FakeHttp(200, actionDoneBody)
        val client = HomeAssistantClient(http, "http://ha.local:8123", "")
        assertEquals(HaResult.NotConfigured, client.process("x"))
    }

    @Test fun unauthorized_mapsToBadAuth() = runTest {
        val client = HomeAssistantClient(FakeHttp(401, "401: Unauthorized"), "http://ha.local:8123", "bad")
        assertIs<HaResult.Failure.BadAuth>(client.process("x"))
    }

    @Test fun notFound_mapsToOtherWithGuidance() = runTest {
        val result = HomeAssistantClient(FakeHttp(404, "Not Found"), "http://ha.local:8123", "tok").process("x")
        val other = assertIs<HaResult.Failure.Other>(result)
        assertTrue(other.message.contains("404"))
    }

    @Test fun transportFailure_mapsToNetwork() = runTest {
        val result = HomeAssistantClient(FakeHttp(0, "", error = "connect refused"), "http://ha.local:8123", "tok")
            .process("x")
        val net = assertIs<HaResult.Failure.Network>(result)
        assertEquals("connect refused", net.message)
    }

    @Test fun malformedJson_mapsToNetwork() = runTest {
        val result = HomeAssistantClient(FakeHttp(200, "not json {"), "http://ha.local:8123", "tok").process("x")
        assertIs<HaResult.Failure.Network>(result)
    }

    @Test fun missingSpeech_mapsToOther() = runTest {
        val body = """{"response":{"response_type":"error"},"conversation_id":null}"""
        val result = HomeAssistantClient(FakeHttp(200, body), "http://ha.local:8123", "tok").process("x")
        assertIs<HaResult.Failure.Other>(result)
    }

    @Test fun tokenNeverAppearsInAnyFailureMessage() = runTest {
        val token = "super-secret-llat"
        for (status in listOf(0, 401, 403, 404, 500)) {
            val result = HomeAssistantClient(FakeHttp(status, "body mentioning nothing"), "http://ha.local:8123", token)
                .process("x")
            val msg = (result as? HaResult.Failure)?.message ?: ""
            assertFalse(msg.contains(token), "token leaked in status=$status message: $msg")
        }
    }
}
