package dev.gitdashboard.github

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import dev.gitdashboard.git.GitOperationException
import org.jetbrains.plugins.github.api.GithubApiRequestExecutor
import org.jetbrains.plugins.github.api.GithubApiRequests
import org.jetbrains.plugins.github.authentication.GHLoginSource
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount
import org.jetbrains.plugins.github.util.GHCompatibilityUtil
import java.io.IOException

data class CreatedRepository(val cloneUrl: String, val webUrl: String)

/**
 * Creates a repository on GitHub under [account], using the token IntelliJ stores for that
 * account (IntelliJ asks the user to sign in again if it has none). Call off the EDT.
 */
fun createGitHubRepository(
    project: Project,
    indicator: ProgressIndicator,
    account: GithubAccount,
    name: String,
    description: String,
    private: Boolean,
): CreatedRepository {
    val token = GHCompatibilityUtil.getOrRequestToken(account, project, GHLoginSource.SHARE)
        ?: throw GitOperationException("You need to sign in to GitHub as ${account.name} to create a repository.")
    val executor = GithubApiRequestExecutor.Factory.getInstance().create(account.server, token)
    val repo = try {
        executor.execute(indicator, GithubApiRequests.CurrentUser.Repos.create(account.server, name, description, private, null))
    }
    catch (e: IOException) {
        throw GitOperationException(explainCreateFailure(name, e))
    }
    return CreatedRepository(cloneUrl = repo.cloneUrl, webUrl = repo.cloneUrl.removeSuffix(".git"))
}

/** GitHub's own rules for repository names. */
fun isValidRepositoryName(name: String): Boolean = name.matches(Regex("[A-Za-z0-9._-]+")) && name != "." && name != ".."

/** Turns a folder name into a valid repository name, e.g. "My Project" -> "My-Project". */
fun suggestRepositoryName(folderName: String): String =
    folderName.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifEmpty { "my-project" }

private fun explainCreateFailure(name: String, e: IOException): String {
    val message = e.message.orEmpty()
    return when {
        "name already exists" in message -> "You already have a GitHub repository called \"$name\". Pick another name."
        message.isBlank() -> "GitHub didn't accept the request. Check your connection and try again."
        else -> "GitHub said: $message"
    }
}
