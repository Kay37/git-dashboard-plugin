package dev.gitdashboard.toolwindow

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import dev.gitdashboard.git.GitOperationException
import dev.gitdashboard.git.addRemote
import dev.gitdashboard.git.hasCommits
import dev.gitdashboard.git.initRepository
import dev.gitdashboard.git.pushCurrentBranch
import dev.gitdashboard.github.CreatedRepository
import dev.gitdashboard.github.createGitHubRepository
import dev.gitdashboard.github.suggestRepositoryName
import dev.gitdashboard.notifyUser
import git4idea.repo.GitRepository
import org.jetbrains.plugins.github.api.GithubServerPath
import org.jetbrains.plugins.github.authentication.GHAccountsUtil
import org.jetbrains.plugins.github.authentication.GHLoginSource
import org.jetbrains.plugins.github.util.GHCompatibilityUtil

/** Asks, then runs `git init` in the project folder. */
fun createRepositoryWithConfirmation(project: Project, onFinished: () -> Unit = {}) {
    val directory = project.guessProjectDir() ?: return
    val answer = Messages.showOkCancelDialog(
        project,
        "Start tracking this project with Git?\n\nA new repository is created in ${directory.presentableUrl}. Nothing is uploaded anywhere.",
        "Create Git Repository", "Create", "Cancel", Messages.getQuestionIcon(),
    )
    if (answer != Messages.OK) return

    object : Task.Backgroundable(project, "Creating Git repository", false) {
        override fun run(indicator: ProgressIndicator) = initRepository(project, directory)
        override fun onSuccess() = notifyUser(
            project, NotificationType.INFORMATION, "Git repository created",
            "Make a first commit, then publish it to GitHub or add a remote.",
        )
        override fun onThrowable(error: Throwable) =
            notifyUser(project, NotificationType.ERROR, "Couldn't create the repository", (error as? GitOperationException)?.message ?: error.toString())
        override fun onFinished() = onFinished()
    }.queue()
}

/**
 * Creates a GitHub repository for [repo], adds it as a remote and (optionally) pushes. Signs the
 * user in to GitHub first if IntelliJ has no GitHub account yet.
 */
fun publishToGitHub(project: Project, repo: GitRepository, onFinished: () -> Unit = {}) {
    val accounts = GHAccountsUtil.accounts.toList().ifEmpty {
        listOfNotNull(GHCompatibilityUtil.requestNewAccountForServer(GithubServerPath.DEFAULT_SERVER, project, GHLoginSource.SHARE))
    }
    if (accounts.isEmpty()) return
    val defaultAccount = GHAccountsUtil.getDefaultAccount(project)?.takeIf { it in accounts } ?: accounts.first()

    val dialog = PublishToGitHubDialog(
        project, accounts, defaultAccount,
        defaultName = suggestRepositoryName(repo.root.name),
        existingRemotes = repo.remotes.map { it.name }.toSet(),
        hasCommits = hasCommits(repo),
    )
    if (!dialog.showAndGet()) return
    val account = dialog.account ?: return
    val name = dialog.repositoryName.trim()
    val remoteName = dialog.remoteName.trim()

    object : Task.Backgroundable(project, "Publishing to GitHub", false) {
        private var created: CreatedRepository? = null
        private var pushedTo: String? = null

        override fun run(indicator: ProgressIndicator) {
            indicator.text = "Creating $name on GitHub"
            val repository = createGitHubRepository(project, indicator, account, name, dialog.description.trim(), dialog.isPrivate)
            created = repository
            indicator.text = "Adding remote $remoteName"
            addRemote(project, repo, remoteName, repository.cloneUrl)
            if (dialog.pushNow) {
                indicator.text = "Pushing to $remoteName"
                pushedTo = pushCurrentBranch(project, repo, publishTo = remoteName)
            }
        }

        override fun onSuccess() {
            val repository = created ?: return
            val visibility = if (dialog.isPrivate) "private" else "public"
            val pushed = pushedTo?.let { " and pushed to $it" } ?: ""
            notifyUser(
                project, NotificationType.INFORMATION, "Published to GitHub",
                "Created the $visibility repository $name$pushed.",
                actions = listOf(NotificationAction.createSimple("Open on GitHub") { BrowserUtil.browse(repository.webUrl) }),
            )
        }

        override fun onThrowable(error: Throwable) {
            val reason = (error as? GitOperationException)?.message ?: error.toString()
            val title = if (created == null) "Couldn't create the GitHub repository" else "Created $name on GitHub, but a later step failed"
            notifyUser(project, NotificationType.ERROR, title, reason)
        }

        override fun onFinished() = onFinished()
    }.queue()
}
