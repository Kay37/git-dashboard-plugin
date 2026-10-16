package dev.gitdashboard.git

import com.intellij.dvcs.repo.Repository
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import git4idea.branch.GitBranchUtil
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitCommandResult
import git4idea.repo.GitRepository

// All functions here run git and block: call them from a background task, never on the EDT.

/** What a sync did. [pulled] / [pushed] count commits; [published] means the branch was pushed for the first time. */
data class SyncResult(val upstream: String, val pulled: Int, val pushed: Int, val published: Boolean = false)

/** A pull stopped on conflicts that the user has to resolve before the sync can finish. */
class SyncConflictException(val repo: GitRepository) :
    Exception("${repo.root.name} has conflicts that need resolving.")

/**
 * One-click sync of the current branch: fetch, bring in remote commits (fast-forward when
 * possible, otherwise merge, or rebase if the user's git config asks for `pull.rebase`), then
 * push. Uncommitted changes are stashed and restored around the pull.
 *
 * @throws SyncConflictException when the pull stops on conflicts.
 */
fun syncCurrentBranch(project: Project, repo: GitRepository): SyncResult {
    val branch = repo.currentBranch
        ?: throw GitOperationException("You're not on a branch (detached HEAD), so there is nothing to sync.")
    if (repo.state != Repository.State.NORMAL) {
        throw GitOperationException("A ${repo.state.name.lowercase()} is in progress. Finish or abort it first.")
    }
    val trackInfo = GitBranchUtil.getTrackInfoForBranch(repo, branch)
    if (trackInfo == null) {
        // Never pushed: nothing to pull, so syncing means publishing.
        return SyncResult(pushCurrentBranch(project, repo), pulled = 0, pushed = 0, published = true)
    }
    val upstream = trackInfo.remoteBranch.nameForLocalOperations

    val fetch = Git.getInstance().fetch(repo, trackInfo.remote, emptyList())
    if (!fetch.success()) throw GitOperationException(explainRemoteFailure(fetch, "Fetch failed."))
    repo.update()

    val (ahead, behind) = countAheadBehind(project, repo).let { (a, b) -> (a ?: 0) to (b ?: 0) }
    if (behind > 0) {
        val pull = when {
            ahead == 0 -> tryGit(project, repo, GitCommand.MERGE, "--ff-only", "--autostash", upstream)
            prefersRebase(project, repo) -> tryGit(project, repo, GitCommand.REBASE, "--autostash", upstream)
            else -> tryGit(project, repo, GitCommand.MERGE, "--no-edit", "--autostash", upstream)
        }
        afterWorkingTreeChanged(project, repo)
        failOnConflicts(project, repo)
        if (!pull.success()) throw GitOperationException(pull.errorOutputAsJoinedString.ifBlank { "Pulling from $upstream failed." })
    }

    val toPush = countAheadBehind(project, repo).first ?: 0
    if (toPush > 0) pushCurrentBranch(project, repo)
    return SyncResult(upstream, pulled = behind, pushed = toPush)
}

/** Unresolved conflict files, refreshed from disk so the merge dialog sees current content. */
fun conflictedFiles(project: Project, repo: GitRepository): List<VirtualFile> {
    VfsUtil.markDirtyAndRefresh(false, true, false, repo.root)
    return conflictedPaths(project, repo).mapNotNull { repo.root.findFileByRelativePath(it) }
}

/**
 * Completes a merge or rebase once its conflicts are resolved: commits the merge, or continues
 * the rebase (which may stop again on the next commit's conflicts).
 *
 * @throws SyncConflictException when conflicts remain or the rebase hits new ones.
 */
fun finishOperation(project: Project, repo: GitRepository) {
    failOnConflicts(project, repo)
    val result: GitCommandResult = when (repo.state) {
        Repository.State.MERGING -> tryGit(project, repo, GitCommand.COMMIT, "--no-edit")
        // Accept each rebased commit's message as is instead of opening an editor.
        Repository.State.REBASING -> tryGit(project, repo, GitCommand.REBASE, "--continue") {
            addCustomEnvironmentVariable("GIT_EDITOR", "true")
        }
        else -> return
    }
    afterWorkingTreeChanged(project, repo)
    failOnConflicts(project, repo)
    if (!result.success()) throw GitOperationException(result.errorOutputAsJoinedString.ifBlank { "Couldn't finish the ${repo.state.name.lowercase()}." })
}

/** Cancels an in-progress merge or rebase, returning the branch to where it was before the pull. */
fun abortOperation(project: Project, repo: GitRepository) {
    when (repo.state) {
        Repository.State.MERGING -> runGit(project, repo, GitCommand.MERGE, "--abort")
        Repository.State.REBASING -> runGit(project, repo, GitCommand.REBASE, "--abort")
        else -> return
    }
    afterWorkingTreeChanged(project, repo)
}

private fun failOnConflicts(project: Project, repo: GitRepository) {
    if (conflictedPaths(project, repo).isNotEmpty()) throw SyncConflictException(repo)
}

/** Honors the user's own `pull.rebase` setting (true / merges / interactive); merging is the default. */
private fun prefersRebase(project: Project, repo: GitRepository): Boolean {
    val value = tryGit(project, repo, GitCommand.CONFIG, "--get", "pull.rebase") { setSilent(true) }
        .takeIf { it.success() }?.outputAsJoinedString?.trim()?.lowercase()
    return value in setOf("true", "merges", "m", "interactive", "i")
}

/** A pull rewrites files on disk: reload them in the editor as well as the repository state. */
private fun afterWorkingTreeChanged(project: Project, repo: GitRepository) {
    VfsUtil.markDirtyAndRefresh(false, true, false, repo.root)
    afterRepoChanged(project, repo)
}
