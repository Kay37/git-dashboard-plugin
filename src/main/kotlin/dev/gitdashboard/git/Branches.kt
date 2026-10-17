package dev.gitdashboard.git

import com.intellij.openapi.project.Project
import git4idea.commands.GitCommand
import git4idea.repo.GitRepository

// All functions here run git and block: call them from a background task, never on the EDT.

data class BranchInfo(
    /** "main" for a local branch, "origin/feature" for a remote one. */
    val name: String,
    val isRemote: Boolean,
    val isCurrent: Boolean,
    /** Commit time of the branch tip, in epoch seconds. */
    val lastCommitTime: Long,
    /** Commits not yet pushed / pulled relative to its upstream; 0 when unknown or none. */
    val ahead: Int,
    val behind: Int,
) {
    /** Local name to use when checking out a remote branch: "origin/feature" -> "feature". */
    val localName: String get() = if (isRemote) name.substringAfter('/') else name
}

/**
 * Local branches (current first, then most recently committed to), followed by remote branches
 * that no local branch tracks yet.
 */
fun loadBranches(project: Project, repo: GitRepository): List<BranchInfo> {
    val result = runGit(
        project, repo, GitCommand.FOR_EACH_REF,
        "--sort=-committerdate",
        "--format=%(refname)%09%(committerdate:unix)%09%(upstream:short)%09%(upstream:track,nobracket)",
        "refs/heads", "refs/remotes",
    )
    val current = repo.currentBranchName
    val rows = result.output.mapNotNull { line -> line.split('\t').takeIf { it.size >= 4 } }

    val local = rows.filter { it[0].startsWith("refs/heads/") }.map { row ->
        val name = row[0].removePrefix("refs/heads/")
        BranchInfo(
            name = name,
            isRemote = false,
            isCurrent = name == current,
            lastCommitTime = row[1].toLongOrNull() ?: 0,
            ahead = Regex("ahead (\\d+)").find(row[3])?.groupValues?.get(1)?.toInt() ?: 0,
            behind = Regex("behind (\\d+)").find(row[3])?.groupValues?.get(1)?.toInt() ?: 0,
        )
    }
    val tracked = rows.filter { it[0].startsWith("refs/heads/") }.map { it[2] }.filter { it.isNotEmpty() }.toSet()
    val localNames = local.map { it.name }.toSet()
    val remote = rows.filter { it[0].startsWith("refs/remotes/") && !it[0].endsWith("/HEAD") }
        .map { row -> BranchInfo(row[0].removePrefix("refs/remotes/"), true, false, row[1].toLongOrNull() ?: 0, 0, 0) }
        .filter { it.name !in tracked && it.localName !in localNames }

    return local.sortedByDescending { it.isCurrent } + remote
}

/**
 * Local branches whose work is already contained in the current branch, so deleting them loses
 * nothing. The current branch and long-lived ones (main, master, develop...) are never included.
 */
fun mergedBranches(project: Project, repo: GitRepository): List<String> {
    val current = repo.currentBranchName ?: return emptyList()
    return runGit(project, repo, GitCommand.BRANCH, "--merged", "HEAD", "--format=%(refname:short)").output
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != current && it.lowercase() !in PROTECTED_BRANCHES }
}

/** Null when [name] is a usable new branch name, otherwise why it isn't (in plain words). */
fun branchNameProblem(repo: GitRepository, name: String): String? = when {
    name.isBlank() -> "Enter a name."
    name.any { it.isWhitespace() } -> "Branch names can't contain spaces. Try dashes: my-feature."
    name.any { it in "~^:?*[\\" } || ".." in name || "@{" in name || name == "@" ->
        "Branch names can't contain ~ ^ : ? * [ \\ .. or @{."
    name.startsWith("-") || name.startsWith("/") || name.endsWith("/") || name.endsWith(".") || name.endsWith(".lock") ->
        "Branch names can't start with - or /, or end with /, . or .lock."
    repo.branches.findLocalBranch(name) != null -> "You already have a branch called \"$name\"."
    else -> null
}

private val PROTECTED_BRANCHES = setOf("main", "master", "develop", "development", "dev", "trunk", "release")
