package com.ceasle.intellicursor.ui

import com.ceasle.intellicursor.protocol.CursorAskQuestionParams
import com.ceasle.intellicursor.protocol.CursorQuestion
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JComponent

class CursorQuestionDialog(
    project: Project? = null,
    val params: CursorAskQuestionParams
) : DialogWrapper(project, true) {

    data class QuestionWidgetHolder(
        val question: CursorQuestion,
        val radioGroup: ButtonGroup?,
        val radioButtons: List<Pair<JBRadioButton, String>>,
        val otherRadioButton: JBRadioButton?,
        val checkBoxes: List<Pair<JBCheckBox, String>>,
        val customTextField: JBTextField?
    )

    val questionHolders = mutableListOf<QuestionWidgetHolder>()

    var answers: Map<String, List<String>> = emptyMap()
        private set

    var isCancelled: Boolean = false
        private set

    init {
        title = params.title?.takeIf { it.isNotBlank() } ?: "Cursor Question"
        init()
    }

    public override fun createCenterPanel(): JComponent {
        questionHolders.clear()
        val mainPanel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(12)
        }

        if (params.questions.isEmpty()) {
            val emptyLabel = JBLabel("No questions to answer.").apply {
                alignmentX = Component.LEFT_ALIGNMENT
            }
            mainPanel.add(emptyLabel)
        } else {
            for ((index, question) in params.questions.withIndex()) {
                if (index > 0) {
                    mainPanel.add(Box.createVerticalStrut(12))
                }
                val qPanel = createQuestionPanel(question)
                mainPanel.add(qPanel)
            }
        }

        val scrollPane = JBScrollPane(mainPanel).apply {
            preferredSize = Dimension(500, 320)
            border = JBUI.Borders.customLine(JBColor.border())
        }
        return scrollPane
    }

    private fun createQuestionPanel(question: CursorQuestion): JBPanel<*> {
        val qPanel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(10)
            )
        }

        val promptLabel = JBLabel(question.prompt).apply {
            font = font.deriveFont(Font.BOLD)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        qPanel.add(promptLabel)
        qPanel.add(Box.createVerticalStrut(8))

        var radioGroup: ButtonGroup? = null
        val radioButtons = mutableListOf<Pair<JBRadioButton, String>>()
        var otherRadioButton: JBRadioButton? = null
        val checkBoxes = mutableListOf<Pair<JBCheckBox, String>>()
        var customTextField: JBTextField? = null

        if (question.options.isEmpty()) {
            val field = JBTextField().apply {
                emptyText.text = "Enter your answer here"
                alignmentX = Component.LEFT_ALIGNMENT
            }
            customTextField = field
            qPanel.add(field)
        } else if (question.allowMultiple) {
            for (opt in question.options) {
                val cb = JBCheckBox(opt.label).apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                }
                checkBoxes.add(cb to opt.id)
                qPanel.add(cb)
                qPanel.add(Box.createVerticalStrut(4))
            }
            val otherPanel = JBPanel<JBPanel<*>>(BorderLayout(6, 0)).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.emptyTop(4)
            }
            val otherLabel = JBLabel("Other / Custom:")
            val field = JBTextField().apply {
                emptyText.text = "Additional answer (optional)"
            }
            customTextField = field
            otherPanel.add(otherLabel, BorderLayout.WEST)
            otherPanel.add(field, BorderLayout.CENTER)
            qPanel.add(otherPanel)
        } else {
            val bg = ButtonGroup()
            radioGroup = bg

            for (opt in question.options) {
                val rb = JBRadioButton(opt.label).apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                }
                bg.add(rb)
                radioButtons.add(rb to opt.id)
                qPanel.add(rb)
                qPanel.add(Box.createVerticalStrut(4))
            }

            // Default to first option
            radioButtons.firstOrNull()?.first?.isSelected = true

            val otherPanel = JBPanel<JBPanel<*>>(BorderLayout(6, 0)).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.emptyTop(4)
            }
            val otherRb = JBRadioButton("Other:").apply {
                alignmentX = Component.LEFT_ALIGNMENT
            }
            otherRadioButton = otherRb
            bg.add(otherRb)

            val field = JBTextField().apply {
                emptyText.text = "Type custom answer"
            }
            customTextField = field
            field.addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusGained(e: java.awt.event.FocusEvent?) {
                    otherRb.isSelected = true
                }
            })

            otherPanel.add(otherRb, BorderLayout.WEST)
            otherPanel.add(field, BorderLayout.CENTER)
            qPanel.add(otherPanel)
        }

        val holder = QuestionWidgetHolder(
            question = question,
            radioGroup = radioGroup,
            radioButtons = radioButtons,
            otherRadioButton = otherRadioButton,
            checkBoxes = checkBoxes,
            customTextField = customTextField
        )
        questionHolders.add(holder)
        return qPanel
    }

    fun collectAnswers(): Map<String, List<String>> {
        val result = mutableMapOf<String, List<String>>()
        for (holder in questionHolders) {
            val q = holder.question
            val answersList = mutableListOf<String>()

            if (q.options.isEmpty()) {
                val text = holder.customTextField?.text?.trim()
                if (!text.isNullOrEmpty()) {
                    answersList.add(text)
                }
            } else if (q.allowMultiple) {
                for ((cb, optId) in holder.checkBoxes) {
                    if (cb.isSelected) {
                        answersList.add(optId)
                    }
                }
                val customText = holder.customTextField?.text?.trim()
                if (!customText.isNullOrEmpty()) {
                    answersList.add(customText)
                }
            } else {
                if (holder.otherRadioButton?.isSelected == true) {
                    val customText = holder.customTextField?.text?.trim()
                    if (!customText.isNullOrEmpty()) {
                        answersList.add(customText)
                    }
                } else {
                    for ((rb, optId) in holder.radioButtons) {
                        if (rb.isSelected) {
                            answersList.add(optId)
                            break
                        }
                    }
                }
            }

            result[q.id] = answersList
        }
        return result
    }

    fun setAnswer(questionId: String, selectedOptionIds: List<String>) {
        val holder = questionHolders.find { it.question.id == questionId } ?: return
        if (holder.question.allowMultiple) {
            for ((cb, optId) in holder.checkBoxes) {
                cb.isSelected = selectedOptionIds.contains(optId)
            }
        } else {
            val selectedId = selectedOptionIds.firstOrNull()
            var matched = false
            for ((rb, optId) in holder.radioButtons) {
                if (optId == selectedId) {
                    rb.isSelected = true
                    matched = true
                    break
                }
            }
            if (!matched && selectedId != null) {
                holder.otherRadioButton?.isSelected = true
                holder.customTextField?.text = selectedId
            }
        }
    }

    fun setCustomAnswer(questionId: String, text: String) {
        val holder = questionHolders.find { it.question.id == questionId } ?: return
        if (holder.otherRadioButton != null) {
            holder.otherRadioButton.isSelected = true
        }
        holder.customTextField?.text = text
    }

    public override fun doOKAction() {
        answers = collectAnswers()
        isCancelled = false
        super.doOKAction()
    }

    public override fun doCancelAction() {
        isCancelled = true
        answers = emptyMap()
        super.doCancelAction()
    }
}
