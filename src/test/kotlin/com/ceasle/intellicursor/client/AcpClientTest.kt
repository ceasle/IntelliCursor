package com.ceasle.intellicursor.client

import com.ceasle.intellicursor.protocol.*
import com.ceasle.intellicursor.transport.StdioJsonRpcTransport
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AcpClientTest {

    private class MockAcpServer : OutputStream(), Closeable {
        val inPipe = PipedInputStream()
        private val inWriter = PipedOutputStream(inPipe)
        private val buffer = ByteArrayOutputStream()
        private val responses = ConcurrentHashMap<Long, String>()

        fun queueResponse(id: Long, responseJson: String) {
            responses[id] = responseJson
        }

        override fun write(b: Int) {
            if (b == '\n'.code) {
                val line = buffer.toString(StandardCharsets.UTF_8)
                buffer.reset()
                if (line.isNotBlank()) {
                    val node = JsonRpcCodec.readTree(line)
                    val id = node.get("id")?.asLong()
                    if (id != null) {
                        val resp = responses[id]
                        if (resp != null) {
                            inWriter.write(resp.toByteArray(StandardCharsets.UTF_8))
                            inWriter.write("\n".toByteArray(StandardCharsets.UTF_8))
                            inWriter.flush()
                        }
                    }
                }
            } else {
                buffer.write(b)
            }
        }

        override fun close() {
            inWriter.close()
            inPipe.close()
        }
    }

    @Test
    fun testInitializeAndAuthFlow() {
        val server = MockAcpServer()
        server.queueResponse(1, """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"agentCapabilities":{},"authMethods":[]}}""")
        server.queueResponse(2, """{"jsonrpc":"2.0","id":2,"result":{}}""")
        server.queueResponse(3, """{"jsonrpc":"2.0","id":3,"result":{"sessionId":"sess_xyz","modes":null,"models":null,"configOptions":null}}""")

        val transport = StdioJsonRpcTransport(server.inPipe, server)
        transport.start()

        val client = AcpClient(transport)
        val initRes = client.initialize().get()
        assertEquals(1, initRes.protocolVersion)

        val authRes = client.authenticate("cursor_login").get()
        assertNotNull(authRes)

        val sessRes = client.newSession("/my/workspace").get()
        assertEquals("sess_xyz", sessRes.sessionId)
        client.close()
        server.close()
    }

    @Test
    fun testSessionOperations() {
        val server = MockAcpServer()
        server.queueResponse(1, """{"jsonrpc":"2.0","id":1,"result":{"sessionId":"sess_loaded","modes":null,"models":null,"configOptions":null}}""")
        server.queueResponse(2, """{"jsonrpc":"2.0","id":2,"result":{"stopReason":"end_turn"}}""")
        server.queueResponse(3, """{"jsonrpc":"2.0","id":3,"result":{"ok":true}}""")
        server.queueResponse(4, """{"jsonrpc":"2.0","id":4,"result":{"ok":true}}""")
        server.queueResponse(5, """{"jsonrpc":"2.0","id":5,"result":{"sessions":[{"sessionId":"sess_1","title":"Old chat"}]}}""")

        val transport = StdioJsonRpcTransport(server.inPipe, server)
        transport.start()

        val client = AcpClient(transport)

        val loadRes = client.loadSession("sess_loaded", "/my/workspace").get()
        assertEquals(null, loadRes.modes)

        val promptRes = client.sendPrompt("sess_loaded", "Hello").get()
        assertEquals("end_turn", promptRes.stopReason)

        client.cancelPrompt("sess_loaded")

        val setModeRes = client.setMode("sess_loaded", "agent").get()
        assertTrue(setModeRes.get("ok").asBoolean())

        val setConfigRes = client.setConfigOption("sess_loaded", "model", "claude-3.5-sonnet").get()
        assertTrue(setConfigRes.get("ok").asBoolean())

        val listRes = client.listSessions().get()
        assertEquals(listOf("sess_1"), listRes.map { it.sessionId })

        client.close()
        server.close()
    }

    @Test
    fun testRespondPermission() {
        val inStream = ByteArrayInputStream(ByteArray(0))
        val outStream = ByteArrayOutputStream()
        val transport = StdioJsonRpcTransport(inStream, outStream)
        val client = AcpClient(transport)

        client.respondPermission(42L, "allow_once", false)
        val written = outStream.toString(StandardCharsets.UTF_8)
        assertTrue(written.contains(""""id":42"""))
        assertTrue(written.contains(""""outcome":"selected""""))
        assertTrue(written.contains(""""optionId":"allow_once""""))

        outStream.reset()
        client.respondPermission(43L, null, true)
        val cancelledWritten = outStream.toString(StandardCharsets.UTF_8)
        assertTrue(cancelledWritten.contains(""""id":43"""))
        assertTrue(cancelledWritten.contains(""""outcome":"cancelled""""))
    }

    @Test
    fun testDispatchNotificationAndAgentRequest() {
        val inStream = ByteArrayInputStream(ByteArray(0))
        val outStream = ByteArrayOutputStream()
        val transport = StdioJsonRpcTransport(inStream, outStream)
        val client = AcpClient(transport)

        val thoughtReceived = AtomicBoolean(false)
        val messageReceived = AtomicBoolean(false)
        val toolCallReceived = AtomicBoolean(false)
        val toolCallUpdateReceived = AtomicBoolean(false)
        val planReceived = AtomicBoolean(false)
        val permissionRequested = AtomicReference<RequestPermissionParams>()
        val questionRequested = AtomicReference<CursorAskQuestionParams>()
        val planRequested = AtomicReference<CursorCreatePlanParams>()

        client.listener = object : AcpClientListener {
            override fun onThoughtChunk(chunk: ThoughtChunkUpdate) {
                thoughtReceived.set(true)
            }

            override fun onMessageChunk(chunk: MessageChunkUpdate) {
                messageReceived.set(true)
            }

            override fun onToolCall(event: ToolCallUpdateEvent) {
                toolCallReceived.set(true)
            }

            override fun onToolCallUpdate(event: ToolCallUpdateEvent) {
                toolCallUpdateReceived.set(true)
            }

            override fun onPlanUpdate(plan: PlanUpdate) {
                planReceived.set(true)
            }

            override fun onPermissionRequest(requestId: Long, params: RequestPermissionParams) {
                permissionRequested.set(params)
            }

            override fun onAskQuestionRequest(requestId: Long, params: CursorAskQuestionParams) {
                questionRequested.set(params)
            }

            override fun onCreatePlanRequest(requestId: Long, params: CursorCreatePlanParams) {
                planRequested.set(params)
            }
        }

        // Test notifications
        client.dispatchNotification(JsonRpcNotification(
            method = "session/update",
            params = SessionUpdateNotification(
                sessionId = "s1",
                update = ThoughtChunkUpdate(TextContentBlock("thinking..."))
            )
        ))
        assertTrue(thoughtReceived.get())

        client.dispatchNotification(JsonRpcNotification(
            method = "session/update",
            params = SessionUpdateNotification(
                sessionId = "s1",
                update = MessageChunkUpdate("m1", TextContentBlock("hello"))
            )
        ))
        assertTrue(messageReceived.get())

        client.dispatchNotification(JsonRpcNotification(
            method = "session/update",
            params = SessionUpdateNotification(
                sessionId = "s1",
                update = ToolCallUpdateEvent(toolCallId = "tc1", status = "pending", rawOutput = null)
            )
        ))
        assertTrue(toolCallReceived.get())

        client.dispatchNotification(JsonRpcNotification(
            method = "session/update",
            params = SessionUpdateNotification(
                sessionId = "s1",
                update = ToolCallUpdateEvent(toolCallId = "tc1", status = "completed", rawOutput = mapOf("result" to "done"))
            )
        ))
        assertTrue(toolCallUpdateReceived.get())

        client.dispatchNotification(JsonRpcNotification(
            method = "session/update",
            params = SessionUpdateNotification(
                sessionId = "s1",
                update = PlanUpdate(listOf(PlanEntry("step 1", "high", "completed")))
            )
        ))
        assertTrue(planReceived.get())

        // Test agent requests
        val permNode = JsonRpcCodec.mapper.readTree("""{"sessionId":"s1","toolCall":{"toolCallId":"tc1"},"options":[]}""")
        client.dispatchAgentRequest(10L, "session/request_permission", permNode)
        assertNotNull(permissionRequested.get())
        assertEquals("s1", permissionRequested.get().sessionId)

        val qNode = JsonRpcCodec.mapper.readTree("""{"toolCallId":"tc1","title":"Choice","questions":[]}""")
        client.dispatchAgentRequest(11L, "cursor/ask_question", qNode)
        assertNotNull(questionRequested.get())
        assertEquals("Choice", questionRequested.get().title)

        val pNode = JsonRpcCodec.mapper.readTree("""{"toolCallId":"tc1","plan":"p","todos":[]}""")
        client.dispatchAgentRequest(12L, "cursor/create_plan", pNode)
        assertNotNull(planRequested.get())
        assertEquals("p", planRequested.get().plan)
    }
}
