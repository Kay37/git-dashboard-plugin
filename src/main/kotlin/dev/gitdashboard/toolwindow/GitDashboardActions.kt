package dev.gitdashboard.toolwindow

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

/**
 * Base for the dashboard's main actions as registered IDE actions (plugin.xml), so they show up
 * in Find Action and can get keyboard shortcuts. They act on the repository of the file being
 * edited, or the project's first repository.
 */
abstract class RepositoryAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val repo = project?.let { currentRepository(it, e) }
        e.presentation.isEnabled = repo != null && isEnabled(project, repo)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repo = currentRepository(project, e) ?: return
        perform(project, repo, e)
    }

    protected open fun isEnabled(project: Project, repo: GitRepository) = true
    protected abstract fun perform(project: Project, repo: GitRepository, e: AnActionEvent)

    private fun currentRepository(project: Project, e: AnActionEvent): GitRepository? {
        val manager = GitRepositoryManager.getInstance(project)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        return file?.let { manager.getRepositoryForFileQuick(it) } ?: manager.repositories.firstOrNull()
    }
}

class SyncAction : RepositoryAction() {
    override fun isEnabled(project: Project, repo: GitRepository) =
        repo.currentBranch != null && !project.service<SyncTracker>().isSyncing(repo)
    override fun perform(project: Project, repo: GitRepository, e: AnActionEvent) = syncInBackground(project, repo)
}

class PushAction : RepositoryAction() {
    override fun isEnabled(project: Project, repo: GitRepository) = repo.currentBranch != null && repo.remotes.isNotEmpty()
    override fun perform(project: Project, repo: GitRepository, e: AnActionEvent) = pushWithChoice(project, repo)
}

class SwitchBranchAction : RepositoryAction() {
    override fun perform(project: Project, repo: GitRepository, e: AnActionEvent) =
        showBranchSwitcher(project, repo) { popup -> popup.showInBestPositionFor(e.dataContext) }
}
