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
 * Italic text drawn after the end of a line: the blame description, then the commit ID
 * ("#14 a1a9423") in its own colour. Both colours can be changed under
 * Settings > Editor > Color Scheme > Git Dashboard.
 */
class BlameInlayRenderer(val blame: BlameLine) : EditorCustomElementRenderer {
    private val separator = "  •  "

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val metrics = metrics(inlay.editor)
        return gap() + metrics.stringWidth(blame.label) + (blame.commitId?.let { metrics.stringWidth(separator + it) } ?: 0)
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        val metrics = metrics(editor)
        (g as? Graphics2D)?.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.font = metrics.font
        val baseline = targetRegion.y + (targetRegion.height + metrics.ascent - metrics.descent) / 2
        var x = targetRegion.x + gap()

        g.color = colorOf(editor, BlameColors.TEXT, BlameColors.DEFAULT_TEXT)
        g.drawString(blame.label, x, baseline)
        x += metrics.stringWidth(blame.label)

        blame.commitId?.let { id ->
            g.drawString(separator, x, baseline)
            x += metrics.stringWidth(separator)
            g.color = colorOf(editor, BlameColors.COMMIT_ID, BlameColors.DEFAULT_COMMIT_ID)
            g.drawString(id, x, baseline)
        }
    }

    override fun getContextMenuGroupId(inlay: Inlay<*>): String = "GitDashboard.BlameOptions"

    private fun gap() = JBUI.scale(28)

    private fun colorOf(editor: Editor, key: TextAttributesKey, fallback: Color): Color =
        editor.colorsScheme.getAttributes(key)?.foregroundColor ?: fallback

    private fun metrics(editor: Editor): FontMetrics {
        val font = editor.colorsScheme.getFont(EditorFontType.ITALIC).let { it.deriveFont(Font.ITALIC, it.size2D) }
        return editor.contentComponent.getFontMetrics(font)
    }
}

object BlameColors {
    val TEXT: TextAttributesKey = TextAttributesKey.createTextAttributesKey("GIT_DASHBOARD_INLINE_BLAME")
    val COMMIT_ID: TextAttributesKey = TextAttributesKey.createTextAttributesKey("GIT_DASHBOARD_INLINE_BLAME_COMMIT")

    /** Used when the colour scheme has no value (e.g. a third-party scheme): soft purple text, teal commit ID. */
    val DEFAULT_TEXT = JBColor(Color(0x8A7FBF), Color(0x9C8FD6))
    val DEFAULT_COMMIT_ID = JBColor(Color(0x3E9C9C), Color(0x5DB7B0))
}
