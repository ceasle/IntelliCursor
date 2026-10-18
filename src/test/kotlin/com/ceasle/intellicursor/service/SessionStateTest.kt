package com.ceasle.intellicursor.service

import com.ceasle.intellicursor.protocol.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SessionStateTest {
    @Test
    fun testAppendThoughtChunks() {
        val turn = ActiveTurn(userPrompt = "Hello")
        turn.appendThought("Thinking step 1. ")
        turn.appendThought("Thinking step 2.")
        assertEquals("Thinking step 1. Thinking step 2.", turn.getThoughtText())
    }

    @Test
    fun testAppendMessageChunks() {
        val turn = ActiveTurn(userPrompt = "Hello")
        turn.appendMessage("Part 1 ")
        turn.appendMessage("Part 2")
        assertEquals("Part 1 Part 2", turn.getMessageText())
        assertFalse(turn.isThinking)
    }

    @Test
    fun testSessionStateInitialValues() {
        val session = SessionState("test-session-id", "/test/dir")
        assertEquals("test-session-id", session.sessionId)
        assertEquals("/test/dir", session.cwd)
        assertEquals("New Session", session.title)
        assertEquals("agent", session.currentMode)
        assertEquals("composer-2.5", session.currentModel)
        assertTrue(session.turns.isEmpty())
        assertNull(session.activeTurn)
    }

    @Test
    fun testToolCallStateLifecycle() {
        val turn = ActiveTurn(userPrompt = "Run command")
        val toolCall = ToolCallState(
            toolCallId = "tc-1",
            title = "Run ls",
            kind = "terminal",
            status = "pending",
            rawInput = mapOf("command" to "ls")
        )
        turn.toolCalls[toolCall.toolCallId] = toolCall

        assertEquals(1, turn.toolCalls.size)
        assertEquals("pending", turn.toolCalls["tc-1"]?.status)

        // Simulate update
        turn.toolCalls["tc-1"]?.status = "completed"
        turn.toolCalls["tc-1"]?.rawOutput = mapOf("exitCode" to 0)

        assertEquals("completed", turn.toolCalls["tc-1"]?.status)
        assertEquals(0, turn.toolCalls["tc-1"]?.rawOutput?.get("exitCode"))
    }

    @Test
    fun testToolCallsPreserveInsertionOrder() {
        val turn = ActiveTurn("Test order")
        for (i in 1..10) {
            val id = "tc-$i"
            turn.toolCalls[id] = ToolCallState(
                toolCallId = id,
                title = "Tool $i",
                kind = "tool",
                status = "completed"
            )
        }
        val ids = synchronized(turn.toolCalls) { turn.toolCalls.keys.toList() }
        assertEquals((1..10).map { "tc-$it" }, ids)
    }
}
