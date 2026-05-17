package com.lazydevs.wristotle.handlers

/**
 * Result of dispatching a query through the [HandlerRegistry].
 *
 * @property response  The text that will be sent back to the watch chat UI.
 * @property handler   Short tag identifying which handler produced the response
 *                     ("call", "sms", "unknown", "error"). Logged into the
 *                     conversation history so future tuning can see which paths
 *                     are taken most often / fail most.
 * @property success   Heuristic — false if the response is a known failure
 *                     string ("Contact not found", "Couldn't…", "Error:",
 *                     "Unknown command"), true otherwise. Used to badge entries
 *                     in the Conversation screen.
 */
data class HandlerResult(
    val response: String,
    val handler: String,
    val success: Boolean,
)

/**
 * Dispatches a voice query to the first [ActionHandler] whose [ActionHandler.canHandle]
 * returns true, then returns the result for the watch display + persistence.
 *
 * Handlers are checked in registration order, so more specific matchers
 * (e.g. "send message to") should be registered before broader ones (e.g. "message").
 */
class HandlerRegistry(private val handlers: List<ActionHandler>) {

    suspend fun dispatch(query: String): HandlerResult {
        val handler = handlers.firstOrNull { it.canHandle(query) }
        return try {
            if (handler != null) {
                val response = handler.handle(query)
                HandlerResult(response, handler.tag, success = isSuccessResponse(response))
            } else {
                HandlerResult("Unknown command: $query", handler = "unknown", success = false)
            }
        } catch (e: Exception) {
            HandlerResult(
                response = "Error: ${e.localizedMessage ?: "Action failed"}",
                handler = "error",
                success = false,
            )
        }
    }

    companion object {
        // Conservative prefix list — anything we send back that starts with one
        // of these strings is treated as a failure for history badging. Keep in
        // sync with handler response strings; over-classifying as failure is
        // safer than under-classifying.
        private val FAILURE_PREFIXES = listOf(
            "Contact not found",
            "Couldn't",
            "Error:",
            "Unknown command",
            "Failed",
            "No phone target",
            "Query too long",
            "Contacts permission",
            "SMS permission",
        )

        fun isSuccessResponse(response: String): Boolean =
            FAILURE_PREFIXES.none { response.startsWith(it) }
    }
}
