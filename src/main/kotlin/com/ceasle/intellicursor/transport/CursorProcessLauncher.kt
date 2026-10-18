package com.ceasle.intellicursor.transport

import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

object CursorProcessLauncher {
    private val LOG = Logger.getInstance(CursorProcessLauncher::class.java)

    fun findAgentExecutable(customPath: String? = null, candidates: List<File>? = null): File {
        if (!customPath.isNullOrBlank()) {
            val file = File(customPath)
            if (file.canExecute()) return file
            throw IllegalStateException("Configured Cursor agent executable not found or not executable at: $customPath")
        }

        val searchCandidates = candidates ?: listOf(
            File(System.getProperty("user.home"), ".local/bin/agent"),
            File(System.getProperty("user.home"), ".local/bin/cursor"),
            File("/usr/local/bin/agent"),
            File("/usr/local/bin/cursor"),
            File("/opt/homebrew/bin/agent"),
            File("/opt/homebrew/bin/cursor")
        )

        for (candidate in searchCandidates) {
            if (candidate.canExecute()) {
                LOG.info("Discovered Cursor agent executable at: ${candidate.absolutePath}")
                return candidate
            }
        }

        throw IllegalStateException("Cursor CLI agent executable not found. Install Cursor CLI or set path in Settings.")
    }

    fun buildCommandLine(executable: File): List<String> {
        return if (executable.name.startsWith("cursor")) {
            listOf(executable.absolutePath, "agent", "--trust", "acp")
        } else {
            listOf(executable.absolutePath, "--trust", "acp")
        }
    }

    fun launch(workingDir: File, customPath: String? = null): Process {
        val executable = findAgentExecutable(customPath)
        val command = buildCommandLine(executable)
        val pb = ProcessBuilder(command)
            .directory(workingDir)

        pb.environment().putAll(System.getenv())
        LOG.info("Launching Cursor ACP subprocess: ${pb.command()} in ${workingDir.absolutePath}")
        val proc = pb.start()

        Thread({
            try {
                BufferedReader(InputStreamReader(proc.errorStream, StandardCharsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) {
                            LOG.warn("Cursor ACP stderr: $line")
                        }
                    }
                }
            } catch (e: Exception) {
                LOG.debug("Error draining stderr", e)
            }
        }, "Cursor-ACP-Stderr").apply { isDaemon = true; start() }

        return proc
    }
}
