package com.ceasle.intellicursor.client

import com.ceasle.intellicursor.protocol.*
import com.ceasle.intellicursor.transport.StdioJsonRpcTransport
import com.fasterxml.jackson.databind.JsonNode
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

class AcpClient(
    val transport: StdioJsonRpcTransport,
    var listener: AcpClientListener? = null
) : Closeable {
    private val nextId = AtomicLong(1)

    /** Session whose streamed updates reach the listener; updates for prefetched sessions are dropped. */
    @Volatile var activeSessionId: String? = null

    fun initialize(): CompletableFuture<InitializeResult> {
        val params = InitializeParams(
            protocolVersion = 1,
            clientCapabilities = ClientCapabilities(
                fs = FsCapabilities(readTextFile = false, writeTextFile = false),
                terminal = false,
                session = SessionCapabilities(notices = emptyMap())
            ),
            clientInfo = ClientInfo(name = "intellij-cursor-agent", title = "IntelliJ Cursor Agent", version = "1.0.0")
        )
        return transport.sendRequest(JsonRpcRequest(id = nextId.getAndIncrement(), method = "initialize", params = params))
            .thenApply { JsonRpcCodec.fromNode<InitializeResult>(it) }
    }

    fun authenticate(methodId: String = "cursor_login"): CompletableFuture<JsonNode> {
        val params = AuthenticateParams(methodId = methodId)
        return transport.sendRequest(JsonRpcRequest(id = nextId.getAndIncrement(), method = "authenticate", params = params))
    }

    fun newSession(cwd: String): CompletableFuture<SessionNewResult> {
        val params = SessionNewParams(cwd = cwd, mcpServers = emptyList())
        return transport.sendRequest(JsonRpcRequest(id = nextId.getAndIncrement(), method = "session/new", params = params))
            .thenApply { JsonRpcCodec.fromNode<SessionNewResult>(it) }
    }

    fun loadSession(sessionId: String, cwd: String): CompletableFuture<SessionLoadResult> {
        val params = SessionLoadParams(sessionId = sessionId, cwd = cwd, mcpServers = emptyList())
        return transport.sendRequest(JsonRpcRequest(id = nextId.getAndIncrement(), method = "session/load", params = params))
            .thenApply { if (it == null || it.isNull) SessionLoadResult() else JsonRpcCodec.fromNode<SessionLoadResult>(it) }
    }

    fun sendPrompt(sessionId: String, text: String): CompletableFuture<PromptResult> =
        sendPrompt(sessionId, listOf(TextContentBlock(text)))

    fun sendPrompt(sessionId: String, blocks: List<ContentBlock>): CompletableFuture<PromptResult> {
        val params = PromptParams(sessionId = sessionId, prompt = blocks)
        return transport.sendRequest(JsonRpcRequest(id = nextId.getAndIncrement(), method = "session/prompt", params = params))
            .thenApply { JsonRpcCodec.fromNode<PromptResult>(it) }
    }

    fun cancelPrompt(sessionId: String) {
        val params = CancelParams(sessionId = sessionId)
        transport.sendNotification(JsonRpcNotification(method = "session/cancel", params = params))
    }

    fun setMode(sessionId: String, modeId: String): CompletableFuture<JsonNode> {
        val params = SetModeParams(sessionId = sessionId, modeId = modeId)
        return transport.sendRequest(JsonRpcRequest(id = nextId.getAndIncrement(), method = "session/set_mode", params = params))
    }

    fun setModel(sessionId: String, modelId: String): CompletableFuture<JsonNode> {
        val params = mapOf("sessionId" to sessionId, "modelId" to modelId)
        return transport.sendRequest(JsonRpcRequest(id = nextId.getAndIncrement(), method = "session/set_model", params = params))
    }

    fun setConfigOption(sessionId: String, configId: String, value: String): CompletableFuture<JsonNode> {
        val params = SetConfigOptionParams(sessionId = sessionId, configId = configId, value = value)
        return transport.sendRequest(JsonRpcRequest(id = nextId.getAndIncrement(), method = "session/set_config_option", params = params))
    }

    fun listSessions(cwd: String? = null): CompletableFuture<List<SessionSummary>> {
        val params = if (cwd != null) mapOf("cwd" to cwd) else emptyMap<String, Any>()
        return transport.sendRequest(JsonRpcRequest(id = nextId.getAndIncrement(), method = "session/list", params = params))
            .thenApply { JsonRpcCodec.fromNode<SessionListResult>(it).sessions }
    }

    fun respondPermission(requestId: Long, optionId: String?, cancelled: Boolean) {
        val outcome = if (cancelled || optionId == null) {
            PermissionOutcome(outcome = "cancelled")
        } else {
            PermissionOutcome(outcome = "selected", optionId = optionId)
        }
        val response = JsonRpcResponse(id = requestId, result = RequestPermissionResult(outcome = outcome))
        transport.sendResponse(response)
    }

    fun respondAskQuestion(requestId: Long, answers: Map<String, List<String>>, cancelled: Boolean = false) {
        val result = if (cancelled) mapOf("cancelled" to true) else mapOf("answers" to answers)
        transport.sendResponse(JsonRpcResponse(id = requestId, result = JsonRpcCodec.mapper.valueToTree(result)))
    }

    fun respondCreatePlan(requestId: Long, approved: Boolean) {
        transport.sendResponse(JsonRpcResponse(id = requestId, result = JsonRpcCodec.mapper.valueToTree(mapOf("approved" to approved))))
    }

    fun dispatchNotification(notification: JsonRpcNotification) {
        if (notification.method == "session/update" && notification.params is SessionUpdateNotification) {
            val update = notification.params.update
            val active = activeSessionId
            if (active != null && notification.params.sessionId != active && update !is AvailableCommandsUpdate) return
            when (update) {
                is ThoughtChunkUpdate -> listener?.onThoughtChunk(update)
                is MessageChunkUpdate -> listener?.onMessageChunk(update)
                is UserMessageChunkUpdate -> listener?.onUserMessageChunk(update)
                is ToolCallUpdateEvent -> {
                    if (update.status == "pending" && update.rawOutput == null) {
                        listener?.onToolCall(update)
                    } else {
                        listener?.onToolCallUpdate(update)
                    }
                }
                is PlanUpdate -> listener?.onPlanUpdate(update)
                is SessionInfoUpdate -> listener?.onSessionInfoUpdate(update)
                is AvailableCommandsUpdate -> listener?.onAvailableCommandsUpdate(update)
                is CurrentModeUpdate -> listener?.onCurrentModeUpdate(update)
                is UsageUpdate -> listener?.onUsageUpdate(update)
            }
        }
    }

    fun dispatchAgentRequest(id: Long, method: String, paramsNode: JsonNode?) {
        if (paramsNode == null) return
        when (method) {
            "session/request_permission" -> {
                val params = JsonRpcCodec.fromNode<RequestPermissionParams>(paramsNode)
                listener?.onPermissionRequest(id, params)
            }
            "cursor/ask_question" -> {
                val params = JsonRpcCodec.fromNode<CursorAskQuestionParams>(paramsNode)
                listener?.onAskQuestionRequest(id, params)
            }
            "cursor/create_plan" -> {
                val params = JsonRpcCodec.fromNode<CursorCreatePlanParams>(paramsNode)
                listener?.onCreatePlanRequest(id, params)
            }
        }
    }

    override fun close() {
        transport.close()
    }
}
