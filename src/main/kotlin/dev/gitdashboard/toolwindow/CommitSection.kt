package dev.gitdashboard.toolwindow

import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.openapi.vcs.changes.actions.diff.ShowDiffAction
import com.intellij.openapi.vcs.changes.ui.ChangesTreeImpl
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.gitdashboard.git.GitOperationException
import dev.gitdashboard.git.affectedPaths
import dev.gitdashboard.git.commitFiles
import dev.gitdashboard.git.pushCurrentBranch
import dev.gitdashboard.git.repository
import dev.gitdashboard.notifyUser
import dev.gitdashboard.pluralize
import dev.gitdashboard.saveOpenFiles
import git4idea.repo.GitRepository
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent

/**
 * Changed files with checkboxes, a message box and Commit / Commit & Push buttons.
 * Changed files are included by default and new files aren't; the user's ticks and unticks stay
 * put across refreshes.
 */
class CommitSection(
    private val project: Project,
    parentDisposable: Disposable,
    /** Called after a commit or push finishes, successfully or not. */
    private val onGitStateChanged: () -> Unit,
) : JPanel(BorderLayout()) {

    private val tree = ChangesTreeImpl.Changes(project, true, false).apply {
        emptyText.text = "No changes. You're all caught up."
        setDoubleClickAndEnterKeyHandler { showDiffForSelection() }
        setInclusionListener { onInclusionChanged() }
    }
    private val selectionLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    private val messageArea = JBTextArea(3, 0).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(4, 6)
        emptyText.text = "Commit message (Ctrl+Enter to commit & push)"
    }
    private val commitButton = JButton("Commit").apply { addActionListener { commit(push = false) } }
    private val commitAndPushButton = JButton("Commit & Push").apply {
        putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
        addActionListener { commit(push = true) }
    }

    /** Paths the user unticked; remembered so a refresh doesn't tick them again. */
    private val excludedPaths = mutableSetOf<FilePath>()
    /**
     * New (untracked) files start unticked, like in IntelliJ's own commit, so a password file or
     * build output never goes in by accident. These are the ones the user ticked.
     */
    private val chosenNewPaths = mutableSetOf<FilePath>()
    private var newPaths: Set<FilePath> = emptySet()
    private var updatingInclusion = false
    private var busy = false

    init {
        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 12, 4, 12)
            add(JBLabel("Changes").apply { font = JBUI.Fonts.label().asBold() }, BorderLayout.WEST)
            add(selectionLabel, BorderLayout.EAST)
        }
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
            add(commitButton)
            add(commitAndPushButton)
        }
        val commitBox = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(6, 12, 10, 12)
            add(ScrollPaneFactory.createScrollPane(messageArea), BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }

        add(header, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
        add(commitBox, BorderLayout.SOUTH)

        messageArea.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = updateControls()
        })
        DumbAwareAction.create { commit(push = true) }.registerCustomShortcutSet(
            CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK)),
            messageArea, parentDisposable,
        )
        updateControls()
    }

    /**
     * Replaces the displayed changes. Changed files are ticked unless the user unticked them; new
     * [untracked] files stay unticked unless the user ticked them. Call on the EDT.
     */
    fun setChanges(changes: List<Change>, untracked: Set<FilePath>) {
        newPaths = untracked
        updatingInclusion = true
        try {
            tree.setChangesToDisplay(changes)
            tree.setIncludedChanges(changes.filter { change ->
                val path = pathOf(change)
                if (path in untracked) path in chosenNewPaths else path !in excludedPaths
            })
        }
        finally {
            updatingInclusion = false
        }
        updateControls()
    }

    private fun onInclusionChanged() {
        if (updatingInclusion) return
        excludedPaths.clear()
        chosenNewPaths.clear()
        for (change in tree.changes) {
            val path = pathOf(change)
            val included = tree.isIncluded(change)
            if (path in newPaths) { if (included) chosenNewPaths += path }
            else if (!included) excludedPaths += path
        }
        updateControls()
    }

    private fun updateControls() {
        val total = tree.changes.size
        val included = tree.includedChanges.size
        val newLeftOut = tree.changes.count { pathOf(it) in newPaths && !tree.isIncluded(it) }
        selectionLabel.text = when {
            total == 0 -> ""
            included == total -> pluralize(total, "file")
            else -> "$included of $total selected"
        } + if (newLeftOut > 0) " · ${pluralize(newLeftOut, "new file")} not ticked" else ""
        selectionLabel.toolTipText = if (newLeftOut == 0) null
            else "New files start unticked, so passwords or build output don't get committed by accident. Tick the ones you want to add."
        val canCommit = !busy && included > 0 && messageArea.text.isNotBlank()
        commitButton.isEnabled = canCommit
        commitAndPushButton.isEnabled = canCommit
        messageArea.isEnabled = !busy
    }

    private fun showDiffForSelection() {
        val selected = tree.selectedChanges.firstOrNull() ?: return
        val all = tree.changes
        ShowDiffAction.showDiffForChange(project, all, all.indexOf(selected))
    }

    private fun commit(push: Boolean) {
        // Git commits what's on disk: save first, so the commit has the latest typing.
        saveOpenFiles()
        val message = messageArea.text.trim()
        val included = tree.includedChanges.toList()
        if (busy || message.isEmpty() || included.isEmpty()) return

        val changesByRepo: Map<GitRepository, List<Change>> = included
            .mapNotNull { change -> change.repository(project)?.let { it to change } }
            .groupBy({ it.first }, { it.second })

        busy = true
        updateControls()

        object : Task.Backgroundable(project, if (push) "Committing and pushing" else "Committing", false) {
            private var committed = false
            private val pushedTo = mutableListOf<String>()

            override fun run(indicator: ProgressIndicator) {
                for ((repo, changes) in changesByRepo) {
                    indicator.text = "Committing in ${repo.root.name}"
                    commitFiles(project, repo, changes.flatMap { it.affectedPaths() }, message)
                }
                committed = true
                if (push) {
                    for (repo in changesByRepo.keys) {
                        indicator.text = "Pushing ${repo.root.name}"
                        pushedTo += pushCurrentBranch(project, repo)
                    }
                }
            }

            override fun onSuccess() {
                val files = pluralize(included.size, "file")
                if (push) notifyUser(project, NotificationType.INFORMATION, "Committed and pushed", "$files pushed to ${pushedTo.joinToString()}")
                else notifyUser(
                    project, NotificationType.INFORMATION, "Committed", "$files committed. Push when you're ready.",
                    actions = listOf(NotificationAction.createSimpleExpiring("Push") {
                        val repos = changesByRepo.keys
                        if (repos.size == 1) pushWithChoice(project, repos.single(), onGitStateChanged)
                        else pushInBackground(project, repos, onGitStateChanged)
                    }),
                )
            }

            override fun onThrowable(error: Throwable) {
                val reason = (error as? GitOperationException)?.message ?: error.toString()
                if (committed) notifyUser(project, NotificationType.WARNING, "Committed, but the push failed", reason)
                else notifyUser(project, NotificationType.ERROR, "Commit failed", reason)
            }

            override fun onFinished() {
                if (committed) {
                    messageArea.text = ""
                    excludedPaths.clear()
                    chosenNewPaths.clear()
                }
                busy = false
                updateControls()
                onGitStateChanged()
            }
        }.queue()
    }

    private fun pathOf(change: Change): FilePath = ChangesUtil.getFilePath(change)
}
