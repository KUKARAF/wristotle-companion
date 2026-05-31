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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Per-server probe state shown next to each row in the Settings card.
 * Refresh button drops the cache + refetches; tap a tool row to invoke
 * it with JSON args from the dialog (phase A debugging surface — the
 * agent loop in phase B builds args automatically).
 */
sealed interface ServerProbeState {
    data object Idle : ServerProbeState
    data object Loading : ServerProbeState
    data class Tools(val tools: List<McpTool>) : ServerProbeState
    data class Failed(val message: String) : ServerProbeState
}

/** Outcome of a debug tool invocation, surfaced via AlertDialog. */
sealed interface ToolCallState {
    data object Idle : ToolCallState
    data object Calling : ToolCallState
    data class Done(val result: ToolCallResult) : ToolCallState
}

/**
 * Settings → MCP card state. Holds the persisted server list (observed
 * via Room flow) plus two transient maps keyed by server name:
 *  - [probeStates] — last "list tools" result per server
 *  - [callState]   — single in-flight / completed tool call
 *
 * Each probe spins up a one-shot [HttpMcpIntegration]. We do NOT keep a
 * long-lived connection per server in phase A — every refresh / call
 * opens and closes its own client. The cost is negligible at human-tap
 * frequency and avoids leaking sockets when the user navigates away.
 * Phase B's agent loop will keep one open session per session-turn.
 */
class McpServersViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: McpServerRepository =
        (app as WristotleApplication).mcpServerRepository

    val servers: StateFlow<List<McpServerEntity>> = repo.observeServers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _probeStates = MutableStateFlow<Map<String, ServerProbeState>>(emptyMap())
    val probeStates: StateFlow<Map<String, ServerProbeState>> = _probeStates

    private val _callState = MutableStateFlow<ToolCallState>(ToolCallState.Idle)
    val callState: StateFlow<ToolCallState> = _callState

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
        viewModelScope.launch { repo.delete(id) }
    }

    /** Open a one-shot client, list tools, close. */
    fun refreshTools(server: McpServerEntity) {
        viewModelScope.launch {
            _probeStates.value = _probeStates.value + (server.name to ServerProbeState.Loading)
            val integration = HttpMcpIntegration(
                name = server.name,
                url = server.url,
                streamable = server.streamable,
                authHeader = server.authHeader,
            )
            val nextState: ServerProbeState = try {
                integration.connect()
                ServerProbeState.Tools(integration.listTools())
            } catch (t: Throwable) {
                ServerProbeState.Failed(t.message ?: t::class.java.simpleName)
            } finally {
                runCatching { integration.close() }
            }
            _probeStates.value = _probeStates.value + (server.name to nextState)
        }
    }

    /**
     * Call [toolName] on [server] with [jsonArgs] (a JSON object string,
     * e.g. `{"city":"Tokyo"}`). Invalid JSON surfaces as a Failure
     * result via [callState]. Empty / blank input is sent as `{}`.
     */
    fun callTool(server: McpServerEntity, toolName: String, jsonArgs: String) {
        viewModelScope.launch {
            _callState.value = ToolCallState.Calling
            val parsedArgs: Map<String, JsonElement> = try {
                val raw = jsonArgs.trim().ifEmpty { "{}" }
                (Json.parseToJsonElement(raw) as JsonObject).toMap()
            } catch (t: Throwable) {
                _callState.value = ToolCallState.Done(
                    ToolCallResult.Failure("Invalid JSON: ${t.message ?: "parse error"}"),
                )
                return@launch
            }
            val integration = HttpMcpIntegration(
                name = server.name,
                url = server.url,
                streamable = server.streamable,
                authHeader = server.authHeader,
            )
            val result = try {
                integration.connect()
                integration.callTool(toolName, parsedArgs)
            } catch (t: Throwable) {
                ToolCallResult.Failure(t.message ?: t::class.java.simpleName)
            } finally {
                runCatching { integration.close() }
            }
            _callState.value = ToolCallState.Done(result)
        }
    }

    fun dismissCallResult() {
        _callState.value = ToolCallState.Idle
    }
}
