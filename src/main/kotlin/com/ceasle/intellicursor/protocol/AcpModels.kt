package com.ceasle.intellicursor.protocol

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

data class InitializeParams(
    val protocolVersion: Int = 1,
    val clientCapabilities: ClientCapabilities,
    val clientInfo: ClientInfo
)

data class ClientCapabilities(
    val fs: FsCapabilities = FsCapabilities(readTextFile = false, writeTextFile = false),
    val terminal: Boolean = false,
    val session: SessionCapabilities? = SessionCapabilities(notices = emptyMap())
)

data class FsCapabilities(val readTextFile: Boolean, val writeTextFile: Boolean)
data class SessionCapabilities(val notices: Map<String, Any>?)
data class ClientInfo(val name: String, val title: String, val version: String)

data class InitializeResult(
    val protocolVersion: Int,
    val agentCapabilities: Map<String, Any>?,
    val authMethods: List<AuthMethodInfo>?
)

data class AuthMethodInfo(val id: String, val name: String, val description: String?)

data class AuthenticateParams(val methodId: String)

data class SessionNewParams(val cwd: String, val mcpServers: List<Any> = emptyList())
data class SessionLoadParams(val sessionId: String, val cwd: String, val mcpServers: List<Any> = emptyList())

data class SessionNewResult(
    val sessionId: String,
    val modes: ModesState?,
    val models: ModelsState?,
    val configOptions: List<ConfigOption>?
)

/** session/load answers like session/new, minus the id. */
data class SessionLoadResult(val modes: ModesState? = null, val models: ModelsState? = null)

/** One past chat from session/list. */
data class SessionSummary(val sessionId: String, val cwd: String? = null, val title: String? = null, val updatedAt: String? = null)
data class SessionListResult(val sessions: List<SessionSummary> = emptyList())

data class ModesState(val currentModeId: String, val availableModes: List<ModeOption>)
data class ModeOption(val id: String, val name: String, val description: String?)
data class ModelsState(val currentModelId: String, val availableModels: List<ModelOption>)
data class ModelOption(val modelId: String, val name: String)

data class ConfigOption(
    val id: String,
    val name: String,
    val description: String?,
    val category: String?,
    val type: String,
    val currentValue: String,
    val options: List<ConfigOptionValue>
)

data class ConfigOptionValue(val value: String, val name: String, val description: String?)

data class SetModeParams(val sessionId: String, val modeId: String)
data class SetConfigOptionParams(val sessionId: String, val configId: String, val value: String)

data class PromptParams(val sessionId: String, val prompt: List<ContentBlock>)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = TextContentBlock::class, name = "text"),
    JsonSubTypes.Type(value = ResourceContentBlock::class, name = "resource"),
    JsonSubTypes.Type(value = ResourceLinkContentBlock::class, name = "resource_link")
)
sealed interface ContentBlock

data class TextContentBlock(val text: String) : ContentBlock
data class ResourceContentBlock(val resource: ResourcePayload) : ContentBlock
/** A file the agent should read itself; every ACP agent accepts these. */
data class ResourceLinkContentBlock(val uri: String, val name: String) : ContentBlock
data class ResourcePayload(val uri: String, val mimeType: String, val text: String)

data class PromptResult(val stopReason: String)

data class CancelParams(val sessionId: String)

data class SessionUpdateNotification(val sessionId: String, val update: SessionUpdatePayload)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "sessionUpdate")
@JsonSubTypes(
    JsonSubTypes.Type(value = ThoughtChunkUpdate::class, name = "agent_thought_chunk"),
    JsonSubTypes.Type(value = MessageChunkUpdate::class, name = "agent_message_chunk"),
    JsonSubTypes.Type(value = UserMessageChunkUpdate::class, name = "user_message_chunk"),
    JsonSubTypes.Type(value = ToolCallUpdateEvent::class, name = "tool_call"),
    JsonSubTypes.Type(value = ToolCallUpdateEvent::class, name = "tool_call_update"),
    JsonSubTypes.Type(value = PlanUpdate::class, name = "plan"),
    JsonSubTypes.Type(value = SessionInfoUpdate::class, name = "session_info_update"),
    JsonSubTypes.Type(value = AvailableCommandsUpdate::class, name = "available_commands_update"),
    JsonSubTypes.Type(value = CurrentModeUpdate::class, name = "current_mode_update"),
    JsonSubTypes.Type(value = UsageUpdate::class, name = "usage_update")
)
sealed interface SessionUpdatePayload

data class ThoughtChunkUpdate(val content: TextContentBlock) : SessionUpdatePayload
data class MessageChunkUpdate(val messageId: String?, val content: TextContentBlock) : SessionUpdatePayload
data class UserMessageChunkUpdate(val content: TextContentBlock) : SessionUpdatePayload

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
data class ToolCallUpdateEvent(
    val toolCallId: String,
    val title: String? = null,
    val name: String? = null,
    val kind: String? = null,
    val status: String? = null,
    val rawInput: Map<String, Any?>? = null,
    val rawOutput: Map<String, Any?>? = null,
    val locations: List<ToolLocation>? = null,
    val content: List<Any>? = null
) : SessionUpdatePayload

data class ToolLocation(val path: String, val line: Int? = null)

data class PlanUpdate(val entries: List<PlanEntry>) : SessionUpdatePayload
data class PlanEntry(val content: String, val priority: String, val status: String)

data class SessionInfoUpdate(val title: String) : SessionUpdatePayload
data class AvailableCommandsUpdate(val availableCommands: List<SlashCommandInfo>) : SessionUpdatePayload
data class SlashCommandInfo(val name: String, val description: String)
data class CurrentModeUpdate(val currentModeId: String) : SessionUpdatePayload
data class UsageUpdate(val used: Long, val size: Long, val cost: CostInfo? = null) : SessionUpdatePayload
data class CostInfo(val amount: Double, val currency: String)

data class RequestPermissionParams(
    val sessionId: String,
    val toolCall: ToolCallUpdateEvent,
    val options: List<PermissionOption>
)

data class PermissionOption(val optionId: String, val name: String, val kind: String)

data class RequestPermissionResult(val outcome: PermissionOutcome)
data class PermissionOutcome(val outcome: String, val optionId: String? = null)
