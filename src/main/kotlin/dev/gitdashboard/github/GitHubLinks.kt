package dev.gitdashboard.github

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.FilePath
import git4idea.repo.GitRepository
import java.net.URLEncoder

private val GITHUB_REMOTE = Regex("""github\.com[:/]([^/]+)/(.+?)(?:\.git)?/?$""")

/** "https://github.com/owner/name" for the first GitHub remote ("origin" preferred), or null. */
fun gitHubRepoUrl(repo: GitRepository): String? =
    repo.remotes.sortedBy { if (it.name == "origin") 0 else 1 }
        .firstNotNullOfOrNull { remote -> remote.urls.firstNotNullOfOrNull { GITHUB_REMOTE.find(it) } }
        ?.destructured?.let { (owner, name) -> "https://github.com/$owner/$name" }

fun gitHubCommitUrl(repo: GitRepository, hash: String): String? =
    gitHubRepoUrl(repo)?.let { "$it/commit/$hash" }

/**
 * Permanent link to lines of [file] as they are in commit [hash], e.g. ".../blob/<hash>/src/Main.kt#L10-L12".
 * [file] is the path the file had in that commit; line numbers are 1-based.
 */
fun gitHubLineUrl(repo: GitRepository, hash: String, file: FilePath, firstLine: Int, lastLine: Int = firstLine): String? {
    val base = gitHubRepoUrl(repo) ?: return null
    val path = FileUtil.getRelativePath(repo.root.path, file.path, '/') ?: return null
    val encodedPath = path.split('/').joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }
    val anchor = if (lastLine > firstLine) "#L$firstLine-L$lastLine" else "#L$firstLine"
    return "$base/blob/$hash/$encodedPath$anchor"
}
