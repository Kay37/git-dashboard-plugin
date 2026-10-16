package dev.gitdashboard.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.gitdashboard.GitDashboardScope
import dev.gitdashboard.git.RepoStatus
import dev.gitdashboard.git.loadRepoStatuses
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JComponent

/** The Git Dashboard tool window: one status card per Git repository, refreshed whenever Git state changes. */
class GitDashboardPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
    private val cards = JBPanelWithEmptyText(VerticalLayout(JBUI.scale(8))).withEmptyText("No Git repository in this project")

    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        toolbar = createToolbar()
        setContent(JBScrollPane(cards).apply { border = JBUI.Borders.empty() })
        cards.border = JBUI.Borders.empty(8, 12)

        startRefreshLoop()
        subscribeToGitChanges()
        requestRefresh()
    }

    fun requestRefresh() {
        refreshRequests.tryEmit(Unit)
    }

    @OptIn(FlowPreview::class)
    private fun startRefreshLoop() {
        val job = project.service<GitDashboardScope>().cs.launch {
            // Git fires several events per operation; settle before running git again.
            refreshRequests.debounce(200).collectLatest {
                val statuses = withContext(Dispatchers.IO) { loadRepoStatuses(project) }
                withContext(Dispatchers.EDT) { render(statuses) }
            }
        }
        Disposer.register(this) { job.cancel() }
    }

    private fun subscribeToGitChanges() {
        val connection = project.messageBus.connect(this)
        connection.subscribe(GitRepository.GIT_REPO_CHANGE, object : GitRepositoryChangeListener {
            override fun repositoryChanged(repository: GitRepository) = requestRefresh()
        })
        connection.subscribe(VcsRepositoryManager.VCS_REPOSITORY_MAPPING_UPDATED, object : VcsRepositoryMappingListener {
            override fun mappingChanged() = requestRefresh()
        })
        ChangeListManager.getInstance(project).addChangeListListener(object : ChangeListListener {
            override fun changeListUpdateDone() = requestRefresh()
        }, this)
    }

    private fun render(statuses: List<RepoStatus>) {
        cards.removeAll()
        statuses.forEach { cards.add(createCard(it, showRepoName = statuses.size > 1)) }
        cards.revalidate()
        cards.repaint()
    }

    private fun createCard(status: RepoStatus, showRepoName: Boolean): JComponent = panel {
        if (showRepoName) {
            row { label(status.name).bold() }
        }
        row {
            icon(AllIcons.Vcs.Branch)
            label(status.branch ?: "Detached at ${status.detachedAt ?: "unknown"}").bold()
        }
        row { secondaryLabel(syncText(status)) }
        row { secondaryLabel(changesText(status.changedFiles)) }
    }

    private fun com.intellij.ui.dsl.builder.Row.secondaryLabel(text: String) =
        label(text).applyToComponent { foreground = UIUtil.getContextHelpForeground() }

    private fun syncText(status: RepoStatus): String = when {
        status.upstream == null -> "Not published to a remote yet"
        status.ahead == null || status.behind == null -> "Tracking ${status.upstream}"
        status.ahead == 0 && status.behind == 0 -> "Up to date with ${status.upstream}"
        else -> buildList {
            if (status.ahead > 0) add("${status.ahead} to push")
            if (status.behind > 0) add("${status.behind} to pull")
        }.joinToString(", ", postfix = " · ${status.upstream}")
    }

    private fun changesText(count: Int): String = when (count) {
        0 -> "No uncommitted changes"
        1 -> "1 changed file"
        else -> "$count changed files"
    }

    private fun createToolbar(): JComponent {
        val refresh = object : DumbAwareAction("Refresh", "Reload Git status", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = requestRefresh()
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("GitDashboard", DefaultActionGroup(refresh), true)
        toolbar.targetComponent = this
        return toolbar.component
    }

    override fun dispose() = Unit
}
