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

/**
 * Pushes the current branch to its upstream. A branch that has never been pushed is published
 * to the default remote under the same name and set to track it.
 *
 * @return the remote branch pushed to, e.g. "origin/main".
 */
fun pushCurrentBranch(project: Project, repo: GitRepository): String {
    val branch = repo.currentBranch
        ?: throw GitOperationException("You're not on a branch (detached HEAD), so there is nothing to push.")
    val trackInfo = GitBranchUtil.getTrackInfoForBranch(repo, branch)
    val remote = trackInfo?.remote
        ?: GitUtil.getDefaultOrFirstRemote(repo.remotes)
        ?: throw GitOperationException("${repo.root.name} has no remote yet. Add one (e.g. a GitHub repository) to push.")
    val url = remote.firstUrl
        ?: throw GitOperationException("The remote '${remote.name}' has no URL.")
    val remoteBranchName = trackInfo?.remoteBranch?.nameForRemoteOperations ?: branch.name

    val result = Git.getInstance().push(
        repo, remote.name, url,
        "refs/heads/${branch.name}:refs/heads/$remoteBranchName",
        /* updateTracking = */ trackInfo == null,
    )
    if (!result.success()) throw GitOperationException(explainPushFailure(result))

    afterRepoChanged(project, repo)
    return "${remote.name}/$remoteBranchName"
}

/** Subject line of the latest commit, for confirmation prompts. */
fun lastCommitSubject(project: Project, repo: GitRepository): String =
    runGit(project, repo, GitCommand.LOG, "-1", "--format=%s").outputAsJoinedString.trim()

/** Removes the latest commit but keeps its changes in the working tree, ready to commit again. */
fun undoLastCommit(project: Project, repo: GitRepository) {
    val hasParent = Git.getInstance().runCommand(
        GitLineHandler(project, repo.root, GitCommand.REV_PARSE).apply {
            addParameters("--verify", "--quiet", "HEAD~1")
            setSilent(true)
        }
    ).success()
    if (!hasParent) throw GitOperationException("This is the repository's first commit, so it can't be undone this way.")

    runGit(project, repo, GitCommand.RESET, "--soft", "HEAD~1")
    afterRepoChanged(project, repo)
}

private fun runGit(project: Project, repo: GitRepository, command: GitCommand, vararg params: String): GitCommandResult {
    val handler = GitLineHandler(project, repo.root, command).apply { addParameters(*params) }
    val result = Git.getInstance().runCommand(handler)
    if (!result.success()) {
        throw GitOperationException(result.errorOutputAsJoinedString.ifBlank { "git ${command.name()} failed." })
    }
    return result
}

private fun explainPushFailure(result: GitCommandResult): String {
    val output = result.errorOutputAsJoinedString
    return when {
        "rejected" in output && ("fetch first" in output || "non-fast-forward" in output) ->
            "The remote has commits you don't have yet. Pull them first, then push again."
        "Authentication failed" in output || "could not read Username" in output ->
            "GitHub sign-in failed. Check your account under Settings > Version Control > GitHub."
        else -> output.ifBlank { "Push failed." }
    }
}

/** Reloads IntelliJ's view of the repository so the dashboard and the IDE's own Git UI update. */
private fun afterRepoChanged(project: Project, repo: GitRepository) {
    repo.update()
    VcsDirtyScopeManager.getInstance(project).rootDirty(repo.root)
}

private fun relativePath(repo: GitRepository, file: FilePath): String =
    FileUtil.getRelativePath(repo.root.path, file.path, '/')
        ?: throw GitOperationException("${file.path} is outside ${repo.root.path}.")

private fun tempFile(kind: String, content: String): File =
    FileUtil.createTempFile("git-dashboard-$kind", ".txt", true).apply { writeText(content, Charsets.UTF_8) }
