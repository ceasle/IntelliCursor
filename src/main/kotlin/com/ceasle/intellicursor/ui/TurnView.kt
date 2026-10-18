package com.ceasle.intellicursor.ui

import com.ceasle.intellicursor.service.ActiveTurn
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.Timer

/**
 * One exchange as two chat bubbles: the prompt on the right, Cursor's reply on the left.
 * While the reply is in progress the bubble ends with a live status line ("Thinking… 7s") so
 * there is always visible progress, even when the CLI is slow to produce its first token.
 */
class TurnView(val turn: ActiveTurn, private val project: Project? = null) : JPanel() {
    val thinkingPanel = ThinkingPanel()
    val planPanel = PlanProgressPanel()
    val toolActivityPanel = ToolActivityPanel(project)
    val finalResponsePanel = FinalResponsePanel(project)

    private val activityLabel = JBLabel(AnimatedIcon.Default.INSTANCE).apply {
        foreground = CursorUi.secondary
        iconTextGap = JBUI.scale(6)
    }
    private val elapsedLabel = CursorUi.secondaryLabel()
    private val activityRow = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(2, 0)
        add(activityLabel)
        add(Box.createHorizontalStrut(JBUI.scale(6)))
        add(elapsedLabel)
        add(Box.createHorizontalGlue())
    }

    private val statusLabel = CursorUi.secondaryLabel()
    private val copyButton = IconButton(AllIcons.Actions.Copy, "Copy reply") {
        CopyPasteManager.getInstance().setContents(StringSelection(turn.getMessageText()))
    }
    private val agentTime = CursorUi.secondaryLabel()
    private val footer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        border = JBUI.Borders.emptyTop(2)
        add(statusLabel)
        add(Box.createHorizontalGlue())
        add(Box.createHorizontalStrut(JBUI.scale(8)))
        add(copyButton)
        add(Box.createHorizontalStrut(JBUI.scale(2)))
        add(agentTime)
    }

    private val agentBody = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private val userTime = CursorUi.secondaryLabel()
    private val userText = WrapTextArea(turn.displayPrompt).apply { foreground = CursorUi.foreground }

    private val ticker = Timer(1000) { refreshActivity() }

    /** True once a finished turn has been drawn; it never changes after that. */
    var isSettled = false
        private set

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(4, 0, 14, 0)

        listOf(thinkingPanel, planPanel, toolActivityPanel, finalResponsePanel, activityRow, footer).forEach {
            it.alignmentX = LEFT_ALIGNMENT
            agentBody.add(it)
        }

        add(BubbleRow(true, InitialAvatarIcon(userInitial(), 26), userBubble(), ::userNaturalWidth))
        add(Box.createVerticalStrut(JBUI.scale(10)))
        add(BubbleRow(false, CursorUi.roundAvatarIcon,
            Bubble(false, { CursorUi.agentBubble }, { CursorUi.agentBubbleBorder }).apply { add(agentBody, BorderLayout.CENTER) },
            ::agentNaturalWidth))
        syncState()
    }

    override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)

    fun syncState() {
        if (userText.text != turn.displayPrompt) userText.text = turn.displayPrompt
        val thoughtText = turn.getThoughtText()
        thinkingPanel.isVisible = thoughtText.isNotBlank()
        if (thoughtText.isNotBlank()) thinkingPanel.updateContent(thoughtText, turn.isThinking && !turn.isCompleted)

        planPanel.updatePlan(turn.planEntries)
        val tools = synchronized(turn.toolCalls) { ArrayList(turn.toolCalls.values) }
        toolActivityPanel.updateTools(tools)
        val message = turn.getMessageText()
        finalResponsePanel.updateText(message)

        val done = turn.isCompleted
        activityRow.isVisible = !done
        if (done) ticker.stop() else if (!ticker.isRunning && isDisplayable) ticker.start()
        refreshActivity()

        userTime.text = if (turn.sentAt == 0L && !done) "Sending…" else clock(turn.createdAt)

        val reason = turn.stopReason
        footer.isVisible = done
        copyButton.isVisible = message.isNotBlank()
        agentTime.text = if (done) clock(turn.completedAt.takeIf { it > 0 } ?: System.currentTimeMillis()) else ""
        agentTime.toolTipText = if (done && turn.completedAt > 0) "Took ${elapsed(turn.completedAt - turn.createdAt)}" else null
        statusLabel.foreground = if (reason?.startsWith("error") == true) CursorUi.danger else CursorUi.secondary
        statusLabel.text = when {
            reason == null || reason == "end_turn" -> if (done && message.isBlank() && tools.isEmpty()) "No reply" else ""
            reason == "cancelled" -> "Stopped"
            reason.startsWith("error") -> reason.removePrefix("error: ").replaceFirstChar { it.uppercase() }
            reason == "max_tokens" -> "Reply cut off at the length limit"
            reason == "refusal" -> "Cursor declined this request"
            else -> reason.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
        isSettled = done
        revalidate()
        repaint()
    }

    /** What Cursor is doing right now, so a slow reply never looks frozen. */
    private fun refreshActivity() {
        if (turn.isCompleted) return
        val runningTool = synchronized(turn.toolCalls) {
            turn.toolCalls.values.lastOrNull { it.status == "in_progress" || it.status == "pending" }
        }
        activityLabel.text = when {
            turn.sentAt == 0L -> "Starting Cursor…"
            runningTool != null -> (runningTool.title.ifBlank { "Running a tool" }) + "…"
            turn.getMessageText().isNotBlank() -> "Writing…"
            turn.getThoughtText().isNotBlank() -> "Thinking…"
            else -> "Waiting for Cursor…"
        }
        elapsedLabel.text = elapsed(System.currentTimeMillis() - turn.createdAt)
    }

    override fun addNotify() {
        super.addNotify()
        if (!turn.isCompleted) ticker.start()
    }

    override fun removeNotify() {
        ticker.stop()
        super.removeNotify()
    }

    private fun userBubble(): Bubble {
        val bubble = Bubble(true, { CursorUi.userBubble })
        val column = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        if (turn.attachments.isNotEmpty()) {
            column.add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
                border = JBUI.Borders.emptyBottom(4)
                turn.attachments.forEach { add(attachmentChip(it.path, it.label)); add(Box.createHorizontalStrut(JBUI.scale(4))) }
            })
        }
        userText.alignmentX = LEFT_ALIGNMENT
        column.add(userText)
        column.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.emptyTop(2)
            add(userTime, BorderLayout.EAST)
        })
        bubble.add(column, BorderLayout.CENTER)
        return bubble
    }

    private fun attachmentChip(path: String, label: String) =
        RoundedPanel(BorderLayout(JBUI.scale(4), 0), arc = 8, fill = { CursorUi.chipBackground }).apply {
            border = JBUI.Borders.empty(1, 6)
            toolTipText = path
            add(JBLabel(label, FileTypeManager.getInstance().getFileTypeByFileName(File(path).name).icon, JBLabel.LEFT).apply {
                font = CursorUi.smallFont()
            }, BorderLayout.CENTER)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val p = project ?: return
                    val vf = LocalFileSystem.getInstance().findFileByPath(path) ?: return
                    OpenFileDescriptor(p, vf).navigate(true)
                }
            })
        }

    private fun bubbleInsets(): Int = JBUI.scale(24) + JBUI.scale(4)

    private fun userNaturalWidth(): Int {
        val fm = userText.getFontMetrics(userText.font)
        val text = turn.displayPrompt.lines().maxOfOrNull { fm.stringWidth(it) } ?: 0
        val chips = if (turn.attachments.isEmpty()) 0
            else turn.attachments.sumOf { fm.stringWidth(it.label) + JBUI.scale(36) }
        return maxOf(text, chips, userTime.preferredSize.width) + bubbleInsets()
    }

    private fun agentNaturalWidth(): Int {
        if (toolActivityPanel.isVisible || planPanel.isVisible || finalResponsePanel.hasCode() ||
            (thinkingPanel.isVisible && thinkingPanel.isExpanded)) return Int.MAX_VALUE
        val fm = getFontMetrics(CursorUi.bodyFont())
        val text = finalResponsePanel.getText().lines().maxOfOrNull { line ->
            val plain = line.replace(Regex("[*_`#>]"), "").trim()
            val indent = if (Regex("^\\s*([-*+]|\\d+\\.)\\s").containsMatchIn(line)) JBUI.scale(24) else 0
            (fm.stringWidth(plain) * 1.08).toInt() + indent
        } ?: 0
        val chrome = maxOf(
            if (activityRow.isVisible) activityRow.preferredSize.width else 0,
            if (footer.isVisible) footer.preferredSize.width else 0,
            if (thinkingPanel.isVisible) thinkingPanel.preferredSize.width else 0,
        )
        return maxOf(text, chrome) + bubbleInsets()
    }

    private fun clock(time: Long): String = SimpleDateFormat("HH:mm").format(Date(time))

    private fun elapsed(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s < 60) "${s}s" else "${s / 60}m ${"%02d".format(s % 60)}s"
    }

    private fun userInitial(): String =
        System.getProperty("user.name")?.firstOrNull { it.isLetter() }?.uppercase() ?: "Y"
}
