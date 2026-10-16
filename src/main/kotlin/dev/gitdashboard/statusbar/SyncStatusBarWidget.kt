package dev.gitdashboard.statusbar

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.ClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import dev.gitdashboard.git.RepoStatus
import dev.gitdashboard.git.loadRepoStatuses
import dev.gitdashboard.toolwindow.publishToGitHub
import dev.gitdashboard.toolwindow.pushWithChoice
import dev.gitdashboard.toolwindow.resolveConflicts
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
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

class SyncStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = SyncStatusBarWidget.ID
    override fun getDisplayName() = "Git Dashboard: Branch & Sync"
    override fun createWidget(project: Project, scope: CoroutineScope): StatusBarWidget = SyncStatusBarWidget(project, scope)
}

/**
 * Status bar button showing the current repository's branch and how far it is from the remote
 * ("⎇ main ↑2 ↓1"). Clicking does the obvious next step: Sync, Publish, or resolve conflicts.
 * Right-click for more.
 */
class SyncStatusBarWidget(private val project: Project, private val scope: CoroutineScope) : CustomStatusBarWidget {
    private val label = SimpleColoredComponent().apply {
        isOpaque = false
        border = JBUI.Borders.empty(0, 6)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isVisible = false
    }
    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    @Volatile private var status: RepoStatus? = null
    private var syncing = false

    override fun ID() = ID

    override fun getComponent(): JComponent = label

    @OptIn(FlowPreview::class)
    override fun install(statusBar: com.intellij.openapi.wm.StatusBar) {
        object : ClickListener() {
            override fun onClick(event: MouseEvent, clickCount: Int): Boolean {
                if (event.button != MouseEvent.BUTTON1) return false
                onPrimaryClick()
                return true
            }
        }.installOn(label)
        label.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShowMenu(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowMenu(e)
        })

        val connection = project.messageBus.connect(this)
        connection.subscribe(GitRepository.GIT_REPO_CHANGE, object : GitRepositoryChangeListener {
            override fun repositoryChanged(repository: GitRepository) = requestRefresh()
        })
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = requestRefresh()
        })

        scope.launch {
            refreshRequests.debounce(250).collectLatest {
                val current = withContext(Dispatchers.IO) { currentRepository()?.let { repo -> loadRepoStatuses(project).find { it.repository == repo } } }
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

    // ---- what's shown ---------------------------------------------------------------------------

    private fun render() {
        val status = status
        label.clear()
        label.isVisible = status != null
        if (status == null) return

        label.icon = AllIcons.Vcs.Branch
        label.append(status.branch ?: "detached ${status.detachedAt.orEmpty()}", SimpleTextAttributes.REGULAR_ATTRIBUTES)
        label.append("  ")

        when {
            syncing -> label.append("⟳ Syncing…", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            status.operationInProgress || status.conflicts > 0 ->
                label.append("⚠ ${operationName(status)} in progress", colored(WARNING))
            !status.hasRemotes -> label.append("local only", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            status.upstream == null -> label.append("not published", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            (status.ahead ?: 0) == 0 && (status.behind ?: 0) == 0 -> label.append("✓", colored(UP_TO_DATE))
            else -> {
                if ((status.ahead ?: 0) > 0) label.append("↑${status.ahead}", colored(TO_PUSH))
                if ((status.ahead ?: 0) > 0 && (status.behind ?: 0) > 0) label.append(" ")
                if ((status.behind ?: 0) > 0) label.append("↓${status.behind}", colored(TO_PULL))
            }
        }
        label.toolTipText = tooltip(status)
        label.revalidate()
        label.repaint()
    }

    private fun tooltip(status: RepoStatus): String {
        val where = status.upstream?.let { "${status.branch} → $it" } ?: status.branch.orEmpty()
        val state = when {
            status.operationInProgress || status.conflicts > 0 -> "A ${operationName(status).lowercase()} stopped on conflicts."
            !status.hasRemotes -> "This repository is only on your computer."
            status.upstream == null -> "This branch isn't on the remote yet."
            else -> listOfNotNull(
                (status.ahead ?: 0).takeIf { it > 0 }?.let { "$it to push" },
                (status.behind ?: 0).takeIf { it > 0 }?.let { "$it to pull (as of the last sync)" },
            ).joinToString(", ").ifEmpty { "Up to date (as of the last sync)." }
        }
        return "<html><b>$where</b><br>$state<br><br>Click to ${primaryActionName(status).lowercase()}. Right-click for more.</html>"
    }

    private fun primaryActionName(status: RepoStatus) = when {
        status.operationInProgress || status.conflicts > 0 -> "Resolve conflicts"
        !status.hasRemotes -> "Publish to GitHub"
        status.upstream == null -> "Publish this branch"
        else -> "Sync"
    }

    private fun operationName(status: RepoStatus) = if (status.state.name == "REBASING") "Rebase" else "Merge"

    // ---- what clicking does -----------------------------------------------------------------

    private fun onPrimaryClick() {
        val status = status ?: return
        if (syncing) return
        val repo = status.repository
        when {
            status.operationInProgress || status.conflicts > 0 -> resolveConflicts(project, repo, ::requestRefresh)
            !status.hasRemotes -> publishToGitHub(project, repo, ::requestRefresh)
            status.upstream == null -> pushWithChoice(project, repo, ::requestRefresh)
            else -> startSync(repo)
        }
    }

    private fun startSync(repo: GitRepository) {
        syncing = true
        render()
        syncInBackground(project, repo) {
            syncing = false
            render()
            requestRefresh()
        }
    }

    private fun maybeShowMenu(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val status = status ?: return
        JBPopupFactory.getInstance()
            .createActionGroupPopup(null, menu(status), com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT,
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
            .show(RelativePoint(e))
        e.consume()
    }

    private fun menu(status: RepoStatus): ActionGroup {
        val repo = status.repository
        fun action(text: String, icon: javax.swing.Icon?, enabled: Boolean = true, run: () -> Unit) =
            object : DumbAwareAction(text, null, icon) {
                override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = enabled && !syncing
                }
                override fun actionPerformed(e: AnActionEvent) = run()
            }
        return DefaultActionGroup(
            action("Sync", AllIcons.Actions.CheckOut, enabled = status.upstream != null && !status.operationInProgress) { startSync(repo) },
            action("Push…", AllIcons.Vcs.Push, enabled = status.hasUnpushedCommits && status.hasRemotes) { pushWithChoice(project, repo, ::requestRefresh) },
            action("Refresh", AllIcons.Actions.Refresh) { requestRefresh() },
            action("Open Git Dashboard", AllIcons.Vcs.Branch) {
                ToolWindowManager.getInstance(project).getToolWindow("Git Dashboard")?.show()
            },
        )
    }

    private fun colored(color: Color) = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color)

    override fun dispose() = Unit

    companion object {
        const val ID = "GitDashboard.Sync"

        private val TO_PUSH = JBColor(Color(0xD9822B), Color(0xF0A35E))
        private val TO_PULL = JBColor(Color(0x3574F0), Color(0x5C9DFF))
        private val UP_TO_DATE = JBColor(Color(0x2E9E4F), Color(0x5FB865))
        private val WARNING = JBColor(Color(0xC0702A), Color(0xE0A050))
    }
}
