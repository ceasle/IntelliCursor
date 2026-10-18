package com.ceasle.intellicursor.ui

import com.ceasle.intellicursor.protocol.SlashCommandInfo
import com.ceasle.intellicursor.service.PromptAttachment
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.WrapLayout
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Point
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** A command the plugin handles itself, listed with the agent's commands under "/". */
data class LocalCommand(val name: String, val description: String, val icon: Icon? = null, val run: () -> Unit)

/** A project file offered under "@". [path] is absolute; [relativePath] is shown beside the name. */
data class FileSuggestion(val path: String, val relativePath: String)

/**
 * Chat input in the Copilot style: one rounded field holding the context chips, an
 * auto-growing prompt, and a bottom row with the mode and model pickers and a send button
 * that turns into stop while Cursor is replying.
 *
 * Enter sends, Shift+Enter adds a line. "/" lists commands; "@" attaches project files.
 */
class MessageComposer(
    var onSend: ((String) -> Unit)? = null
) : JPanel(BorderLayout()) {

    var onStop: (() -> Unit)? = null
    var onToggleContext: ((Boolean) -> Unit)? = null
    /** Commands advertised by the Cursor CLI. */
    var commands: List<SlashCommandInfo> = emptyList()
    /** Commands the plugin runs itself, shown first. */
    var localCommands: List<LocalCommand> = emptyList()
    /** Finds project files for "@"; null disables file mentions. */
    var fileSearch: ((String) -> List<FileSuggestion>)? = null

    val textArea = JBTextArea().apply {
        rows = 1
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        font = CursorUi.bodyFont()
        border = JBUI.Borders.empty(2, 2)
        emptyText.text = "Message Cursor, @ to add files, / for commands"
    }

    private var running = false
    val isRunning: Boolean get() = running
    val sendButton = IconButton(SendIcon { textArea.text.isNotBlank() || running }, "Send (Enter)") { clickSendOrStop() }

    /** Left side of the bottom row; the tool window puts the mode and model pickers here. */
    val controls = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false }

    private val contextChip = ContextChip { included -> onToggleContext?.invoke(included) }
    private val attachmentList = mutableListOf<PromptAttachment>()
    /** Files added with "@" for the next message. */
    val attachments: List<PromptAttachment> get() = attachmentList.toList()

    private val contextRow = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(3))).apply {
        isOpaque = false
        border = JBUI.Borders.emptyBottom(2)
        isVisible = false
    }
    private var contextFileVisible = false

    private val box = RoundedPanel(BorderLayout(), arc = 12,
        fill = { CursorUi.inputBackground },
        outline = { if (textArea.hasFocus()) CursorUi.focus else CursorUi.border })

    private var popup: JBPopup? = null
    private var popupList: JBList<Suggestion>? = null
    private var popupKind: Char? = null

    init {
        isOpaque = false
        border = JBUI.Borders.empty(6, 10, 10, 10)

        val scroll = object : JBScrollPane(textArea) {
            override fun getPreferredSize(): Dimension {
                val line = textArea.getFontMetrics(textArea.font).height
                val h = textArea.preferredSize.height.coerceIn(line * 2, line * 10)
                return Dimension(super.getPreferredSize().width, h + JBUI.scale(4))
            }
        }.apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }

        val mentionButton = TextButton("@", "Add a file (@)") { startMention() }
        val bottom = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.emptyTop(6)
            add(mentionButton)
            add(Box.createHorizontalStrut(JBUI.scale(2)))
            add(controls)
            add(Box.createHorizontalGlue())
            add(sendButton)
        }

        box.border = JBUI.Borders.empty(8, 10, 6, 6)
        box.add(contextRow, BorderLayout.NORTH)
        box.add(scroll, BorderLayout.CENTER)
        box.add(bottom, BorderLayout.SOUTH)
        box.cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
        box.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { textArea.requestFocusInWindow() }
        })
        add(box, BorderLayout.CENTER)

        textArea.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = box.repaint()
            override fun focusLost(e: FocusEvent) = box.repaint()
        })
        textArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onTextChanged()
            override fun removeUpdate(e: DocumentEvent) = onTextChanged()
            override fun changedUpdate(e: DocumentEvent) = onTextChanged()
        })
        textArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) = handleKey(e)
        })
        rebuildContextRow()
    }

    private fun handleKey(e: KeyEvent) {
        val list = popupList
        if (popup?.isVisible == true && list != null) {
            when (e.keyCode) {
                KeyEvent.VK_DOWN -> { list.selectedIndex = (list.selectedIndex + 1).coerceAtMost(list.model.size - 1); list.ensureIndexIsVisible(list.selectedIndex); e.consume(); return }
                KeyEvent.VK_UP -> { list.selectedIndex = (list.selectedIndex - 1).coerceAtLeast(0); list.ensureIndexIsVisible(list.selectedIndex); e.consume(); return }
                KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> { list.selectedValue?.let { apply(it) }; e.consume(); return }
                KeyEvent.VK_ESCAPE -> { hidePopup(); e.consume(); return }
            }
        }
        if (e.keyCode == KeyEvent.VK_BACK_SPACE && textArea.text.isEmpty() && attachmentList.isNotEmpty()) {
            removeAttachment(attachmentList.last())
            e.consume()
            return
        }
        if (e.keyCode == KeyEvent.VK_ENTER) {
            if (e.isShiftDown) {
                textArea.insert("\n", textArea.caretPosition)
            } else if (!running) {
                submit()
            }
            e.consume()
        }
    }

    private fun onTextChanged() {
        sendButton.repaint()
        revalidate()
        // The caret moves after the document event, so look at it once things settle.
        SwingUtilities.invokeLater { updateSuggestions() }
    }

    private fun updateSuggestions() {
        val text = textArea.text
        if (text.startsWith("/") && !text.contains(' ') && !text.contains('\n')) {
            showPopup('/', commandSuggestions(text.removePrefix("/")))
            return
        }
        val mention = mentionAtCaret()
        if (mention != null && fileSearch != null) {
            showPopup('@', fileSuggestions(mention.second))
            return
        }
        hidePopup()
    }

    /** The "@query" being typed at the caret, as its start offset and query text. */
    private fun mentionAtCaret(): Pair<Int, String>? {
        val text = textArea.text
        val caret = textArea.caretPosition.coerceIn(0, text.length)
        var i = caret - 1
        while (i >= 0 && !text[i].isWhitespace() && text[i] != '@') i--
        if (i < 0 || text[i] != '@') return null
        if (i > 0 && !text[i - 1].isWhitespace()) return null
        return i to text.substring(i + 1, caret)
    }

    private fun commandSuggestions(query: String): List<Suggestion> {
        val local = localCommands.filter { it.name.startsWith(query, ignoreCase = true) }.map { cmd ->
            Suggestion("/${cmd.name}", cmd.description, cmd.icon ?: AllIcons.Actions.Lightning) {
                textArea.text = ""
                cmd.run()
            }
        }
        val localNames = localCommands.map { it.name }.toSet()
        val agent = commands.filter { it.name !in localNames && it.name.contains(query, ignoreCase = true) }
            .sortedBy { if (it.name.startsWith(query, ignoreCase = true)) 0 else 1 }
            .map { cmd ->
                Suggestion("/${cmd.name}", cmd.description, AllIcons.Nodes.Function) {
                    textArea.text = "/${cmd.name} "
                    textArea.caretPosition = textArea.text.length
                }
            }
        return local + agent
    }

    private fun fileSuggestions(query: String): List<Suggestion> =
        fileSearch?.invoke(query).orEmpty().take(40).map { file ->
            val name = File(file.path).name
            val folder = file.relativePath.removeSuffix(name).removeSuffix("/")
            Suggestion(name, folder, FileTypeManager.getInstance().getFileTypeByFileName(name).icon) {
                removeMentionToken()
                addAttachment(PromptAttachment(file.path, name))
            }
        }

    private fun removeMentionToken() {
        val (start, query) = mentionAtCaret() ?: return
        val end = start + 1 + query.length
        textArea.replaceRange("", start, end)
        textArea.caretPosition = start
    }

    /** Insert "@" at the caret and open the file list, for people who reach for the mouse. */
    fun startMention() {
        val text = textArea.text
        val caret = textArea.caretPosition
        val needsSpace = caret > 0 && !text[caret - 1].isWhitespace()
        textArea.insert(if (needsSpace) " @" else "@", caret)
        textArea.requestFocusInWindow()
    }

    private fun showPopup(kind: Char, items: List<Suggestion>) {
        if (items.isEmpty()) { hidePopup(); return }
        val existing = popupList
        if (popup?.isVisible == true && existing != null && popupKind == kind) {
            existing.setListData(items.toTypedArray())
            existing.selectedIndex = 0
            popup?.pack(true, true)
            reposition()
            return
        }
        hidePopup()
        val list = JBList(items).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            selectedIndex = 0
            visibleRowCount = 9
            cellRenderer = object : ColoredListCellRenderer<Suggestion>() {
                override fun customizeCellRenderer(l: JList<out Suggestion>, v: Suggestion, i: Int, sel: Boolean, focus: Boolean) {
                    icon = v.icon
                    append(v.title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    if (v.detail.isNotBlank()) {
                        val detail = v.detail.removeSuffix(" (builtin skill)").removeSuffix(" (global)")
                        append("  " + if (detail.length > 80) detail.take(79) + "…" else detail, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
            }
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) { selectedValue?.let { apply(it) } }
            })
        }
        popupList = list
        popupKind = kind
        val created = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(JBScrollPane(list).apply { border = JBUI.Borders.empty() }, null)
            .setRequestFocus(false)
            .setFocusable(false)
            .setCancelOnClickOutside(true)
            .setMinSize(Dimension(box.width, 0))
            .createPopup()
        popup = created
        created.show(RelativePoint(box, Point(0, -popupHeight(list))))
    }

    private fun popupHeight(list: JBList<Suggestion>): Int {
        val rows = list.model.size.coerceAtMost(9)
        val rowHeight = if (list.model.size > 0) list.getCellBounds(0, 0)?.height ?: JBUI.scale(24) else JBUI.scale(24)
        return rows * rowHeight + JBUI.scale(8)
    }

    private fun reposition() {
        val list = popupList ?: return
        popup?.setLocation(RelativePoint(box, Point(0, -popupHeight(list))).screenPoint)
    }

    private fun apply(item: Suggestion) {
        hidePopup()
        item.apply()
        textArea.requestFocusInWindow()
    }

    private fun hidePopup() {
        popup?.cancel()
        popup = null
        popupList = null
        popupKind = null
    }

    fun addAttachment(attachment: PromptAttachment) {
        if (attachmentList.none { it.path == attachment.path }) attachmentList += attachment
        rebuildContextRow()
    }

    fun removeAttachment(attachment: PromptAttachment) {
        attachmentList.removeIf { it.path == attachment.path }
        rebuildContextRow()
    }

    fun clearAttachments() {
        attachmentList.clear()
        rebuildContextRow()
    }

    private fun rebuildContextRow() {
        contextRow.removeAll()
        if (contextFileVisible) contextRow.add(contextChip)
        attachmentList.forEach { a -> contextRow.add(AttachmentChip(a) { removeAttachment(a) }) }
        contextRow.isVisible = contextRow.componentCount > 0
        revalidate()
        repaint()
    }

    /** Same as clicking the send/stop button. */
    fun clickSendOrStop() {
        if (running) onStop?.invoke() else submit()
    }

    fun submit() {
        val text = textArea.text.trim()
        if (text.isNotEmpty()) {
            hidePopup()
            textArea.text = ""
            onSend?.invoke(text)
        }
    }

    /** Swap send for stop while a reply streams. */
    fun setRunning(isRunning: Boolean) {
        if (running == isRunning) return
        running = isRunning
        sendButton.icon = if (isRunning) StopIcon() else SendIcon { textArea.text.isNotBlank() || running }
        sendButton.toolTipText = if (isRunning) "Stop" else "Send (Enter)"
    }

    /** Show the file that will be attached to the next prompt, or hide the chip with null. */
    fun setContextFile(name: String?, path: String?, included: Boolean) {
        contextFileVisible = name != null
        if (name != null) contextChip.bind(name, path, included)
        rebuildContextRow()
    }

    fun getText(): String = textArea.text

    fun setText(text: String) {
        textArea.text = text
    }

    fun clear() {
        textArea.text = ""
    }

    fun focusInput() {
        textArea.requestFocusInWindow()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        textArea.isEnabled = enabled
        sendButton.isEnabled = enabled
    }

    private class Suggestion(val title: String, val detail: String, val icon: Icon?, val apply: () -> Unit)
}

/** "Current file" chip with an eye toggle, like Copilot's implicit-context chip. */
private class ContextChip(private val onToggle: (Boolean) -> Unit) :
    RoundedPanel(BorderLayout(JBUI.scale(4), 0), arc = 8, fill = { CursorUi.chipBackground }) {

    private val nameLabel = JBLabel().apply { font = CursorUi.smallFont() }
    private val hintLabel = CursorUi.secondaryLabel("Current file")
    private var included = true
    private val eye = IconButton(AllIcons.Actions.Show, "Don't attach this file") { setIncluded(!included) }

    init {
        border = JBUI.Borders.empty(1, 6, 1, 2)
        add(nameLabel, BorderLayout.WEST)
        add(hintLabel, BorderLayout.CENTER)
        add(eye, BorderLayout.EAST)
        eye.border = JBUI.Borders.empty(1)
    }

    override fun getMaximumSize() = preferredSize

    fun bind(name: String, path: String?, included: Boolean) {
        nameLabel.text = name
        toolTipText = path
        this.included = included
        refresh()
    }

    private fun setIncluded(value: Boolean) {
        included = value
        refresh()
        onToggle(value)
    }

    private fun refresh() {
        nameLabel.foreground = if (included) CursorUi.foreground else CursorUi.secondary
        hintLabel.text = if (included) "Current file" else "Not attached"
        eye.icon = if (included) AllIcons.Actions.Show else AllIcons.Actions.ToggleVisibility
        eye.toolTipText = if (included) "Don't attach this file" else "Attach this file"
        repaint()
    }
}

/** A file added with "@": its icon and name, with a button to take it off again. */
private class AttachmentChip(attachment: PromptAttachment, onRemove: () -> Unit) :
    RoundedPanel(BorderLayout(JBUI.scale(2), 0), arc = 8, fill = { CursorUi.chipBackground }) {
    init {
        border = JBUI.Borders.empty(1, 6, 1, 2)
        toolTipText = attachment.path
        add(JBLabel(attachment.label, FileTypeManager.getInstance().getFileTypeByFileName(attachment.label).icon, JBLabel.LEFT).apply {
            font = CursorUi.smallFont()
            iconTextGap = JBUI.scale(4)
        }, BorderLayout.CENTER)
        add(IconButton(AllIcons.Actions.Close, "Remove") { onRemove() }.apply { border = JBUI.Borders.empty(1) }, BorderLayout.EAST)
    }

    override fun getMaximumSize() = preferredSize
}

/** Borderless text button with the same hover plate as [IconButton]. */
class TextButton(text: String, tooltip: String, private val onClick: () -> Unit) : JBLabel(text) {
    private var hovered = false

    init {
        toolTipText = tooltip
        foreground = CursorUi.secondary
        border = JBUI.Borders.empty(3, 7)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hovered = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hovered = false; repaint() }
            override fun mouseClicked(e: MouseEvent) { if (isEnabled) onClick() }
        })
    }

    override fun paintComponent(g: Graphics) {
        if (hovered && isEnabled) {
            val g2 = CursorUi.antialias(g)
            g2.color = CursorUi.hoverBackground
            g2.fillRoundRect(0, 0, width, height, JBUI.scale(6), JBUI.scale(6))
            g2.dispose()
        }
        super.paintComponent(g)
    }
}
