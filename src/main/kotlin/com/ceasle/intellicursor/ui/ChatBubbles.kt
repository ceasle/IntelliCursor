package com.ceasle.intellicursor.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Graphics
import java.awt.LayoutManager
import java.awt.geom.Path2D
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Messenger-style bubble: generously rounded, with a tight corner on the side of the
 * avatar so the bubble reads as coming from it.
 */
class Bubble(
    private val outgoing: Boolean,
    private val fill: () -> Color,
    private val outline: () -> Color? = { null },
    layout: LayoutManager = BorderLayout(),
) : JPanel(layout) {
    init {
        isOpaque = false
        border = JBUI.Borders.empty(8, 12, 6, 12)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = CursorUi.antialias(g)
        try {
            val r = JBUI.scale(14).toFloat()
            val tail = JBUI.scale(4).toFloat()
            val shape = roundedRect(0.5f, 0.5f, width - 1f, height - 1f,
                tl = if (outgoing) r else tail, tr = if (outgoing) tail else r, br = r, bl = r)
            g2.color = fill()
            g2.fill(shape)
            outline()?.let {
                g2.color = it
                g2.stroke = BasicStroke(JBUI.scale(1).toFloat())
                g2.draw(shape)
            }
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    private fun roundedRect(x: Float, y: Float, w: Float, h: Float, tl: Float, tr: Float, br: Float, bl: Float) =
        Path2D.Float().apply {
            moveTo(x + tl, y)
            lineTo(x + w - tr, y); quadTo(x + w, y, x + w, y + tr)
            lineTo(x + w, y + h - br); quadTo(x + w, y + h, x + w - br, y + h)
            lineTo(x + bl, y + h); quadTo(x, y + h, x, y + h - bl)
            lineTo(x, y + tl); quadTo(x, y, x + tl, y)
            closePath()
        }
}

/**
 * One chat line: a round avatar in the outer top corner and a bubble beside it. Outgoing
 * rows sit on the right, incoming on the left. The bubble shrinks to its content up to most
 * of the row width, so short messages stay short and long ones wrap.
 */
class BubbleRow(
    private val outgoing: Boolean,
    avatarIcon: Icon,
    val bubble: JComponent,
    /** Width the content would like before wrapping, or [Int.MAX_VALUE] to fill the row. */
    private val naturalWidth: () -> Int,
) : JPanel(null) {
    private val avatar = JBLabel(avatarIcon)

    init {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        add(avatar)
        add(bubble)
    }

    private val gap get() = JBUI.scale(8)
    /** Empty space kept on the far side so the two speakers stay visually apart. */
    private val farMargin get() = JBUI.scale(if (outgoing) 44 else 28)

    private fun rowWidth(): Int {
        if (width > 0) return width
        val p = parent ?: return JBUI.scale(420)
        val w = p.width - p.insets.left - p.insets.right
        return if (w > 0) w else JBUI.scale(420)
    }

    private fun bubbleWidth(rowWidth: Int): Int {
        val max = (rowWidth - avatar.preferredSize.width - gap - farMargin).coerceAtLeast(JBUI.scale(80))
        val natural = naturalWidth()
        return if (natural >= max) max else natural.coerceAtLeast(JBUI.scale(56)).coerceAtMost(max)
    }

    private fun bubbleHeight(w: Int): Int {
        if (bubble.width != w) bubble.setSize(w, bubble.height.coerceAtLeast(1))
        layoutTree(bubble)
        return bubble.preferredSize.height
    }

    override fun getPreferredSize(): Dimension {
        val h = maxOf(bubbleHeight(bubbleWidth(rowWidth())), avatar.preferredSize.height)
        return Dimension(JBUI.scale(120), h)
    }

    override fun getMinimumSize() = Dimension(0, preferredSize.height)
    override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)

    override fun doLayout() {
        val a = avatar.preferredSize
        val bw = bubbleWidth(width)
        val bh = bubbleHeight(bw)
        if (outgoing) {
            avatar.setBounds(width - a.width, 0, a.width, a.height)
            bubble.setBounds(width - a.width - gap - bw, 0, bw, bh)
        } else {
            avatar.setBounds(0, 0, a.width, a.height)
            bubble.setBounds(a.width + gap, 0, bw, bh)
        }
    }

    companion object {
        /** Lay a subtree out at its current size so wrapped text reports its real height. */
        fun layoutTree(c: Component) {
            if (c !is Container || !c.isVisible) return
            // BoxLayout and friends cache child sizes; drop the cache so new widths count.
            (c.layout as? java.awt.LayoutManager2)?.invalidateLayout(c)
            c.doLayout()
            for (child in c.components) layoutTree(child)
        }
    }
}
