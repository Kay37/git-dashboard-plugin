package dev.gitdashboard.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.CheckBoxList
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import dev.gitdashboard.blame.relativeTime
import dev.gitdashboard.git.BranchInfo
import dev.gitdashboard.git.GitOperationException
import dev.gitdashboard.git.branchNameProblem
import dev.gitdashboard.git.loadBranches
import dev.gitdashboard.git.mergedBranches
import dev.gitdashboard.notifyUser
import git4idea.branch.GitBrancher
import git4idea.repo.GitRepository
import java.awt.Color
import java.awt.event.KeyEvent
import java.util.Date
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.KeyStroke

/**
 * Quick-pick popup for branches: search, switch (Enter), create, delete (Delete key), and tidy up
 * merged branches. Switching goes through IntelliJ's own branch machinery, which offers to carry
 * uncommitted changes along when they'd clash with the target branch.
 *
 * @param show positions the popup, e.g. `{ it.showUnderneathOf(component) }`.
 */
fun showBranchSwitcher(project: Project, repo: GitRepository, onFinished: () -> Unit = {}, show: (JBPopup) -> Unit) {
    object : Task.Backgroundable(project, "Loading branches", false) {
        private var branches: List<BranchInfo> = emptyList()
        override fun run(indicator: ProgressIndicator) {
            branches = loadBranches(project, repo)
        }
        override fun onSuccess() = show(createPopup(project, repo, branches, onFinished))
        override fun onThrowable(error: Throwable) =
            notifyUser(project, NotificationType.ERROR, "Couldn't load branches", (error as? GitOperationException)?.message ?: error.toString())
    }.queue()
}

private sealed interface Item {
    data class Branch(val info: BranchInfo) : Item
    data class Command(val text: String, val icon: javax.swing.Icon, val run: () -> Unit) : Item
}

private fun createPopup(project: Project, repo: GitRepository, branches: List<BranchInfo>, onFinished: () -> Unit): JBPopup {
    lateinit var popup: JBPopup
    var selected: Item? = null

    val items = buildList {
        add(Item.Command("New Branch…", AllIcons.General.Add) { createBranch(project, repo) })
        branches.forEach { add(Item.Branch(it)) }
        add(Item.Command("Delete Merged Branches…", AllIcons.Actions.GC) { deleteMergedBranches(project, repo, onFinished) })
    }

    popup = JBPopupFactory.getInstance().createPopupChooserBuilder(items)
        .setTitle("Branches of ${repo.root.name}")
        .setRenderer(ItemRenderer())
        .setNamerForFiltering { item ->
            when (item) {
                is Item.Branch -> item.info.name
                is Item.Command -> item.text
            }
        }
        .setSelectedValue(items.firstOrNull { it is Item.Branch && !it.info.isCurrent } ?: items.first(), true)
        .setVisibleRowCount(14)
        .setItemSelectedCallback { selected = it }
        .registerKeyboardAction(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0)) {
            val branch = (selected as? Item.Branch)?.info ?: return@registerKeyboardAction
            popup.cancel()
            deleteBranch(project, repo, branch, onFinished)
        }
        .setAdText("Type to search · Enter to switch · Delete to remove a branch")
        .setItemChosenCallback { item ->
            when (item) {
                is Item.Command -> item.run()
                is Item.Branch -> switchTo(project, repo, item.info, onFinished)
            }
        }
        .createPopup()
    return popup
}

private fun switchTo(project: Project, repo: GitRepository, branch: BranchInfo, onFinished: () -> Unit) {
    if (branch.isCurrent) return
    val brancher = GitBrancher.getInstance(project)
    // A remote branch gets a local branch of the same name that tracks it.
    if (branch.isRemote) brancher.checkoutNewBranchStartingFrom(branch.localName, branch.name, listOf(repo), onFinished)
    else brancher.checkout(branch.name, false, listOf(repo), onFinished)
}

private fun createBranch(project: Project, repo: GitRepository) {
    val name = Messages.showInputDialog(
        project,
        "Name for the new branch. It starts from where you are now (${repo.currentBranchName ?: "this commit"}), and you'll switch to it.",
        "New Branch", null, "", object : InputValidatorEx {
            override fun getErrorText(inputString: String) = branchNameProblem(repo, inputString.trim())
            override fun checkInput(inputString: String) = getErrorText(inputString) == null
            override fun canClose(inputString: String) = checkInput(inputString)
        },
    )?.trim() ?: return
    GitBrancher.getInstance(project).checkoutNewBranch(name, listOf(repo))
}

private fun deleteBranch(project: Project, repo: GitRepository, branch: BranchInfo, onFinished: () -> Unit) {
    when {
        branch.isCurrent -> Messages.showInfoMessage(project, "You're on \"${branch.name}\". Switch to another branch first, then delete it.", "Delete Branch")
        branch.isRemote -> {
            val answer = Messages.showOkCancelDialog(
                project, "Delete \"${branch.name}\" from the remote?\n\nThis removes it for everyone who uses the repository.",
                "Delete Remote Branch", "Delete", "Cancel", Messages.getWarningIcon(),
            )
            if (answer == Messages.OK) GitBrancher.getInstance(project).deleteRemoteBranch(branch.name, listOf(repo))
        }
        else -> {
            val answer = Messages.showOkCancelDialog(
                project, "Delete the branch \"${branch.name}\"?\n\nIf it has work that isn't in another branch, you'll be asked again before anything is lost.",
                "Delete Branch", "Delete", "Cancel", Messages.getQuestionIcon(),
            )
            // IntelliJ asks again for unmerged work and offers "Restore" in its notification.
            if (answer == Messages.OK) GitBrancher.getInstance(project).deleteBranches(mapOf(branch.name to listOf(repo)), onFinished)
        }
    }
}

private fun deleteMergedBranches(project: Project, repo: GitRepository, onFinished: () -> Unit) {
    object : Task.Backgroundable(project, "Finding merged branches", false) {
        private var merged: List<String> = emptyList()
        override fun run(indicator: ProgressIndicator) {
            merged = mergedBranches(project, repo)
        }
        override fun onSuccess() {
            if (merged.isEmpty()) {
                notifyUser(project, NotificationType.INFORMATION, "Nothing to tidy up", "No other branches are fully merged into ${repo.currentBranchName}.")
                return
            }
            val dialog = MergedBranchesDialog(project, repo.currentBranchName.orEmpty(), merged)
            if (!dialog.showAndGet() || dialog.chosen.isEmpty()) return
            GitBrancher.getInstance(project).deleteBranches(dialog.chosen.associateWith { listOf(repo) }, onFinished)
        }
        override fun onThrowable(error: Throwable) =
            notifyUser(project, NotificationType.ERROR, "Couldn't check merged branches", (error as? GitOperationException)?.message ?: error.toString())
    }.queue()
}

/** Checklist of merged branches to delete, all ticked by default. */
private class MergedBranchesDialog(project: Project, private val into: String, branches: List<String>) : DialogWrapper(project) {
    private val list = CheckBoxList<String>().apply { branches.forEach { addItem(it, it, true) } }

    val chosen: List<String> get() = (0 until list.itemsCount).mapNotNull { list.getItemAt(it)?.takeIf { name -> list.isItemSelected(name) } }

    init {
        title = "Delete Merged Branches"
        setOKButtonText("Delete Selected")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            text("All the work on these branches is already in <b>$into</b>, so deleting them loses nothing. Untick any you want to keep.", maxLineLength = 60)
        }
        row { cell(JBScrollPane(list)).align(Align.FILL) }.resizableRow()
    }
}

private class ItemRenderer : ColoredListCellRenderer<Item>() {
    override fun customizeCellRenderer(list: JList<out Item>, value: Item, index: Int, selected: Boolean, hasFocus: Boolean) {
        when (value) {
            is Item.Command -> {
                icon = value.icon
                append(value.text, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            }
            is Item.Branch -> {
                val info = value.info
                icon = when {
                    info.isCurrent -> AllIcons.Actions.Checked
                    info.isRemote -> AllIcons.General.Web
                    else -> AllIcons.Vcs.Branch
                }
                append(info.name, if (info.isCurrent) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                if (info.ahead > 0) append("  ↑${info.ahead}", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, TO_PUSH))
                if (info.behind > 0) append("  ↓${info.behind}", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, TO_PULL))
                val details = buildList {
                    if (info.isCurrent) add("current")
                    if (info.isRemote) add("on the remote only")
                    if (info.lastCommitTime > 0) add(relativeTime(Date(info.lastCommitTime * 1000)))
                }
                append("   " + details.joinToString(" · "), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }
    }

    private companion object {
        val TO_PUSH = JBColor(Color(0xD9822B), Color(0xF0A35E))
        val TO_PULL = JBColor(Color(0x3574F0), Color(0x5C9DFF))
    }
}
