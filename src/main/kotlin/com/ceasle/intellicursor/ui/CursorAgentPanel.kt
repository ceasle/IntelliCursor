package com.ceasle.intellicursor.ui

import com.ceasle.intellicursor.service.ConnectionStatus
import com.ceasle.intellicursor.service.CursorSessionManager
import com.ceasle.intellicursor.service.PromptAttachment
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.GridBagLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * The Cursor chat tool window: a welcome screen until the first prompt, then a messenger-style
 * transcript above a Copilot-style composer.
 */
class CursorAgentPanel(
    val sessionManager: CursorSessionManager,
    private val project: Project? = null,
) : JPanel(BorderLayout()), Disposable {

    private val LOG = Logger.getInstance(CursorAgentPanel::class.java)

    constructor(project: Project) : this(
        project.getService(CursorSessionManager::class.java) ?: CursorSessionManager(project),
        project
    )

    val titleLabel = JBLabel("IntelliCursor").apply {
        font = CursorUi.boldFont()
        border = JBUI.Borders.emptyLeft(2)
    }
    private val connectionLabel = CursorUi.secondaryLabel().apply {
        iconTextGap = JBUI.scale(5)
        border = JBUI.Borders.emptyRight(6)
    }

    val modePicker = PickerButton("Mode") { mode -> runInBackground { sessionManager.setMode(mode) } }
    val modelPicker = PickerButton("Model") { model -> runInBackground { sessionManager.setModel(model) } }
    private val runEverythingChip = TextButton("Run everything  ✕", "Cursor runs every tool without asking. Click to turn this off.") {
        sessionManager.runEverything = false
    }.apply {
        foreground = WARNING
        font = CursorUi.smallFont()
    }

    val newSessionButton = IconButton(AllIcons.General.Add, "New chat") { startNewSessionInBackground() }
    private val historyButton: IconButton = IconButton(AllIcons.Vcs.History, "Recent chats") { showHistory() }
    private val mcpButton: IconButton = IconButton(AllIcons.Nodes.Plugin, "MCP servers") { showMcp() }

    val turnsContainer = WidthTrackingPanel().apply {
        border = JBUI.Borders.empty(10, 12, 8, 12)
    }

    val scrollPane = JBScrollPane(turnsContainer).apply {
        border = JBUI.Borders.empty()
        isOpaque = false
        viewport.isOpaque = false
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }

    val composer = MessageComposer { text -> send(text) }

    val turnViews = mutableListOf<TurnView>()

    private val header = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.compound(
            JBUI.Borders.customLineBottom(CursorUi.subtleBorder),
            JBUI.Borders.empty(5, 12, 5, 8)
        )
        add(titleLabel, BorderLayout.CENTER)
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(connectionLabel)
            add(mcpButton)
            add(historyButton)
            add(newSessionButton)
        }, BorderLayout.EAST)
    }

    private val errorBanner = ErrorBanner()
    private val signInBanner = SignInBanner(onSignIn = { sessionManager.signIn() }, onCancel = { sessionManager.cancelSignIn() })
    private val cards = CardLayout()
    private val center = JPanel(cards).apply { isOpaque = false }
    private val files = project?.let { ProjectFiles(it) }

    private var currentFile: VirtualFile? = null
    private var attachCurrentFile = true

    private val stateListener = { updateUiFromState() }

    init {
        background = CursorUi.background
        isOpaque = true

        center.add(WelcomePanel { prompt -> send(prompt) }, CARD_WELCOME)
        center.add(scrollPane, CARD_CHAT)

        composer.controls.add(modePicker)
        composer.controls.add(Box.createHorizontalStrut(JBUI.scale(2)))
        composer.controls.add(modelPicker)
        composer.controls.add(Box.createHorizontalStrut(JBUI.scale(2)))
        composer.controls.add(runEverythingChip)
        composer.onStop = { sessionManager.cancelTurn() }
        composer.onToggleContext = { attachCurrentFile = it }
        composer.fileSearch = files?.let { f -> { query -> f.search(query, openFiles()) } }

        val south = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(0, 10)
                add(signInBanner, BorderLayout.NORTH)
                add(errorBanner, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(composer, BorderLayout.CENTER)
        }

        add(header, BorderLayout.NORTH)
        add(center, BorderLayout.CENTER)
        add(south, BorderLayout.SOUTH)

        trackCurrentFile()
        sessionManager.stateListeners.add(stateListener)
        updateUiFromState()
        // Opening the tool window is a strong hint a prompt is coming; make sure Cursor is booting.
        if (project != null) sessionManager.warmUp()
        files?.refreshAsync()
    }

    private fun send(text: String) {
        errorBanner.isVisible = false
        val attachments = buildList {
            currentFile?.takeIf { attachCurrentFile }?.let { add(PromptAttachment(it.path, it.name)) }
            composer.attachments.forEach { a -> if (none { it.path == a.path }) add(a) }
        }
        composer.clearAttachments()
        sessionManager.submitPrompt(text, attachments)
        scrollToBottom()
    }

    fun startNewSessionInBackground() {
        errorBanner.isVisible = false
        composer.clearAttachments()
        sessionManager.startNewChat()
        composer.focusInput()
    }

    fun updateUiFromState() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { updateUiFromState() }
            return
        }
        val state = sessionManager.sessionState
        val title = state?.title?.takeUnless { it.isBlank() || it == "New Session" }
        titleLabel.text = title ?: "IntelliCursor"
        refreshConnection()

        val modes = state?.availableModes.orEmpty()
        modePicker.items = if (modes.isNotEmpty()) modes.map { it.id to it.name }
            else listOf("agent" to "Agent", "plan" to "Plan", "ask" to "Ask")
        modePicker.selectedId = state?.currentMode ?: modePicker.selectedId ?: "agent"

        val models = state?.availableModels.orEmpty()
        if (models.isNotEmpty()) modelPicker.items = models.map { it.modelId to it.name }
        if (state != null) modelPicker.selectedId = state.currentModel

        runEverythingChip.isVisible = sessionManager.runEverything
        composer.commands = sessionManager.availableCommands.ifEmpty { state?.availableCommands.orEmpty() }
        composer.localCommands = localCommands()

        val currentTurns = sessionManager.allTurns()
        composer.setRunning(currentTurns.any { !it.isCompleted })

        if (turnViews.size > currentTurns.size ||
            turnViews.indices.any { it < currentTurns.size && turnViews[it].turn !== currentTurns[it] }) {
            turnViews.clear()
            turnsContainer.removeAll()
        }

        val added = turnViews.size < currentTurns.size
        val stickToBottom = added || isNearBottom()
        while (turnViews.size < currentTurns.size) {
            val tv = TurnView(currentTurns[turnViews.size], project)
            turnViews.add(tv)
            turnsContainer.add(tv)
        }
        // Finished turns never change again, so only the live ones are refreshed.
        for (tv in turnViews) if (!tv.isSettled) tv.syncState()

        cards.show(center, if (turnViews.isEmpty()) CARD_WELCOME else CARD_CHAT)
        turnsContainer.revalidate()
        turnsContainer.repaint()
        if (stickToBottom) scrollToBottom()
    }

    private fun refreshConnection() {
        composer.textArea.emptyText.text = if (sessionManager.carryOverSummary != null)
            "Summary saved. Your next message continues from it."
            else "Message Cursor, @ to add files, / for commands"
        if (sessionManager.isOpeningChat) {
            connectionLabel.icon = AnimatedIcon.Default.INSTANCE
            connectionLabel.text = "Opening chat…"
            connectionLabel.toolTipText = null
            return
        }
        when (sessionManager.connectionStatus) {
            ConnectionStatus.CONNECTING -> {
                connectionLabel.icon = AnimatedIcon.Default.INSTANCE
                connectionLabel.text = "Starting Cursor…"
                connectionLabel.toolTipText = "Starting the Cursor CLI. You can type and send now; your message goes out as soon as it's ready."
            }
            ConnectionStatus.READY -> {
                connectionLabel.icon = DotIcon({ CursorUi.success })
                connectionLabel.text = ""
                connectionLabel.toolTipText = "Connected to the Cursor CLI"
            }
            ConnectionStatus.SIGNED_OUT -> {
                connectionLabel.icon = DotIcon({ CursorUi.border })
                connectionLabel.text = "Signed out"
                connectionLabel.toolTipText = "Sign in to Cursor to start chatting"
            }
            ConnectionStatus.SIGNING_IN -> {
                connectionLabel.icon = AnimatedIcon.Default.INSTANCE
                connectionLabel.text = "Signing in…"
                connectionLabel.toolTipText = "Finish signing in in your browser"
            }
            ConnectionStatus.FAILED -> {
                connectionLabel.icon = DotIcon({ CursorUi.danger })
                connectionLabel.text = "Offline"
                connectionLabel.toolTipText = sessionManager.connectionError?.first
            }
            ConnectionStatus.IDLE -> {
                connectionLabel.icon = DotIcon({ CursorUi.border })
                connectionLabel.text = ""
                connectionLabel.toolTipText = "Cursor starts when you send a message"
            }
        }
        signInBanner.update(sessionManager.connectionStatus, sessionManager.connectionError)
        val error = sessionManager.connectionError
        if (sessionManager.connectionStatus == ConnectionStatus.FAILED && error != null) {
            errorBanner.show(error.first, error.second, "Retry") {
                errorBanner.isVisible = false
                sessionManager.warmUp()
            }
        } else if (errorBanner.isRetryBanner) {
            errorBanner.isVisible = false
        }
    }

    private fun localCommands(): List<LocalCommand> {
        val on = sessionManager.runEverything
        return listOf(
            LocalCommand("new", "Start a new chat", AllIcons.General.Add) { startNewSessionInBackground() },
            LocalCommand("clear", "Start a new chat", AllIcons.Actions.GC) { startNewSessionInBackground() },
            LocalCommand("resume", "Open recent chats and resume one", AllIcons.Vcs.History) { showHistory() },
            LocalCommand("summarize", "Summarize this chat and continue in a fresh one to free up context", AllIcons.Actions.Collapseall) {
                if (sessionManager.sessionState?.turns.isNullOrEmpty()) Popups.info(composer, "Nothing to summarize yet.")
                else sessionManager.summarizeAndContinue()
            },
            LocalCommand("mcp", "Manage MCP servers: status, tools, sign in, turn on or off", AllIcons.Nodes.Plugin) { showMcp() },
            LocalCommand(
                "run-everything",
                if (on) "Turn off: ask again before Cursor runs tools" else "Let Cursor run every tool without asking",
                AllIcons.Actions.Execute
            ) { sessionManager.runEverything = !on },
            LocalCommand("agent", "Switch to Agent mode: edits files and runs commands", AllIcons.Actions.Lightning) { pickMode("agent") },
            LocalCommand("plan", "Switch to Plan mode: designs an approach without editing", AllIcons.Actions.ListFiles) { pickMode("plan") },
            LocalCommand("ask", "Switch to Ask mode: answers questions, read-only", AllIcons.Actions.Help) { pickMode("ask") },
            LocalCommand("model", "Choose the model", AllIcons.General.Settings) { modelPicker.showPopup() },
            LocalCommand("changes", "Review uncommitted changes in the diff viewer", AllIcons.Actions.Diff) { runIdeAction("Cursor.ShowDiff") },
            LocalCommand("commit", "Ask Cursor to stage and commit the current changes", AllIcons.Actions.Commit) {
                send("Stage and commit the current changes with a clear, conventional commit message. Show me the message you used.")
            },
            LocalCommand("copy", "Copy Cursor's last reply", AllIcons.Actions.Copy) {
                val last = sessionManager.allTurns().lastOrNull { it.getMessageText().isNotBlank() }?.getMessageText()
                if (last != null) com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(last))
            },
            LocalCommand("copy-conversation-id", "Copy this chat's ID", AllIcons.Actions.Copy) {
                sessionManager.sessionState?.sessionId?.let {
                    com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(it))
                }
            },
            LocalCommand("help", "Show every command", AllIcons.Actions.Help) {
                SwingUtilities.invokeLater { composer.setText("/"); composer.focusInput() }
            },
        )
    }

    private fun showHistory() = HistoryPopup.show(historyButton, sessionManager)

    private fun showMcp() = McpPopup.show(mcpButton, sessionManager, project)

    private fun runIdeAction(id: String) {
        val action = com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction(id) ?: return
        com.intellij.openapi.actionSystem.ex.ActionUtil.invokeAction(action, this, "CursorChat", null, null)
    }

    private fun pickMode(mode: String) {
        modePicker.selectedId = mode
        runInBackground { sessionManager.setMode(mode) }
    }

    private fun isNearBottom(): Boolean {
        val bar = scrollPane.verticalScrollBar
        return bar.value + bar.visibleAmount >= bar.maximum - JBUI.scale(48)
    }

    fun scrollToBottom() {
        SwingUtilities.invokeLater {
            turnsContainer.validate()
            val vertical = scrollPane.verticalScrollBar
            vertical.value = vertical.maximum
        }
    }

    private fun openFiles(): List<String> =
        project?.let { p -> FileEditorManager.getInstance(p).openFiles.map { it.path } }.orEmpty()

    private fun trackCurrentFile() {
        val p = project ?: return
        fun refresh(file: VirtualFile?) {
            currentFile = file
            composer.setContextFile(file?.name, file?.let { displayPath(it) }, attachCurrentFile)
        }
        refresh(FileEditorManager.getInstance(p).selectedFiles.firstOrNull())
        p.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = refresh(event.newFile)
        })
    }

    private fun displayPath(file: VirtualFile): String {
        val base = project?.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        return base?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.path
    }

    private fun runInBackground(action: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app != null) app.executeOnPooledThread(action) else action()
    }

    override fun dispose() {
        sessionManager.stateListeners.remove(stateListener)
        turnViews.clear()
        turnsContainer.removeAll()
    }

    private companion object {
        const val CARD_WELCOME = "welcome"
        const val CARD_CHAT = "chat"
        val WARNING = JBColor(0xB86E00, 0xE3A33B)
    }
}

/** The project's files for "@", indexed in the background and ranked by how well the name matches. */
private class ProjectFiles(private val project: Project) {
    @Volatile private var files: List<FileSuggestion> = emptyList()
    @Volatile private var indexedAt = 0L

    fun refreshAsync() {
        val app = ApplicationManager.getApplication() ?: return
        if (System.currentTimeMillis() - indexedAt < 15_000) return
        indexedAt = System.currentTimeMillis()
        app.executeOnPooledThread {
            val base = project.basePath.orEmpty()
            val result = ArrayList<FileSuggestion>()
            ReadAction.run<RuntimeException> {
                if (project.isDisposed) return@run
                ProjectFileIndex.getInstance(project).iterateContent { vf ->
                    if (!vf.isDirectory) result += FileSuggestion(vf.path, vf.path.removePrefix(base).removePrefix("/"))
                    result.size < 50_000
                }
            }
            files = result.sortedBy { it.relativePath.length }
        }
    }

    fun search(query: String, openPaths: List<String>): List<FileSuggestion> {
        refreshAsync()
        val all = files
        if (query.isEmpty()) {
            val open = openPaths.mapNotNull { p -> all.firstOrNull { it.path == p } }
            return (open + all.filter { it.path !in openPaths }).take(40)
        }
        val q = query.lowercase()
        return all.asSequence()
            .mapNotNull { f ->
                val name = f.relativePath.substringAfterLast('/').lowercase()
                val rank = when {
                    name.startsWith(q) -> 0
                    name.contains(q) -> 1
                    f.relativePath.lowercase().contains(q) -> 2
                    else -> return@mapNotNull null
                }
                (if (f.path in openPaths) rank - 0.5 else rank.toDouble()) to f
            }
            .sortedBy { it.first }
            .take(40)
            .map { it.second }
            .toList()
    }
}

/** First-run screen: the Cursor mark, one line on what it can do, and a few starter prompts. */
private class WelcomePanel(private val onPrompt: (String) -> Unit) : JPanel(GridBagLayout()) {
    init {
        isOpaque = false
        val column = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(0, 20)
            maximumSize = Dimension(JBUI.scale(340), Int.MAX_VALUE)
        }

        column.add(JBLabel(CursorUi.logoIcon).leftAligned())
        column.add(Box.createVerticalStrut(JBUI.scale(14)))
        column.add(JBLabel("Ask Cursor").apply { font = CursorUi.boldFont().deriveFont(CursorUi.bodyFont().size2D + 5f) }.leftAligned())
        column.add(Box.createVerticalStrut(JBUI.scale(6)))
        column.add(WrapTextArea("Cursor works in this project with you. It can read code, make edits and run commands, and asks before anything that needs your approval.").apply {
            foreground = CursorUi.secondary
            columns = 28
        }.leftAligned())
        column.add(Box.createVerticalStrut(JBUI.scale(18)))

        listOf(
            "Explain how this project is structured",
            "Find bugs in the current file",
            "Write tests for the current file",
        ).forEach { prompt ->
            column.add(StarterPrompt(prompt) { onPrompt(prompt) }.leftAligned())
            column.add(Box.createVerticalStrut(JBUI.scale(6)))
        }

        column.add(Box.createVerticalStrut(JBUI.scale(10)))
        column.add(CursorUi.secondaryLabel("Enter to send, Shift+Enter for a new line, / for commands").leftAligned())
        add(column)
    }
}

private class StarterPrompt(text: String, onClick: () -> Unit) :
    RoundedPanel(BorderLayout(), arc = 10, outline = { CursorUi.border }) {
    private var hovered = false

    init {
        fill = { if (hovered) CursorUi.hoverBackground else null }
        border = JBUI.Borders.empty(7, 10)
        add(JBLabel(text), BorderLayout.CENTER)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hovered = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hovered = false; repaint() }
            override fun mouseClicked(e: MouseEvent) = onClick()
        })
    }
}

/**
 * Shown while there is no Cursor login. Sign-in opens the browser, so it only starts when the
 * person clicks the button, never on its own when the IDE opens.
 */
private class SignInBanner(onSignIn: () -> Unit, onCancel: () -> Unit) :
    RoundedPanel(BorderLayout(JBUI.scale(10), 0), arc = 10, fill = { CursorUi.chipBackground }) {

    private val title = JBLabel().apply { font = CursorUi.boldFont() }
    private val hint = WrapTextArea().apply {
        font = CursorUi.smallFont()
        foreground = CursorUi.secondary
    }
    private val signInButton = JButton("Sign in").apply {
        putClientProperty("JButton.buttonType", "default")
        isDefaultCapable = false
        addActionListener { onSignIn() }
    }
    private val cancelLink = JBLabel("Cancel").apply {
        foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onCancel()
        })
    }
    private val waiting = JBLabel(AnimatedIcon.Default.INSTANCE)

    init {
        isVisible = false
        border = JBUI.Borders.empty(10, 12)
        add(JBLabel(CursorUi.roundAvatarIcon).apply { verticalAlignment = JBLabel.TOP }, BorderLayout.WEST)
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(title, BorderLayout.NORTH)
            add(hint, BorderLayout.CENTER)
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                border = JBUI.Borders.emptyTop(8)
                add(signInButton)
                add(waiting)
                add(Box.createHorizontalStrut(JBUI.scale(8)))
                add(cancelLink)
                add(Box.createHorizontalGlue())
            }, BorderLayout.SOUTH)
        }, BorderLayout.CENTER)
    }

    fun update(status: ConnectionStatus, error: Pair<String, String>?) {
        val signingIn = status == ConnectionStatus.SIGNING_IN
        isVisible = signingIn || status == ConnectionStatus.SIGNED_OUT
        if (!isVisible) return
        title.text = when {
            signingIn -> "Finish signing in in your browser"
            error != null -> error.first
            else -> "Sign in to Cursor"
        }
        hint.text = when {
            signingIn -> "A Cursor sign-in page opened in your browser. Chat starts as soon as you finish there."
            error != null -> error.second
            else -> "IntelliCursor uses your Cursor account. Signing in opens cursor.com in your browser."
        }
        signInButton.isVisible = !signingIn
        signInButton.text = if (error != null) "Try again" else "Sign in"
        waiting.isVisible = signingIn
        cancelLink.isVisible = signingIn
        revalidate()
        repaint()
    }
}

/** Inline error above the composer: what went wrong, what to do next, and optionally a fix-it action. */
private class ErrorBanner : RoundedPanel(BorderLayout(JBUI.scale(8), 0), arc = 8,
    fill = { JBColor(0xFDECEE, 0x402929) }) {

    private val title = JBLabel().apply { font = CursorUi.boldFont() }
    private val hint = WrapTextArea().apply {
        font = CursorUi.smallFont()
        foreground = CursorUi.secondary
    }
    private var action: (() -> Unit)? = null
    private val actionLink = JBLabel().apply {
        foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.emptyTop(4)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { action?.invoke() }
        })
    }

    /** True while showing a connection error, which clears itself once Cursor connects. */
    var isRetryBanner = false
        private set

    init {
        isVisible = false
        border = JBUI.Borders.empty(8, 10)
        add(JBLabel(AllIcons.General.Error).apply { verticalAlignment = JBLabel.TOP }, BorderLayout.WEST)
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(title, BorderLayout.NORTH)
            add(hint, BorderLayout.CENTER)
            add(actionLink, BorderLayout.SOUTH)
        }, BorderLayout.CENTER)
        add(IconButton(AllIcons.Actions.Close, "Dismiss") { isVisible = false }.apply {
            verticalAlignment = JBLabel.TOP
        }, BorderLayout.EAST)
    }

    fun show(message: String, help: String, actionText: String? = null, onAction: (() -> Unit)? = null) {
        title.text = message
        hint.text = help
        action = onAction
        actionLink.text = actionText.orEmpty()
        actionLink.isVisible = actionText != null
        isRetryBanner = actionText != null
        isVisible = true
        revalidate()
    }
}
