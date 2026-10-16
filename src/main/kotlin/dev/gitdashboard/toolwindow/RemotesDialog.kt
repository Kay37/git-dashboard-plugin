package dev.gitdashboard.toolwindow

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.COLUMNS_SHORT
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import dev.gitdashboard.git.GitOperationException
import dev.gitdashboard.git.addRemote
import dev.gitdashboard.git.editRemote
import dev.gitdashboard.git.isValidRemoteName
import dev.gitdashboard.git.removeRemote
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel

/**
 * Lists a repository's remotes with Add / Edit / Remove. Changes are applied to git immediately,
 * so Close just closes.
 */
class RemotesDialog(private val project: Project, private val repo: GitRepository) : DialogWrapper(project) {
    private val model = DefaultListModel<GitRemote>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        emptyText.text = "No remotes yet. Add one to push this repository somewhere."
        cellRenderer = object : ColoredListCellRenderer<GitRemote>() {
            override fun customizeCellRenderer(list: JList<out GitRemote>, value: GitRemote, index: Int, selected: Boolean, hasFocus: Boolean) {
                append(value.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("   ${value.firstUrl ?: "(no URL)"}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }

    init {
        title = "Remotes of ${repo.root.name}"
        setOKButtonText("Close")
        reload()
        init()
    }

    override fun createCenterPanel(): JComponent =
        ToolbarDecorator.createDecorator(list)
            .setAddAction { addRemote() }
            .setEditAction { list.selectedValue?.let(::editRemote) }
            .setRemoveAction { list.selectedValue?.let(::removeRemote) }
            .disableUpDownActions()
            .setPreferredSize(java.awt.Dimension(560, 220))
            .createPanel()

    override fun createActions() = arrayOf(okAction)

    private fun addRemote() {
        val names = repo.remotes.map { it.name }.toSet()
        val dialog = RemoteEditDialog(project, if ("origin" in names) "" else "origin", "", names)
        if (dialog.showAndGet()) apply("Adding remote") { addRemote(project, repo, dialog.name.trim(), dialog.url.trim()) }
    }

    private fun editRemote(remote: GitRemote) {
        val otherNames = repo.remotes.map { it.name }.toSet() - remote.name
        val dialog = RemoteEditDialog(project, remote.name, remote.firstUrl.orEmpty(), otherNames, editing = true)
        if (dialog.showAndGet()) apply("Updating remote") { editRemote(project, repo, remote.name, dialog.name.trim(), dialog.url.trim()) }
    }

    private fun removeRemote(remote: GitRemote) {
        val answer = Messages.showOkCancelDialog(
            project,
            "Remove the remote \"${remote.name}\"?\n\nThis only removes the link from this project. The repository at ${remote.firstUrl ?: "that address"} isn't touched.",
            "Remove Remote", "Remove", "Cancel", Messages.getWarningIcon(),
        )
        if (answer == Messages.OK) apply("Removing remote") { removeRemote(project, repo, remote.name) }
    }

    /** Runs a git change under a short modal progress, reports failures, then refreshes the list. */
    private fun apply(title: String, change: () -> Unit) {
        try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously<Unit, GitOperationException>(change, title, false, project)
        }
        catch (e: GitOperationException) {
            Messages.showErrorDialog(project, e.message, title)
        }
        reload()
    }

    private fun reload() {
        val selected = list.selectedValue?.name
        model.clear()
        repo.remotes.sortedBy { it.name }.forEach(model::addElement)
        list.selectedIndex = (0 until model.size()).firstOrNull { model[it].name == selected } ?: 0
    }
}

/** Name + URL of one remote. */
private class RemoteEditDialog(
    project: Project,
    var name: String,
    var url: String,
    private val takenNames: Set<String>,
    editing: Boolean = false,
) : DialogWrapper(project) {
    init {
        title = if (editing) "Edit Remote" else "Add Remote"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") {
            textField().bindText(::name).columns(COLUMNS_SHORT)
                .validationOnApply {
                    val value = it.text.trim()
                    when {
                        !isValidRemoteName(value) -> error("Use letters, digits, '.', '_' or '-' only.")
                        value in takenNames -> error("There is already a remote called \"$value\".")
                        else -> null
                    }
                }
        }
        row("URL:") {
            textField().bindText(::url).columns(COLUMNS_LARGE).focused()
                .validationOnApply { if (it.text.isBlank()) error("Enter the repository's URL.") else null }
                .comment("e.g. https://github.com/you/project.git or git@gitlab.com:you/project.git")
        }
    }
}
