package dev.gitdashboard.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.EmptyIcon
import dev.gitdashboard.git.UnpushedCommit
import dev.gitdashboard.pluralize
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel

/**
 * Lets the user pick how far to push: the chosen commit goes together with every older unpushed
 * commit (Git can't push a commit without its ancestors); newer commits stay local.
 *
 * @param commits unpushed commits, newest first.
 */
class PushCommitsDialog(
    project: Project,
    private val target: String,
    private val commits: List<UnpushedCommit>,
) : DialogWrapper(project) {

    private val list = JBList(commits).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        selectedIndex = 0
        visibleRowCount = commits.size.coerceAtMost(10)
        cellRenderer = CommitRenderer()
        addListSelectionListener {
            updateOkButton()
            repaint()
        }
    }

    /** The newest commit to push, or null when that's the branch tip (a plain push of the whole branch). */
    val upToCommit: String?
        get() = list.selectedIndex.takeIf { it > 0 }?.let { commits[it].hash }

    init {
        title = "Push Commits"
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                doOKAction()
                return true
            }
        }.installOn(list)
        init()
        updateOkButton()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            text(
                "Choose the newest commit to push to <b>$target</b>. Older commits go with it; newer ones stay on your machine.",
                maxLineLength = 60,
            )
        }
        row {
            scrollCell(list).align(Align.FILL)
        }.resizableRow()
    }

    override fun getPreferredFocusedComponent(): JComponent = list

    private fun updateOkButton() {
        val count = commits.size - list.selectedIndex.coerceAtLeast(0)
        setOKButtonText("Push ${pluralize(count, "Commit")}")
    }

    private inner class CommitRenderer : ColoredListCellRenderer<UnpushedCommit>() {
        override fun customizeCellRenderer(
            list: JList<out UnpushedCommit>, value: UnpushedCommit, index: Int, selected: Boolean, hasFocus: Boolean,
        ) {
            val included = index >= this@PushCommitsDialog.list.selectedIndex
            icon = if (included) AllIcons.Actions.Checked else EmptyIcon.ICON_16
            append(value.subject, if (included) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append("   ${value.shortHash} · ${value.relativeDate}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            if (!included) append("  stays local", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
        }
    }
}
