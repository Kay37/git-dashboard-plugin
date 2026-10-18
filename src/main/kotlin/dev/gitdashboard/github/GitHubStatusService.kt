package dev.gitdashboard.github

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.branch.GitBranchUtil
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.github.authentication.GHAccountsUtil
import org.jetbrains.plugins.github.authentication.accounts.GHAccountManager
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** What the dashboard knows about one repository on GitHub. */
sealed interface GitHubState {
    /** No github.com remote: nothing to show. */
    data object NotOnGitHub : GitHubState
    /**
     * On GitHub, but IntelliJ has no GitHub account to ask with, or GitHub rejected its sign-in
     * ([expiredAccount] is then the account to sign in again).
     */
    data class NeedsSignIn(val repo: RepoCoordinates, val expiredAccount: GithubAccount? = null) : GitHubState
    data class Failed(val repo: RepoCoordinates, val message: String) : GitHubState

    data class Ready(
        /** Where pull requests are opened: the repository itself, or the original a fork came from. */
        val repo: RepoCoordinates,
        /** Where the current branch is pushed; differs from [repo] when working in a fork. */
        val headRepo: RepoCoordinates,
        val defaultBranch: String,
        /** Current local branch, or null when detached. */
        val branch: String?,
        /** Name of the current branch on the remote, or null if it was never pushed. */
        val remoteBranch: String?,
        val pullRequests: List<PullRequest>,
        /** The open pull request whose head is the current branch, if any. */
        val currentPullRequest: PullRequest?,
        /** Checks for the current branch's pushed commit (its PR head, or its remote branch). */
        val currentChecks: ChecksSummary?,
        /** Checks for the first few pull requests in [pullRequests], by PR number. */
        val pullRequestChecks: Map<Int, ChecksSummary?>,
    ) : GitHubState {
        val otherPullRequests: List<PullRequest> get() = pullRequests.filter { it != currentPullRequest }
        val isFork: Boolean get() = !headRepo.fullName.equals(repo.fullName, ignoreCase = true)
    }
}

/**
 * Keeps open pull requests and CI status for every GitHub repository in the project, shared by
 * the Git Dashboard panel and the status bar. Refreshes after Git changes (e.g. a push), every
 * 5 minutes, and every 30 seconds while checks are running.
 */
@Service(Service.Level.PROJECT)
class GitHubStatusService(private val project: Project, private val cs: CoroutineScope) : Disposable {
    private val _states = MutableStateFlow<Map<VirtualFile, GitHubState>>(emptyMap())
    val states: StateFlow<Map<VirtualFile, GitHubState>> = _states.asStateFlow()

    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val repoInfos = ConcurrentHashMap<RepoCoordinates, RepoInfo>()

    init {
        project.messageBus.connect(this).subscribe(GitRepository.GIT_REPO_CHANGE, object : GitRepositoryChangeListener {
            override fun repositoryChanged(repository: GitRepository) = refresh()
        })
        startLoops()
        // Signing in or out under Settings > Version Control > GitHub updates the dashboard straight away.
        cs.launch { ApplicationManager.getApplication().service<GHAccountManager>().accountsState.collect { refresh() } }
    }

    fun refresh() {
        refreshRequests.tryEmit(Unit)
    }

    fun stateFor(repo: GitRepository): GitHubState = _states.value[repo.root] ?: GitHubState.NotOnGitHub

    @OptIn(FlowPreview::class)
    private fun startLoops() {
        cs.launch {
            refreshRequests.debounce(1500).collectLatest {
                val repos = GitRepositoryManager.getInstance(project).repositories
                _states.value = repos.associate { it.root to load(it) }
            }
        }
        cs.launch {
            while (true) {
                val checksRunning = _states.value.values.any { state ->
                    state is GitHubState.Ready && (state.currentChecks?.state == ChecksSummary.State.RUNNING ||
                        state.pullRequestChecks.values.any { it?.state == ChecksSummary.State.RUNNING })
                }
                delay(if (checksRunning) TimeUnit.SECONDS.toMillis(30) else TimeUnit.MINUTES.toMillis(5))
                refresh()
            }
        }
    }

    private suspend fun load(repo: GitRepository): GitHubState {
        val coordinates = gitHubCoordinates(repo) ?: return GitHubState.NotOnGitHub
        val (account, token) = signedInAccount() ?: return GitHubState.NeedsSignIn(coordinates)
        return withContext(Dispatchers.IO) {
            try {
                // In a fork, pull requests live on the original repository, so ask that one.
                val info = repoInfos.getOrPut(coordinates) { GitHubApi(token, coordinates).repoInfo() }
                val pullRepo = info.parent ?: coordinates
                val api = GitHubApi(token, pullRepo)
                val defaultBranch = info.parentDefaultBranch ?: info.defaultBranch
                val branch = repo.currentBranch
                val trackInfo = branch?.let { GitBranchUtil.getTrackInfoForBranch(repo, it) }
                val tracked = trackInfo?.remoteBranch
                val remoteBranch = tracked?.nameForRemoteOperations
                val headRepo = trackInfo?.remote?.let(::gitHubCoordinates) ?: coordinates

                val pullRequests = api.openPullRequests()
                val current = remoteBranch?.let { name ->
                    pullRequests.firstOrNull { it.headRef == name && (it.headRepo ?: pullRepo.fullName).equals(headRepo.fullName, ignoreCase = true) }
                }
                // A failed checks look-up only hides the CI line; it shouldn't hide the pull requests.
                val currentChecks = when {
                    current != null -> pullRequestChecks(api, current)
                    tracked != null -> repo.branches.getHash(tracked)?.asString()
                        ?.let { sha -> runCatching { GitHubApi(token, headRepo).checks(sha) }.getOrNull() }
                    else -> null
                }
                val pullRequestChecks = pullRequests.take(CHECKED_PULL_REQUESTS).associate { pr ->
                    pr.number to if (pr == current) currentChecks else pullRequestChecks(api, pr)
                }
                GitHubState.Ready(pullRepo, headRepo, defaultBranch, branch?.name, remoteBranch, pullRequests, current, currentChecks, pullRequestChecks)
            }
            catch (e: GitHubApiException) {
                if (e.code == 401) GitHubState.NeedsSignIn(coordinates, expiredAccount = account)
                else GitHubState.Failed(coordinates, e.message.orEmpty())
            }
        }
    }

    /** Checks on a pull request's latest commit, linking to the pull request's Checks tab. */
    private fun pullRequestChecks(api: GitHubApi, pr: PullRequest): ChecksSummary? =
        runCatching { api.checks(pr.headSha) }.getOrNull()?.copy(url = "${pr.url}/checks")

    /** A github.com account IntelliJ knows, and its stored token. Never shows a sign-in dialog. */
    suspend fun signedInAccount(): Pair<GithubAccount, String>? {
        val accounts = GHAccountsUtil.accounts.filter { it.server.isGithubDotCom }
        val preferred = GHAccountsUtil.getDefaultAccount(project)?.takeIf { it in accounts }
        val manager = ApplicationManager.getApplication().service<GHAccountManager>()
        for (account in listOfNotNull(preferred) + (accounts - setOfNotNull(preferred))) {
            manager.findCredentials(account)?.let { return account to it }
        }
        return null
    }

    override fun dispose() = Unit

    private companion object {
        /** Checks are fetched for this many pull requests per refresh, to stay well within GitHub's limits. */
        const val CHECKED_PULL_REQUESTS = 8
    }
}
