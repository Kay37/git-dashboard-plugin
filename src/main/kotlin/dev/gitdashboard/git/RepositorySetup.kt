package dev.gitdashboard.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.actions.GitInit
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository

// All functions here run git and block: call them from a background task, never on the EDT.

/**
 * Turns [directory] into a Git repository and registers it with IntelliJ so the IDE (and the
 * dashboard) start tracking it. The first branch is "main" unless the user's git config names
 * another default.
 */
fun initRepository(project: Project, directory: VirtualFile) {
    val git = Git.getInstance()
    val configuredDefault = git.runCommand(GitLineHandler(project, directory, GitCommand.CONFIG).apply {
        addParameters("--get", "init.defaultBranch")
        setSilent(true)
    })
    val init = GitLineHandler(project, directory, GitCommand.INIT).apply {
        if (!configuredDefault.success() || configuredDefault.outputAsJoinedString.isBlank()) addParameters("--initial-branch=main")
    }
    val result = git.runCommand(init)
    if (!result.success()) throw GitOperationException(result.errorOutputAsJoinedString.ifBlank { "git init failed." })
    GitInit.refreshAndConfigureVcsMappings(project, directory, directory.path)
}

/** Whether the repository has at least one commit (a brand-new one has nothing to push yet). */
fun hasCommits(repo: GitRepository): Boolean = repo.currentRevision != null

fun addRemote(project: Project, repo: GitRepository, name: String, url: String) {
    runGit(project, repo, GitCommand.REMOTE, "add", name, url)
    afterRepoChanged(project, repo)
}

/** Renames a remote and/or changes its URL. */
fun editRemote(project: Project, repo: GitRepository, oldName: String, newName: String, url: String) {
    if (newName != oldName) runGit(project, repo, GitCommand.REMOTE, "rename", oldName, newName)
    runGit(project, repo, GitCommand.REMOTE, "set-url", newName, url)
    afterRepoChanged(project, repo)
}

/** Removes the link to a remote. The repository on the server is not touched. */
fun removeRemote(project: Project, repo: GitRepository, name: String) {
    runGit(project, repo, GitCommand.REMOTE, "remove", name)
    afterRepoChanged(project, repo)
}

/** Remote names git accepts and that are easy to type: letters, digits, '.', '_' and '-'. */
fun isValidRemoteName(name: String): Boolean = name.matches(Regex("[A-Za-z0-9._-]+")) && !name.startsWith("-")
