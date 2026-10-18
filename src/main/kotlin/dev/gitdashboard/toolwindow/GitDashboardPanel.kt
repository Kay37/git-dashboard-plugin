package dev.gitdashboard.toolwindow

import com.intellij.dvcs.repo.Repository
import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
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
import com.intellij.ui.SimpleTextAttributes
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
import dev.gitdashboard.github.GitHubStatusService
import dev.gitdashboard.notifyUser
import dev.gitdashboard.pluralize
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
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The Git Dashboard tool window: a status card per Git repository on top, the commit area below.
 * Everything refreshes whenever Git state or local changes change.
 */
class GitDashboardPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
    private val cards = JBPanelWithEmptyText(VerticalLayout(JBUI.scale(8))).withEmptyText("This project isn't tracked by Git yet").apply {
        emptyText.appendLine("Create Git Repository", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
            createRepositoryWithConfirmation(project, ::requestRefresh)
        }
    }
    private val commitSection = CommitSection(project, this, onGitStateChanged = ::requestRefresh)
    private val pullRequests = PullRequestsSection(project, this)
    private val content = JPanel(BorderLayout())

    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    @Volatile private var statuses: List<RepoStatus> = emptyList()

    init {
        toolbar = createToolbar()
        setContent(content)

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
        commitSection.setChanges(changes)

        // With no repository, the empty-state message (and its Create link) gets the whole panel.
        content.removeAll()
        if (statuses.isEmpty()) {
            cards.border = JBUI.Borders.empty(8, 12)
            content.add(cards, BorderLayout.CENTER)
        }
        else {
            cards.border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12))
            content.add(JPanel(BorderLayout()).apply {
                add(cards, BorderLayout.NORTH)
                add(pullRequests, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            content.add(commitSection, BorderLayout.CENTER)
        }
        content.revalidate()
        content.repaint()
    }

    private fun createCard(status: RepoStatus, showRepoName: Boolean): JComponent = panel {
        if (showRepoName) {
            row { label(status.name).bold() }
        }
        row {
            icon(AllIcons.Vcs.Branch)
            // The branch name opens the branch switcher.
            link("${status.branch ?: "Detached at ${status.detachedAt ?: "unknown"}"} ▾") { event ->
                showBranchSwitcher(project, status.repository, ::requestRefresh) { it.showUnderneathOf(event.source as JComponent) }
            }.applyToComponent {
                font = font.deriveFont(Font.BOLD)
                toolTipText = "Switch, create or delete branches"
            }
        }
        row {
            if (status.operationInProgress || status.conflicts > 0) {
                operationRow(status)
            }
            else if (!status.hasRemotes) {
                secondaryLabel("Only on this computer")
                link("Publish to GitHub…") { publishToGitHub(project, status.repository, ::requestRefresh) }
                link("Add Remote…") { showRemotes(status.repository) }
            }
            else {
                secondaryLabel(syncText(status))
                if (status.canSync) {
                    link("Sync") { syncInBackground(project, status.repository, ::requestRefresh) }
                }
                if (status.hasUnpushedCommits) {
                    link(if (status.upstream == null) "Publish" else "Push") { pushWithChoice(project, status.repository, ::requestRefresh) }
                }
            }
        }
    }

    /** A merge or rebase stopped part-way: say so and offer the ways out. */
    private fun Row.operationRow(status: RepoStatus) {
        val operation = if (status.state == Repository.State.REBASING) "Rebase" else "Merge"
        val text = buildList {
            if (status.operationInProgress) add("$operation in progress")
            if (status.conflicts > 0) add(pluralize(status.conflicts, "conflict"))
        }.joinToString(" · ")
        label(text).applyToComponent { foreground = JBColor.ORANGE }

        val next = when {
            status.conflicts > 0 -> "Resolve"
            status.state == Repository.State.REBASING -> "Continue"
            else -> "Finish"
        }
        link(next) { resolveConflicts(project, status.repository, ::requestRefresh) }
        if (status.operationInProgress) {
            link("Abort") { abortWithConfirmation(project, status.repository, ::requestRefresh) }
        }
    }

    private val RepoStatus.canSync: Boolean get() = branch != null && upstream != null && !operationInProgress

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
            override fun actionPerformed(e: AnActionEvent) {
                requestRefresh()
                project.service<GitHubStatusService>().refresh()
            }
        }
        val branches = object : DumbAwareAction("Switch Branch…", "Switch, create or delete branches", AllIcons.Vcs.Branch) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = statuses.isNotEmpty()
            }
            override fun actionPerformed(e: AnActionEvent) =
                chooseRepo("Switch Branch", statuses.map { it.repository }) { repo ->
                    showBranchSwitcher(project, repo, ::requestRefresh) { it.showInBestPositionFor(e.dataContext) }
                }
        }
        val sync = object : DumbAwareAction("Sync", "Pull remote commits, then push yours", AllIcons.Actions.CheckOut) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = statuses.any { it.canSync }
            }
            override fun actionPerformed(e: AnActionEvent) =
                chooseRepo("Sync", statuses.filter { it.canSync }.map { it.repository }) {
                    syncInBackground(project, it, ::requestRefresh)
                }
        }
        val push = object : DumbAwareAction("Push", "Push unpushed commits on the current branch", AllIcons.Vcs.Push) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = statuses.any { it.hasUnpushedCommits }
            }
            override fun actionPerformed(e: AnActionEvent) =
                chooseRepo("Push", statuses.filter { it.hasUnpushedCommits }.map { it.repository }) {
                    pushWithChoice(project, it, ::requestRefresh)
                }
        }
        val undo = object : DumbAwareAction("Undo Last Commit", "Remove the latest unpushed commit and keep its changes", AllIcons.Actions.Rollback) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = statuses.any { it.hasUnpushedCommits }
            }
            override fun actionPerformed(e: AnActionEvent) =
                chooseRepo("Undo Last Commit", statuses.filter { it.hasUnpushedCommits }.map { it.repository }, ::confirmAndUndo)
        }
        val remotes = object : DumbAwareAction("Remotes…", "See, add, edit or remove where this repository pushes to", AllIcons.General.Web) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = statuses.isNotEmpty()
            }
            override fun actionPerformed(e: AnActionEvent) =
                chooseRepo("Remotes", statuses.map { it.repository }, ::showRemotes)
        }
        val publish = object : DumbAwareAction("Publish to GitHub…", "Create a GitHub repository for this project and push to it", AllIcons.Vcs.Vendors.Github) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = statuses.isNotEmpty()
            }
            override fun actionPerformed(e: AnActionEvent) =
                chooseRepo("Publish to GitHub", statuses.map { it.repository }) { publishToGitHub(project, it, ::requestRefresh) }
        }
        val group = DefaultActionGroup(refresh, branches, sync, push, undo, Separator.getInstance(), remotes, publish)
        val toolbar = ActionManager.getInstance().createActionToolbar("GitDashboard", group, true)
        toolbar.targetComponent = this
        return toolbar.component
    }

    private fun showRemotes(repo: GitRepository) {
        RemotesDialog(project, repo).show()
        requestRefresh()
    }

    /** Runs [action] on the only candidate, or asks which repository when the project has several. */
    private fun chooseRepo(actionName: String, candidates: List<GitRepository>, action: (GitRepository) -> Unit) {
        when (candidates.size) {
            0 -> return
            1 -> action(candidates.single())
            else -> JBPopupFactory.getInstance()
                .createPopupChooserBuilder(candidates.map { it.root.name })
                .setTitle("$actionName In")
                .setItemChosenCallback { name -> candidates.find { it.root.name == name }?.let(action) }
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
