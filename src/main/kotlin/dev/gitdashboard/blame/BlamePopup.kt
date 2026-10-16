package dev.gitdashboard.blame

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.vcs.log.impl.HashImpl
import com.intellij.vcs.log.impl.VcsLogNavigationUtil
import dev.gitdashboard.git.tryGit
import dev.gitdashboard.github.gitHubCommitUrl
import dev.gitdashboard.github.gitHubLineUrl
import git4idea.commands.GitCommand
import git4idea.repo.GitRepository
import java.awt.datatransfer.StringSelection

/** Commit card shown when the inline blame text is clicked. */
fun showBlamePopup(project: Project, repo: GitRepository, line: BlameLine.Committed, at: RelativePoint) {
    val info = line.info
    val hash = line.hash
    lateinit var popup: JBPopup

    // The blame data only carries the subject line; the rest of the message is fetched on demand.
    val body = JBLabel().apply {
        isVisible = false
        foreground = UIUtil.getLabelForeground()
    }
    val content = panel {
        row { label(info.subject).bold() }
        row { cell(body) }
        row {
            label("${line.author} · ${DateFormatUtil.formatDateTime(info.authorDate)} · ${line.commitId}")
                .applyToComponent { foreground = UIUtil.getContextHelpForeground() }
        }
        row {
            link("Show in Git Log") {
                popup.cancel()
                VcsLogNavigationUtil.jumpToRevisionAsync(project, repo.root, HashImpl.build(hash), info.filePath)
            }
            link("Copy Hash") {
                CopyPasteManager.getInstance().setContents(StringSelection(hash))
                popup.cancel()
            }
            // Unpushed commits don't exist on GitHub yet, so their links would 404.
            if (CommitBadge.NOT_PUSHED !in line.badges) {
                gitHubCommitUrl(repo, hash)?.let { url ->
                    link("Open on GitHub") {
                        popup.cancel()
                        BrowserUtil.browse(url)
                    }
                }
                gitHubLineUrl(repo, hash, info.filePath, info.originalLineNumber)?.let { url ->
                    link("Copy Link to Line") {
                        popup.cancel()
                        copyAndConfirm(project, url)
                    }
                }
            }
        }
        if (line.badges.isNotEmpty()) {
            row {
                comment(line.badges.joinToString("   ") {
                    when (it) {
                        CommitBadge.NOT_PUSHED -> "↑ Only on your computer: push to share it."
                        CommitBadge.NEW -> "✦ Arrived from someone else in the last day."
                    }
                })
            }
        }
    }.apply { border = JBUI.Borders.empty(8, 12) }

    popup = JBPopupFactory.getInstance().createComponentPopupBuilder(content, null)
        .setRequestFocus(true)
        .setCancelOnClickOutside(true)
        .createPopup()
    popup.show(at)

    ApplicationManager.getApplication().executeOnPooledThread {
        val result = tryGit(project, repo, GitCommand.LOG, "-1", "--format=%b", hash) { setSilent(true) }
        val text = if (result.success()) result.outputAsJoinedString.trim() else ""
        if (text.isEmpty()) return@executeOnPooledThread
        ApplicationManager.getApplication().invokeLater({
            if (popup.isDisposed) return@invokeLater
            body.text = "<html>${text.escapeHtml().replace("\n", "<br>")}</html>"
            body.isVisible = true
            popup.pack(true, true)
        }, ModalityState.any())
    }
}

private fun String.escapeHtml() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
