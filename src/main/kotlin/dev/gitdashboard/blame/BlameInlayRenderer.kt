package dev.gitdashboard.blame

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints

/**
 * The inline blame, drawn after the end of the caret line as a tinted pill so it never reads as
 * code:
 *
 *   ( (K) Kay37, 2 days ago • Fix rounding  •  #14 a1a9423 )  ( ↑ Not pushed )  ( ✦ New )
 *
 * The circle holds the author's initials in a colour fixed per author. Edited lines get an amber
 * pill instead. Text colours come from Settings > Editor > Color Scheme > Git Dashboard.
 */
class BlameInlayRenderer(val blame: BlameLine) : EditorCustomElementRenderer {

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val fonts = Fonts(inlay.editor)
        val pill = pillWidth(fonts, pillHeight(inlay.editor.lineHeight))
        return GAP.get() + pill + badges().sumOf { BADGE_GAP.get() + badgeWidth(fonts, it) }
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val fonts = Fonts(editor)
            val height = pillHeight(targetRegion.height)
            val top = targetRegion.y + (targetRegion.height - height) / 2
            var x = targetRegion.x + GAP.get()

            x += paintPill(g2, editor, fonts, x, top, height)
            for (badge in badges()) {
                x += BADGE_GAP.get()
                x += paintBadge(g2, fonts, badge, x, top, height)
            }
        }
        finally {
            g2.dispose()
        }
    }

    override fun getContextMenuGroupId(inlay: Inlay<*>): String = "GitDashboard.BlameOptions"

    // ---- pill -------------------------------------------------------------------------------

    private fun pillWidth(fonts: Fonts, height: Int): Int {
        val content = fonts.text.stringWidth(blame.label) +
            (blame.commitId?.let { fonts.text.stringWidth(SEPARATOR + it) } ?: 0)
        return PAD.get() + markerSize(height) + PAD.get() + content + PAD_END.get()
    }

    private fun paintPill(g: Graphics2D, editor: Editor, fonts: Fonts, x: Int, top: Int, height: Int): Int {
        val width = pillWidth(fonts, height)
        val textColor = colorOf(editor, BlameColors.TEXT, BlameColors.DEFAULT_TEXT)
        val accent = if (blame is BlameLine.Uncommitted) BlameColors.UNCOMMITTED else textColor

        g.color = accent.withAlpha(PILL_ALPHA)
        g.fillRoundRect(x, top, width, height, height, height)

        // Author initials in their colour, or an amber dot for uncommitted lines.
        val marker = markerSize(height)
        val markerX = x + PAD.get()
        val markerY = top + (height - marker) / 2
        when (blame) {
            is BlameLine.Committed -> {
                g.color = BlameColors.authorColor(blame.info.authorUser?.email ?: blame.info.author)
                g.fillOval(markerX, markerY, marker, marker)
                val initials = initialsOf(blame.info.author)
                g.font = fonts.initials.font
                g.color = Color.WHITE
                g.drawString(
                    initials,
                    markerX + (marker - fonts.initials.stringWidth(initials)) / 2,
                    markerY + (marker + fonts.initials.ascent - fonts.initials.descent) / 2,
                )
            }
            BlameLine.Uncommitted -> {
                val dot = marker / 2
                g.color = BlameColors.UNCOMMITTED
                g.fillOval(markerX + (marker - dot) / 2, markerY + (marker - dot) / 2, dot, dot)
            }
        }

        var textX = markerX + marker + PAD.get()
        val baseline = top + (height + fonts.text.ascent - fonts.text.descent) / 2
        g.font = fonts.text.font
        g.color = if (blame is BlameLine.Uncommitted) BlameColors.UNCOMMITTED else textColor
        g.drawString(blame.label, textX, baseline)
        textX += fonts.text.stringWidth(blame.label)

        blame.commitId?.let { id ->
            g.drawString(SEPARATOR, textX, baseline)
            textX += fonts.text.stringWidth(SEPARATOR)
            g.color = colorOf(editor, BlameColors.COMMIT_ID, BlameColors.DEFAULT_COMMIT_ID)
            g.drawString(id, textX, baseline)
        }
        return width
    }

    // ---- badges -----------------------------------------------------------------------------

    private fun badges(): List<CommitBadge> = (blame as? BlameLine.Committed)?.badges?.sortedBy { it.ordinal }.orEmpty()

    private fun badgeWidth(fonts: Fonts, badge: CommitBadge) = BADGE_PAD.get() * 2 + fonts.badge.stringWidth(badge.text)

    private fun paintBadge(g: Graphics2D, fonts: Fonts, badge: CommitBadge, x: Int, top: Int, height: Int): Int {
        val width = badgeWidth(fonts, badge)
        val color = BlameColors.badgeColor(badge)
        g.color = color.withAlpha(BADGE_ALPHA)
        g.fillRoundRect(x, top, width, height, height, height)
        g.font = fonts.badge.font
        g.color = color
        g.drawString(badge.text, x + BADGE_PAD.get(), top + (height + fonts.badge.ascent - fonts.badge.descent) / 2)
        return width
    }

    // ---- helpers ----------------------------------------------------------------------------

    private class Fonts(editor: Editor) {
        private val base = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        val text: FontMetrics = editor.contentComponent.getFontMetrics(base.deriveFont(Font.ITALIC, base.size2D * 0.92f))
        val badge: FontMetrics = editor.contentComponent.getFontMetrics(base.deriveFont(Font.BOLD, base.size2D * 0.82f))
        val initials: FontMetrics = editor.contentComponent.getFontMetrics(base.deriveFont(Font.BOLD, base.size2D * 0.62f))
    }

    private fun pillHeight(lineHeight: Int) = (lineHeight - JBUI.scale(2)).coerceAtLeast(JBUI.scale(10))

    private fun markerSize(pillHeight: Int) = (pillHeight - JBUI.scale(4)).coerceAtLeast(JBUI.scale(8))

    private fun colorOf(editor: Editor, key: TextAttributesKey, fallback: Color): Color =
        editor.colorsScheme.getAttributes(key)?.foregroundColor ?: fallback

    private fun Color.withAlpha(alpha: Int) = Color(red, green, blue, alpha)

    private companion object {
        const val SEPARATOR = "  •  "
        const val PILL_ALPHA = 38
        const val BADGE_ALPHA = 46
        // Spacing in unscaled pixels; JBValue scales with the IDE zoom when read.
        val GAP = JBUI.uiIntValue("GitDashboard.blame.gap", 24)
        val PAD = JBUI.uiIntValue("GitDashboard.blame.pad", 3)
        val PAD_END = JBUI.uiIntValue("GitDashboard.blame.padEnd", 9)
        val BADGE_GAP = JBUI.uiIntValue("GitDashboard.blame.badgeGap", 6)
        val BADGE_PAD = JBUI.uiIntValue("GitDashboard.blame.badgePad", 7)
    }
}

/** "Kay37" -> "K", "Jane Doe" -> "JD". */
fun initialsOf(name: String): String =
    name.split(Regex("[\\s._-]+")).filter { it.isNotEmpty() }.take(2)
        .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

object BlameColors {
    val TEXT: TextAttributesKey = TextAttributesKey.createTextAttributesKey("GIT_DASHBOARD_INLINE_BLAME")
    val COMMIT_ID: TextAttributesKey = TextAttributesKey.createTextAttributesKey("GIT_DASHBOARD_INLINE_BLAME_COMMIT")

    /** Used when the colour scheme has no value (e.g. a third-party scheme): soft purple text, teal commit ID. */
    val DEFAULT_TEXT = JBColor(Color(0x8A7FBF), Color(0x9C8FD6))
    val DEFAULT_COMMIT_ID = JBColor(Color(0x3E9C9C), Color(0x5DB7B0))

    val UNCOMMITTED = JBColor(Color(0xB7791F), Color(0xE0B040))
    private val NOT_PUSHED = JBColor(Color(0xD9822B), Color(0xF0A35E))
    private val NEW = JBColor(Color(0x3574F0), Color(0x5C9DFF))

    /** Distinct, readable-with-white-text colours; each author always gets the same one. */
    private val AUTHOR_PALETTE = listOf(0xE5534B, 0x3D8BD9, 0x46A758, 0xE08A1E, 0xA35BC9, 0x2A9D8F, 0xD6457C, 0x6A6BD9).map(::Color)

    fun authorColor(author: String): Color = AUTHOR_PALETTE[Math.floorMod(author.lowercase().hashCode(), AUTHOR_PALETTE.size)]

    fun badgeColor(badge: CommitBadge): Color = when (badge) {
        CommitBadge.NOT_PUSHED -> NOT_PUSHED
        CommitBadge.NEW -> NEW
    }
}
