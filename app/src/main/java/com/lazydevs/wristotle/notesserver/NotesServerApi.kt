// SPDX-License-Identifier: AGPL-3.0-only

package com.lazydevs.wristotle.notesserver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Hard-coded deployment: rust_note at notes.osmosis.page, Authentik in front of it. */
object NotesServerConfig {
    const val BASE_URL = "https://notes.osmosis.page"

    /** Starts the server-side OIDC flow; `client=app` makes the callback mint a
     *  device token and redirect to [APP_REDIRECT_SCHEME]`://auth?token=…`. */
    const val LOGIN_URL = "$BASE_URL/auth/login?client=app"
    const val APP_REDIRECT_SCHEME = "dev.rustnote.app"

    /** Folder that notes created from the watch / companion land in. */
    const val WRISTOTLE_FOLDER = "wristotle"
}

@Serializable
data class RemoteNoteMeta(
    val id: String,
    val title: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("created_at") val createdAt: String = "",
    val version: String = "",
)

@Serializable
data class RemoteNote(val meta: RemoteNoteMeta, val content: String)

@Serializable
data class RemoteTodo(
    @SerialName("note_id") val noteId: String,
    val date: String? = null,
    val line: Int,
    val depth: Int = 0,
    val marker: String = " ",
    val done: Boolean = false,
    val text: String,
    @SerialName("text_clean") val textClean: String = "",
)

@Serializable
data class RemoteUser(
    val id: String,
    val email: String? = null,
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
private data class CreateNoteBody(
    @SerialName("id_or_title") val idOrTitle: String,
    val content: String,
)

@Serializable
private data class UpdateNoteBody(
    val content: String,
    @SerialName("expected_version") val expectedVersion: String? = null,
)

/** Non-2xx answer from the server. [status] 0 never happens — transport
 *  failures surface as plain [IOException]s so callers can tell them apart. */
class NotesServerHttpException(val status: Int, message: String) : IOException("HTTP $status: $message")

/**
 * Minimal blocking-IO client for the rust_note REST API, run on
 * [Dispatchers.IO]. Auth is the server's own opaque device token as a
 * bearer header (see NotesServerAuth).
 */
class NotesServerApi(
    private val tokenProvider: () -> String?,
    private val baseUrl: String = NotesServerConfig.BASE_URL,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun me(tokenOverride: String? = null): RemoteUser =
        json.decodeFromString(call("GET", "/auth/me", token = tokenOverride))

    suspend fun listNotes(): List<RemoteNoteMeta> =
        json.decodeFromString(call("GET", "/api/notes"))

    /** Returns null on 404. */
    suspend fun getNote(id: String): RemoteNote? = try {
        json.decodeFromString<RemoteNote>(call("GET", "/api/notes/${encodePath(id)}"))
    } catch (e: NotesServerHttpException) {
        if (e.status == 404) null else throw e
    }

    /** POST — the server slugifies [idOrTitle]; 409 when the id already exists. */
    suspend fun createNote(idOrTitle: String, content: String): RemoteNoteMeta =
        json.decodeFromString(
            call("POST", "/api/notes", json.encodeToString(CreateNoteBody.serializer(), CreateNoteBody(idOrTitle, content)))
        )

    /** Full-content replace; 409 when [expectedVersion] is stale. */
    suspend fun updateNote(id: String, content: String, expectedVersion: String?): RemoteNoteMeta =
        json.decodeFromString(
            call(
                "PUT", "/api/notes/${encodePath(id)}",
                json.encodeToString(UpdateNoteBody.serializer(), UpdateNoteBody(content, expectedVersion?.ifBlank { null })),
            )
        )

    /** Treats 404 as success (already gone). */
    suspend fun deleteNote(id: String) {
        try {
            call("DELETE", "/api/notes/${encodePath(id)}")
        } catch (e: NotesServerHttpException) {
            if (e.status != 404) throw e
        }
    }

    suspend fun todos(scope: String = "diary", includeDone: Boolean = true): List<RemoteTodo> =
        json.decodeFromString(call("GET", "/api/todos?scope=$scope&include_done=$includeDone"))

    suspend fun logout() {
        runCatching { call("POST", "/auth/logout") }
    }

    private suspend fun call(
        method: String,
        path: String,
        body: String? = null,
        token: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val bearer = token ?: tokenProvider() ?: throw NotesServerHttpException(401, "not logged in")
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Authorization", "Bearer $bearer")
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw NotesServerHttpException(status, errorMessage(text))
            // Unknown /api paths fall through to the SPA's HTML; never treat that as data.
            val type = conn.contentType.orEmpty()
            if (text.isNotEmpty() && !type.contains("json")) {
                throw NotesServerHttpException(status, "unexpected $type response")
            }
            text
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(text: String): String =
        runCatching { json.decodeFromString<ErrorBody>(text).message }.getOrNull()
            ?: text.take(200).ifBlank { "no body" }

    @Serializable
    private data class ErrorBody(val message: String)

    companion object {
        private const val TIMEOUT_MS = 15_000

        /** Percent-encode each `/` segment separately, keeping the slashes. */
        fun encodePath(id: String): String =
            id.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    }
}
