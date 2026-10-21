package dev.gitdashboard.toolwindow

import com.intellij.dvcs.repo.Repository
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.AbstractVcsHelper
import com.intellij.openapi.vfs.VirtualFile
import dev.gitdashboard.git.GitOperationException
import dev.gitdashboard.git.SyncConflictException
import dev.gitdashboard.git.SyncResult
import dev.gitdashboard.git.abortOperation
import dev.gitdashboard.git.conflictedFiles
import dev.gitdashboard.git.finishOperation
import dev.gitdashboard.git.syncCurrentBranch
import dev.gitdashboard.notifyUser
import dev.gitdashboard.pluralize
import dev.gitdashboard.saveOpenFiles
import git4idea.GitVcs
import git4idea.repo.GitRepository

/** Fetches, pulls and pushes [repo]'s current branch; opens the merge dialog if the pull hits conflicts. */
fun syncInBackground(project: Project, repo: GitRepository, onFinished: () -> Unit = {}) {
    // A pull rewrites files on disk: save first, so it never lands under unsaved edits.
    saveOpenFiles()
    object : Task.Backgroundable(project, "Syncing ${repo.root.name}", true) {
        private lateinit var result: SyncResult

        override fun run(indicator: ProgressIndicator) {
            result = syncCurrentBranch(project, repo)
        }

        override fun onSuccess() {
            val summary = when {
                result.published -> "Published to ${result.upstream}."
                result.pulled == 0 && result.pushed == 0 -> "Already up to date with ${result.upstream}."
                else -> buildList {
                    if (result.pulled > 0) add("pulled ${pluralize(result.pulled, "commit")}")
                    if (result.pushed > 0) add("pushed ${pluralize(result.pushed, "commit")}")
                }.joinToString(" and ").replaceFirstChar { it.uppercase() } + " (${result.upstream})."
            }
            notifyUser(project, NotificationType.INFORMATION, "Synced", summary)
        }

        override fun onThrowable(error: Throwable) {
            if (error is SyncConflictException) resolveConflicts(project, repo, onFinished)
            else notifyUser(project, NotificationType.ERROR, "Sync failed", (error as? GitOperationException)?.message ?: error.toString())
        }

        override fun onFinished() = onFinished()
    }.queue()
}

/**
 * Shows IntelliJ's merge dialog for [repo]'s conflicts. Once everything is resolved, finishes the
 * merge or continues the rebase and then syncs again so the result gets pushed.
 */
fun resolveConflicts(project: Project, repo: GitRepository, onFinished: () -> Unit = {}) {
    runThenOnEdt(project, "Reading conflicts", work = { conflictedFiles(project, repo) }) { files ->
        if (files.isNotEmpty()) {
            AbstractVcsHelper.getInstance(project).showMergeDialog(files, GitVcs.getInstance(project).mergeProvider)
        }
        finishThenSync(project, repo, onFinished)
    }
}

private fun finishThenSync(project: Project, repo: GitRepository, onFinished: () -> Unit) {
    // Resolved files may still be open with edits, and continuing a rebase rewrites files.
    saveOpenFiles()
    object : Task.Backgroundable(project, "Finishing ${repo.root.name}", false) {
        override fun run(indicator: ProgressIndicator) = finishOperation(project, repo)

        override fun onSuccess() = syncInBackground(project, repo, onFinished)

        override fun onThrowable(error: Throwable) {
            if (error is SyncConflictException) {
                notifyUser(
                    project, NotificationType.WARNING, "Sync paused: conflicts to resolve",
                    "Resolve the conflicts in ${repo.root.name} to finish syncing, or abort to go back to how things were.",
                    actions = listOf(
                        NotificationAction.createSimpleExpiring("Resolve Conflicts") { resolveConflicts(project, repo, onFinished) },
                        NotificationAction.createSimpleExpiring("Abort") { abortWithConfirmation(project, repo, onFinished) },
                    ),
                )
            }
            else notifyUser(project, NotificationType.ERROR, "Couldn't finish syncing", (error as? GitOperationException)?.message ?: error.toString())
        }

        override fun onFinished() = onFinished()
    }.queue()
}

/** Asks, then aborts the in-progress merge or rebase, restoring the branch to its state before the pull. */
fun abortWithConfirmation(project: Project, repo: GitRepository, onFinished: () -> Unit = {}) {
    val operation = if (repo.state == Repository.State.REBASING) "rebase" else "merge"
    val answer = Messages.showOkCancelDialog(
        project,
        "Abort the $operation in ${repo.root.name}?\n\nYour branch goes back to how it was before the pull. " +
            "The remote's commits stay unmerged until you sync again.",
        "Abort ${operation.replaceFirstChar { it.uppercase() }}", "Abort", "Cancel", Messages.getWarningIcon(),
    )
    if (answer != Messages.OK) return

    saveOpenFiles()
    object : Task.Backgroundable(project, "Aborting $operation", false) {
        override fun run(indicator: ProgressIndicator) = abortOperation(project, repo)
        override fun onSuccess() = notifyUser(project, NotificationType.INFORMATION, "${operation.replaceFirstChar { it.uppercase() }} aborted")
        override fun onThrowable(error: Throwable) =
            notifyUser(project, NotificationType.ERROR, "Couldn't abort the $operation", (error as? GitOperationException)?.message ?: error.toString())
        override fun onFinished() = onFinished()
    }.queue()
}

private fun runThenOnEdt(project: Project, title: String, work: () -> List<VirtualFile>, then: (List<VirtualFile>) -> Unit) {
    object : Task.Backgroundable(project, title, false) {
        private var files: List<VirtualFile> = emptyList()
        override fun run(indicator: ProgressIndicator) {
            files = work()
        }
        override fun onSuccess() = then(files)
    }.queue()
}
