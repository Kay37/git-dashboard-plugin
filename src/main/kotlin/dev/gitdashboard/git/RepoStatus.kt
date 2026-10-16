package dev.gitdashboard.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ChangesUtil
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

data class RepoStatus(
    val name: String,
    /** Current branch, or null when HEAD is detached. */
    val branch: String?,
    /** Short hash shown when HEAD is detached. */
    val detachedAt: String?,
    /** Remote branch the current branch tracks, e.g. "origin/main". */
    val upstream: String?,
    /** Commits on this branch not yet pushed; null when there is no upstream. */
    val ahead: Int?,
    /** Commits on the upstream not yet pulled (as of the last fetch); null when there is no upstream. */
    val behind: Int?,
    val changedFiles: Int,
)

/** Collects the dashboard's view of every Git repository in the project. Runs git, so call off the EDT. */
fun loadRepoStatuses(project: Project): List<RepoStatus> {
    val manager = GitRepositoryManager.getInstance(project)
    val changes = ChangeListManager.getInstance(project).allChanges
    return manager.repositories.map { repo ->
        val branch = repo.currentBranch
        val upstream = branch?.findTrackedBranch(repo)
        val (ahead, behind) = if (upstream != null) countAheadBehind(project, repo) else null to null
        RepoStatus(
            name = repo.root.name,
            branch = branch?.name,
            detachedAt = if (branch == null) repo.currentRevision?.take(7) else null,
            upstream = upstream?.nameForLocalOperations,
            ahead = ahead,
            behind = behind,
            changedFiles = changes.count { manager.getRepositoryForFileQuick(ChangesUtil.getFilePath(it)) == repo },
        )
    }
}

private fun countAheadBehind(project: Project, repo: GitRepository): Pair<Int?, Int?> {
    val handler = GitLineHandler(project, repo.root, GitCommand.REV_LIST).apply {
        addParameters("--left-right", "--count", "HEAD...@{upstream}")
        setSilent(true)
    }
    val result = Git.getInstance().runCommand(handler)
    if (!result.success()) return null to null
    val counts = result.outputAsJoinedString.trim().split(Regex("\\s+")).map { it.toIntOrNull() }
    return counts.getOrNull(0) to counts.getOrNull(1)
}
