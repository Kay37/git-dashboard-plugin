package dev.gitdashboard.statusbar

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAwareAction
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
import dev.gitdashboard.git.RepoStatus
import dev.gitdashboard.git.loadRepoStatuses
import dev.gitdashboard.pluralize
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Color
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.JComponent

class SyncStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = SyncStatusBarWidget.ID
    override fun getDisplayName() = "Git Dashboard: Branch & Sync"
    override fun createWidget(project: Project, scope: CoroutineScope): StatusBarWidget = SyncStatusBarWidget(project, scope)
}

/**
 * Coloured chips in the status bar for the current repository, e.g.
 *
 *   [⎇ main ▾]  [↑ 2]  [↓ 1]
 *
 * The branch chip opens the branch switcher; the ↑ chip pushes; the ↓ / ✓ chips sync. Special
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

        scope.launch {
            refreshRequests.debounce(250).collectLatest {
                val current = withContext(Dispatchers.IO) {
                    currentRepository()?.let { repo -> loadRepoStatuses(project).find { it.repository == repo } }
                }
                withContext(Dispatchers.EDT) {
                    status = current
                    render()
                }
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
        bar.chips = if (status == null) emptyList() else listOf(branchChip(status)) + stateChips(status)
    }

    private fun branchChip(status: RepoStatus) = Chip(
        text = "${status.branch ?: "detached ${status.detachedAt.orEmpty()}"} ▾",
        color = BRANCH,
        solid = false,
        icon = AllIcons.Vcs.Branch,
        tooltip = html("Branch <b>${status.branch ?: "(detached)"}</b>", "Click to switch, create or delete branches."),
        onClick = { event -> openBranchSwitcher(status.repository, event) },
    )

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
                tooltip = html("Up to date with <b>$upstream</b> as of the last check.", "Click to sync and check for new commits."),
                onClick = { startSync(repo) }))

            else -> buildList {
                if (ahead > 0) add(Chip("↑ $ahead", TO_PUSH, solid = true,
                    tooltip = html("${pluralize(ahead, "commit")} to push to <b>$upstream</b>.",
                        if (behind > 0) "Click to sync: pull first, then push." else "Click to push (you can choose which commits)."),
                    onClick = { if (behind > 0) startSync(repo) else pushWithChoice(project, repo, ::requestRefresh) }))
                if (behind > 0) add(Chip("↓ $behind", TO_PULL, solid = true,
                    tooltip = html("${pluralize(behind, "commit")} to pull from <b>$upstream</b>.", "Click to sync."),
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
            action("Refresh", AllIcons.Actions.Refresh) { requestRefresh() },
            action("Open Git Dashboard", AllIcons.Vcs.Branch) {
                ToolWindowManager.getInstance(project).getToolWindow("Git Dashboard")?.show()
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
    }
}
