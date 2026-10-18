package com.ceasle.intellicursor.ui

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingConstants

/**
 * Design tokens for the Cursor tool window. Everything derives from the active IDE theme
 * so the panel blends into IntelliJ the way the Copilot chat does; the only brand colour
 * is the Cursor mark itself.
 */
object CursorUi {
    val background: Color get() = JBColor.namedColor("ToolWindow.background", UIUtil.getPanelBackground())
    val foreground: Color get() = UIUtil.getLabelForeground()
    val secondary: Color get() = JBColor.namedColor("Label.infoForeground", JBColor(0x6C707E, 0x868A91))
    val border: Color get() = JBColor.namedColor("Component.borderColor", JBColor(0xD3D5DB, 0x4E5157))
    val subtleBorder: Color get() = JBColor.namedColor("Borders.color", JBColor(0xEBECF0, 0x393B40))
    val focus: Color get() = JBUI.CurrentTheme.Focus.focusColor()
    val inputBackground: Color get() = JBColor.namedColor("TextField.background", JBColor(0xFFFFFF, 0x2B2D30))
    val codeBackground: Color get() = JBColor.namedColor("EditorPane.background", JBColor(0xF7F8FA, 0x1E1F22))
    val chipBackground: Color get() = JBColor(0xEDF0F5, 0x393B40)
    val hoverBackground: Color get() = JBColor.namedColor("ActionButton.hoverBackground", JBColor(0xDFE1E5, 0x43454A))
    /** Outgoing bubble: a quiet tint of the IDE accent, readable with the normal text colour. */
    val userBubble: Color get() = JBColor(0xD9E6FF, 0x2C3E63)
    /** Incoming bubble: one step off the tool-window background. */
    val agentBubble: Color get() = JBColor(0xFFFFFF, 0x35373C)
    val agentBubbleBorder: Color get() = JBColor(0xE1E3E8, 0x404247)
    val success: Color = JBColor(0x208A3C, 0x5FB865)
    val danger: Color = JBColor(0xDB3B4B, 0xE55765)
    val accent: Color = JBColor(0x3574F0, 0x548AF7)

    val toolWindowIcon: Icon by lazy { IconLoader.getIcon("/icons/cursorToolWindow.svg", CursorUi::class.java) }
    val avatarIcon: Icon by lazy { IconLoader.getIcon("/icons/cursorAvatar.svg", CursorUi::class.java) }
    val roundAvatarIcon: Icon by lazy { IconLoader.getIcon("/icons/cursorAvatarRound.svg", CursorUi::class.java) }
    val logoIcon: Icon by lazy { IconLoader.getIcon("/icons/cursorLogo.svg", CursorUi::class.java) }

    fun bodyFont(): Font = JBFont.label()
    fun smallFont(): Font = JBFont.small()
    fun boldFont(): Font = JBFont.label().asBold()
    fun codeFont(): Font {
        val size = JBFont.label().size2D - 0.5f
        return Font(Font.MONOSPACED, Font.PLAIN, 1).deriveFont(size)
    }

    fun secondaryLabel(text: String = ""): JBLabel = JBLabel(text).apply {
        foreground = secondary
        font = smallFont()
    }

    fun antialias(g: Graphics): Graphics2D = (g.create() as Graphics2D).apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    }
}

/** Transparent panel with a painted rounded fill and optional outline. */
open class RoundedPanel(
    layout: LayoutManager,
    private val arc: Int = 10,
    var fill: (() -> Color?)? = null,
    var outline: (() -> Color?)? = null,
) : JPanel(layout) {
    init {
        isOpaque = false
    }

    /** Never stretch vertically inside a vertical BoxLayout. */
    override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)

    override fun paintComponent(g: Graphics) {
        val g2 = CursorUi.antialias(g)
        try {
            val a = JBUI.scale(arc).toFloat()
            val shape = RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, a, a)
            fill?.invoke()?.let {
                g2.color = it
                g2.fill(shape)
            }
            outline?.invoke()?.let {
                g2.color = it
                g2.stroke = BasicStroke(JBUI.scale(1).toFloat())
                g2.draw(shape)
            }
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

/** Vertical stack that always tracks the viewport width, so wrapped text reflows on resize. */
class WidthTrackingPanel : JPanel(), Scrollable {
    init {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        isOpaque = false
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
        if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = parent?.let { it.height > preferredSize.height } ?: false
}

/** Small borderless icon button that shows a hover plate, like IDE toolbar buttons. */
class IconButton(
    icon: Icon,
    tooltip: String,
    private val onClick: () -> Unit,
) : JBLabel(icon) {
    private var hovered = false

    init {
        toolTipText = tooltip
        isOpaque = false
        border = JBUI.Borders.empty(3)
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
            val a = JBUI.scale(6)
            g2.fillRoundRect(0, 0, width, height, a, a)
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

/** Circular "send" glyph: an up-arrow inside a filled disc. Painted so it scales cleanly. */
class SendIcon(private val enabled: () -> Boolean) : Icon {
    override fun getIconWidth() = JBUI.scale(24)
    override fun getIconHeight() = JBUI.scale(24)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = CursorUi.antialias(g)
        val s = iconWidth.toFloat()
        g2.color = if (enabled()) CursorUi.foreground else CursorUi.border
        g2.fill(java.awt.geom.Ellipse2D.Float(x.toFloat(), y.toFloat(), s, s))
        g2.color = CursorUi.background
        g2.stroke = BasicStroke(s * 0.085f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val cx = x + s / 2
        val path = Path2D.Float().apply {
            moveTo(cx, y + s * 0.70f); lineTo(cx, y + s * 0.30f)
            moveTo(cx - s * 0.17f, y + s * 0.46f); lineTo(cx, y + s * 0.29f); lineTo(cx + s * 0.17f, y + s * 0.46f)
        }
        g2.draw(path)
        g2.dispose()
    }
}

/** Filled square inside a disc: the stop affordance that replaces send while a turn runs. */
class StopIcon : Icon {
    override fun getIconWidth() = JBUI.scale(24)
    override fun getIconHeight() = JBUI.scale(24)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = CursorUi.antialias(g)
        val s = iconWidth.toFloat()
        g2.color = CursorUi.foreground
        g2.fill(java.awt.geom.Ellipse2D.Float(x.toFloat(), y.toFloat(), s, s))
        g2.color = CursorUi.background
        val q = s * 0.32f
        g2.fill(RoundRectangle2D.Float(x + (s - q) / 2, y + (s - q) / 2, q, q, s * 0.08f, s * 0.08f))
        g2.dispose()
    }
}

/** Circle with the user's initial, shown beside their messages. */
class InitialAvatarIcon(private val initial: String, private val size: Int = 20) : Icon {
    override fun getIconWidth() = JBUI.scale(size)
    override fun getIconHeight() = JBUI.scale(size)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = CursorUi.antialias(g)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val s = iconWidth
        g2.color = CursorUi.accent
        g2.fillOval(x, y, s, s)
        g2.color = Color.WHITE
        g2.font = CursorUi.boldFont().deriveFont(s * 0.5f)
        val fm = g2.fontMetrics
        g2.drawString(initial, x + (s - fm.stringWidth(initial)) / 2, y + (s - fm.height) / 2 + fm.ascent)
        g2.dispose()
    }
}

internal fun JComponent.leftAligned(): JComponent = apply { alignmentX = Component.LEFT_ALIGNMENT }

/**
 * Borderless dropdown ("Agent ⌄") used in the composer's bottom row. Opens a list popup
 * of [items]; each item is an id plus the label people see.
 */
class PickerButton(
    tooltip: String,
    private val onPick: (String) -> Unit,
) : JBLabel() {
    var items: List<Pair<String, String>> = emptyList()
        set(value) { field = value; refresh() }
    var selectedId: String? = null
        set(value) { field = value; refresh() }
    private var hovered = false

    init {
        toolTipText = tooltip
        icon = com.intellij.icons.AllIcons.General.ChevronDown
        horizontalTextPosition = SwingConstants.LEFT
        iconTextGap = JBUI.scale(2)
        font = CursorUi.smallFont()
        foreground = CursorUi.secondary
        border = JBUI.Borders.empty(3, 6)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hovered = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hovered = false; repaint() }
            override fun mouseClicked(e: MouseEvent) { if (isEnabled && items.size > 1) showPopup() }
        })
        refresh()
    }

    private fun refresh() {
        text = items.firstOrNull { it.first == selectedId }?.second ?: selectedId?.let(::prettify) ?: ""
        isVisible = text.isNotEmpty()
    }

    fun showPopup() {
        val labels = items.map { it.second }
        val step = object : com.intellij.openapi.ui.popup.util.BaseListPopupStep<String>(null, labels) {
            override fun onChosen(selectedValue: String, finalChoice: Boolean): com.intellij.openapi.ui.popup.PopupStep<*>? {
                items.firstOrNull { it.second == selectedValue }?.let { (id, _) ->
                    selectedId = id
                    onPick(id)
                }
                return FINAL_CHOICE
            }
            override fun getIconFor(value: String): Icon? =
                if (items.firstOrNull { it.second == value }?.first == selectedId) com.intellij.icons.AllIcons.Actions.Checked
                else com.intellij.util.ui.EmptyIcon.ICON_16
        }
        val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createListPopup(step)
        val size = popup.content.preferredSize
        popup.show(com.intellij.ui.awt.RelativePoint(this, java.awt.Point(0, -size.height - JBUI.scale(4))))
    }

    override fun paintComponent(g: Graphics) {
        if (hovered && isEnabled && items.size > 1) {
            val g2 = CursorUi.antialias(g)
            g2.color = CursorUi.hoverBackground
            g2.fillRoundRect(0, 0, width, height, JBUI.scale(6), JBUI.scale(6))
            g2.dispose()
        }
        super.paintComponent(g)
    }

    companion object {
        fun prettify(id: String): String =
            id.replace('-', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

/**
 * Swing sizes wrapped text from its width, but only learns the width after the first layout.
 * Re-validate whenever the width changes so the height catches up and nothing gets clipped.
 */
private fun javax.swing.text.JTextComponent.relayoutOnWidthChange(oldWidth: Int, newWidth: Int) {
    if (oldWidth != newWidth) javax.swing.SwingUtilities.invokeLater { revalidate() }
}

open class WrapTextArea(text: String = "") : javax.swing.JTextArea(text) {
    init {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        font = CursorUi.bodyFont()
        border = JBUI.Borders.empty()
    }

    @Suppress("DEPRECATION")
    override fun reshape(x: Int, y: Int, w: Int, h: Int) {
        val old = width
        super.reshape(x, y, w, h)
        relayoutOnWidthChange(old, w)
    }
}

open class WrapEditorPane : javax.swing.JEditorPane() {
    @Suppress("DEPRECATION")
    override fun reshape(x: Int, y: Int, w: Int, h: Int) {
        val old = width
        super.reshape(x, y, w, h)
        relayoutOnWidthChange(old, w)
    }
}

/** Small filled circle, used for the connection indicator. */
class DotIcon(private val color: () -> java.awt.Color, private val size: Int = 7) : Icon {
    override fun getIconWidth() = JBUI.scale(size)
    override fun getIconHeight() = JBUI.scale(size)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = CursorUi.antialias(g)
        g2.color = color()
        g2.fillOval(x, y, iconWidth, iconHeight)
        g2.dispose()
    }
}
