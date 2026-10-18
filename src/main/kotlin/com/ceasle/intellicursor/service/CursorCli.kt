package com.ceasle.intellicursor.service

import com.ceasle.intellicursor.transport.CursorProcessLauncher
import java.io.File
import java.util.concurrent.TimeUnit

/** One MCP server from `agent mcp list`. */
data class McpServerInfo(val name: String, val status: String) {
    val isReady get() = status.equals("ready", true)
    val needsLogin get() = status.contains("auth", true)
    val isDisabled get() = status.contains("disabled", true)
    val statusLabel get() = status.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/**
 * Runs one-shot Cursor CLI subcommands (`agent mcp …`, `agent update`). These features have no
 * ACP equivalent, so the plugin drives the same commands people would type in a terminal.
 * Call off the EDT: each call starts a process and waits for it.
 */
object CursorCli {
    private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    data class Result(val exitCode: Int, val output: String) {
        val ok get() = exitCode == 0
    }

    fun run(workingDir: File, vararg args: String, timeoutSeconds: Long = 60): Result {
        val exe = CursorProcessLauncher.findAgentExecutable()
        val command = buildList {
            add(exe.absolutePath)
            if (exe.name.startsWith("cursor") && !exe.name.startsWith("cursor-agent")) add("agent")
            addAll(args)
        }
        val proc = ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(true)
            .apply { environment()["NO_COLOR"] = "1" }
            .start()
        proc.outputStream.close()
        val output = StringBuilder()
        val reader = Thread {
            proc.inputStream.bufferedReader().useLines { lines -> lines.forEach { synchronized(output) { output.appendLine(it) } } }
        }.apply { isDaemon = true; start() }
        val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) proc.destroyForcibly()
        reader.join(2000)
        val text = synchronized(output) { output.toString() }.replace(ansi, "").trim()
        return Result(if (finished) proc.exitValue() else -1, if (finished) text else "$text\nTimed out after ${timeoutSeconds}s".trim())
    }

    /** Whether the CLI has a stored Cursor login, or null if that couldn't be checked. Takes well under a second. */
    fun isSignedIn(workingDir: File): Boolean? = try {
        val result = run(workingDir, "status", "--format", "json", timeoutSeconds = 15)
        if (!result.ok) null
        else Regex("\"isAuthenticated\"\\s*:\\s*(true|false)").find(result.output)?.groupValues?.get(1)?.toBoolean()
    } catch (e: Exception) {
        null
    }

    fun listMcpServers(workingDir: File): List<McpServerInfo> {
        val result = run(workingDir, "mcp", "list", timeoutSeconds = 60)
        if (!result.ok) throw IllegalStateException(result.output.ifBlank { "agent mcp list failed" })
        return result.output.lines().mapNotNull { line ->
            val idx = line.lastIndexOf(':')
            if (idx <= 0) return@mapNotNull null
            val name = line.substring(0, idx).trim().removePrefix("-").trim()
            val status = line.substring(idx + 1).trim()
            if (name.isEmpty() || status.isEmpty() || name.contains(' ')) null else McpServerInfo(name, status)
        }
    }
}
