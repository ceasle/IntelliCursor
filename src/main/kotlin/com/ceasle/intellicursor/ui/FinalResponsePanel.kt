package com.ceasle.intellicursor.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.Timer
import javax.swing.event.HyperlinkEvent
import javax.swing.text.html.HTMLEditorKit

/**
 * The assistant's reply. Prose renders as themed HTML; fenced code renders as a read-only
 * editor with Copy and Insert actions, matching how Copilot presents suggested code.
 */
class FinalResponsePanel(private val project: Project? = null) : JPanel() {
    private var rawText = ""
    private val blocks = mutableListOf<Pair<MarkdownRenderer.Segment, JComponent>>()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        isVisible = false
    }

    fun updateText(text: String) {
        if (text == rawText) return
        rawText = text
        isVisible = text.isNotBlank()

        val segments = MarkdownRenderer.split(text)
        var i = 0
        while (i < segments.size) {
            val seg = segments[i]
            val existing = blocks.getOrNull(i)
            if (existing != null && existing.first::class == seg::class) {
                if (existing.first != seg) update(existing.second, seg)
                blocks[i] = seg to existing.second
            } else {
                while (blocks.size > i) remove(blocks.removeAt(blocks.size - 1).second)
                val c = create(seg)
                blocks += seg to c
                add(c)
            }
            i++
        }
        while (blocks.size > segments.size) remove(blocks.removeAt(blocks.size - 1).second)
        revalidate()
        repaint()
    }

    fun getText(): String = rawText

    /** True once the reply contains a fenced code block, which wants the full bubble width. */
    fun hasCode(): Boolean = blocks.any { it.first is MarkdownRenderer.Code }

    private fun create(seg: MarkdownRenderer.Segment): JComponent = when (seg) {
        is MarkdownRenderer.Prose -> ProsePane().also { it.render(seg.markdown) }
        is MarkdownRenderer.Code -> CodeBlock(project, seg)
    }.leftAligned()

    private fun update(c: JComponent, seg: MarkdownRenderer.Segment) {
        when (seg) {
            is MarkdownRenderer.Prose -> (c as ProsePane).render(seg.markdown)
            is MarkdownRenderer.Code -> (c as CodeBlock).update(seg)
        }
    }
}

private class ProsePane : WrapEditorPane() {
    init {
        editorKit = HTMLEditorKit()
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty(2, 0)
        putClientProperty(HONOR_DISPLAY_PROPERTIES, true)
        font = CursorUi.bodyFont()
        addHyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) e.url?.let { BrowserUtil.browse(it) }
        }
    }

    fun render(markdown: String) {
        text = "<html><head><style>${css()}</style></head><body>${MarkdownRenderer.toHtml(markdown)}</body></html>"
    }

    private fun css(): String {
        val fg = UIUtil.colorToHex(CursorUi.foreground)
        val muted = UIUtil.colorToHex(CursorUi.secondary)
        val chip = UIUtil.colorToHex(CursorUi.chipBackground)
        val link = UIUtil.colorToHex(CursorUi.accent)
        val size = CursorUi.bodyFont().size
        return """
            body { color: #$fg; font-size: ${size}pt; margin: 0; }
            p { margin-top: 0; margin-bottom: 6px; }
            h2, h3, h4, h5, h6 { margin-top: 8px; margin-bottom: 4px; }
            h2 { font-size: ${size + 3}pt; } h3 { font-size: ${size + 1}pt; } h4, h5, h6 { font-size: ${size}pt; }
            ul, ol { margin-top: 0; margin-bottom: 6px; margin-left: 18px; }
            li { margin-bottom: 2px; }
            code { font-family: monospace; background-color: #$chip; font-size: ${size - 1}pt; }
            a { color: #$link; }
            blockquote { color: #$muted; margin-left: 8px; }
        """.trimIndent()
    }
}

private class CodeBlock(private val project: Project?, private var seg: MarkdownRenderer.Code) :
    RoundedPanel(BorderLayout(), arc = 8, fill = { CursorUi.codeBackground }, outline = { CursorUi.subtleBorder }) {

    private val languageLabel = CursorUi.secondaryLabel(seg.language.ifBlank { "code" })
    private val editorField: EditorTextField? = createEditor()
    private val fallback: JTextArea? = if (editorField == null) JTextArea(seg.code).apply {
        isEditable = false
        font = CursorUi.codeFont()
        isOpaque = false
        border = JBUI.Borders.empty(6, 10, 8, 10)
    } else null
    private val copyButton: IconButton = IconButton(AllIcons.Actions.Copy, "Copy") { copyCode() }

    init {
        border = JBUI.Borders.empty(1)
        val toolbar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(3, 10, 0, 4)
            add(languageLabel)
            add(Box.createHorizontalGlue())
            if (project != null) add(IconButton(AllIcons.Actions.MoveTo2, "Insert at caret") { insertAtCaret() })
            add(copyButton)
        }
        add(toolbar, BorderLayout.NORTH)
        add(editorField ?: fallback!!, BorderLayout.CENTER)
        alignmentX = LEFT_ALIGNMENT
    }


    fun update(next: MarkdownRenderer.Code) {
        seg = next
        languageLabel.text = next.language.ifBlank { "code" }
        editorField?.let { if (it.text != next.code) it.text = next.code }
        fallback?.text = next.code
        revalidate()
    }

    private fun createEditor(): EditorTextField? {
        if (ApplicationManager.getApplication() == null) return null
        return EditorTextField(seg.code, project, fileTypeFor(seg.language)).apply {
            setOneLineMode(false)
            isViewer = true
            border = JBUI.Borders.empty(2, 6, 6, 6)
            addSettingsProvider { editor: EditorEx ->
                editor.settings.apply {
                    isLineNumbersShown = false
                    isFoldingOutlineShown = false
                    isUseSoftWraps = false
                    additionalLinesCount = 0
                    additionalColumnsCount = 0
                    isCaretRowShown = false
                }
                editor.setHorizontalScrollbarVisible(true)
                editor.setVerticalScrollbarVisible(false)
                editor.backgroundColor = CursorUi.codeBackground
                editor.setBorder(null)
                editor.gutterComponentEx.isVisible = false
            }
        }
    }

    private fun copyCode() {
        CopyPasteManager.getInstance().setContents(StringSelection(seg.code))
        copyButton.icon = AllIcons.Actions.Checked
        Timer(1500) { copyButton.icon = AllIcons.Actions.Copy }.apply { isRepeats = false; start() }
    }

    private fun insertAtCaret() {
        val p = project ?: return
        val editor = FileEditorManager.getInstance(p).selectedTextEditor ?: return
        WriteCommandAction.runWriteCommandAction(p, "Insert Cursor Suggestion", null, {
            val caret = editor.caretModel.primaryCaret
            if (caret.hasSelection()) {
                editor.document.replaceString(caret.selectionStart, caret.selectionEnd, seg.code)
            } else {
                editor.document.insertString(caret.offset, seg.code)
            }
        })
    }

    private fun fileTypeFor(language: String): FileType {
        val ext = when (language.lowercase()) {
            "kotlin" -> "kt"; "java" -> "java"; "python", "py" -> "py"
            "javascript", "js" -> "js"; "typescript", "ts" -> "ts"; "tsx" -> "tsx"; "jsx" -> "jsx"
            "bash", "shell", "sh", "zsh", "console" -> "sh"; "yaml", "yml" -> "yml"
            "rust" -> "rs"; "go", "golang" -> "go"; "ruby" -> "rb"; "csharp", "c#" -> "cs"
            "markdown", "md" -> "md"; "" -> "txt"
            else -> language.lowercase()
        }
        return FileTypeManager.getInstance().getFileTypeByExtension(ext).takeUnless { it.isBinary }
            ?: PlainTextFileType.INSTANCE
    }
}
