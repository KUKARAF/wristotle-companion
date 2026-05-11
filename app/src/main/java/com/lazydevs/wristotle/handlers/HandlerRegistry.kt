package com.lazydevs.wristotle.handlers

/**
 * Dispatches a voice query to the first [ActionHandler] whose [ActionHandler.canHandle]
 * returns true, then returns the result string for the watch display.
 *
 * Handlers are checked in registration order, so more specific matchers
 * (e.g. "send message to") should be registered before broader ones (e.g. "message").
 */
class HandlerRegistry(private val handlers: List<ActionHandler>) {

    /**
     * Finds the first matching handler and calls its [ActionHandler.handle].
     * Returns "Unknown command" if no handler claims the query.
     */
    suspend fun dispatch(query: String): String =
        handlers.firstOrNull { it.canHandle(query) }?.handle(query)
            ?: "Unknown command"
}
