package dev.gitdashboard.blame

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.impl.UpToDateLineNumberProviderImpl
import com.intellij.vcsUtil.VcsUtil
import dev.gitdashboard.git.listUnpushedCommits
import dev.gitdashboard.github.gitHubLineUrl
import dev.gitdashboard.github.gitHubRepoUrl
import dev.gitdashboard.notifyUser
import git4idea.repo.GitRepositoryManager
import java.awt.datatransfer.StringSelection

/**
 * Copies a permanent GitHub link to the selected lines (or the caret line), ready to paste into
 * chat or an issue.
 *
 * When everything on the branch is pushed and the lines aren't edited, the link points at the
 * current commit and covers the whole selection. Otherwise it falls back to the commit that last
 * changed the caret line, as long as that commit is on GitHub.
 */
class CopyGitHubLinkAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val repo = if (project != null && file != null) GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(file) else null
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.EDITOR) != null && repo != null && gitHubRepoUrl(repo) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val repo = GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(file) ?: return

        // Everything that needs the document is captured now, on the EDT.
        val document = editor.document
        val selection = editor.selectionModel
        val first = document.getLineNumber(if (selection.hasSelection()) selection.selectionStart else editor.caretModel.offset)
        val last = if (selection.hasSelection()) document.getLineNumber((selection.selectionEnd - 1).coerceAtLeast(selection.selectionStart)) else first
        val lines = UpToDateLineNumberProviderImpl(document, project)
        val edited = lines.isRangeChanged(first, last + 1)
        val committedFirst = lines.getLineNumber(first)
        val committedLast = lines.getLineNumber(last)
        val head = repo.currentRevision
        val hasUpstream = repo.currentBranch?.findTrackedBranch(repo) != null
        val annotation = (project.service<BlameCache>().get(file) as? BlameCache.Entry.Ready)?.annotation
        val caretInfo = annotation?.takeIf { committedFirst in 0 until it.lineCount }?.getLineInfo(committedFirst)

        object : Task.Backgroundable(project, "Preparing GitHub link", false) {
            private var url: String? = null

            override fun run(indicator: ProgressIndicator) {
                val unpushed = listUnpushedCommits(project, repo).map { it.hash }.toSet()
                url = when {
                    head != null && hasUpstream && unpushed.isEmpty() && !edited && committedFirst >= 0 && committedLast >= 0 ->
                        gitHubLineUrl(repo, head, VcsUtil.getFilePath(file), committedFirst + 1, committedLast + 1)
                    caretInfo != null && caretInfo.revisionNumber.asString() !in unpushed ->
                        gitHubLineUrl(repo, caretInfo.revisionNumber.asString(), caretInfo.filePath, caretInfo.originalLineNumber)
                    else -> null
                }
            }

            override fun onSuccess() = copyAndConfirm(project, url)
        }.queue()
    }
}

/** Puts [url] on the clipboard and confirms, or explains why there's no link yet. */
fun copyAndConfirm(project: Project, url: String?) {
    if (url == null) {
        notifyUser(
            project, NotificationType.WARNING, "These lines aren't on GitHub yet",
            "Commit and push them first, then copy the link again.",
        )
        return
    }
    CopyPasteManager.getInstance().setContents(StringSelection(url))
    notifyUser(
        project, NotificationType.INFORMATION, "GitHub link copied", url,
        actions = listOf(NotificationAction.createSimpleExpiring("Open in Browser") { BrowserUtil.browse(url) }),
    )
}
