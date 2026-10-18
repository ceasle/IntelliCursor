package com.ceasle.intellicursor.service

import com.ceasle.intellicursor.protocol.*
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A file sent along with a prompt. [path] is absolute; [label] is what people see on the chip. */
data class PromptAttachment(val path: String, val label: String)

class ActiveTurn(
    userPrompt: String,
    val attachments: List<PromptAttachment> = emptyList(),
    /** What the bubble shows, when it differs from what is sent (e.g. "/summarize"). */
    displayPrompt: String? = null,
) {
    /** Grows while an old chat is replayed, since the CLI may send a prompt in pieces. */
    @Volatile var userPrompt: String = userPrompt
        internal set
    @Volatile var displayPrompt: String = displayPrompt ?: userPrompt
        internal set
    private val thoughtBuffer = StringBuilder()
    private val messageBuffer = StringBuilder()
    val toolCalls: MutableMap<String, ToolCallState> = Collections.synchronizedMap(LinkedHashMap<String, ToolCallState>())
    @Volatile var planEntries: List<PlanEntry> = emptyList()
    @Volatile var isThinking: Boolean = true
    @Volatile var isCompleted: Boolean = false
    @Volatile var stopReason: String? = null

    /** When the person pressed send. */
    val createdAt: Long = System.currentTimeMillis()
    /** When the prompt actually reached Cursor; 0 while it waits for the connection. */
    @Volatile var sentAt: Long = 0
    @Volatile var completedAt: Long = 0

    @Synchronized fun appendThought(text: String) {
        thoughtBuffer.append(text)
    }

    @Synchronized fun getThoughtText(): String = thoughtBuffer.toString()

    @Synchronized fun appendMessage(text: String) {
        isThinking = false
        messageBuffer.append(text)
    }

    @Synchronized fun getMessageText(): String = messageBuffer.toString()

    fun complete(reason: String?) {
        if (isCompleted) return
        isCompleted = true
        isThinking = false
        stopReason = reason
        completedAt = System.currentTimeMillis()
    }
}

data class ToolCallState(
    val toolCallId: String,
    var title: String,
    val kind: String,
    var status: String,
    var rawInput: Map<String, Any?>? = null,
    var rawOutput: Map<String, Any?>? = null,
    var locations: List<ToolLocation>? = null
)

class SessionState(var sessionId: String, val cwd: String) {
    @Volatile var title: String = "New Session"
    @Volatile var currentMode: String = "agent"
    @Volatile var currentModel: String = "composer-2.5"
    @Volatile var availableModes: List<ModeOption> = emptyList()
    @Volatile var availableModels: List<ModelOption> = emptyList()
    @Volatile var availableCommands: List<SlashCommandInfo> = emptyList()
    val turns = CopyOnWriteArrayList<ActiveTurn>()
    @Volatile var activeTurn: ActiveTurn? = null
}
