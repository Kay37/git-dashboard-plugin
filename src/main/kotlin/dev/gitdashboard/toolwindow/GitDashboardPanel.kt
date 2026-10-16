package dev.gitdashboard.toolwindow

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.gitdashboard.GitDashboardScope
import dev.gitdashboard.git.GitOperationException
import dev.gitdashboard.git.RepoStatus
import dev.gitdashboard.git.collectLocalChanges
import dev.gitdashboard.git.lastCommitSubject
import dev.gitdashboard.git.loadRepoStatuses
import dev.gitdashboard.git.undoLastCommit
import dev.gitdashboard.notifyUser
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
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The Git Dashboard tool window: a status card per Git repository on top, the commit area below.
 * Everything refreshes whenever Git state or local changes change.
 */
class GitDashboardPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
    private val cards = JBPanelWithEmptyText(VerticalLayout(JBUI.scale(8))).withEmptyText("No Git repository in this project")
    private val commitSection = CommitSection(project, this, onGitStateChanged = ::requestRefresh)

    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    @Volatile private var statuses: List<RepoStatus> = emptyList()

    init {
        toolbar = createToolbar()
        cards.border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12))
        setContent(JPanel(BorderLayout()).apply {
            add(cards, BorderLayout.NORTH)
            add(commitSection, BorderLayout.CENTER)
        })

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
                val (statuses, changes) = withContext(Dispatchers.IO) { loadRepoStatuses(project) to collectLocalChanges(project) }
                withContext(Dispatchers.EDT) { render(statuses, changes) }
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

    private fun render(statuses: List<RepoStatus>, changes: List<Change>) {
        this.statuses = statuses
        cards.removeAll()
        statuses.forEach { cards.add(createCard(it, showRepoName = statuses.size > 1)) }
        cards.revalidate()
        cards.repaint()

        commitSection.isVisible = statuses.isNotEmpty()
        commitSection.setChanges(changes)
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
    }

    private fun Row.secondaryLabel(text: String) =
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

    private fun createToolbar(): JComponent {
        val refresh = object : DumbAwareAction("Refresh", "Reload Git status", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = requestRefresh()
        }
        val undo = object : DumbAwareAction("Undo Last Commit", "Remove the latest unpushed commit and keep its changes", AllIcons.Actions.Rollback) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = statuses.any { it.hasUnpushedCommits }
            }
            override fun actionPerformed(e: AnActionEvent) = chooseRepoAndUndo()
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("GitDashboard", DefaultActionGroup(refresh, undo), true)
        toolbar.targetComponent = this
        return toolbar.component
    }

    private fun chooseRepoAndUndo() {
        val candidates = statuses.filter { it.hasUnpushedCommits }.map { it.repository }
        when (candidates.size) {
            0 -> return
            1 -> confirmAndUndo(candidates.single())
            else -> JBPopupFactory.getInstance()
                .createPopupChooserBuilder(candidates.map { it.root.name })
                .setTitle("Undo Last Commit In")
                .setItemChosenCallback { name -> candidates.find { it.root.name == name }?.let(::confirmAndUndo) }
                .createPopup()
                .showInCenterOf(this)
        }
    }

    private fun confirmAndUndo(repo: GitRepository) {
        object : Task.Backgroundable(project, "Reading last commit", false) {
            private var subject = ""
            override fun run(indicator: ProgressIndicator) {
                subject = lastCommitSubject(project, repo)
            }
            override fun onSuccess() {
                val answer = Messages.showOkCancelDialog(
                    project,
                    "Undo \"$subject\"?\n\nThe commit is removed and its changes go back to your uncommitted changes. Nothing is lost.",
                    "Undo Last Commit", "Undo", "Cancel", Messages.getQuestionIcon(),
                )
                if (answer == Messages.OK) runUndo(repo, subject)
            }
            override fun onThrowable(error: Throwable) = reportUndoFailure(error)
        }.queue()
    }

    private fun runUndo(repo: GitRepository, subject: String) {
        object : Task.Backgroundable(project, "Undoing last commit", false) {
            override fun run(indicator: ProgressIndicator) = undoLastCommit(project, repo)
            override fun onSuccess() = notifyUser(project, NotificationType.INFORMATION, "Commit undone", "\"$subject\" is back in your changes.")
            override fun onThrowable(error: Throwable) = reportUndoFailure(error)
            override fun onFinished() = requestRefresh()
        }.queue()
    }

    private fun reportUndoFailure(error: Throwable) {
        notifyUser(project, NotificationType.ERROR, "Couldn't undo the commit", (error as? GitOperationException)?.message ?: error.toString())
    }

    override fun dispose() = Unit
}
