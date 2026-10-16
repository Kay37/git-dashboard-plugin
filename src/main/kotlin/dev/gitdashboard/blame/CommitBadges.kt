package dev.gitdashboard.blame

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.gitdashboard.git.listUnpushedCommits
import dev.gitdashboard.git.tryGit
import git4idea.GitUserRegistry
import git4idea.commands.GitCommand
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Small labels shown next to the inline blame. */
enum class CommitBadge(val text: String) {
    NOT_PUSHED("↑ Not pushed"),
    NEW("✦ New"),
}

/**
 * Knows which commits deserve a badge:
 *  - NOT_PUSHED: on the current branch but not on its upstream yet;
 *  - NEW: arrived in the last day from someone else, via Sync or any other pull/merge, so the
 *    user can spot teammates' changes.
 */
@Service(Service.Level.PROJECT)
class CommitBadges(private val project: Project, private val cs: CoroutineScope) : Disposable {
    private val unpushed = ConcurrentHashMap<VirtualFile, Set<String>>()
    private val unpushedLoading = ConcurrentHashMap.newKeySet<VirtualFile>()

    /** Last seen (branch, HEAD) per repository root, to notice commits arriving. */
    private val lastSeen = ConcurrentHashMap<VirtualFile, Pair<String?, String>>()
    /** Hash -> time it arrived; persisted per project so badges survive an IDE restart. */
    private val arrived = ConcurrentHashMap<String, Long>(loadArrived())

    /** Called on the EDT when badge data changed, so decorations can be redrawn. */
    var onChanged: () -> Unit = {}

    init {
        GitRepositoryManager.getInstance(project).repositories.forEach(::remember)
        project.messageBus.connect(this).subscribe(GitRepository.GIT_REPO_CHANGE, object : GitRepositoryChangeListener {
            override fun repositoryChanged(repository: GitRepository) = onRepositoryChanged(repository)
        })
    }

    /** Badges for [hash], or null while the unpushed list is still loading ([onReady] runs on the EDT). */
    fun badgesFor(repo: GitRepository, hash: String, onReady: () -> Unit): Set<CommitBadge>? {
        val unpushedHashes = unpushed[repo.root] ?: run {
            loadUnpushed(repo, onReady)
            return null
        }
        return buildSet {
            if (hash in unpushedHashes) add(CommitBadge.NOT_PUSHED)
            if (isNew(hash)) add(CommitBadge.NEW)
        }
    }

    fun isNew(hash: String): Boolean = arrived[hash]?.let { System.currentTimeMillis() - it < NEW_FOR_MILLIS } ?: false

    private fun loadUnpushed(repo: GitRepository, onReady: () -> Unit) {
        if (!unpushedLoading.add(repo.root)) return
        cs.launch(Dispatchers.IO) {
            unpushed[repo.root] = runCatching { listUnpushedCommits(project, repo).map { it.hash }.toSet() }.getOrDefault(emptySet())
            unpushedLoading.remove(repo.root)
            withContext(Dispatchers.EDT) { onReady() }
        }
    }

    private fun onRepositoryChanged(repo: GitRepository) {
        // Any change (commit, push, fetch) can change what's unpushed: reload lazily.
        unpushed.remove(repo.root)

        val previous = lastSeen[repo.root]
        remember(repo)
        val head = repo.currentRevision
        val sameBranch = previous != null && previous.first == repo.currentBranch?.name
        if (head == null || previous == null || previous.second == head || !sameBranch) {
            // A branch switch brings in other commits too, but they aren't "new from teammates".
            cs.launch(Dispatchers.EDT) { onChanged() }
            return
        }
        cs.launch(Dispatchers.IO) {
            recordArrivals(repo, from = previous.second, to = head)
            withContext(Dispatchers.EDT) { onChanged() }
        }
    }

    /** Commits now reachable from HEAD that weren't before, written by someone other than the user. */
    private fun recordArrivals(repo: GitRepository, from: String, to: String) {
        val yourEmail = GitUserRegistry.getInstance(project).getUser(repo.root)?.email
        val result = tryGit(project, repo, GitCommand.LOG, "--format=%H%x1f%ae", "$from..$to") { setSilent(true) }
        if (!result.success()) return
        val now = System.currentTimeMillis()
        result.output.map { it.split('\u001f') }
            .filter { it.size == 2 && !it[1].equals(yourEmail, ignoreCase = true) }
            .take(MAX_TRACKED)
            .forEach { arrived[it[0]] = now }
        saveArrived()
    }

    private fun remember(repo: GitRepository) {
        repo.currentRevision?.let { lastSeen[repo.root] = repo.currentBranch?.name to it }
    }

    private fun loadArrived(): Map<String, Long> {
        val now = System.currentTimeMillis()
        return PropertiesComponent.getInstance(project).getList(ARRIVED_KEY).orEmpty()
            .mapNotNull { entry -> entry.split(':').takeIf { it.size == 2 }?.let { it[0] to (it[1].toLongOrNull() ?: 0) } }
            .filter { now - it.second < NEW_FOR_MILLIS }
            .toMap()
    }

    private fun saveArrived() {
        val now = System.currentTimeMillis()
        arrived.entries.removeIf { now - it.value >= NEW_FOR_MILLIS }
        val entries = arrived.entries.sortedByDescending { it.value }.take(MAX_TRACKED).map { "${it.key}:${it.value}" }
        PropertiesComponent.getInstance(project).setList(ARRIVED_KEY, entries)
    }

    override fun dispose() = Unit

    private companion object {
        const val ARRIVED_KEY = "gitDashboard.newCommits"
        val NEW_FOR_MILLIS = TimeUnit.HOURS.toMillis(24)
        const val MAX_TRACKED = 500
    }
}
