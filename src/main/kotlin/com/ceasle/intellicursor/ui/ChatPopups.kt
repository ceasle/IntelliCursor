package com.ceasle.intellicursor.ui

import com.ceasle.intellicursor.protocol.SessionSummary
import com.ceasle.intellicursor.service.CursorCli
import com.ceasle.intellicursor.service.CursorSessionManager
import com.ceasle.intellicursor.service.McpServerInfo
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.io.File
import java.time.Duration
import java.time.Instant
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JList

/** Small helpers shared by the header popups. */
internal object Popups {
    fun info(anchor: JComponent, text: String, type: MessageType = MessageType.INFO) {
        JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder(text, type, null)
            .setFadeoutTime(4000)
            .createBalloon()
            .show(RelativePoint.getSouthOf(anchor), com.intellij.openapi.ui.popup.Balloon.Position.below)
    }

    fun notify(project: Project?, text: String, type: NotificationType = NotificationType.INFORMATION) {
        NotificationGroupManager.getInstance().getNotificationGroup("IntelliCursor").createNotification(text, type).notify(project)
    }

    fun onEdt(action: () -> Unit) = ApplicationManager.getApplication().invokeLater(action, ModalityState.any())

    fun relativeTime(iso: String?): String {
        val then = try { Instant.parse(iso ?: return "") } catch (e: Exception) { return "" }
        val d = Duration.between(then, Instant.now())
        return when {
            d.toMinutes() < 1 -> "just now"
            d.toMinutes() < 60 -> "${d.toMinutes()}m ago"
            d.toHours() < 24 -> "${d.toHours()}h ago"
            d.toDays() < 7 -> "${d.toDays()}d ago"
            else -> java.time.format.DateTimeFormatter.ofPattern("d MMM").withZone(java.time.ZoneId.systemDefault()).format(then)
        }
    }
}

/** "Recent chats": past sessions in this project, searchable by typing; picking one resumes it. */
internal object HistoryPopup {
    fun show(anchor: IconButton, manager: CursorSessionManager) {
        val idle = anchor.icon
        anchor.icon = com.intellij.ui.AnimatedIcon.Default.INSTANCE
        manager.listSessions().whenComplete { sessions, err ->
            Popups.onEdt {
                anchor.icon = idle
                when {
                    err != null -> Popups.info(anchor, "Couldn't load your chats. Check that Cursor is running, then try again.", MessageType.ERROR)
                    sessions.isNullOrEmpty() -> Popups.info(anchor, "No earlier chats in this project yet.")
                    else -> open(anchor, manager, sessions)
                }
            }
        }
    }

    private fun open(anchor: JComponent, manager: CursorSessionManager, sessions: List<SessionSummary>) {
        val current = manager.sessionState?.sessionId
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(sessions)
            .setTitle("Recent Chats")
            .setRenderer(object : ColoredListCellRenderer<SessionSummary>() {
                override fun customizeCellRenderer(list: JList<out SessionSummary>, value: SessionSummary, index: Int, selected: Boolean, hasFocus: Boolean) {
                    icon = if (value.sessionId == current) AllIcons.Actions.Checked else EmptyIcon.ICON_16
                    append(value.title?.takeIf { it.isNotBlank() } ?: "Untitled chat")
                    append("   " + Popups.relativeTime(value.updatedAt), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            })
            .setNamerForFiltering { it.title.orEmpty() }
            .setVisibleRowCount(12)
            .setItemChosenCallback { chosen ->
                if (chosen.sessionId == current) return@setItemChosenCallback
                manager.openSession(chosen).whenComplete { _, e ->
                    if (e != null) Popups.onEdt { Popups.info(anchor, "Couldn't open that chat. It may have been deleted.", MessageType.ERROR) }
                }
            }
            .createPopup()
            .showUnderneathOf(anchor)
    }
}

/**
 * MCP servers from the Cursor CLI's config, with their status. Each server can be turned on
 * or off, signed in to, or inspected for its tools. Changes restart the CLI and resume the
 * current chat so the agent picks them up without losing context.
 */
internal object McpPopup {
    private sealed class Item {
        class Server(val info: McpServerInfo) : Item()
        object EditConfig : Item()
    }

    fun show(anchor: IconButton, manager: CursorSessionManager, project: Project?) {
        val idle = anchor.icon
        anchor.icon = com.intellij.ui.AnimatedIcon.Default.INSTANCE
        val dir = workDir(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { CursorCli.listMcpServers(dir) }
            Popups.onEdt {
                anchor.icon = idle
                result.onSuccess { open(anchor, manager, project, it) }
                    .onFailure { Popups.info(anchor, "Couldn't list MCP servers: ${it.message}", MessageType.ERROR) }
            }
        }
    }

    private fun open(anchor: JComponent, manager: CursorSessionManager, project: Project?, servers: List<McpServerInfo>) {
        val items: List<Item> = servers.map { Item.Server(it) } + Item.EditConfig
        val step = object : BaseListPopupStep<Item>(if (servers.isEmpty()) "No MCP Servers Configured" else "MCP Servers", items) {
            override fun getTextFor(value: Item) = when (value) {
                is Item.Server -> "${value.info.name}   ${value.info.statusLabel}"
                Item.EditConfig -> "Edit mcp.json…"
            }

            override fun getIconFor(value: Item): Icon = when (value) {
                is Item.Server -> DotIcon({
                    when {
                        value.info.isReady -> CursorUi.success
                        value.info.needsLogin -> WARNING
                        value.info.isDisabled -> CursorUi.border
                        else -> CursorUi.danger
                    }
                }, 8)
                Item.EditConfig -> AllIcons.Actions.Edit
            }

            override fun getSeparatorAbove(value: Item) =
                if (value is Item.EditConfig && servers.isNotEmpty()) com.intellij.openapi.ui.popup.ListSeparator() else null

            override fun hasSubstep(value: Item) = value is Item.Server

            override fun onChosen(value: Item, finalChoice: Boolean): PopupStep<*>? = when (value) {
                is Item.Server -> actionsFor(value.info, manager, project)
                Item.EditConfig -> doFinalStep { openConfig(project) }
            }
        }
        JBPopupFactory.getInstance().createListPopup(step).showUnderneathOf(anchor)
    }

    private fun actionsFor(server: McpServerInfo, manager: CursorSessionManager, project: Project?): PopupStep<*> {
        val actions = buildList {
            add("Show tools")
            if (server.needsLogin) add("Sign in")
            add(if (server.isDisabled) "Enable" else "Disable")
        }
        return object : BaseListPopupStep<String>(server.name, actions) {
            override fun getIconFor(value: String): Icon = when (value) {
                "Show tools" -> AllIcons.Nodes.Function
                "Sign in" -> AllIcons.General.User
                "Enable" -> AllIcons.Actions.Execute
                else -> AllIcons.Actions.Suspend
            }

            override fun onChosen(value: String, finalChoice: Boolean): PopupStep<*>? = doFinalStep {
                when (value) {
                    "Show tools" -> showTools(server, project)
                    "Sign in" -> runAndRestart(project, manager, "Signing in to ${server.name}… finish in your browser",
                        "Signed in to ${server.name}.", "mcp", "login", server.name, timeout = 300)
                    "Enable" -> runAndRestart(project, manager, "Enabling ${server.name}…",
                        "${server.name} is on.", "mcp", "enable", server.name)
                    "Disable" -> runAndRestart(project, manager, "Disabling ${server.name}…",
                        "${server.name} is off.", "mcp", "disable", server.name)
                }
            }
        }
    }

    private fun runAndRestart(project: Project?, manager: CursorSessionManager, progress: String, done: String,
                              vararg args: String, timeout: Long = 60) {
        object : Task.Backgroundable(project, progress, true) {
            override fun run(indicator: ProgressIndicator) {
                val result = CursorCli.run(workDir(project), *args, timeoutSeconds = timeout)
                if (result.ok) {
                    manager.restartCli()
                    Popups.notify(project, "$done Cursor restarted to apply it; your chat continues.")
                } else {
                    Popups.notify(project, result.output.ifBlank { "agent ${args.joinToString(" ")} failed" }, NotificationType.ERROR)
                }
            }
        }.queue()
    }

    private fun showTools(server: McpServerInfo, project: Project?) {
        object : Task.Backgroundable(project, "Loading tools for ${server.name}…", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = CursorCli.run(workDir(project), "mcp", "list-tools", server.name, timeoutSeconds = 90)
                Popups.onEdt {
                    val area = JBTextArea(result.output.ifBlank { "No tools reported." }).apply {
                        isEditable = false
                        font = CursorUi.codeFont()
                        border = JBUI.Borders.empty(8)
                    }
                    JBPopupFactory.getInstance()
                        .createComponentPopupBuilder(JBScrollPane(area).apply { preferredSize = Dimension(JBUI.scale(520), JBUI.scale(320)) }, area)
                        .setTitle("${server.name} Tools")
                        .setResizable(true)
                        .setMovable(true)
                        .setRequestFocus(true)
                        .createPopup()
                        .showInFocusCenter()
                }
            }
        }.queue()
    }

    private fun openConfig(project: Project?) {
        val p = project ?: return
        val projectConfig = File(workDir(project), ".cursor/mcp.json")
        val file = if (projectConfig.exists()) projectConfig else File(System.getProperty("user.home"), ".cursor/mcp.json")
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
        if (vf != null) FileEditorManager.getInstance(p).openFile(vf, true)
        else Popups.notify(project, "No mcp.json yet. Create ${file.path} to add servers.")
    }

    private fun workDir(project: Project?) = File(project?.basePath ?: System.getProperty("user.home"))

    private val WARNING = com.intellij.ui.JBColor(0xB86E00, 0xE3A33B)
}
