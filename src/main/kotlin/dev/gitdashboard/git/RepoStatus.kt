package dev.gitdashboard.git

import com.intellij.dvcs.repo.Repository
import com.intellij.openapi.project.Project
import git4idea.commands.GitCommand
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

data class RepoStatus(
    val repository: GitRepository,
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
    /** NORMAL, or MERGING / REBASING etc. while such an operation waits for the user. */
    val state: Repository.State,
    /** Files with unresolved merge conflicts. */
    val conflicts: Int,
) {
    val name: String get() = repository.root.name

    /** Whether the latest commit exists only locally, so undoing it can't disturb anyone else. */
    val hasUnpushedCommits: Boolean get() = branch != null && (upstream == null || (ahead ?: 0) > 0)

    /** A merge or rebase stopped part-way (usually on conflicts) and needs finishing or aborting. */
    val operationInProgress: Boolean get() = state == Repository.State.MERGING || state == Repository.State.REBASING
}

/** Collects the dashboard's view of every Git repository in the project. Runs git, so call off the EDT. */
fun loadRepoStatuses(project: Project): List<RepoStatus> =
    GitRepositoryManager.getInstance(project).repositories.map { repo ->
        val branch = repo.currentBranch
        val upstream = branch?.findTrackedBranch(repo)
        val (ahead, behind) = if (upstream != null) countAheadBehind(project, repo) else null to null
        RepoStatus(
            repository = repo,
            branch = branch?.name,
            detachedAt = if (branch == null) repo.currentRevision?.take(7) else null,
            upstream = upstream?.nameForLocalOperations,
            ahead = ahead,
            behind = behind,
            state = repo.state,
            conflicts = conflictedPaths(project, repo).size,
        )
    }

/** Commits ahead of / behind the upstream, or nulls if that can't be determined. */
internal fun countAheadBehind(project: Project, repo: GitRepository): Pair<Int?, Int?> {
    val result = tryGit(project, repo, GitCommand.REV_LIST, "--left-right", "--count", "HEAD...@{upstream}") { setSilent(true) }
    if (!result.success()) return null to null
    val counts = result.outputAsJoinedString.trim().split(Regex("\\s+")).map { it.toIntOrNull() }
    return counts.getOrNull(0) to counts.getOrNull(1)
}

/** Paths (relative to the repository root) that still have merge conflicts. */
internal fun conflictedPaths(project: Project, repo: GitRepository): List<String> =
    tryGit(project, repo, GitCommand.DIFF, "--name-only", "--diff-filter=U") { setSilent(true) }
        .takeIf { it.success() }?.output?.filter { it.isNotBlank() }.orEmpty()
