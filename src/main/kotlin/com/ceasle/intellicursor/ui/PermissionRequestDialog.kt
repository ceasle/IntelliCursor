package com.ceasle.intellicursor.ui

import com.ceasle.intellicursor.protocol.PermissionOption
import com.ceasle.intellicursor.protocol.RequestPermissionParams
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JTextArea

class PermissionRequestDialog(
    project: Project? = null,
    val params: RequestPermissionParams
) : DialogWrapper(project, true) {

    var selectedOptionId: String? = null
        private set

    var isCancelled: Boolean = false
        private set

    init {
        title = "Cursor Permission Request"
        init()
    }

    public override fun createCenterPanel(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout(0, 10)).apply {
            border = JBUI.Borders.empty(12)
        }

        val headerLabel = JBLabel("The Cursor Agent requests permission to perform this action:").apply {
            font = font.deriveFont(Font.BOLD)
        }
        panel.add(headerLabel, BorderLayout.NORTH)

        val tool = params.toolCall
        val sb = StringBuilder()
        sb.append("Tool: ").append(tool.kind?.uppercase() ?: "EXECUTE").append("\n")
        sb.append("Action: ").append(tool.title ?: tool.name ?: "Operation").append("\n")

        val locations = tool.locations
        if (!locations.isNullOrEmpty()) {
            sb.append("\nLocations:\n")
            for (loc in locations) {
                val lineStr = if (loc.line != null) ":${loc.line}" else ""
                sb.append("  • ").append(loc.path).append(lineStr).append("\n")
            }
        }

        if (!tool.content.isNullOrEmpty()) {
            sb.append("\nContent:\n")
            for (item in tool.content) {
                sb.append(item.toString()).append("\n")
            }
        } else if (!tool.rawInput.isNullOrEmpty()) {
            sb.append("\nInput:\n")
            tool.rawInput.forEach { (k, v) ->
                sb.append("  $k: $v\n")
            }
        }

        val textArea = JTextArea(sb.toString().trimEnd()).apply {
            isEditable = false
            background = panel.background
            font = JBUI.Fonts.label()
            border = JBUI.Borders.empty(8)
        }

        val scrollPane = JBScrollPane(textArea).apply {
            preferredSize = Dimension(480, 180)
            border = JBUI.Borders.customLine(JBColor.border())
        }
        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    public override fun createActions(): Array<Action> {
        val actions = mutableListOf<Action>()
        for (option in params.options) {
            actions.add(object : DialogWrapperAction(option.name) {
                override fun doAction(e: ActionEvent?) {
                    selectedOptionId = option.optionId
                    isCancelled = false
                    close(OK_EXIT_CODE)
                }
            })
        }
        val cancel = object : DialogWrapperAction("Cancel") {
            override fun doAction(e: ActionEvent?) {
                isCancelled = true
                selectedOptionId = null
                close(CANCEL_EXIT_CODE)
            }
        }
        actions.add(cancel)
        return actions.toTypedArray()
    }

    public override fun doCancelAction() {
        isCancelled = true
        selectedOptionId = null
        super.doCancelAction()
    }

    fun selectOption(optionId: String) {
        selectedOptionId = optionId
        isCancelled = false
        close(OK_EXIT_CODE)
    }
}
