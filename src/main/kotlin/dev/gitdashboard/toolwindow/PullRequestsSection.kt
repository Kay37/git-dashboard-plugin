package dev.gitdashboard.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.gitdashboard.GitDashboardScope
import dev.gitdashboard.blame.relativeTime
import dev.gitdashboard.github.ChecksSummary
import dev.gitdashboard.github.GitHubState
import dev.gitdashboard.github.GitHubStatusService
import dev.gitdashboard.github.PullRequest
import dev.gitdashboard.notifyUser
import git4idea.branch.GitBrancher
import git4idea.commands.Git
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.github.api.GithubServerPath
import org.jetbrains.plugins.github.authentication.GHLoginSource
import org.jetbrains.plugins.github.util.GHCompatibilityUtil
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.JPanel

/**
 * "Pull requests" part of the Git Dashboard: the current branch's pull request and its checks
 * (or a Create link), then the repository's other open pull requests with a checks dot each.
 * Hidden for repositories that aren't on GitHub.
 */
class PullRequestsSection(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {
    private val service = project.service<GitHubStatusService>()

    init {
        isOpaque = false
        val job = project.service<GitDashboardScope>().cs.launch {
            service.states.collect { states -> withContext(Dispatchers.EDT) { render(states) } }
        }
        Disposer.register(parent) { job.cancel() }
    }

    private fun render(states: Map<com.intellij.openapi.vfs.VirtualFile, GitHubState>) {
        removeAll()
        val manager = GitRepositoryManager.getInstance(project)
        val shown = states.mapNotNull { (root, state) ->
            manager.getRepositoryForRootQuick(root)?.takeIf { state != GitHubState.NotOnGitHub }?.let { it to state }
        }
        isVisible = shown.isNotEmpty()
        if (shown.isNotEmpty()) {
            add(panel {
                shown.forEach { (repo, state) -> section(repo, state, showRepoName = shown.size > 1) }
            }.apply {
                border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(6, 12, 8, 12))
            }, BorderLayout.CENTER)
        }
        revalidate()
        repaint()
    }

    private fun Panel.section(repo: GitRepository, state: GitHubState, showRepoName: Boolean) {
        row {
            label(if (showRepoName) "Pull requests · ${repo.root.name}" else "Pull requests").bold()
            when (state) {
                is GitHubState.Ready -> link("Open on GitHub") { BrowserUtil.browse(state.repo.pullsUrl) }
                is GitHubState.NeedsSignIn -> link("Open on GitHub") { BrowserUtil.browse(state.repo.pullsUrl) }
                else -> {}
            }
        }
        when (state) {
            GitHubState.NotOnGitHub -> {}
            is GitHubState.NeedsSignIn -> {
                row { secondary("Sign in to GitHub to see pull requests and whether your checks pass.") }
                row {
                    link("Sign in to GitHub…") {
                        if (GHCompatibilityUtil.requestNewAccountForServer(GithubServerPath.DEFAULT_SERVER, project, GHLoginSource.PR_TW) != null) {
                            service.refresh()
                        }
                    }
                }
            }
            is GitHubState.Failed -> {
                row { secondary(state.message) }
                row { link("Try again") { service.refresh() } }
            }
            is GitHubState.Ready -> ready(repo, state)
        }
    }

    private fun Panel.ready(repo: GitRepository, state: GitHubState.Ready) {
        // This branch first: its pull request, or a way to open one.
        val current = state.currentPullRequest
        when {
            current != null -> {
                row {
                    icon(AllIcons.Vcs.Vendors.Github)
                    link("#${current.number} ${current.title.ellipsize(48)}") { BrowserUtil.browse(current.url) }
                        .applyToComponent { toolTipText = "Open this branch's pull request on GitHub" }
                    if (current.draft) secondary("draft")
                }
            }
            state.branch == null -> {}
            state.branch == state.defaultBranch ->
                row { secondary("You're on ${state.defaultBranch}. Start a branch to open a pull request.") }
            state.remoteBranch == null ->
                row { secondary("Publish this branch to open a pull request for it.") }
            else ->
                row {
                    link("＋ Create Pull Request…") { createPullRequest(project, repo, state) }
                        .applyToComponent { toolTipText = "Ask to merge ${state.branch} into ${state.defaultBranch}" }
                }
        }
        state.currentChecks?.let { checks ->
            row {
                checksLabel(checks, checks.description)
                link("Details") { BrowserUtil.browse(checks.url) }
            }
        }

        // Everyone else's open pull requests.
        val others = state.otherPullRequests
        if (others.isEmpty() && current == null) {
            row { secondary("No open pull requests.") }
        }
        others.take(SHOWN_PULL_REQUESTS).forEach { pr ->
            row {
                val checks = state.pullRequestChecks[pr.number]
                if (checks != null) checksLabel(checks, symbol(checks.state)).applyToComponent { toolTipText = checks.description }
                link("#${pr.number} ${pr.title.ellipsize(40)}") { BrowserUtil.browse(pr.url) }
                secondary("${pr.author} · ${relativeTime(pr.updatedAt)}${if (pr.draft) " · draft" else ""}")
                if (pr.headRepo.equals(state.repo.fullName, ignoreCase = true) && pr.headRef != state.branch) {
                    link("Check out") { checkOut(repo, pr) }.applyToComponent { toolTipText = "Switch to ${pr.headRef} to try it locally" }
                }
            }
        }
        if (others.size > SHOWN_PULL_REQUESTS) {
            row { link("See all ${others.size} on GitHub") { BrowserUtil.browse(state.repo.pullsUrl) } }
        }
    }

    /** Fetches the pull request's branch, then switches to it (creating a local tracking branch if needed). */
    private fun checkOut(repo: GitRepository, pr: PullRequest) {
        val remote = repo.remotes.firstOrNull { remote -> remote.urls.any { "github.com" in it } } ?: return
        object : Task.Backgroundable(project, "Fetching ${pr.headRef}", false) {
            override fun run(indicator: ProgressIndicator) {
                Git.getInstance().fetch(repo, remote, emptyList(), "refs/heads/${pr.headRef}:refs/remotes/${remote.name}/${pr.headRef}")
                repo.update()
            }
            override fun onSuccess() {
                val brancher = GitBrancher.getInstance(project)
                if (repo.branches.findLocalBranch(pr.headRef) != null) brancher.checkout(pr.headRef, false, listOf(repo), service::refresh)
                else brancher.checkoutNewBranchStartingFrom(pr.headRef, "${remote.name}/${pr.headRef}", listOf(repo), service::refresh)
            }
            override fun onThrowable(error: Throwable) =
                notifyUser(project, NotificationType.ERROR, "Couldn't check out #${pr.number}", error.message.orEmpty())
        }.queue()
    }

    private fun Row.secondary(text: String) = label(text).applyToComponent { foreground = UIUtil.getContextHelpForeground() }

    private fun Row.checksLabel(checks: ChecksSummary, text: String) =
        label(if (text.startsWith(symbol(checks.state))) text else "${symbol(checks.state)} $text")
            .applyToComponent { foreground = colorOf(checks.state) }

    private fun symbol(state: ChecksSummary.State) = when (state) {
        ChecksSummary.State.PASSED -> "✓"
        ChecksSummary.State.RUNNING -> "●"
        ChecksSummary.State.FAILED -> "✗"
    }

    private fun colorOf(state: ChecksSummary.State): Color = when (state) {
        ChecksSummary.State.PASSED -> CHECKS_PASSED
        ChecksSummary.State.RUNNING -> CHECKS_RUNNING
        ChecksSummary.State.FAILED -> CHECKS_FAILED
    }

    private fun String.ellipsize(max: Int) = if (length <= max) this else take(max - 1).trimEnd() + "…"

    companion object {
        private const val SHOWN_PULL_REQUESTS = 5

        val CHECKS_PASSED = JBColor(Color(0x2E9E4F), Color(0x5FB865))
        val CHECKS_RUNNING = JBColor(Color(0xC98A00), Color(0xE0B040))
        val CHECKS_FAILED = JBColor(Color(0xD64545), Color(0xE06C6C))
    }
}
