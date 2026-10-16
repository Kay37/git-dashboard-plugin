package dev.gitdashboard.blame

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.ProjectManager

/** IDE-wide on/off switches for the editor decorations; both default to on. */
object BlameSettings {
    private const val INLINE_BLAME = "gitDashboard.inlineBlame"
    private const val COMMIT_MARKS = "gitDashboard.commitLineMarks"
    private const val ALIGN_RIGHT = "gitDashboard.blameAlignRight"

    var inlineBlame: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(INLINE_BLAME, true)
        set(value) = PropertiesComponent.getInstance().setValue(INLINE_BLAME, value, true)

    var commitLineMarks: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(COMMIT_MARKS, true)
        set(value) = PropertiesComponent.getInstance().setValue(COMMIT_MARKS, value, true)

    /** Pill at the right-margin guide (default), or right after the line's code. */
    var alignRight: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(ALIGN_RIGHT, true)
        set(value) = PropertiesComponent.getInstance().setValue(ALIGN_RIGHT, value, true)

    fun redrawAllEditors() {
        ProjectManager.getInstance().openProjects.forEach { it.serviceIfCreated<InlineBlameManager>()?.refreshAll() }
    }
}

class ToggleInlineBlameAction : DumbAwareToggleAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun isSelected(e: AnActionEvent) = BlameSettings.inlineBlame
    override fun setSelected(e: AnActionEvent, state: Boolean) {
        BlameSettings.inlineBlame = state
        BlameSettings.redrawAllEditors()
    }
}

class ToggleCommitLineMarksAction : DumbAwareToggleAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun isSelected(e: AnActionEvent) = BlameSettings.commitLineMarks
    override fun setSelected(e: AnActionEvent, state: Boolean) {
        BlameSettings.commitLineMarks = state
        BlameSettings.redrawAllEditors()
    }
}

class ToggleBlameAlignRightAction : DumbAwareToggleAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun isSelected(e: AnActionEvent) = BlameSettings.alignRight
    override fun setSelected(e: AnActionEvent, state: Boolean) {
        BlameSettings.alignRight = state
        BlameSettings.redrawAllEditors()
    }
}

/** Alt+Shift+B by default ("B" for Blame): light up (or clear) every line from the caret line's commit. */
class HighlightCommitLinesAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null && e.getData(CommonDataKeys.EDITOR) != null && BlameSettings.commitLineMarks
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        project.service<InlineBlameManager>().toggleCommitHighlight(editor)
    }
}
