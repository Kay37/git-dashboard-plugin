package dev.gitdashboard.toolwindow

import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import dev.gitdashboard.git.GitOperationException
import dev.gitdashboard.git.UnpushedCommit
import dev.gitdashboard.git.listUnpushedCommits
import dev.gitdashboard.git.pushCurrentBranch
import dev.gitdashboard.notifyUser
import git4idea.branch.GitBranchUtil
import git4idea.repo.GitRepository

/**
 * Pushes [repo], first letting the user choose how many commits to send when there is more than
 * one unpushed commit. A single commit (or a branch that is only being published) is pushed directly.
 */
fun pushWithChoice(project: Project, repo: GitRepository, onFinished: () -> Unit = {}) {
    object : Task.Backgroundable(project, "Reading unpushed commits", false) {
        private var commits: List<UnpushedCommit> = emptyList()

        override fun run(indicator: ProgressIndicator) {
            commits = listUnpushedCommits(project, repo)
        }

        override fun onSuccess() {
            if (commits.size <= 1) return runPush(project, listOf(repo to null), onFinished)

            val target = repo.currentBranch
                ?.let { GitBranchUtil.getTrackInfoForBranch(repo, it)?.remoteBranch?.nameForLocalOperations }
                ?: "the remote"
            val dialog = PushCommitsDialog(project, target, commits)
            if (dialog.showAndGet()) runPush(project, listOf(repo to dialog.upToCommit), onFinished)
        }

        override fun onThrowable(error: Throwable) = reportPushFailure(project, error, pushedTo = emptyList())
    }.queue()
}

/** Pushes the whole current branch of each repository, with no questions asked. */
fun pushInBackground(project: Project, repos: Collection<GitRepository>, onFinished: () -> Unit = {}) =
    runPush(project, repos.map { it to null }, onFinished)

/** @param targets each repository with the newest commit to push, or null for its whole branch. */
private fun runPush(project: Project, targets: List<Pair<GitRepository, String?>>, onFinished: () -> Unit) {
    if (targets.isEmpty()) return
    object : Task.Backgroundable(project, "Pushing", false) {
        private val pushedTo = mutableListOf<String>()

        override fun run(indicator: ProgressIndicator) {
            for ((repo, upToCommit) in targets) {
                indicator.text = "Pushing ${repo.root.name}"
                pushedTo += pushCurrentBranch(project, repo, upToCommit)
            }
        }

        override fun onSuccess() =
            notifyUser(project, NotificationType.INFORMATION, "Pushed", "Pushed to ${pushedTo.joinToString()}")

        override fun onThrowable(error: Throwable) = reportPushFailure(project, error, pushedTo)

        override fun onFinished() = onFinished()
    }.queue()
}

private fun reportPushFailure(project: Project, error: Throwable, pushedTo: List<String>) {
    val reason = (error as? GitOperationException)?.message ?: error.toString()
    val title = if (pushedTo.isEmpty()) "Push failed" else "Pushed to ${pushedTo.joinToString()}, then a push failed"
    notifyUser(project, NotificationType.ERROR, title, reason)
}
