package dev.gitdashboard.blame

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.ProjectManager

/** IDE-wide on/off switches for the editor decorations; both default to on. */
object BlameSettings {
    private const val INLINE_BLAME = "gitDashboard.inlineBlame"
    private const val COMMIT_MARKS = "gitDashboard.commitLineMarks"

    var inlineBlame: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(INLINE_BLAME, true)
        set(value) = PropertiesComponent.getInstance().setValue(INLINE_BLAME, value, true)

    var commitLineMarks: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(COMMIT_MARKS, true)
        set(value) = PropertiesComponent.getInstance().setValue(COMMIT_MARKS, value, true)

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
