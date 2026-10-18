package com.ceasle.intellicursor.service

import com.ceasle.intellicursor.client.AcpClient
import com.ceasle.intellicursor.client.AcpClientListener
import com.ceasle.intellicursor.protocol.*
import com.ceasle.intellicursor.transport.CursorProcessLauncher
import com.ceasle.intellicursor.transport.StdioJsonRpcTransport
import com.ceasle.intellicursor.ui.CursorQuestionDialog
import com.ceasle.intellicursor.ui.PermissionRequestDialog
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class ConnectionStatus { IDLE, CONNECTING, SIGNED_OUT, SIGNING_IN, READY, FAILED }

/** The CLI has no stored Cursor login; connecting would open a browser, so wait for the person to ask. */
class SignInRequiredException : IllegalStateException("Not signed in to Cursor")

/** How streamed updates are treated while session/load replays an old chat. */
private enum class Replay { NONE, RENDER, SUPPRESS }

/**
 * Owns the Cursor CLI process and the current chat.
 *
 * The CLI takes several seconds to boot and open a session, so the connection starts as soon
 * as the project opens instead of on the first prompt, and a spare session is kept ready so
 * "New chat" is instant. Prompts sent before the connection is up are queued and go out the
 * moment it is ready.
 */
@Service(Service.Level.PROJECT)
class CursorSessionManager(private val project: Project? = null) : Disposable, AcpClientListener {
    private val LOG = Logger.getInstance(CursorSessionManager::class.java)
    @Volatile var client: AcpClient? = null
    @Volatile var process: Process? = null
    @Volatile var sessionState: SessionState? = null
    val stateListeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile var connectionStatus: ConnectionStatus = ConnectionStatus.IDLE
        private set
    /** What went wrong and what to do about it, when [connectionStatus] is FAILED. */
    @Volatile var connectionError: Pair<String, String>? = null
        private set

    /** Prompts sent before the first session exists. */
    val pendingTurns = CopyOnWriteArrayList<ActiveTurn>()

    /** Slash commands from the CLI; they are the same for every session. */
    @Volatile var availableCommands: List<SlashCommandInfo> = emptyList()
        private set

    private val lock = Any()
    private var connectFuture: CompletableFuture<SessionState>? = null
    private var spareSession: CompletableFuture<SessionNewResult>? = null
    private var preferredMode: String? = null
    private var preferredModel: String? = null
    private val notifyQueued = AtomicBoolean(false)
    @Volatile private var disposed = false
    @Volatile private var replay = Replay.NONE
    @Volatile private var signInCancelled = false
    /** Session to resume, instead of starting a new one, the next time the CLI starts. */
    @Volatile private var resumeOnConnect: String? = null
    /** Turns to keep on screen in the next new session (the summary after /summarize). */
    @Volatile private var seedTurns: List<ActiveTurn> = emptyList()
    /** Summary of the previous chat, sent along with the next prompt after /summarize. */
    @Volatile var carryOverSummary: String? = null
        private set
    /** True while an old chat is being opened from history. */
    @Volatile var isOpeningChat = false
        private set

    private val cwd: String get() = File(project?.basePath ?: System.getProperty("user.home")).absolutePath

    /** Approve every tool call without asking, like "Run Everything" in the Cursor terminal UI. */
    var runEverything: Boolean
        get() = properties()?.getBoolean(RUN_EVERYTHING_KEY, false) ?: runEverythingFallback
        set(value) {
            properties()?.setValue(RUN_EVERYTHING_KEY, value, false) ?: run { runEverythingFallback = value }
            notifyStateChanged()
        }
    private var runEverythingFallback = false

    /** Every turn to show, including ones still waiting for the connection. */
    fun allTurns(): List<ActiveTurn> = (sessionState?.turns ?: emptyList<ActiveTurn>()) + pendingTurns

    // ---- connection ----------------------------------------------------------------------

    /**
     * Start the CLI and open a session if that isn't done or underway. Never blocks. Without
     * [signIn] it stops at SIGNED_OUT when there is no Cursor login, instead of opening a browser.
     */
    fun warmUp(signIn: Boolean = false): CompletableFuture<SessionState> {
        synchronized(lock) {
            val state = sessionState
            if (connectionStatus == ConnectionStatus.READY && client?.transport?.isOpen == true && state != null) {
                return CompletableFuture.completedFuture(state)
            }
            connectFuture?.takeIf { !it.isDone }?.let { return it }

            val future = CompletableFuture<SessionState>()
            connectFuture = future
            connectionStatus = if (signIn) ConnectionStatus.SIGNING_IN else ConnectionStatus.CONNECTING
            connectionError = null
            signInCancelled = false
            notifyStateChanged()
            runAsync {
                try {
                    future.complete(connect(signIn))
                } catch (e: Throwable) {
                    // A login that has expired surfaces as an auth error; treat it like no login at all.
                    val needsSignIn = rootCause(e) is SignInRequiredException || describe(e).first == NOT_SIGNED_IN
                    when {
                        needsSignIn && !signIn -> connectionError = null
                        signIn -> connectionError = if (signInCancelled) null
                            else "Sign-in didn't finish" to "Try again, or run \"agent login\" in a terminal."
                        else -> { LOG.warn("Could not start Cursor", e); connectionError = describe(e) }
                    }
                    connectionStatus = if (signIn || needsSignIn) ConnectionStatus.SIGNED_OUT
                        else ConnectionStatus.FAILED
                    notifyStateChanged()
                    future.completeExceptionally(e)
                }
            }
            return future
        }
    }

    /** Sign in to Cursor in the browser, then connect. Only ever started by the person. */
    fun signIn(): CompletableFuture<SessionState> {
        synchronized(lock) {
            if (connectionStatus == ConnectionStatus.SIGNING_IN) connectFuture?.let { return it }
            connectFuture = null
        }
        return warmUp(signIn = true)
    }

    /** Stop waiting for the browser sign-in. */
    fun cancelSignIn() {
        if (connectionStatus != ConnectionStatus.SIGNING_IN) return
        signInCancelled = true
        closeProcess()
    }

    /** Kept for callers that want a fresh process and session synchronously. */
    fun startSession(): SessionState {
        synchronized(lock) {
            sessionState = null
            pendingTurns.clear()
            connectFuture = null
            connectionStatus = ConnectionStatus.IDLE
        }
        return unwrap { warmUp().get() }
    }

    private fun connect(signIn: Boolean): SessionState {
        closeProcess()
        val workingDir = File(cwd)
        if (!signIn && CursorCli.isSignedIn(workingDir) == false) throw SignInRequiredException()
        val proc = CursorProcessLauncher.launch(workingDir)
        process = proc

        lateinit var acp: AcpClient
        val transport = StdioJsonRpcTransport(
            inputStream = proc.inputStream,
            outputStream = proc.outputStream,
            onNotification = { acp.dispatchNotification(it) },
            onAgentRequest = { id, method, params -> acp.dispatchAgentRequest(id, method, params) },
            onClosed = { onTransportClosed(acp) }
        )
        acp = AcpClient(transport, this)
        client = acp
        transport.start()

        unwrap { acp.initialize().get(30, TimeUnit.SECONDS) }
        // With no stored login this opens the browser and waits for the person to finish there.
        unwrap { acp.authenticate("cursor_login").get(if (signIn) 300L else 60L, TimeUnit.SECONDS) }

        val resume = resumeOnConnect
        resumeOnConnect = null
        val state = if (resume != null) {
            // Same chat in a fresh process: the transcript is already on screen, so drop the replay.
            acp.activeSessionId = resume
            replay = Replay.SUPPRESS
            try {
                val loaded = unwrap { acp.loadSession(resume, workingDir.absolutePath).get(60, TimeUnit.SECONDS) }
                adopt(resume, loaded.modes, loaded.models, keepTranscript = true)
            } finally {
                replay = Replay.NONE
            }
        } else {
            val res = unwrap { acp.newSession(workingDir.absolutePath).get(60, TimeUnit.SECONDS) }
            adopt(res, keepTranscript = true)
        }
        connectionStatus = ConnectionStatus.READY
        notifyStateChanged()
        prefetchSpareSession()
        return state
    }

    /** Make [res] the current session, carrying over the person's chosen mode and model. */
    private fun adopt(res: SessionNewResult, keepTranscript: Boolean): SessionState =
        adopt(res.sessionId, res.modes, res.models, keepTranscript)

    private fun adopt(sessionId: String, modes: ModesState?, models: ModelsState?, keepTranscript: Boolean): SessionState {
        val existing = sessionState
        val state = if (keepTranscript && existing != null) existing.also { it.sessionId = sessionId }
            else SessionState(sessionId = sessionId, cwd = cwd).also { fresh ->
                fresh.turns.addAll(seedTurns)
                seedTurns = emptyList()
            }
        state.availableModes = modes?.availableModes ?: emptyList()
        state.availableModels = models?.availableModels ?: emptyList()
        state.currentMode = modes?.currentModeId ?: "agent"
        models?.currentModelId?.let { state.currentModel = it }
        state.availableCommands = availableCommands

        val acp = client
        acp?.activeSessionId = sessionId
        preferredMode?.takeIf { it != state.currentMode }?.let { mode ->
            state.currentMode = mode
            acp?.setMode(sessionId, mode)
        }
        preferredModel?.takeIf { it != state.currentModel && state.availableModels.any { m -> m.modelId == it } }?.let { model ->
            state.currentModel = model
            acp?.setModel(sessionId, model)
        }
        sessionState = state
        return state
    }

    private fun prefetchSpareSession() {
        val acp = client ?: return
        synchronized(lock) {
            if (spareSession != null) return
            spareSession = try {
                acp.newSession(cwd).also { f -> f.exceptionally { synchronized(lock) { if (spareSession === f) spareSession = null }; null } }
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Clear the transcript and switch to a fresh session, reusing the running CLI when possible. */
    fun startNewChat() {
        carryOverSummary = null
        openFreshSession()
    }

    private fun openFreshSession() {
        sessionState?.activeTurn?.takeIf { !it.isCompleted }?.let { cancelTurn() }
        synchronized(lock) {
            pendingTurns.clear()
            sessionState = null
            val acp = client
            if (acp == null || connectionStatus != ConnectionStatus.READY || !acp.transport.isOpen) {
                connectFuture = null
                connectionStatus = ConnectionStatus.IDLE
                notifyStateChanged()
                warmUp()
                return
            }
            val future = CompletableFuture<SessionState>()
            connectFuture = future
            val next = spareSession ?: try { acp.newSession(cwd) } catch (e: Exception) { CompletableFuture.failedFuture(e) }
            spareSession = null
            next.whenCompleteAsync { res, err ->
                if (err != null || res == null) {
                    LOG.warn("Could not open a new Cursor session; restarting the CLI", err)
                    synchronized(lock) { connectFuture = null; connectionStatus = ConnectionStatus.IDLE }
                    warmUp().whenComplete { s, e -> if (e != null) future.completeExceptionally(e) else future.complete(s) }
                } else {
                    future.complete(adopt(res, keepTranscript = false))
                    notifyStateChanged()
                    prefetchSpareSession()
                }
            }
        }
        notifyStateChanged()
    }

    /** Past chats in this project, newest first. */
    fun listSessions(): CompletableFuture<List<SessionSummary>> =
        warmUp().thenCompose { _ ->
            val acp = client ?: throw IllegalStateException("Cursor isn't running")
            acp.listSessions(cwd)
        }.thenApply { list ->
            // Untitled sessions were never used (e.g. prefetched spares), so they aren't worth listing.
            list.filter { (it.cwd == null || it.cwd == cwd) && !it.title.isNullOrBlank() }
                .sortedByDescending { it.updatedAt.orEmpty() }
        }

    /** Resume a past chat: the CLI replays it, and the replay is rebuilt into turns. */
    fun openSession(summary: SessionSummary): CompletableFuture<SessionState> {
        sessionState?.activeTurn?.takeIf { !it.isCompleted }?.let { cancelTurn() }
        val future = CompletableFuture<SessionState>()
        carryOverSummary = null
        isOpeningChat = true
        notifyStateChanged()
        val ready: CompletableFuture<*> =
            if (connectionStatus == ConnectionStatus.READY && client?.transport?.isOpen == true) CompletableFuture.completedFuture(Unit)
            else warmUp()
        ready.thenCompose { _ ->
            val acp = client ?: throw IllegalStateException("Cursor isn't running")
            val state = SessionState(summary.sessionId, cwd).apply { title = summary.title ?: "Chat" }
            synchronized(lock) {
                pendingTurns.clear()
                sessionState = state
                connectFuture = future
            }
            acp.activeSessionId = summary.sessionId
            replay = Replay.RENDER
            notifyStateChanged()
            acp.loadSession(summary.sessionId, cwd)
        }.whenComplete { loaded, err ->
            replay = Replay.NONE
            isOpeningChat = false
            val state = sessionState
            state?.turns?.forEach { it.complete("end_turn") }
            state?.activeTurn = null
            if (err != null || loaded == null || state == null || state.sessionId != summary.sessionId) {
                LOG.warn("Could not open chat ${summary.sessionId}", err)
                future.completeExceptionally(err ?: IllegalStateException("Chat could not be opened"))
                notifyStateChanged()
                return@whenComplete
            }
            future.complete(adopt(summary.sessionId, loaded.modes, loaded.models, keepTranscript = true))
            notifyStateChanged()
        }
        return future
    }

    /** Restart the CLI and pick the current chat back up, e.g. after MCP servers change. */
    fun restartCli(): CompletableFuture<SessionState> {
        sessionState?.activeTurn?.takeIf { !it.isCompleted }?.let { cancelTurn() }
        synchronized(lock) {
            resumeOnConnect = sessionState?.sessionId?.takeIf { sessionState?.turns?.isNotEmpty() == true }
            connectFuture = null
            connectionStatus = ConnectionStatus.IDLE
        }
        return warmUp()
    }

    /**
     * The terminal's /summarize compacts context inside the CLI, which ACP can't reach. The
     * closest equivalent: ask for a summary, move to a fresh session, keep the summary on
     * screen and send it along with the next prompt.
     */
    fun summarizeAndContinue() {
        if (sessionState?.turns.isNullOrEmpty()) return
        submitPrompt(SUMMARY_PROMPT, display = "/summarize") { turn ->
            val summary = turn.getMessageText().trim()
            if (turn.stopReason != "end_turn" || summary.isEmpty()) return@submitPrompt
            seedTurns = listOf(turn)
            openFreshSession()
            carryOverSummary = summary
            notifyStateChanged()
        }
    }

    private fun onTransportClosed(closed: AcpClient) {
        if (disposed || client !== closed) return
        LOG.warn("Cursor CLI exited")
        val wasReady = connectionStatus == ConnectionStatus.READY
        synchronized(lock) {
            client = null
            spareSession = null
            connectFuture = null
            connectionStatus = ConnectionStatus.IDLE
        }
        sessionState?.activeTurn?.takeIf { !it.isCompleted }?.complete("error: Cursor stopped unexpectedly. Send your message again.")
        notifyStateChanged()
        // Reconnect in the background so the next prompt doesn't wait on a cold start.
        if (wasReady) warmUp()
    }

    private fun closeProcess() {
        val oldClient = client
        val oldProcess = process
        client = null
        process = null
        synchronized(lock) { spareSession = null }
        try { oldClient?.close() } catch (_: Exception) {}
        try { oldProcess?.destroyForcibly() } catch (_: Exception) {}
    }

    // ---- prompts ---------------------------------------------------------------------------

    fun submitPrompt(
        promptText: String,
        attachments: List<PromptAttachment> = emptyList(),
        display: String? = null,
        onDone: ((ActiveTurn) -> Unit)? = null,
    ) {
        val summary = carryOverSummary
        val sent = if (summary != null && onDone == null) {
            carryOverSummary = null
            "Summary of our earlier conversation (we moved to a fresh chat to save context):\n\n$summary\n\n---\n\n$promptText"
        } else promptText
        val turn = ActiveTurn(sent, attachments, display ?: promptText)
        val state = sessionState
        if (state != null && connectionStatus == ConnectionStatus.READY) {
            state.turns.add(turn)
            state.activeTurn = turn
        } else {
            pendingTurns.add(turn)
        }
        notifyStateChanged()

        warmUp().whenCompleteAsync { ready, err ->
            if (err != null || ready == null) {
                turn.complete("error: " + when {
                    connectionStatus == ConnectionStatus.SIGNED_OUT -> "Sign in to Cursor, then send your message again."
                    else -> connectionError?.first ?: describe(err ?: IllegalStateException()).first
                })
                notifyStateChanged()
                return@whenCompleteAsync
            }
            if (pendingTurns.remove(turn)) ready.turns.add(turn)
            if (turn.isCompleted) { notifyStateChanged(); return@whenCompleteAsync }
            ready.activeTurn = turn
            send(ready, turn, onDone)
        }
    }

    private fun send(state: SessionState, turn: ActiveTurn, onDone: ((ActiveTurn) -> Unit)? = null) {
        val acp = client
        if (acp == null) {
            turn.complete("error: Cursor isn't running")
            notifyStateChanged()
            return
        }
        val blocks = mutableListOf<ContentBlock>(TextContentBlock(turn.userPrompt))
        turn.attachments.forEach { blocks += ResourceLinkContentBlock(uri = "file://${it.path}", name = File(it.path).name) }
        turn.sentAt = System.currentTimeMillis()
        notifyStateChanged()
        try {
            acp.sendPrompt(state.sessionId, blocks).whenComplete { res, err ->
                turn.complete(if (err != null) "error: ${describePromptError(err)}" else res?.stopReason)
                notifyStateChanged()
                onDone?.invoke(turn)
            }
        } catch (e: Exception) {
            turn.complete("error: ${describePromptError(e)}")
            notifyStateChanged()
        }
    }

    fun cancelTurn() {
        val active = sessionState?.activeTurn?.takeIf { !it.isCompleted }
        pendingTurns.filter { !it.isCompleted }.forEach { it.complete("cancelled") }
        val state = sessionState ?: run { notifyStateChanged(); return }
        if (active == null) { notifyStateChanged(); return }
        active.complete("cancelled")
        notifyStateChanged()
        if (active.sentAt == 0L) return
        runAsync {
            try {
                client?.cancelPrompt(state.sessionId)
            } catch (e: Exception) {
                LOG.warn("Failed to send cancel prompt notification", e)
            }
        }
    }

    fun setMode(modeId: String) {
        preferredMode = modeId
        val state = sessionState ?: return
        state.currentMode = modeId
        notifyStateChanged()
        client?.setMode(state.sessionId, modeId)
    }

    fun setModel(modelId: String) {
        preferredModel = modelId
        val state = sessionState ?: return
        state.currentModel = modelId
        notifyStateChanged()
        client?.setModel(state.sessionId, modelId)
    }

    /** Coalesces bursts of streamed chunks into one UI refresh per EDT cycle. */
    fun notifyStateChanged() {
        val app = ApplicationManager.getApplication()
        if (app == null) {
            stateListeners.forEach { it.invoke() }
            return
        }
        if (!notifyQueued.compareAndSet(false, true)) return
        app.invokeLater({
            notifyQueued.set(false)
            stateListeners.forEach { it.invoke() }
        }, ModalityState.any())
    }

    // ---- streamed updates ------------------------------------------------------------------

    override fun onUserMessageChunk(chunk: UserMessageChunkUpdate) {
        if (replay != Replay.RENDER) return
        val state = sessionState ?: return
        val current = state.activeTurn
        if (current != null && current.getMessageText().isEmpty() && current.getThoughtText().isEmpty() && current.toolCalls.isEmpty()) {
            current.userPrompt += chunk.content.text
            current.displayPrompt = current.userPrompt
        } else {
            val turn = ActiveTurn(chunk.content.text).apply { sentAt = createdAt }
            state.turns.add(turn)
            state.activeTurn = turn
        }
        notifyStateChanged()
    }

    override fun onThoughtChunk(chunk: ThoughtChunkUpdate) {
        if (replay == Replay.SUPPRESS) return
        sessionState?.activeTurn?.appendThought(chunk.content.text)
        notifyStateChanged()
    }

    override fun onMessageChunk(chunk: MessageChunkUpdate) {
        if (replay == Replay.SUPPRESS) return
        sessionState?.activeTurn?.appendMessage(chunk.content.text)
        notifyStateChanged()
    }

    override fun onToolCall(event: ToolCallUpdateEvent) {
        if (replay == Replay.SUPPRESS) return
        val turn = sessionState?.activeTurn ?: return
        turn.toolCalls[event.toolCallId] = ToolCallState(
            toolCallId = event.toolCallId,
            title = event.title ?: "Tool Execution",
            kind = event.kind ?: "other",
            status = event.status ?: "pending",
            rawInput = event.rawInput
        )
        notifyStateChanged()
    }

    override fun onToolCallUpdate(event: ToolCallUpdateEvent) {
        if (replay == Replay.SUPPRESS) return
        val turn = sessionState?.activeTurn ?: return
        val tc = turn.toolCalls.computeIfAbsent(event.toolCallId) {
            ToolCallState(
                toolCallId = event.toolCallId,
                title = event.title ?: "Tool Execution",
                kind = event.kind ?: "other",
                status = event.status ?: "in_progress",
                rawInput = event.rawInput
            )
        }
        if (event.title != null) tc.title = event.title
        if (event.status != null) tc.status = event.status
        if (event.rawOutput != null) tc.rawOutput = event.rawOutput
        if (event.locations != null) tc.locations = event.locations
        notifyStateChanged()
    }

    override fun onPlanUpdate(plan: PlanUpdate) {
        if (replay == Replay.SUPPRESS) return
        sessionState?.activeTurn?.planEntries = plan.entries
        notifyStateChanged()
    }

    override fun onSessionInfoUpdate(update: SessionInfoUpdate) {
        sessionState?.title = update.title
        notifyStateChanged()
    }

    override fun onAvailableCommandsUpdate(update: AvailableCommandsUpdate) {
        availableCommands = update.availableCommands
        sessionState?.availableCommands = update.availableCommands
        notifyStateChanged()
    }

    override fun onCurrentModeUpdate(update: CurrentModeUpdate) {
        sessionState?.currentMode = update.currentModeId
        notifyStateChanged()
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun <T> unwrap(block: () -> T): T = try {
        block()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    } catch (e: CompletionException) {
        throw e.cause ?: e
    }

    private fun rootCause(e: Throwable): Throwable {
        var t = e
        while ((t is ExecutionException || t is CompletionException) && t.cause != null) t = t.cause!!
        return t
    }

    /** A headline and a next step for a failed connection, in plain words. */
    private fun describe(e: Throwable): Pair<String, String> {
        val cause = rootCause(e)
        val msg = cause.message.orEmpty()
        return when {
            msg.contains("executable not found", true) || msg.contains("not found or not executable", true) ->
                "Cursor CLI isn't installed" to "Install it with: curl https://cursor.com/install -fsS | bash"
            msg.contains("auth", true) || msg.contains("login", true) || msg.contains("unauthor", true) ->
                NOT_SIGNED_IN to "Sign in, then try again."
            cause is java.util.concurrent.TimeoutException ->
                "Cursor took too long to start" to "Check your network connection, then retry."
            cause is CancellationException || msg.contains("closed", true) ->
                "Cursor stopped while starting" to "Run \"agent\" in a terminal to see what's wrong, then retry."
            else -> "Couldn't start Cursor" to (msg.ifBlank { "Run \"agent\" in a terminal to check it works, then retry." })
        }
    }

    private fun describePromptError(e: Throwable): String {
        val cause = rootCause(e)
        if (cause is CancellationException) return "Cursor stopped before replying"
        return cause.message?.removePrefix("JSON-RPC error: ") ?: "Cursor couldn't finish this reply"
    }

    private fun properties(): PropertiesComponent? =
        if (ApplicationManager.getApplication() != null) PropertiesComponent.getInstance() else null

    private fun runAsync(action: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app != null) app.executeOnPooledThread(action)
        else Thread(action, "Cursor-Connect").apply { isDaemon = true; start() }
    }

    // ---- agent requests --------------------------------------------------------------------

    private fun autoApprove(requestId: Long, params: RequestPermissionParams): Boolean {
        if (!runEverything) return false
        val option = params.options.firstOrNull { it.kind == "allow_once" }
            ?: params.options.firstOrNull { it.kind == "allow_always" }
            ?: return false
        dispatchOnPooledThread { client?.respondPermission(requestId, option.optionId, false) }
        return true
    }

    override fun onPermissionRequest(requestId: Long, params: RequestPermissionParams) {
        if (autoApprove(requestId, params)) return
        val showDialog = Runnable {
            val dialog = PermissionRequestDialog(project, params)
            val ok = dialog.showAndGet()
            val optionId = if (ok && !dialog.isCancelled) dialog.selectedOptionId else null
            val cancelled = !ok || dialog.isCancelled || optionId == null

            dispatchOnPooledThread {
                client?.respondPermission(requestId, optionId, cancelled)
            }
        }
        runOnEdt(showDialog)
    }

    override fun onAskQuestionRequest(requestId: Long, params: CursorAskQuestionParams) {
        val showDialog = Runnable {
            val dialog = CursorQuestionDialog(project, params)
            val ok = dialog.showAndGet()
            val answers = if (ok && !dialog.isCancelled) dialog.answers else emptyMap()
            val cancelled = !ok || dialog.isCancelled

            dispatchOnPooledThread {
                client?.respondAskQuestion(requestId, answers, cancelled)
            }
        }
        runOnEdt(showDialog)
    }

    override fun onCreatePlanRequest(requestId: Long, params: CursorCreatePlanParams) {
        val showDialog = Runnable {
            val planTitle = params.name?.takeIf { it.isNotBlank() } ?: "Proposed Plan"
            val message = buildString {
                if (!params.overview.isNullOrBlank()) {
                    append(params.overview).append("\n\n")
                }
                if (params.todos.isNotEmpty()) {
                    append("Tasks:\n")
                    params.todos.forEach { todo ->
                        append(" • [").append(todo.status).append("] ").append(todo.content).append("\n")
                    }
                    append("\n")
                }
                append("Do you want to approve this plan?")
            }

            val approved = try {
                Messages.showYesNoDialog(
                    project,
                    message,
                    planTitle,
                    "Approve Plan",
                    "Reject",
                    Messages.getQuestionIcon()
                ) == Messages.YES
            } catch (e: Exception) {
                LOG.warn("Could not display plan confirmation dialog", e)
                false
            }

            dispatchOnPooledThread {
                client?.respondCreatePlan(requestId, approved)
            }
        }
        runOnEdt(showDialog)
    }

    private fun runOnEdt(action: Runnable) {
        val app = ApplicationManager.getApplication()
        if (app != null) {
            app.invokeLater(action)
        } else if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            action.run()
        } else {
            javax.swing.SwingUtilities.invokeLater(action)
        }
    }

    private fun dispatchOnPooledThread(action: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app != null) {
            app.executeOnPooledThread {
                try {
                    action()
                } catch (e: Exception) {
                    LOG.warn("Failed to dispatch ACP response", e)
                }
            }
        } else {
            try {
                action()
            } catch (e: Exception) {
                LOG.warn("Failed to dispatch ACP response", e)
            }
        }
    }

    override fun dispose() {
        disposed = true
        stateListeners.clear()
        closeProcess()
    }

    private companion object {
        const val RUN_EVERYTHING_KEY = "cursor.agent.runEverything"
        const val NOT_SIGNED_IN = "You're not signed in to Cursor"
        const val SUMMARY_PROMPT = "Summarize our conversation so far so it can continue in a fresh chat. " +
            "Cover the goal, decisions made, files changed or examined, and open next steps. " +
            "Be concise, use short bullet points, and don't use any tools."
    }
}
