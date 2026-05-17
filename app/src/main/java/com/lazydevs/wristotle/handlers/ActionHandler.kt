package com.lazydevs.wristotle.handlers

/**
 * A single capability the companion app can perform in response to a watch query.
 *
 * To add a new feature:
 *  1. Create a class implementing ActionHandler.
 *  2. Register it in WatchMessageService's handler list.
 *  Nothing else needs to change.
 */
interface ActionHandler {
    /** Short tag identifying this handler in conversation history ("call", "sms", etc.). */
    val tag: String

    /** Returns true if this handler should process the given query. */
    fun canHandle(query: String): Boolean

    /** Executes the action and returns a short result string for the watch display. */
    suspend fun handle(query: String): String
}
