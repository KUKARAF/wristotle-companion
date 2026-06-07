// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.mcp.HttpMcpIntegration
import com.lazydevs.wristotle.mcp.McpServerEntity
import com.lazydevs.wristotle.mcp.McpServerRepository
import com.lazydevs.wristotle.mcp.McpTool
import com.lazydevs.wristotle.mcp.ToolCallResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

sealed interface ServerProbeState {
    data object Idle : ServerProbeState
    data object Loading : ServerProbeState
    data class Tools(val tools: List<McpTool>) : ServerProbeState
    data class Failed(val message: String) : ServerProbeState
}

sealed interface ToolCallState {
    data object Idle : ToolCallState
    data class Calling(val toolName: String) : ToolCallState
    data class Done(val result: ToolCallResult) : ToolCallState
}

class McpServersViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: McpServerRepository =
        (app as WristotleApplication).mcpServerRepository

    val servers: StateFlow<List<McpServerEntity>> = repo.observeServers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _probeStates = MutableStateFlow<Map<Long, ServerProbeState>>(emptyMap())
    val probeStates: StateFlow<Map<Long, ServerProbeState>> = _probeStates

    private val _callState = MutableStateFlow<ToolCallState>(ToolCallState.Idle)
    val callState: StateFlow<ToolCallState> = _callState

    private val integrationsMutex = Mutex()
    private val integrations = mutableMapOf<Long, HttpMcpIntegration>()

    fun addServer(name: String, url: String, streamable: Boolean, authHeader: String?) {
        if (name.isBlank() || url.isBlank()) return
        viewModelScope.launch {
            repo.add(
                McpServerEntity(
                    name = name.trim(),
                    url = url.trim(),
                    streamable = streamable,
                    authHeader = authHeader?.trim()?.takeIf { it.isNotEmpty() },
                ),
            )
        }
    }

    fun deleteServer(id: Long) {
        viewModelScope.launch {
            repo.delete(id)
            _probeStates.value = _probeStates.value - id
            evict(id)
        }
    }

    fun setEnabled(server: McpServerEntity, enabled: Boolean) {
        if (server.enabled == enabled) return
        viewModelScope.launch {
            repo.update(server.copy(enabled = enabled))
            if (!enabled) {
                // Drop any open connection + stale probe state on disable;
                // on re-enable the next refresh / agent query will open a
                // fresh integration.
                _probeStates.value = _probeStates.value - server.id
                evict(server.id)
            }
        }
    }

    fun editServer(
        server: McpServerEntity,
        name: String,
        url: String,
        streamable: Boolean,
        authHeader: String?,
    ) {
        if (name.isBlank() || url.isBlank()) return
        viewModelScope.launch {
            repo.update(
                server.copy(
                    name = name.trim(),
                    url = url.trim(),
                    streamable = streamable,
                    authHeader = authHeader?.trim()?.takeIf { it.isNotEmpty() },
                ),
            )
            // Cached integration was opened against the OLD URL/auth;
            // evict so the next refresh re-opens with the new config and
            // the stale probe state goes with it.
            _probeStates.value = _probeStates.value - server.id
            evict(server.id)
        }
    }

    fun refreshTools(server: McpServerEntity) {
        viewModelScope.launch {
            _probeStates.value = _probeStates.value + (server.id to ServerProbeState.Loading)
            val nextState: ServerProbeState = try {
                val integration = obtain(server)
                integration.resetCache()
                ServerProbeState.Tools(integration.listTools())
            } catch (t: Throwable) {
                ServerProbeState.Failed(t.message ?: t::class.java.simpleName)
            }
            _probeStates.value = _probeStates.value + (server.id to nextState)
        }
    }

    fun callTool(server: McpServerEntity, toolName: String, jsonArgs: String) {
        viewModelScope.launch {
            _callState.value = ToolCallState.Calling(toolName)
            val parsedArgs: Map<String, JsonElement> = try {
                val raw = jsonArgs.trim().ifEmpty { "{}" }
                (Json.parseToJsonElement(raw) as JsonObject).toMap()
            } catch (t: Throwable) {
                _callState.value = ToolCallState.Done(
                    ToolCallResult.Failure("Invalid JSON: ${t.message ?: "parse error"}"),
                )
                return@launch
            }
            val result = try {
                obtain(server).callTool(toolName, parsedArgs)
            } catch (t: Throwable) {
                ToolCallResult.Failure(t.message ?: t::class.java.simpleName)
            }
            _callState.value = ToolCallState.Done(result)
        }
    }

    fun dismissCallResult() {
        _callState.value = ToolCallState.Idle
    }

    private suspend fun obtain(server: McpServerEntity): HttpMcpIntegration =
        integrationsMutex.withLock {
            integrations[server.id]?.let { return@withLock it }
            val integration = HttpMcpIntegration(
                name = server.name,
                url = server.url,
                streamable = server.streamable,
                authHeader = server.authHeader,
            )
            try {
                integration.connect()
            } catch (t: Throwable) {
                runCatching { integration.close() }
                throw t
            }
            integrations[server.id] = integration
            integration
        }

    private suspend fun evict(id: Long) {
        val removed = integrationsMutex.withLock { integrations.remove(id) }
        removed?.close()
    }

    override fun onCleared() {
        super.onCleared()
        // viewModelScope is already cancelled; close() is suspend (Mutex)
        // but never blocks on I/O long enough to matter here. runBlocking is
        // the only way to await the Ktor engine teardown from a sync hook.
        val snapshot = integrations.values.toList()
        integrations.clear()
        runBlocking { snapshot.forEach { runCatching { it.close() } } }
    }
}