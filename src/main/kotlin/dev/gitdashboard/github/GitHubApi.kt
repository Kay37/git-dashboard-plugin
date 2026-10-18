package dev.gitdashboard.github

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.util.io.HttpRequests
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.time.Instant
import java.util.Date

data class PullRequest(
    val number: Int,
    val title: String,
    val url: String,
    val author: String,
    val draft: Boolean,
    /** Branch the changes come from, e.g. "feature/login". */
    val headRef: String,
    val headSha: String,
    /** "owner/name" of the repository the head branch lives in; differs from the base repo for forks. */
    val headRepo: String?,
    val baseRef: String,
    val updatedAt: Date,
)

/** Combined result of a commit's GitHub Actions / check runs and legacy commit statuses. */
data class ChecksSummary(val total: Int, val passed: Int, val failed: Int, val running: Int, val url: String) {
    val state: State
        get() = when {
            failed > 0 -> State.FAILED
            running > 0 -> State.RUNNING
            else -> State.PASSED
        }

    enum class State { PASSED, RUNNING, FAILED }

    /** "All 5 checks passed", "2 of 5 checks running", "1 of 5 checks failed". */
    val description: String
        get() = when (state) {
            State.PASSED -> if (total == 1) "Check passed" else "All $total checks passed"
            State.RUNNING -> "$running of $total ${if (total == 1) "check" else "checks"} running"
            State.FAILED -> "$failed of $total ${if (total == 1) "check" else "checks"} failed"
        }
}

/** [code] is GitHub's HTTP status (401 = the sign-in was rejected), or 0 when GitHub couldn't be reached. */
class GitHubApiException(message: String, val code: Int = 0) : Exception(message)

/** A repository's default branch and, for a fork, the repository it was forked from. */
data class RepoInfo(val defaultBranch: String, val parent: RepoCoordinates?, val parentDefaultBranch: String?)

/**
 * Minimal GitHub REST client for one repository. Uses IntelliJ's HTTP stack (so the IDE's proxy
 * settings apply) and the token IntelliJ stores for the user's GitHub account. Blocking: call off the EDT.
 */
class GitHubApi(private val token: String, val repo: RepoCoordinates) {

    fun repoInfo(): RepoInfo {
        val json = get("/repos/${repo.fullName}").asJsonObject
        val parent = json.obj("parent")
        val parentName = parent?.string("full_name")?.split('/')?.takeIf { it.size == 2 }
        return RepoInfo(
            defaultBranch = json.string("default_branch") ?: "main",
            parent = parentName?.let { (owner, name) -> RepoCoordinates(owner, name) },
            parentDefaultBranch = parent?.string("default_branch"),
        )
    }

    /** Open pull requests, most recently updated first. */
    fun openPullRequests(limit: Int = 20): List<PullRequest> =
        get("/repos/${repo.fullName}/pulls?state=open&sort=updated&direction=desc&per_page=$limit")
            .asJsonArray.map { parsePullRequest(it.asJsonObject) }

    /** Checks for [sha], or null when the commit has no CI at all. */
    fun checks(sha: String): ChecksSummary? {
        var total = 0
        var passed = 0
        var failed = 0
        var running = 0

        val runs = get("/repos/${repo.fullName}/commits/$sha/check-runs?per_page=100").asJsonObject.array("check_runs")
        for (run in runs.map { it.asJsonObject }) {
            total++
            when {
                run.string("status") != "completed" -> running++
                run.string("conclusion") in FAILED_CONCLUSIONS -> failed++
                else -> passed++
            }
        }
        // Older CI services report "commit statuses" instead of check runs.
        val statuses = get("/repos/${repo.fullName}/commits/$sha/status").asJsonObject.array("statuses")
        for (status in statuses.map { it.asJsonObject }) {
            total++
            when (status.string("state")) {
                "pending" -> running++
                "failure", "error" -> failed++
                else -> passed++
            }
        }
        return if (total == 0) null else ChecksSummary(total, passed, failed, running, "${repo.webUrl}/commit/$sha")
    }

    fun createPullRequest(title: String, body: String, head: String, base: String, draft: Boolean): PullRequest {
        val payload = JsonObject().apply {
            addProperty("title", title)
            addProperty("body", body)
            addProperty("head", head)
            addProperty("base", base)
            addProperty("draft", draft)
        }
        return parsePullRequest(send("POST", "/repos/${repo.fullName}/pulls", payload.toString()).asJsonObject)
    }

    private fun get(path: String): JsonElement = send("GET", path, null)

    private fun send(method: String, path: String, body: String?): JsonElement {
        val url = "https://api.github.com$path"
        val builder = if (method == "POST") HttpRequests.post(url, HttpRequests.JSON_CONTENT_TYPE) else HttpRequests.request(url)
        val (code, text) = try {
            builder
                .accept("application/vnd.github+json")
                .productNameAsUserAgent()
                .throwStatusCodeException(false)
                .isReadResponseOnError(true)
                .tuner { connection ->
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }
                .connect { request ->
                    body?.let(request::write)
                    val connection = request.connection as HttpURLConnection
                    val status = connection.responseCode
                    status to (if (status >= 400) request.readError() else request.readString()).orEmpty()
                }
        }
        catch (e: IOException) {
            throw GitHubApiException("Couldn't reach GitHub. Check your internet connection.")
        }
        if (code >= 400) throw GitHubApiException(explain(code, text), code)
        return JsonParser.parseString(text.ifBlank { "{}" })
    }

    private fun explain(code: Int, body: String): String {
        val message = runCatching { JsonParser.parseString(body).asJsonObject.string("message") }.getOrNull().orEmpty()
        val details = runCatching {
            JsonParser.parseString(body).asJsonObject.array("errors").mapNotNull { it.asJsonObject.string("message") }
        }.getOrDefault(emptyList())
        return when (code) {
            401 -> "GitHub didn't accept your sign-in. Re-add your account under Settings > Version Control > GitHub."
            403 -> if ("rate limit" in message.lowercase()) "GitHub's request limit was reached. Try again in a few minutes."
                   else "Your GitHub account doesn't have access to ${repo.fullName}."
            404 -> "GitHub couldn't find ${repo.fullName}, or your account can't see it."
            422 -> details.firstOrNull() ?: message.ifEmpty { "GitHub rejected the request." }
            else -> message.ifEmpty { "GitHub returned an error ($code)." }
        }
    }

    private fun parsePullRequest(json: JsonObject): PullRequest {
        val head = json.obj("head")
        return PullRequest(
            number = json.get("number").asInt,
            title = json.string("title").orEmpty(),
            url = json.string("html_url").orEmpty(),
            author = json.obj("user")?.string("login").orEmpty(),
            draft = json.get("draft")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
            headRef = head?.string("ref").orEmpty(),
            headSha = head?.string("sha").orEmpty(),
            headRepo = head?.obj("repo")?.string("full_name"),
            baseRef = json.obj("base")?.string("ref").orEmpty(),
            updatedAt = json.string("updated_at")?.let { Date.from(Instant.parse(it)) } ?: Date(),
        )
    }

    private companion object {
        val FAILED_CONCLUSIONS = setOf("failure", "timed_out", "cancelled", "action_required", "startup_failure")
    }
}

data class RepoCoordinates(val owner: String, val name: String) {
    val fullName: String get() = "$owner/$name"
    val webUrl: String get() = "https://github.com/$fullName"

    /** github.com page that lists open pull requests. */
    val pullsUrl: String get() = "$webUrl/pulls"

    /** github.com page to open a pull request for [branch] by hand. */
    fun compareUrl(base: String, branch: String) =
        "$webUrl/compare/${URLEncoder.encode(base, Charsets.UTF_8)}...${URLEncoder.encode(branch, Charsets.UTF_8)}?expand=1"
}

private fun JsonObject.string(name: String): String? = get(name)?.takeIf { !it.isJsonNull }?.asString
private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject
private fun JsonObject.array(name: String): JsonArray = get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
