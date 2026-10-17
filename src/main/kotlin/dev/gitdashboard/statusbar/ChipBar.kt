package dev.gitdashboard.statusbar

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.ToolTipManager

/**
 * One coloured, clickable chip. [solid] chips are filled with [color] and white text, so they
 * stand out from the grey status bar; the others are a light tint with coloured text.
 */
data class Chip(
    val text: String,
    val color: Color,
    val solid: Boolean,
    val tooltip: String,
    val icon: Icon? = null,
    val onClick: ((MouseEvent) -> Unit)? = null,
)

/** A row of [Chip]s painted as rounded pills, each with its own tooltip and click action. */
class ChipBar(private val onPopupTrigger: (MouseEvent) -> Unit) : JComponent() {
    var chips: List<Chip> = emptyList()
        set(value) {
            field = value
            hovered = -1
            revalidate()
            repaint()
        }

    private var hovered = -1

    init {
        isOpaque = false
        font = JBFont.label().asBold()
        border = JBUI.Borders.empty(0, 4)
        ToolTipManager.sharedInstance().registerComponent(this)

        val mouse = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) = setHovered(chipIndexAt(e))
            override fun mouseExited(e: MouseEvent) = setHovered(-1)
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) onPopupTrigger(e)
            }
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) return onPopupTrigger(e)
                if (e.button != MouseEvent.BUTTON1) return
                chips.getOrNull(chipIndexAt(e))?.onClick?.invoke(e)
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    override fun getToolTipText(event: MouseEvent): String? = chips.getOrNull(chipIndexAt(event))?.tooltip

    override fun getPreferredSize(): Dimension {
        val insets = insets
        val width = chips.sumOf { chipWidth(it) } + GAP.get() * (chips.size - 1).coerceAtLeast(0)
        val height = getFontMetrics(font).height + JBUI.scale(6)
        return Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.font = font
            val metrics = g2.fontMetrics
            layoutChips().forEachIndexed { index, rect ->
                val chip = chips[index]
                val hover = index == hovered && chip.onClick != null
                g2.color = when {
                    chip.solid -> if (hover) chip.color.brighter() else chip.color
                    else -> chip.color.withAlpha(if (hover) 80 else 48)
                }
                g2.fillRoundRect(rect.x, rect.y, rect.width, rect.height, rect.height, rect.height)

                var x = rect.x + PAD.get()
                chip.icon?.let { icon ->
                    icon.paintIcon(this, g2, x, rect.y + (rect.height - icon.iconHeight) / 2)
                    x += icon.iconWidth + ICON_GAP.get()
                }
                g2.color = if (chip.solid) Color.WHITE else chip.color.textOn()
                g2.drawString(chip.text, x, rect.y + (rect.height + metrics.ascent - metrics.descent) / 2)
            }
        }
        finally {
            g2.dispose()
        }
    }

    private fun layoutChips(): List<Rectangle> {
        val insets = insets
        val chipHeight = height - insets.top - insets.bottom - JBUI.scale(4)
        val top = insets.top + (height - insets.top - insets.bottom - chipHeight) / 2
        var x = insets.left
        return chips.map { chip ->
            val width = chipWidth(chip)
            Rectangle(x, top, width, chipHeight).also { x += width + GAP.get() }
        }
    }

    private fun chipWidth(chip: Chip): Int =
        PAD.get() * 2 + getFontMetrics(font).stringWidth(chip.text) + (chip.icon?.let { it.iconWidth + ICON_GAP.get() } ?: 0)

    private fun chipIndexAt(e: MouseEvent) = layoutChips().indexOfFirst { it.contains(e.point) }

    private fun setHovered(index: Int) {
        if (index == hovered) return
        hovered = index
        cursor = if (chips.getOrNull(index)?.onClick != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else null
        repaint()
    }

    private fun Color.withAlpha(alpha: Int) = Color(red, green, blue, alpha)

    /** Text colour on a tinted chip: the chip colour, nudged for contrast against the status bar. */
    private fun Color.textOn(): Color = if (JBColor.isBright()) darker() else brighter()

    private companion object {
        val PAD = JBUI.uiIntValue("GitDashboard.chip.pad", 7)
        val GAP = JBUI.uiIntValue("GitDashboard.chip.gap", 4)
        val ICON_GAP = JBUI.uiIntValue("GitDashboard.chip.iconGap", 4)
    }
}
