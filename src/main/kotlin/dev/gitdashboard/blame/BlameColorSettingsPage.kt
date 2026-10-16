package dev.gitdashboard.blame

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

/** Settings > Editor > Color Scheme > Git Dashboard: lets users recolour the inline blame. */
class BlameColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName() = "Git Dashboard"

    override fun getIcon(): Icon? = null

    override fun getAttributeDescriptors() = arrayOf(
        AttributesDescriptor("Inline blame//Author, date and message", BlameColors.TEXT),
        AttributesDescriptor("Inline blame//Commit number and hash", BlameColors.COMMIT_ID),
    )

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getHighlighter(): SyntaxHighlighter = PlainSyntaxHighlighter()

    override fun getDemoText() =
        "int quotient = divide(a, b);    <blame>Kay37, 2 days ago  •  Fix rounding in divide()</blame>  <commit>#14 a1a9423</commit>"

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> =
        mapOf("blame" to BlameColors.TEXT, "commit" to BlameColors.COMMIT_ID)
}
