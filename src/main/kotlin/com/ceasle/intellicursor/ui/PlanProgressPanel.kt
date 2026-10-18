package com.ceasle.intellicursor.ui

import com.ceasle.intellicursor.protocol.PlanEntry
import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JPanel

/** The agent's to-do list: a quiet outlined block with a progress count and one line per step. */
class PlanProgressPanel : RoundedPanel(BorderLayout(), arc = 8, outline = { CursorUi.subtleBorder }) {
    private val body = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    init {
        border = JBUI.Borders.empty(8, 10)
        alignmentX = LEFT_ALIGNMENT
        add(body, BorderLayout.CENTER)
        isVisible = false
    }


    fun updatePlan(entries: List<PlanEntry>) {
        body.removeAll()
        isVisible = entries.isNotEmpty()
        if (entries.isEmpty()) return

        val done = entries.count { it.status.equals("completed", true) }
        body.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(4)
            add(JBLabel("Plan").apply { font = CursorUi.boldFont() }, BorderLayout.WEST)
            add(CursorUi.secondaryLabel("$done of ${entries.size} done"), BorderLayout.EAST)
        })

        for (entry in entries) {
            val status = entry.status.lowercase()
            body.add(JBLabel(entry.content).apply {
                alignmentX = LEFT_ALIGNMENT
                border = JBUI.Borders.empty(2, 0)
                iconTextGap = JBUI.scale(8)
                icon = when (status) {
                    "completed" -> AllIcons.Actions.Checked
                    "in_progress" -> AnimatedIcon.Default.INSTANCE
                    else -> AllIcons.General.TodoDefault
                }
                foreground = if (status == "completed") CursorUi.secondary else CursorUi.foreground
                if (status == "in_progress") font = CursorUi.boldFont()
                if (entry.priority.equals("high", true) && status != "completed") toolTipText = "High priority"
            })
        }
        revalidate()
        repaint()
    }
}
