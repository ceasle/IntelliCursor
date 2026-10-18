package com.ceasle.intellicursor.protocol

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JsonRpcCodecTest {
    @Test
    fun testDeserializeThoughtChunkUpdate() {
        val json = """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"sess_1","update":{"sessionUpdate":"agent_thought_chunk","content":{"type":"text","text":"Thinking deeply..."}}}}"""
        val notif = JsonRpcCodec.decodeNotification(json)
        assertEquals("session/update", notif.method)
        val update = notif.params as SessionUpdateNotification
        assertTrue(update.update is ThoughtChunkUpdate)
        assertEquals("Thinking deeply...", (update.update as ThoughtChunkUpdate).content.text)
    }

    @Test
    fun testDeserializeToolCallUpdate() {
        val json = """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"sess_1","update":{"sessionUpdate":"tool_call_update","toolCallId":"call_123","status":"completed","rawOutput":{"exitCode":0,"stdout":"success\n"}}}}"""
        val notif = JsonRpcCodec.decodeNotification(json)
        val update = notif.params as SessionUpdateNotification
        val toolUpdate = update.update as ToolCallUpdateEvent
        assertEquals("call_123", toolUpdate.toolCallId)
        assertEquals("completed", toolUpdate.status)
        assertEquals(0, (toolUpdate.rawOutput?.get("exitCode") as Number).toInt())
    }

    @Test
    fun testSerializeSessionNew() {
        val params = SessionNewParams(cwd = "/path/to/project", mcpServers = emptyList())
        val req = JsonRpcRequest(id = 1, method = "session/new", params = params)
        val json = JsonRpcCodec.encode(req)
        assertTrue(json.contains(""""mcpServers":[]"""))
        assertTrue(json.contains(""""cwd":"/path/to/project""""))
    }

    @Test
    fun testDeserializePlanUpdate() {
        val json = """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"sess_1","update":{"sessionUpdate":"plan","entries":[{"content":"Step 1","priority":"high","status":"in_progress"}]}}}"""
        val notif = JsonRpcCodec.decodeNotification(json)
        val update = notif.params as SessionUpdateNotification
        assertTrue(update.update is PlanUpdate)
        val plan = update.update as PlanUpdate
        assertEquals(1, plan.entries.size)
        assertEquals("Step 1", plan.entries[0].content)
        assertEquals("high", plan.entries[0].priority)
        assertEquals("in_progress", plan.entries[0].status)
    }

    @Test
    fun testDeserializeCursorExtensions() {
        val questionParams = CursorAskQuestionParams(
            toolCallId = "call_abc",
            title = "Confirm action",
            questions = listOf(
                CursorQuestion(
                    id = "q1",
                    prompt = "Proceed with edit?",
                    options = listOf(CursorQuestionOption("yes", "Yes"), CursorQuestionOption("no", "No")),
                    allowMultiple = false
                )
            )
        )
        val json = JsonRpcCodec.encode(questionParams)
        val node = JsonRpcCodec.readTree(json)
        val deserialized: CursorAskQuestionParams = JsonRpcCodec.fromNode(node)
        assertEquals("call_abc", deserialized.toolCallId)
        assertEquals(1, deserialized.questions.size)
        assertEquals("Proceed with edit?", deserialized.questions[0].prompt)
    }
}
