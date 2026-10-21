package dev.gitdashboard.toolwindow

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import dev.gitdashboard.git.GitOperationException
import dev.gitdashboard.git.listUnpushedCommits
import dev.gitdashboard.git.pushCurrentBranch
import dev.gitdashboard.git.tryGit
import dev.gitdashboard.github.GitHubApi
import dev.gitdashboard.github.GitHubApiException
import dev.gitdashboard.github.GitHubState
import dev.gitdashboard.github.GitHubStatusService
import dev.gitdashboard.github.PullRequest
import dev.gitdashboard.github.RepoCoordinates
import dev.gitdashboard.github.gitHubRemote
import dev.gitdashboard.notifyUser
import dev.gitdashboard.saveOpenFiles
import git4idea.branch.GitBranchUtil
import git4idea.commands.GitCommand
import git4idea.repo.GitRepository
import javax.swing.JComponent

/**
 * Opens a pull request for the current branch: suggests a title and description from its
 * commits, lets the user pick the base branch, pushes any unpushed commits, then creates it.
 */
fun createPullRequest(project: Project, repo: GitRepository, state: GitHubState.Ready) {
    val branch = state.branch ?: return
    val remoteBranch = state.remoteBranch ?: return

    object : Task.Backgroundable(project, "Preparing pull request", false) {
        private var subjects: List<String> = emptyList()
        private var bases: List<String> = emptyList()

        override fun run(indicator: ProgressIndicator) {
            // The remote for the repository the pull request goes to ("upstream" when in a fork, if the project has one).
            val remote = gitHubRemote(repo, state.repo)
                ?: repo.currentBranch?.let { GitBranchUtil.getTrackInfoForBranch(repo, it)?.remote } ?: return
            val log = tryGit(project, repo, GitCommand.LOG, "--format=%s", "${remote.name}/${state.defaultBranch}..HEAD") { setSilent(true) }
            subjects = if (log.success()) log.output.filter { it.isNotBlank() } else emptyList()
            bases = listOf(state.defaultBranch) + repo.branches.remoteBranches
                .filter { it.remote == remote }
                .map { it.nameForRemoteOperations }
                .filter { it != state.defaultBranch && it != "HEAD" && (state.isFork || it != remoteBranch) }
                .sorted()
        }

        override fun onSuccess() {
            val dialog = CreatePullRequestDialog(
                project, branch, state.repo.fullName, state.isFork, bases,
                suggestedTitle = subjects.singleOrNull() ?: humanize(branch),
                suggestedBody = if (subjects.size > 1) subjects.reversed().joinToString("\n") { "- $it" } else "",
            )
            // From a fork, GitHub needs to know whose branch it is: "my-name:feature".
            val head = if (state.isFork) "${state.headRepo.owner}:$remoteBranch" else remoteBranch
            if (dialog.showAndGet()) submit(project, repo, state.repo, head, dialog)
        }
    }.queue()
}

private fun submit(project: Project, repo: GitRepository, coordinates: RepoCoordinates, head: String, dialog: CreatePullRequestDialog) {
    val service = project.service<GitHubStatusService>()
    saveOpenFiles()
    object : Task.Backgroundable(project, "Creating pull request", false) {
        private var created: PullRequest? = null

        override fun run(indicator: ProgressIndicator) {
            // GitHub can only include commits it has, so send any that are still local.
            if (listUnpushedCommits(project, repo).isNotEmpty()) {
                indicator.text = "Pushing your commits"
                pushCurrentBranch(project, repo)
            }
            indicator.text = "Creating pull request on GitHub"
            val (_, token) = runBlockingCancellable { service.signedInAccount() }
                ?: throw GitHubApiException("Sign in to GitHub first (Settings > Version Control > GitHub).")
            created = GitHubApi(token, coordinates).createPullRequest(
                dialog.prTitle.trim(), dialog.body.trim(), head, dialog.base, dialog.draft,
            )
        }

        override fun onSuccess() {
            val pr = created ?: return
            notifyUser(
                project, NotificationType.INFORMATION, "Pull request #${pr.number} opened", pr.title,
                actions = listOf(NotificationAction.createSimpleExpiring("Open on GitHub") { BrowserUtil.browse(pr.url) }),
            )
        }

        override fun onThrowable(error: Throwable) {
            val reason = when (error) {
                is GitHubApiException, is GitOperationException -> error.message.orEmpty()
                else -> error.toString()
            }
            notifyUser(project, NotificationType.ERROR, "Couldn't open the pull request", reason)
        }

        override fun onFinished() = service.refresh()
    }.queue()
}

/** "feature/add-login_page" -> "Add login page". */
private fun humanize(branch: String): String =
    branch.substringAfterLast('/').replace(Regex("[-_]+"), " ").trim().replaceFirstChar { it.uppercase() }.ifEmpty { branch }

private class CreatePullRequestDialog(
    project: com.intellij.openapi.project.Project,
    private val branch: String,
    private val repoName: String,
    private val fromFork: Boolean,
    private val bases: List<String>,
    suggestedTitle: String,
    suggestedBody: String,
) : DialogWrapper(project) {
    var prTitle = suggestedTitle
    var body = suggestedBody
    var base: String = bases.first()
    var draft = false

    init {
        title = "Create Pull Request"
        setOKButtonText("Create Pull Request")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            val where = if (fromFork) "<b>$repoName</b> (the repository your fork came from)" else "<b>$repoName</b>"
            comment("Asks to merge <b>$branch</b> into the branch below on $where. Your unpushed commits are pushed first.")
        }
        row("Title:") {
            textField().bindText(::prTitle).columns(COLUMNS_LARGE).focused()
                .validationOnApply { if (it.text.isBlank()) error("Give the pull request a title.") else null }
        }
        row("Description:") {
            textArea().bindText(::body).rows(6).columns(COLUMNS_LARGE)
                .comment("What changed and why. Markdown works.")
        }
        row("Merge into:") {
            comboBox(bases).bindItem({ base }, { base = it ?: bases.first() })
        }
        row {
            checkBox("Open as a draft (not ready for review yet)").bindSelected(::draft)
        }
    }
}
