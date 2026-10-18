package com.ceasle.intellicursor.transport

import com.ceasle.intellicursor.protocol.*
import com.fasterxml.jackson.databind.JsonNode
import com.intellij.openapi.diagnostic.Logger
import java.io.*
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class StdioJsonRpcTransport(
    private val inputStream: InputStream,
    private val outputStream: OutputStream,
    private val onNotification: ((JsonRpcNotification) -> Unit)? = null,
    private val onAgentRequest: ((Long, String, JsonNode?) -> Unit)? = null,
    private val onClosed: (() -> Unit)? = null
) : Closeable {
    private val LOG = Logger.getInstance(StdioJsonRpcTransport::class.java)
    private val pendingRequests = ConcurrentHashMap<Long, CompletableFuture<JsonNode>>()
    private val writer = BufferedWriter(OutputStreamWriter(outputStream, StandardCharsets.UTF_8))
    private val isRunning = AtomicBoolean(true)
    private var readerThread: Thread? = null

    val isOpen: Boolean get() = isRunning.get()

    fun start() {
        readerThread = Thread({
            val reader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
            try {
                while (isRunning.get()) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    handleIncomingLine(line)
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    LOG.warn("Transport read loop terminated with exception", e)
                }
            } finally {
                close()
            }
        }, "Cursor-ACP-Reader").apply { isDaemon = true; start() }
    }

    fun sendRequest(request: JsonRpcRequest): CompletableFuture<JsonNode> {
        if (!isRunning.get()) throw IllegalStateException("Transport is closed")
        val future = CompletableFuture<JsonNode>()
        pendingRequests[request.id] = future
        val line = JsonRpcCodec.encode(request)
        synchronized(writer) {
            writer.write(line)
            writer.write("\n")
            writer.flush()
        }
        return future
    }

    fun sendNotification(notification: JsonRpcNotification) {
        if (!isRunning.get()) throw IllegalStateException("Transport is closed")
        val line = JsonRpcCodec.encode(notification)
        synchronized(writer) {
            writer.write(line)
            writer.write("\n")
            writer.flush()
        }
    }

    fun sendResponse(response: JsonRpcResponse) {
        if (!isRunning.get()) throw IllegalStateException("Transport is closed")
        val line = JsonRpcCodec.encode(response)
        synchronized(writer) {
            writer.write(line)
            writer.write("\n")
            writer.flush()
        }
    }

    private fun handleIncomingLine(line: String) {
        try {
            val root = JsonRpcCodec.readTree(line)
            val idNode = root.get("id")
            val methodNode = root.get("method")

            if (idNode != null && !idNode.isNull && (methodNode == null || methodNode.isNull)) {
                // Response to client request
                val id = idNode.asLong()
                val future = pendingRequests.remove(id) ?: return
                val errorNode = root.get("error")
                if (errorNode != null && !errorNode.isNull) {
                    val message = errorNode.get("message")?.asText() ?: "Unknown error"
                    future.completeExceptionally(RuntimeException("JSON-RPC error: $message"))
                } else {
                    future.complete(root.get("result"))
                }
            } else if (idNode != null && !idNode.isNull && methodNode != null && !methodNode.isNull) {
                // Agent-initiated request (e.g. session/request_permission, cursor/ask_question)
                onAgentRequest?.invoke(idNode.asLong(), methodNode.asText(), root.get("params"))
            } else if (methodNode != null && !methodNode.isNull) {
                // Notification from agent
                val notif = JsonRpcCodec.decodeNotification(line)
                onNotification?.invoke(notif)
            }
        } catch (e: Exception) {
            LOG.warn("Ignoring ACP message the plugin does not understand: ${line.take(300)}", e)
        }
    }

    override fun close() {
        if (isRunning.compareAndSet(true, false)) {
            pendingRequests.values.forEach { it.cancel(true) }
            pendingRequests.clear()
            try { inputStream.close() } catch (_: Exception) {}
            try { outputStream.close() } catch (_: Exception) {}
            try { onClosed?.invoke() } catch (e: Exception) { LOG.warn("Transport close callback failed", e) }
        }
    }
}
