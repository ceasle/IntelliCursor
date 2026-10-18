package com.ceasle.intellicursor.ui

import com.ceasle.intellicursor.service.ToolCallState
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JPanel

/**
 * Agent steps as a compact log: one line per tool call with its status, what it did, and
 * the file it touched. Lines with output can be expanded; file chips open the file.
 */
class ToolActivityPanel(private val project: Project? = null) : JPanel() {
    private val rows = LinkedHashMap<String, ToolRow>()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.emptyBottom(4)
    }

    fun updateTools(tools: Collection<ToolCallState>) {
        val ids = tools.map { it.toolCallId }.toSet()
        rows.keys.filter { it !in ids }.forEach { remove(rows.remove(it)) }
        for (tool in tools) {
            val row = rows.getOrPut(tool.toolCallId) { ToolRow(project).also { add(it) } }
            row.bind(tool)
        }
        isVisible = rows.isNotEmpty()
        revalidate()
        repaint()
    }
}

private class ToolRow(private val project: Project?) : JPanel(BorderLayout()) {
    private val statusLabel = JBLabel()
    private val titleLabel = JBLabel().apply { iconTextGap = JBUI.scale(6) }
    private val chips = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply { isOpaque = false }
    private val expander = JBLabel(AllIcons.General.ArrowRight).apply { isVisible = false }
    private val output = WrapTextArea().apply {
        wrapStyleWord = false
        font = CursorUi.codeFont()
        foreground = CursorUi.secondary
    }
    private val outputWrapper = RoundedPanel(BorderLayout(), arc = 6, fill = { CursorUi.codeBackground }).apply {
        border = JBUI.Borders.empty(6, 8)
        add(output, BorderLayout.CENTER)
    }
    private val outputHolder = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(2, 22, 4, 0)
        add(outputWrapper, BorderLayout.CENTER)
        isVisible = false
    }
    private var expanded = false

    init {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        border = JBUI.Borders.empty(2, 0)

        val line = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(statusLabel, BorderLayout.WEST)
            add(JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
                isOpaque = false
                add(titleLabel, BorderLayout.WEST)
                add(chips, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
            add(expander, BorderLayout.EAST)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (!expander.isVisible) return
                    expanded = !expanded
                    expander.icon = if (expanded) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
                    outputHolder.isVisible = expanded
                    this@ToolRow.revalidate()
                }
            })
        }
        add(line, BorderLayout.NORTH)
        add(outputHolder, BorderLayout.CENTER)
    }

    override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)

    fun bind(tool: ToolCallState) {
        statusLabel.icon = statusIcon(tool.status)
        statusLabel.toolTipText = tool.status.replace('_', ' ')
        titleLabel.icon = kindIcon(tool.kind)
        titleLabel.text = tool.title.ifBlank { tool.kind }
        titleLabel.foreground = if (tool.status.equals("failed", true)) CursorUi.danger else CursorUi.foreground

        chips.removeAll()
        tool.locations.orEmpty().take(3).forEach { loc -> chips.add(fileChip(loc.path, loc.line)) }
        val more = tool.locations.orEmpty().size - 3
        if (more > 0) chips.add(CursorUi.secondaryLabel("+$more"))

        val details = outputText(tool)
        output.text = details
        expander.isVisible = details.isNotBlank()
        outputHolder.isVisible = expanded && details.isNotBlank()
        if (expander.isVisible) line().cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private fun line() = getComponent(0) as JPanel

    private fun fileChip(path: String, line: Int?): JPanel {
        val name = File(path).name + (line?.let { ":$it" } ?: "")
        return RoundedPanel(BorderLayout(), arc = 8, fill = { CursorUi.chipBackground }).apply {
            border = JBUI.Borders.empty(0, 6)
            toolTipText = path
            add(JBLabel(name).apply { font = CursorUi.smallFont() }, BorderLayout.CENTER)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = openFile(path, line)
            })
        }
    }

    private fun openFile(path: String, line: Int?) {
        val p = project ?: return
        val file = File(path).let { if (it.isAbsolute) it else File(p.basePath ?: "", path) }
        val vf = LocalFileSystem.getInstance().findFileByIoFile(file) ?: return
        OpenFileDescriptor(p, vf, ((line ?: 1) - 1).coerceAtLeast(0), 0).navigate(true)
    }

    private fun outputText(tool: ToolCallState): String {
        val raw = tool.rawOutput ?: return ""
        val stdout = raw["stdout"]?.toString()?.trim().orEmpty()
        val stderr = raw["stderr"]?.toString()?.trim().orEmpty()
        val text = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n")
            .ifBlank { if (raw.isNotEmpty()) raw.toString() else "" }
        return if (text.length > 2000) text.take(2000) + "\n…" else text
    }

    private fun statusIcon(status: String): Icon = when (status.lowercase()) {
        "completed" -> AllIcons.General.InspectionsOK
        "failed" -> AllIcons.General.Error
        "in_progress" -> AnimatedIcon.Default.INSTANCE
        else -> AllIcons.General.TodoDefault
    }

    private fun kindIcon(kind: String): Icon? = when (kind.lowercase()) {
        "search" -> AllIcons.Actions.Find
        "read" -> AllIcons.Actions.Preview
        "edit" -> AllIcons.Actions.Edit
        "delete" -> AllIcons.Actions.GC
        "move" -> AllIcons.Actions.Forward
        "execute", "terminal" -> AllIcons.Nodes.Console
        "fetch" -> AllIcons.General.Web
        "think" -> AllIcons.Actions.IntentionBulb
        else -> null
    }
}
