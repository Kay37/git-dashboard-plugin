package dev.gitdashboard.statusbar

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.text.DateFormatUtil
import com.intellij.vcs.log.impl.HashImpl
import com.intellij.vcs.log.impl.VcsLogNavigationUtil
import dev.gitdashboard.blame.relativeTime
import dev.gitdashboard.git.BackgroundFetchService
import dev.gitdashboard.git.FetchSettings
import dev.gitdashboard.git.LastCommit
import dev.gitdashboard.git.RepoStatus
import dev.gitdashboard.git.loadRepoStatuses
import dev.gitdashboard.github.ChecksSummary
import dev.gitdashboard.github.GitHubState
import dev.gitdashboard.github.GitHubStatusService
import dev.gitdashboard.notifyUser
import dev.gitdashboard.pluralize
import dev.gitdashboard.refreshRound
import dev.gitdashboard.toolwindow.publishToGitHub
import dev.gitdashboard.toolwindow.pushWithChoice
import dev.gitdashboard.toolwindow.resolveConflicts
import dev.gitdashboard.toolwindow.showBranchSwitcher
import dev.gitdashboard.toolwindow.syncInBackground
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Color
import java.awt.Point
import java.awt.event.MouseEvent
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.swing.JComponent

class SyncStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = SyncStatusBarWidget.ID
    override fun getDisplayName() = "Git Dashboard: Branch & Sync"
    override fun createWidget(project: Project, scope: CoroutineScope): StatusBarWidget = SyncStatusBarWidget(project, scope)
}

/**
 * Coloured chips in the status bar for the current repository, e.g.
 *
 *   [⎇ main ▾]  [🕑 2h ago]  [↑ 2]  [↓ 1]
 *
 * The branch chip opens the branch switcher; the clock chip shows how long ago the last commit
 * was (click: see it in the Git log); the ↑ chip pushes; the ↓ / ✓ chips sync. ↓ knows about new
 * GitHub commits because [BackgroundFetchService] checks for them every few minutes. Special
 * states (not published, merge conflict, syncing) get their own chip. Right-click for a menu.
 */
class SyncStatusBarWidget(private val project: Project, private val scope: CoroutineScope) : CustomStatusBarWidget {
    private val bar = ChipBar(onPopupTrigger = ::showMenu).apply { isVisible = false }
    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    @Volatile private var status: RepoStatus? = null
    private var syncing = false

    override fun ID() = ID

    override fun getComponent(): JComponent = bar

    @OptIn(FlowPreview::class)
    override fun install(statusBar: StatusBar) {
        val connection = project.messageBus.connect(this)
        connection.subscribe(GitRepository.GIT_REPO_CHANGE, object : GitRepositoryChangeListener {
            override fun repositoryChanged(repository: GitRepository) = requestRefresh()
        })
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = requestRefresh()
        })

        // A failed round keeps the chips as they were; the next change or GitHub update tries again.
        scope.launch {
            refreshRequests.debounce(250).collectLatest {
                refreshRound("the status bar chips") {
                    val current = withContext(Dispatchers.IO) {
                        currentRepository()?.let { repo -> loadRepoStatuses(project).find { it.repository == repo } }
                    }
                    withContext(Dispatchers.EDT) {
                        status = current
                        render()
                    }
                }
            }
        }
        scope.launch {
            project.service<GitHubStatusService>().states.collect {
                refreshRound("the status bar's GitHub chips") { withContext(Dispatchers.EDT) { render() } }
            }
        }
        // A check that found nothing new changes no refs, so re-read the "last checked" time here.
        scope.launch { project.service<BackgroundFetchService>().checks.collect { requestRefresh() } }
        // Keeps "5m ago" and "last checked" counting up while nothing else changes.
        scope.launch {
            while (true) {
                delay(TimeUnit.MINUTES.toMillis(1))
                refreshRound("the status bar's clock") { withContext(Dispatchers.EDT) { render() } }
            }
        }
        requestRefresh()
    }

    private fun requestRefresh() {
        refreshRequests.tryEmit(Unit)
    }

    /** The repository of the file being edited, or the project's only/first repository. */
    private fun currentRepository(): GitRepository? {
        val manager = GitRepositoryManager.getInstance(project)
        val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        return file?.let { manager.getRepositoryForFileQuick(it) } ?: manager.repositories.firstOrNull()
    }

    // ---- chips --------------------------------------------------------------------------------

    private fun render() {
        val status = status
        bar.isVisible = status != null
        bar.chips = if (status == null) emptyList() else listOf(branchChip(status)) + listOfNotNull(lastCommitChip(status)) + stateChips(status) + gitHubChips(status.repository)
    }

    /** "PR #12" for the branch's pull request and a CI chip coloured by its checks, when on GitHub. */
    private fun gitHubChips(repo: GitRepository): List<Chip> {
        val state = project.service<GitHubStatusService>().stateFor(repo) as? GitHubState.Ready ?: return emptyList()
        return buildList {
            state.currentPullRequest?.let { pr ->
                add(Chip("PR #${pr.number}", PULL_REQUEST, solid = false, icon = AllIcons.Vcs.Vendors.Github,
                    tooltip = html("Pull request <b>#${pr.number}</b>: ${pr.title.escapeHtml()}", "Click to open it on GitHub."),
                    onClick = { BrowserUtil.browse(pr.url) }))
            }
            state.currentChecks?.let { checks ->
                val (symbol, color) = when (checks.state) {
                    ChecksSummary.State.PASSED -> "✓" to SYNCED
                    ChecksSummary.State.RUNNING -> "●" to CI_RUNNING
                    ChecksSummary.State.FAILED -> "✗" to CONFLICT
                }
                add(Chip("$symbol CI", color, solid = checks.state != ChecksSummary.State.PASSED,
                    tooltip = html(checks.description + ".", "Click to see the checks on GitHub."),
                    onClick = { BrowserUtil.browse(checks.url) }))
            }
        }
    }

    private fun String.escapeHtml() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun branchChip(status: RepoStatus) = Chip(
        text = "${status.branch ?: "detached ${status.detachedAt.orEmpty()}"} ▾",
        color = BRANCH,
        solid = false,
        icon = AllIcons.Vcs.Branch,
        tooltip = html("Branch <b>${status.branch ?: "(detached)"}</b>", "Click to switch, create or delete branches."),
        onClick = { event -> openBranchSwitcher(status.repository, event) },
    )

    /** "2h ago" since the commit the branch is on; click to see that commit in IntelliJ's Git log. */
    private fun lastCommitChip(status: RepoStatus): Chip? {
        val commit = status.lastCommit ?: return null
        return Chip(
            text = shortAge(commit.time),
            color = NEUTRAL,
            solid = false,
            icon = AllIcons.Vcs.History,
            tooltip = html(
                "Last commit${status.branch?.let { " on <b>$it</b>" }.orEmpty()}: <b>${relativeTime(Date(commit.time))}</b>" +
                    " (${DateFormatUtil.formatDateTime(commit.time)})",
                "“${commit.subject.escapeHtml()}” by ${commit.author.escapeHtml()}",
                "Click to see it in the Git log.",
            ),
            onClick = { showInGitLog(status.repository, commit) },
        )
    }

    /** "just now", "5m ago", "3h ago", "2d ago", "3w ago", "4mo ago", "2y ago". */
    private fun shortAge(time: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - time).coerceAtLeast(0)
        val hours = minutes / 60
        val days = hours / 24
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days < 7 -> "${days}d ago"
            days < 30 -> "${days / 7}w ago"
            days < 365 -> "${days / 30}mo ago"
            else -> "${days / 365}y ago"
        }
    }

    /** Last line of the ↑ / ↓ / ✓ tooltips: when GitHub was last asked for new commits, or why it couldn't be. */
    private fun lastCheckedLine(status: RepoStatus): String {
        val check = project.service<BackgroundFetchService>().checks.value[status.repository.root]
        // A Sync or IntelliJ's own fetch after a failed background check counts as a newer, good check.
        if (check?.failure != null && (status.lastFetched ?: 0) < check.at) {
            return "<i>Couldn't check for new commits ${relativeTime(Date(check.at))}: ${check.failure.escapeHtml()}</i>"
        }
        val checked = listOfNotNull(status.lastFetched, check?.at).maxOrNull()
            ?: return "<i>Not checked for new commits yet.</i>"
        return "<i>Checked for new commits ${relativeTime(Date(checked))}.</i>"
    }

    private fun stateChips(status: RepoStatus): List<Chip> {
        val repo = status.repository
        val ahead = status.ahead ?: 0
        val behind = status.behind ?: 0
        val upstream = status.upstream.orEmpty()
        return when {
            syncing -> listOf(Chip("Syncing…", NEUTRAL, solid = false, icon = AnimatedIcon.Default.INSTANCE,
                tooltip = html("Syncing with ${upstream.ifEmpty { "the remote" }}…")))

            status.operationInProgress || status.conflicts > 0 -> {
                val operation = if (status.state.name == "REBASING") "Rebase" else "Merge"
                listOf(Chip("⚠ $operation conflict", CONFLICT, solid = true,
                    tooltip = html("A ${operation.lowercase()} stopped on ${pluralize(status.conflicts, "conflict")}.", "Click to resolve them."),
                    onClick = { resolveConflicts(project, repo, ::requestRefresh) }))
            }

            !status.hasRemotes -> listOf(Chip("☁ Publish to GitHub", PUBLISH, solid = false,
                tooltip = html("This repository is only on your computer.", "Click to create a GitHub repository for it."),
                onClick = { publishToGitHub(project, repo, ::requestRefresh) }))

            status.upstream == null -> listOf(Chip("↑ Publish branch", TO_PUSH, solid = true,
                tooltip = html("This branch isn't on the remote yet.", "Click to publish it."),
                onClick = { pushWithChoice(project, repo, ::requestRefresh) }))

            ahead == 0 && behind == 0 -> listOf(Chip("✓ Synced", SYNCED, solid = false,
                tooltip = html("Up to date with <b>$upstream</b>.", lastCheckedLine(status), "Click to sync now."),
                onClick = { startSync(repo) }))

            else -> buildList {
                if (ahead > 0) add(Chip("↑ $ahead", TO_PUSH, solid = true,
                    tooltip = html("${pluralize(ahead, "commit")} to push to <b>$upstream</b>.", lastCheckedLine(status),
                        if (behind > 0) "Click to sync: pull first, then push." else "Click to push (you can choose which commits)."),
                    onClick = { if (behind > 0) startSync(repo) else pushWithChoice(project, repo, ::requestRefresh) }))
                if (behind > 0) add(Chip("↓ $behind", TO_PULL, solid = true,
                    tooltip = html("${pluralize(behind, "commit")} to pull from <b>$upstream</b>.", lastCheckedLine(status), "Click to sync."),
                    onClick = { startSync(repo) }))
            }
        }
    }

    private fun html(vararg lines: String) = "<html>${lines.joinToString("<br>")}<br><br><i>Right-click for more.</i></html>"

    // ---- actions ------------------------------------------------------------------------------

    private fun startSync(repo: GitRepository) {
        if (syncing) return
        syncing = true
        render()
        syncInBackground(project, repo) {
            syncing = false
            render()
            requestRefresh()
        }
    }

    /** Opens IntelliJ's Git log with [commit] selected. */
    private fun showInGitLog(repo: GitRepository, commit: LastCommit) {
        VcsLogNavigationUtil.jumpToRevisionAsync(project, repo.root, HashImpl.build(commit.hash))
    }

    /** Refresh from the menu: ask GitHub for new commits now (even if the timer is off), then redraw. */
    private fun checkNowAndRefresh(repo: GitRepository) {
        scope.launch {
            val check = refreshRound("the requested check for new commits") {
                project.service<BackgroundFetchService>().checkNow(userAsked = true)[repo.root]
            }
            requestRefresh()
            // Asked for, so say if it failed; otherwise the chips themselves are the answer.
            check?.failure?.let { reason ->
                withContext(Dispatchers.EDT) { notifyUser(project, NotificationType.WARNING, "Couldn't check for new commits", reason) }
            }
        }
    }

    private fun openBranchSwitcher(repo: GitRepository, event: MouseEvent) {
        showBranchSwitcher(project, repo, ::requestRefresh) { popup -> showAbove(popup, event) }
    }

    /** Opens upward from the click, since the status bar sits at the bottom of the window. */
    private fun showAbove(popup: JBPopup, event: MouseEvent) {
        val height = popup.content.preferredSize.height
        popup.show(RelativePoint(bar, Point(event.x, -height)))
    }

    private fun showMenu(event: MouseEvent) {
        val status = status ?: return
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            null, menu(status, event), DataContext.EMPTY_CONTEXT, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true,
        )
        showAbove(popup, event)
    }

    private fun menu(status: RepoStatus, event: MouseEvent): ActionGroup {
        val repo = status.repository
        fun action(text: String, icon: javax.swing.Icon?, enabled: Boolean = true, run: () -> Unit) =
            object : DumbAwareAction(text, null, icon) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = enabled && !syncing
                }
                override fun actionPerformed(e: AnActionEvent) = run()
            }
        return DefaultActionGroup(
            action("Sync", AllIcons.Actions.CheckOut, enabled = status.upstream != null && !status.operationInProgress) { startSync(repo) },
            action("Push…", AllIcons.Vcs.Push, enabled = status.hasUnpushedCommits && status.hasRemotes) { pushWithChoice(project, repo, ::requestRefresh) },
            action("Switch Branch…", AllIcons.Vcs.Branch) { openBranchSwitcher(repo, event) },
            action("Refresh", AllIcons.Actions.Refresh) { checkNowAndRefresh(repo) },
            action("Open Git Dashboard", AllIcons.Vcs.Branch) {
                ToolWindowManager.getInstance(project).getToolWindow("Git Dashboard")?.show()
            },
            Separator.getInstance(),
            object : DumbAwareToggleAction("Check for New Commits Every ${BackgroundFetchService.INTERVAL_MINUTES} Minutes") {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                override fun isSelected(e: AnActionEvent) = FetchSettings.autoFetch
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    FetchSettings.autoFetch = state
                    if (state) scope.launch { refreshRound("the check for new commits") { project.service<BackgroundFetchService>().checkNow() } }
                }
            },
        )
    }

    override fun dispose() = Unit

    companion object {
        const val ID = "GitDashboard.Sync"

        private val BRANCH = JBColor(Color(0x6E5BD6), Color(0x9D8FEB))
        private val TO_PUSH = JBColor(Color(0xDB7A1F), Color(0xD9822B))
        private val TO_PULL = JBColor(Color(0x3574F0), Color(0x3F7EE8))
        private val SYNCED = JBColor(Color(0x2E9E4F), Color(0x5FB865))
        private val CONFLICT = JBColor(Color(0xD64545), Color(0xC94F4F))
        private val PUBLISH = JBColor(Color(0x2A9D8F), Color(0x4DB6AC))
        private val NEUTRAL = JBColor(Color(0x808080), Color(0x9A9A9A))
        private val PULL_REQUEST = JBColor(Color(0x2A9D8F), Color(0x4DB6AC))
        private val CI_RUNNING = JBColor(Color(0xC98A00), Color(0xC9A227))
    }
}
