package dev.gitdashboard.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.COLUMNS_SHORT
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import dev.gitdashboard.git.isValidRemoteName
import dev.gitdashboard.github.isValidRepositoryName
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount
import javax.swing.JComponent

/** Asks where and how to create the GitHub repository for a local one. */
class PublishToGitHubDialog(
    project: Project,
    private val accounts: List<GithubAccount>,
    defaultAccount: GithubAccount,
    defaultName: String,
    private val existingRemotes: Set<String>,
    private val hasCommits: Boolean,
) : DialogWrapper(project) {

    var account: GithubAccount? = defaultAccount
    var repositoryName = defaultName
    var description = ""
    var isPrivate = true
    var remoteName = if ("origin" in existingRemotes) "github" else "origin"
    var pushNow = hasCommits

    init {
        title = "Publish to GitHub"
        setOKButtonText("Create Repository")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Account:") {
            if (accounts.size > 1) {
                comboBox(accounts, SimpleListCellRenderer.create("") { "${it.name} (${it.server})" }).bindItem(::account)
            }
            else {
                label(accounts.single().name)
            }
        }
        row("Repository name:") {
            textField().bindText(::repositoryName).columns(COLUMNS_MEDIUM).focused()
                .validationOnApply {
                    if (isValidRepositoryName(it.text.trim())) null
                    else error("Use letters, digits, '.', '_' or '-' only.")
                }
        }
        row("Description:") {
            textField().bindText(::description).columns(COLUMNS_LARGE)
        }
        row {
            checkBox("Private repository").bindSelected(::isPrivate)
                .comment("Only you and the people you invite can see a private repository.")
        }
        row("Remote name:") {
            textField().bindText(::remoteName).columns(COLUMNS_SHORT)
                .validationOnApply {
                    val name = it.text.trim()
                    when {
                        !isValidRemoteName(name) -> error("Use letters, digits, '.', '_' or '-' only.")
                        name in existingRemotes -> error("This repository already has a remote called \"$name\".")
                        else -> null
                    }
                }
                .comment("How this project refers to the GitHub repository, usually \"origin\".")
        }
        row {
            checkBox("Push my commits now").bindSelected(::pushNow).enabled(hasCommits)
                .comment(if (hasCommits) "" else "Nothing to push yet: make a first commit, then Publish.")
        }
    }
}
