package dev.gitdashboard.git

import com.intellij.ide.PowerSaveMode
import com.intellij.ide.util.PropertiesComponent
import com.intellij.externalProcessAuthHelper.AuthenticationMode
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFrame
import dev.gitdashboard.refreshRound
import git4idea.branch.GitBranchUtil
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val LOG = Logger.getInstance(BackgroundFetchService::class.java)

/**
 * Held while this plugin fetches, so the background check and a Sync never fetch the same
 * repository at once (two fetches can trip over each other's ref locks and fail).
 */
internal val fetchLock = ReentrantLock()

/** IDE-wide switch for the background check; on by default. */
object FetchSettings {
    private const val AUTO_FETCH = "gitDashboard.autoFetch"

    var autoFetch: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(AUTO_FETCH, true)
        set(value) = PropertiesComponent.getInstance().setValue(AUTO_FETCH, value, true)
}

/**
 * Quietly asks each repository's remote (usually GitHub) for new commits, so the status bar's
 * ↓ chip shows commits pushed from elsewhere without the user syncing first. Checks shortly after
 * the project opens, every [INTERVAL_MINUTES] minutes, and when you come back to IntelliJ after a
 * while away (say, from merging a pull request in the browser).
 *
 * It only fetches: it downloads the remote's commits into origin/…, and never changes your
 * branch or files. It never shows a sign-in prompt: if the saved sign-in doesn't work, the check
 * fails quietly and the status bar tooltip says why.
 */
@Service(Service.Level.PROJECT)
class BackgroundFetchService(private val project: Project, private val cs: CoroutineScope) {
    /** How the latest check of one repository went: when, and why it failed (null = it worked). */
    data class Check(val at: Long, val failure: String?)

    private val _checks = MutableStateFlow<Map<VirtualFile, Check>>(emptyMap())
    /** The latest check of each repository, by repository root. */
    val checks: StateFlow<Map<VirtualFile, Check>> = _checks.asStateFlow()

    private val started = AtomicBoolean()
    private val running = Mutex()
    @Volatile private var lastRound = 0L

    fun start() {
        if (!started.compareAndSet(false, true)) return
        cs.launch {
            delay(TimeUnit.SECONDS.toMillis(FIRST_CHECK_SECONDS))
            while (true) {
                refreshRound("the background check for new commits") { checkNow() }
                delay(TimeUnit.MINUTES.toMillis(INTERVAL_MINUTES))
            }
        }
        ApplicationManager.getApplication().messageBus.connect(cs)
            .subscribe(ApplicationActivationListener.TOPIC, object : ApplicationActivationListener {
                override fun applicationActivated(ideFrame: IdeFrame) {
                    val away = System.currentTimeMillis() - lastRound
                    if (ideFrame.project == project && away > TimeUnit.MINUTES.toMillis(RETURN_GAP_MINUTES)) {
                        cs.launch { refreshRound("the check for new commits on return") { checkNow() } }
                    }
                }
            })
    }

    /**
     * Fetches the remote each repository's current branch tracks and returns how each went.
     * Unless [userAsked], it does nothing while the check is switched off or IntelliJ is in power
     * save mode, and skips the round if one is already running.
     */
    suspend fun checkNow(userAsked: Boolean = false): Map<VirtualFile, Check> {
        if (!userAsked && (!FetchSettings.autoFetch || PowerSaveMode.isEnabled())) return emptyMap()
        if (!userAsked && running.isLocked) return emptyMap()
        return running.withLock {
            lastRound = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                GitRepositoryManager.getInstance(project).repositories.mapNotNull { repo ->
                    fetchQuietly(repo, waitForSync = userAsked)?.let { check ->
                        _checks.update { it + (repo.root to check) }
                        repo.root to check
                    }
                }.toMap()
            }
        }
    }

    /** Null when there is nothing to check (no branch, no upstream) or a Sync is fetching right now. */
    private fun fetchQuietly(repo: GitRepository, waitForSync: Boolean): Check? {
        val branch = repo.currentBranch ?: return null
        val remote = GitBranchUtil.getTrackInfoForBranch(repo, branch)?.remote ?: return null
        if (waitForSync) fetchLock.lock() else if (!fetchLock.tryLock()) return null
        val result = try {
            val handler = GitLineHandler(project, repo.root, GitCommand.FETCH).apply {
                setSilent(true)
                setUrls(remote.urls)
                // Saved sign-ins only: a background check must never pop up a password prompt.
                ignoreAuthenticationMode = AuthenticationMode.SILENT
                addCustomEnvironmentVariable("GIT_TERMINAL_PROMPT", "0")
                addParameters(remote.name, "--recurse-submodules=no")
            }
            Git.getInstance().runCommand(handler)
        }
        finally {
            fetchLock.unlock()
        }
        val now = System.currentTimeMillis()
        if (!result.success()) {
            val raw = result.errorOutputAsJoinedString
            LOG.info("Background fetch of ${repo.root.name} from ${remote.name} failed: $raw")
            val fallback = "Couldn't reach ${remote.name}. Click Sync to try it yourself."
            return Check(now, explainRemoteFailure(result, fallback).takeIf { it != raw } ?: fallback)
        }
        repo.update()
        return Check(now, failure = null)
    }

    companion object {
        const val INTERVAL_MINUTES = 10L
        private const val FIRST_CHECK_SECONDS = 30L
        private const val RETURN_GAP_MINUTES = 5L
    }
}

/** Starts the background check when a project opens. */
class BackgroundFetchStartup : ProjectActivity {
    override suspend fun execute(project: Project) = project.service<BackgroundFetchService>().start()
}

/**
 * When anything (this plugin, IntelliJ, the command line) last fetched into [repo]: git rewrites
 * FETCH_HEAD on every fetch and pull. Null if it never has. Reads the disk, so call off the EDT.
 */
internal fun lastFetchTime(repo: GitRepository): Long? {
    // FETCH_HEAD sits beside HEAD, in the .git folder (or a worktree's own git folder).
    val fetchHead = File(repo.repositoryFiles.headFile.parentFile, "FETCH_HEAD")
    return fetchHead.lastModified().takeIf { it > 0 }
}
