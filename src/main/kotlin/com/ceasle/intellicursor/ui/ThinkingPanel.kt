package com.ceasle.intellicursor.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel

/**
 * Collapsed by default: a single quiet line ("Thinking…" while streaming, "Thought for 4s"
 * after). Expanding reveals the reasoning in muted text behind a thin left rule.
 */
class ThinkingPanel : JPanel(BorderLayout()) {
    private val headerLabel = JBLabel().apply {
        foreground = CursorUi.secondary
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        iconTextGap = JBUI.scale(4)
        border = JBUI.Borders.empty(2, 0)
    }
    private val contentArea = WrapTextArea().apply {
        foreground = CursorUi.secondary
        font = CursorUi.smallFont()
    }
    private val contentWrapper = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.compound(
            JBUI.Borders.empty(2, 6, 4, 0),
            JBUI.Borders.customLineLeft(CursorUi.border),
            JBUI.Borders.emptyLeft(10)
        )
        add(contentArea, BorderLayout.CENTER)
    }

    var isExpanded: Boolean = false
        private set
    private var thinking = false
    private var startedAt = 0L
    private var finishedAt = 0L

    init {
        isOpaque = false
        border = JBUI.Borders.emptyBottom(4)
        headerLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) = toggle()
        })
        add(headerLabel, BorderLayout.NORTH)
        refreshHeader()
    }

    fun updateContent(text: String, isThinking: Boolean) {
        if (startedAt == 0L) startedAt = System.currentTimeMillis()
        if (thinking && !isThinking) finishedAt = System.currentTimeMillis()
        if (!isThinking && finishedAt == 0L) finishedAt = System.currentTimeMillis()
        thinking = isThinking
        if (contentArea.text != text) contentArea.text = text
        refreshHeader()
    }

    fun toggle() {
        isExpanded = !isExpanded
        if (isExpanded) add(contentWrapper, BorderLayout.CENTER) else remove(contentWrapper)
        refreshHeader()
        revalidate()
        repaint()
    }

    private fun refreshHeader() {
        // The turn's status line already spins while Cursor thinks; keep this header still.
        headerLabel.icon = if (isExpanded) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        headerLabel.text = if (thinking) "Reasoning" else {
            val seconds = ((finishedAt - startedAt) / 1000).coerceAtLeast(1)
            "Thought for ${seconds}s"
        }
    }

    fun getContentText(): String = contentArea.text
    fun getHeaderText(): String = headerLabel.text
}
