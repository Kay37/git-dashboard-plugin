package dev.gitdashboard.blame

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.markup.ActiveGutterRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.LineMarkerRendererEx
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.vcs.impl.UpToDateLineNumberProviderImpl
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import git4idea.annotate.GitFileAnnotation
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import kotlin.math.ln

/**
 * Gutter stripes, one per run of lines from the same commit, coloured by how old that commit is:
 * bright for recent changes, fading out for old ones. A small gap separates neighbouring runs so
 * each commit's block stands on its own. Hovering a stripe says who changed it and when; clicking
 * opens the commit card.
 */
object CommitAgeStripes {
    val NEWEST: ColorKey = ColorKey.createColorKey("GIT_DASHBOARD_AGE_NEWEST", JBColor(Color(0xFF7B00), Color(0xFF9A3C)))
    val OLDEST: ColorKey = ColorKey.createColorKey("GIT_DASHBOARD_AGE_OLDEST", JBColor(Color(0xD3D7DE), Color(0x4A4F57)))

    /** Changes this old (or older) get the fully faded colour. */
    private const val FADED_AFTER_DAYS = 730.0

    /** A run of consecutive editor lines (inclusive) whose committed text came from one commit. */
    class Block(val firstLine: Int, val lastLine: Int, val info: GitFileAnnotation.LineInfo)

    /** Splits the document into commit blocks; lines edited since the last commit belong to none. */
    fun blocks(annotation: GitFileAnnotation, lines: UpToDateLineNumberProviderImpl, lineCount: Int): List<Block> {
        val blocks = mutableListOf<Block>()
        var start = -1
        var current: GitFileAnnotation.LineInfo? = null
        fun close(end: Int) {
            current?.let { blocks += Block(start, end, it) }
            current = null
        }
        for (line in 0 until lineCount) {
            val committed = lines.getLineNumber(line)
            val info = if (committed in 0 until annotation.lineCount) annotation.getLineInfo(committed) else null
            if (info?.revisionNumber != current?.revisionNumber) {
                close(line - 1)
                if (info != null) {
                    start = line
                    current = info
                }
            }
        }
        close(lineCount - 1)
        return blocks
    }

    /** 1.0 for a change made just now, sliding (fast at first, then slowly) to 0.0 at [FADED_AFTER_DAYS]. */
    fun freshness(info: GitFileAnnotation.LineInfo, now: Long = System.currentTimeMillis()): Double {
        val days = ((now - info.authorDate.time) / 86_400_000.0).coerceAtLeast(0.0)
        return (1 - ln(1 + days) / ln(1 + FADED_AFTER_DAYS)).coerceIn(0.0, 1.0)
    }

    fun colorFor(editor: Editor, info: GitFileAnnotation.LineInfo): Color {
        val scheme = editor.colorsScheme
        val newest = scheme.getColor(NEWEST) ?: NEWEST.defaultColor
        val oldest = scheme.getColor(OLDEST) ?: OLDEST.defaultColor
        return ColorUtil.mix(oldest, newest, freshness(info))
    }

    fun addHighlighter(editor: Editor, block: Block, onClick: (Block, MouseEvent) -> Unit): RangeHighlighter {
        val document = editor.document
        return editor.markupModel.addRangeHighlighter(
            document.getLineStartOffset(block.firstLine), document.getLineEndOffset(block.lastLine),
            HighlighterLayer.ADDITIONAL_SYNTAX, null, HighlighterTargetArea.LINES_IN_RANGE,
        ).apply {
            lineMarkerRenderer = StripeRenderer(block, onClick)
            isGreedyToRight = false
        }
    }

    private class StripeRenderer(private val block: Block, private val onClick: (Block, MouseEvent) -> Unit) :
        LineMarkerRendererEx, ActiveGutterRenderer {

        override fun getPosition() = LineMarkerRendererEx.Position.LEFT

        override fun paint(editor: Editor, g: Graphics, r: Rectangle) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val gap = JBUI.scale(1)
                val width = JBUI.scale(STRIPE_WIDTH).coerceAtMost(r.width - gap)
                val height = (r.height - 2 * gap).coerceAtLeast(gap)
                g2.color = colorFor(editor, block.info)
                g2.fillRoundRect(r.x + (r.width - width) / 2, r.y + gap, width, height, width, width)
            }
            finally {
                g2.dispose()
            }
        }

        override fun getTooltipText(): String {
            val info = block.info
            val lines = block.lastLine - block.firstLine + 1
            return "<html><b>${info.author.escape()}</b>, ${relativeTime(info.authorDate)}<br>" +
                "${info.subject.escape()}<br>" +
                "<span style='color:gray'>${if (lines == 1) "1 line" else "$lines lines"} from this commit · click for details</span></html>"
        }

        override fun doAction(editor: Editor, e: MouseEvent) = onClick(block, e)

        override fun getAccessibleName() = "Changed by ${block.info.author}, ${relativeTime(block.info.authorDate)}"

        private fun String.escape() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    private const val STRIPE_WIDTH = 4
}
