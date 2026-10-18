package com.ceasle.intellicursor.client

import com.ceasle.intellicursor.protocol.*

interface AcpClientListener {
    fun onThoughtChunk(chunk: ThoughtChunkUpdate) {}
    fun onMessageChunk(chunk: MessageChunkUpdate) {}
    fun onUserMessageChunk(chunk: UserMessageChunkUpdate) {}
    fun onToolCall(event: ToolCallUpdateEvent) {}
    fun onToolCallUpdate(event: ToolCallUpdateEvent) {}
    fun onPlanUpdate(plan: PlanUpdate) {}
    fun onSessionInfoUpdate(update: SessionInfoUpdate) {}
    fun onAvailableCommandsUpdate(update: AvailableCommandsUpdate) {}
    fun onCurrentModeUpdate(update: CurrentModeUpdate) {}
    fun onUsageUpdate(update: UsageUpdate) {}
    fun onPermissionRequest(requestId: Long, params: RequestPermissionParams) {}
    fun onAskQuestionRequest(requestId: Long, params: CursorAskQuestionParams) {}
    fun onCreatePlanRequest(requestId: Long, params: CursorCreatePlanParams) {}
}
