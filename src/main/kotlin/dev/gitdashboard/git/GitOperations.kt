package dev.gitdashboard.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import git4idea.GitUtil
import git4idea.branch.GitBranchUtil
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitCommandResult
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import java.io.File

/** A Git operation failed; [message] is written for the user, not as a raw git dump. */
class GitOperationException(message: String) : Exception(message)

// All functions here run git and block: call them from a background task, never on the EDT.

/**
 * Commits exactly [files] in [repo], including new and deleted files, and nothing else that
 * happens to be staged already.
 */
fun commitFiles(project: Project, repo: GitRepository, files: Collection<FilePath>, message: String) {
    val pathspec = tempFile("pathspec", files.joinToString("\u0000") { relativePath(repo, it) })
    val messageFile = tempFile("message", message)
    try {
        val pathspecArgs = arrayOf("--pathspec-from-file=${pathspec.path}", "--pathspec-file-nul")
        runGit(project, repo, GitCommand.ADD, "--all", *pathspecArgs)
        runGit(project, repo, GitCommand.COMMIT, "--file=${messageFile.path}", *pathspecArgs)
    }
    finally {
        FileUtil.delete(pathspec)
        FileUtil.delete(messageFile)
    }
    afterRepoChanged(project, repo)
}

data class UnpushedCommit(val hash: String, val shortHash: String, val subject: String, val relativeDate: String)

/**
 * Commits on the current branch that aren't on its upstream yet, newest first. For a branch
 * that was never pushed, that's every commit not on any remote branch.
 */
fun listUnpushedCommits(project: Project, repo: GitRepository): List<UnpushedCommit> {
    val branch = repo.currentBranch ?: return emptyList()
    val range = if (GitBranchUtil.getTrackInfoForBranch(repo, branch) != null) arrayOf("@{upstream}..HEAD")
                else arrayOf("HEAD", "--not", "--remotes")
    return runGit(project, repo, GitCommand.LOG, "--format=%H%x1f%h%x1f%s%x1f%ar", *range).output
        .mapNotNull { line ->
            val parts = line.split('\u001f')
            if (parts.size < 4) null else UnpushedCommit(parts[0], parts[1], parts[2], parts[3])
        }
}

/**
 * Pushes the current branch to its upstream. A branch that has never been pushed is published
 * to the default remote under the same name and set to track it.
 *
 * @param upToCommit push only this commit and the commits before it; null pushes the whole branch.
 *   Git can't push a commit without its ancestors, so "up to" is the only kind of partial push.
 * @param publishTo remote to publish to when the branch has no upstream yet; defaults to "origin"
 *   or the first remote.
 * @return the remote branch pushed to, e.g. "origin/main".
 */
fun pushCurrentBranch(project: Project, repo: GitRepository, upToCommit: String? = null, publishTo: String? = null): String {
    val branch = repo.currentBranch
        ?: throw GitOperationException("You're not on a branch (detached HEAD), so there is nothing to push.")
    val trackInfo = GitBranchUtil.getTrackInfoForBranch(repo, branch)
    val remote = trackInfo?.remote
        ?: publishTo?.let { GitUtil.findRemoteByName(repo, it) }
        ?: GitUtil.getDefaultOrFirstRemote(repo.remotes)
        ?: throw GitOperationException("${repo.root.name} has no remote yet. Add one (e.g. a GitHub repository) to push.")
    val url = remote.firstUrl
        ?: throw GitOperationException("The remote '${remote.name}' has no URL.")
    val remoteBranchName = trackInfo?.remoteBranch?.nameForRemoteOperations ?: branch.name

    val source = upToCommit ?: "refs/heads/${branch.name}"
    val result = Git.getInstance().push(
        repo, remote.name, url,
        "$source:refs/heads/$remoteBranchName",
        /* updateTracking = */ trackInfo == null,
    )
    if (!result.success()) throw GitOperationException(explainPushFailure(result))
    if (trackInfo == null && upToCommit != null) {
        // --set-upstream only applies when pushing a branch name, not a commit hash.
        runGit(project, repo, GitCommand.BRANCH, "--set-upstream-to=${remote.name}/$remoteBranchName", branch.name)
    }

    afterRepoChanged(project, repo)
    return "${remote.name}/$remoteBranchName"
}

/** Subject line of the latest commit, for confirmation prompts. */
fun lastCommitSubject(project: Project, repo: GitRepository): String =
    runGit(project, repo, GitCommand.LOG, "-1", "--format=%s").outputAsJoinedString.trim()

/** Removes the latest commit but keeps its changes in the working tree, ready to commit again. */
fun undoLastCommit(project: Project, repo: GitRepository) {
    val hasParent = tryGit(project, repo, GitCommand.REV_PARSE, "--verify", "--quiet", "HEAD~1") { setSilent(true) }.success()
    if (!hasParent) throw GitOperationException("This is the repository's first commit, so it can't be undone this way.")

    runGit(project, repo, GitCommand.RESET, "--soft", "HEAD~1")
    afterRepoChanged(project, repo)
}

/** Runs git and returns the result whether or not it succeeded. */
internal fun tryGit(
    project: Project, repo: GitRepository, command: GitCommand, vararg params: String,
    configure: GitLineHandler.() -> Unit = {},
): GitCommandResult {
    val handler = GitLineHandler(project, repo.root, command).apply {
        addParameters(*params)
        configure()
    }
    return Git.getInstance().runCommand(handler)
}

/** Runs git and throws a [GitOperationException] carrying git's error output if it fails. */
internal fun runGit(project: Project, repo: GitRepository, command: GitCommand, vararg params: String): GitCommandResult {
    val result = tryGit(project, repo, command, *params)
    if (!result.success()) {
        throw GitOperationException(result.errorOutputAsJoinedString.ifBlank { "git ${command.name()} failed." })
    }
    return result
}

private fun explainPushFailure(result: GitCommandResult): String {
    val output = result.errorOutputAsJoinedString
    return when {
        "rejected" in output && ("fetch first" in output || "non-fast-forward" in output) ->
            "The remote has commits you don't have yet. Click Sync to pull them in, then push again."
        else -> explainRemoteFailure(result, "Push failed.")
    }
}

internal fun explainRemoteFailure(result: GitCommandResult, fallback: String): String {
    val output = result.errorOutputAsJoinedString
    return when {
        "Authentication failed" in output || "could not read Username" in output ->
            "GitHub sign-in failed. Check your account under Settings > Version Control > GitHub."
        "Could not resolve host" in output || "unable to access" in output ->
            "Couldn't reach the remote. Check your internet connection and try again."
        else -> output.ifBlank { fallback }
    }
}

/** Reloads IntelliJ's view of the repository so the dashboard and the IDE's own Git UI update. */
internal fun afterRepoChanged(project: Project, repo: GitRepository) {
    repo.update()
    VcsDirtyScopeManager.getInstance(project).rootDirty(repo.root)
}

private fun relativePath(repo: GitRepository, file: FilePath): String =
    FileUtil.getRelativePath(repo.root.path, file.path, '/')
        ?: throw GitOperationException("${file.path} is outside ${repo.root.path}.")

private fun tempFile(kind: String, content: String): File =
    FileUtil.createTempFile("git-dashboard-$kind", ".txt", true).apply { writeText(content, Charsets.UTF_8) }
