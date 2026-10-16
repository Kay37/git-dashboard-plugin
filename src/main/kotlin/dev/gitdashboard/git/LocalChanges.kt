package dev.gitdashboard.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

/**
 * Uncommitted changes in the project's Git repositories. Untracked (new, never added) files are
 * included as additions, so the user sees and commits them like any other change.
 */
fun collectLocalChanges(project: Project): List<Change> {
    val changeListManager = ChangeListManager.getInstance(project)
    val repositories = GitRepositoryManager.getInstance(project)
    val tracked = changeListManager.allChanges.filter { repositories.getRepositoryForFileQuick(ChangesUtil.getFilePath(it)) != null }
    val untracked = changeListManager.unversionedFilesPaths
        .filter { repositories.getRepositoryForFileQuick(it) != null }
        .map { Change(null, CurrentContentRevision(it)) }
    return tracked + untracked
}

fun Change.repository(project: Project): GitRepository? =
    GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(ChangesUtil.getFilePath(this))

/** Every path a commit must include for this change: both sides of a rename or move. */
fun Change.affectedPaths(): List<FilePath> =
    listOfNotNull(beforeRevision?.file, afterRevision?.file).distinct()
