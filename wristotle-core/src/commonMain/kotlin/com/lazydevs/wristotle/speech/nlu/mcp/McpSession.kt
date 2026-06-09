// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.mcp

import com.lazydevs.wristotle.speech.nlu.logging.Logger
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

/**
 * Aggregates multiple [McpIntegration]s into a single front for the
 * rest of the app. Routes [callTool] back to the right integration by
 * matching `integrationName`.
 *
 * Lifecycle: [openSession] opens every integration in parallel;
 * [closeSession] tears them down. Failures during open/close are
 * logged per-integration so one bad server doesn't take down the
 * others.
 *
 * R5 batch 3 — lifted from :app/mcp. `@Volatile` switched to
 * `kotlin.concurrent.Volatile`; `android.util.Log` swapped for the
 * Logger seam.
 */
class McpSession(
    val integrations: List<McpIntegration>,
    private val log: Logger,
) {

    private val openMutex = Mutex()
    @Volatile private var isOpen = false

    suspend fun openSession() {
        openMutex.withLock {
            if (isOpen) return
            forEachIntegrationParallel("connect") { it.connect() }
            isOpen = true
        }
    }

    suspend fun closeSession() {
        openMutex.withLock {
            if (!isOpen) return
            forEachIntegrationParallel("close") { it.close() }
            isOpen = false
        }
    }

    /**
     * Tool list across every integration. Each tool carries its
     * `integrationName` so callers don't lose routing info. Per-server
     * failures are swallowed and logged.
     */
    suspend fun listTools(): List<McpTool> = coroutineScope {
        integrations.map { integration ->
            async {
                try {
                    integration.listTools()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    log.w(TAG, "listTools failed for ${integration.name}", t)
                    emptyList()
                }
            }
        }.awaitAll().flatten()
    }

    suspend fun resetCache() {
        forEachIntegrationParallel("resetCache") { it.resetCache() }
    }

    /** Fan out [block] across every integration concurrently; per-integration
     *  failures are logged with [opLabel] and swallowed so one bad server
     *  doesn't take down the others. Rethrows CancellationException to keep
     *  coroutine scope teardown honest (esp. on K/N). */
    private suspend fun forEachIntegrationParallel(
        opLabel: String,
        block: suspend (McpIntegration) -> Unit,
    ) = coroutineScope {
        integrations.map { integration ->
            async {
                try {
                    block(integration)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    log.w(TAG, "$opLabel failed for ${integration.name}", t)
                }
            }
        }.awaitAll()
    }

    suspend fun callTool(
        integrationName: String,
        toolName: String,
        json: Map<String, JsonElement>,
    ): ToolCallResult {
        val target = integrations.firstOrNull { it.name == integrationName }
            ?: return ToolCallResult.Failure("no integration named '$integrationName'")
        return target.callTool(toolName, json)
    }

    companion object {
        private const val TAG = "McpSession"
    }
}
