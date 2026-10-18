package com.ceasle.intellicursor.transport

import com.ceasle.intellicursor.protocol.JsonRpcNotification
import com.ceasle.intellicursor.protocol.JsonRpcRequest
import com.ceasle.intellicursor.protocol.JsonRpcResponse
import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class StdioJsonRpcTransportTest {

    @Test
    fun testRequestResponseCorrelation() {
        val out = ByteArrayOutputStream()
        val inStream = ByteArrayInputStream("""{"jsonrpc":"2.0","id":1,"result":{"status":"ok"}}""".toByteArray(StandardCharsets.UTF_8))
        val transport = StdioJsonRpcTransport(inStream, out)

        val future = transport.sendRequest(JsonRpcRequest(id = 1, method = "test", params = null))
        transport.start()

        val response = future.get(2, TimeUnit.SECONDS)
        assertNotNull(response)
        assertEquals("ok", response.get("status").asText())
        transport.close()
    }

    @Test
    fun testExplicitNullErrorInResponse() {
        val out = ByteArrayOutputStream()
        val inStream = ByteArrayInputStream("""{"jsonrpc":"2.0","id":100,"result":{"status":"success"},"error":null}""".toByteArray(StandardCharsets.UTF_8))
        val transport = StdioJsonRpcTransport(inStream, out)

        val future = transport.sendRequest(JsonRpcRequest(id = 100, method = "test", params = null))
        transport.start()

        val response = future.get(2, TimeUnit.SECONDS)
        assertNotNull(response)
        assertEquals("success", response.get("status").asText())
        transport.close()
    }

    @Test
    fun testPipedRequestResponseFlow() {
        val out = ByteArrayOutputStream()
        val inPipe = PipedInputStream()
        val inWriter = PipedOutputStream(inPipe)
        val transport = StdioJsonRpcTransport(inPipe, out)
        transport.start()

        val future = transport.sendRequest(JsonRpcRequest(id = 5, method = "calc", params = null))
        inWriter.write("""{"jsonrpc":"2.0","id":5,"result":{"val":42}}""".toByteArray(StandardCharsets.UTF_8))
        inWriter.write("\n".toByteArray(StandardCharsets.UTF_8))
        inWriter.flush()

        val response = future.get(2, TimeUnit.SECONDS)
        assertNotNull(response)
        assertEquals(42, response.get("val").asInt())

        // Closing the write end gives the reader EOF, so its thread exits instead of leaking.
        inWriter.close()
        val deadline = System.currentTimeMillis() + 2000
        while (transport.isOpen && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertFalse(transport.isOpen, "Transport should close when the agent's stdout ends")
        transport.close()
    }

    @Test
    fun testErrorResponseCompletesExceptionally() {
        val out = ByteArrayOutputStream()
        val inStream = ByteArrayInputStream("""{"jsonrpc":"2.0","id":2,"error":{"code":-32600,"message":"Invalid Request"}}""".toByteArray(StandardCharsets.UTF_8))
        val transport = StdioJsonRpcTransport(inStream, out)

        val future = transport.sendRequest(JsonRpcRequest(id = 2, method = "test", params = null))
        transport.start()

        val ex = assertThrows(ExecutionException::class.java) {
            future.get(2, TimeUnit.SECONDS)
        }
        assertTrue(ex.cause?.message?.contains("Invalid Request") == true)
        transport.close()
    }

    @Test
    fun testNotificationRouting() {
        val out = ByteArrayOutputStream()
        val notificationReceived = CompletableFuture<JsonRpcNotification>()
        val inStream = ByteArrayInputStream("""{"jsonrpc":"2.0","method":"custom/notify","params":{"msg":"hello"}}""".toByteArray(StandardCharsets.UTF_8))
        val transport = StdioJsonRpcTransport(
            inputStream = inStream,
            outputStream = out,
            onNotification = { notif -> notificationReceived.complete(notif) }
        )
        transport.start()

        val notif = notificationReceived.get(2, TimeUnit.SECONDS)
        assertEquals("custom/notify", notif.method)
        assertNotNull(notif.params)
        transport.close()
    }

    @Test
    fun testAgentRequestRouting() {
        val out = ByteArrayOutputStream()
        data class AgentReq(val id: Long, val method: String, val params: JsonNode?)
        val agentReqReceived = CompletableFuture<AgentReq>()
        val inStream = ByteArrayInputStream("""{"jsonrpc":"2.0","id":42,"method":"session/request_permission","params":{"reason":"need access"}}""".toByteArray(StandardCharsets.UTF_8))
        val transport = StdioJsonRpcTransport(
            inputStream = inStream,
            outputStream = out,
            onAgentRequest = { id, method, params ->
                agentReqReceived.complete(AgentReq(id, method, params))
            }
        )
        transport.start()

        val req = agentReqReceived.get(2, TimeUnit.SECONDS)
        assertEquals(42L, req.id)
        assertEquals("session/request_permission", req.method)
        assertEquals("need access", req.params?.get("reason")?.asText())
        transport.close()
    }

    @Test
    fun testSendNotificationAndResponse() {
        val out = ByteArrayOutputStream()
        val inStream = ByteArrayInputStream(ByteArray(0))
        val transport = StdioJsonRpcTransport(inStream, out)

        transport.sendNotification(JsonRpcNotification(method = "notify/test", params = mapOf("a" to 1)))
        transport.sendResponse(JsonRpcResponse(id = 10, result = mapOf("done" to true)))

        val outputStr = out.toString(StandardCharsets.UTF_8)
        assertTrue(outputStr.contains(""""method":"notify/test""""))
        assertTrue(outputStr.contains(""""id":10"""))
        transport.close()
    }

    @Test
    fun testCloseCancelsPendingRequestsAndDisallowsSending() {
        val out = ByteArrayOutputStream()
        val inStream = ByteArrayInputStream(ByteArray(0))
        val transport = StdioJsonRpcTransport(inStream, out)

        val future = transport.sendRequest(JsonRpcRequest(id = 99, method = "pending", params = null))
        assertFalse(future.isDone)

        transport.close()
        assertTrue(future.isCancelled)
        assertThrows(CancellationException::class.java) {
            future.get(100, TimeUnit.MILLISECONDS)
        }

        assertThrows(IllegalStateException::class.java) {
            transport.sendRequest(JsonRpcRequest(id = 101, method = "test", params = null))
        }
        assertThrows(IllegalStateException::class.java) {
            transport.sendNotification(JsonRpcNotification(method = "test", params = null))
        }
        assertThrows(IllegalStateException::class.java) {
            transport.sendResponse(JsonRpcResponse(id = 102, result = null))
        }
    }

    @Test
    fun testBuildCommandLine() {
        val cursorExec = File("/opt/homebrew/bin/cursor")
        val cursorCmd = CursorProcessLauncher.buildCommandLine(cursorExec)
        assertEquals(listOf("/opt/homebrew/bin/cursor", "agent", "--trust", "acp"), cursorCmd)

        val agentExec = File("/opt/homebrew/bin/agent")
        val agentCmd = CursorProcessLauncher.buildCommandLine(agentExec)
        assertEquals(listOf("/opt/homebrew/bin/agent", "--trust", "acp"), agentCmd)
    }

    @Test
    fun testProcessLauncherInvalidCustomPath() {
        val nonExistentPath = "/tmp/does-not-exist-${System.currentTimeMillis()}"
        val ex = assertThrows(IllegalStateException::class.java) {
            CursorProcessLauncher.findAgentExecutable(nonExistentPath)
        }
        assertTrue(ex.message?.contains("not found or not executable") == true)
    }

    @Test
    fun testProcessLauncherNoCandidatesFound() {
        val emptyCandidates = listOf(File("/tmp/nonexistent-1"), File("/tmp/nonexistent-2"))
        val ex = assertThrows(IllegalStateException::class.java) {
            CursorProcessLauncher.findAgentExecutable(null, emptyCandidates)
        }
        assertTrue(ex.message?.contains("Cursor CLI agent executable not found") == true)
    }

    @Test
    fun testProcessLauncherCustomExecutableFound() {
        val tempFile = File.createTempFile("mock-agent", ".sh")
        try {
            tempFile.writeText("#!/bin/sh\necho ok")
            tempFile.setExecutable(true)
            val found = CursorProcessLauncher.findAgentExecutable(tempFile.absolutePath)
            assertEquals(tempFile.absolutePath, found.absolutePath)
        } finally {
            tempFile.delete()
        }
    }
}
