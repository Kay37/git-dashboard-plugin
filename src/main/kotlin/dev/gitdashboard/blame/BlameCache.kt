package dev.gitdashboard.blame

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import dev.gitdashboard.git.tryGit
import git4idea.GitVcs
import git4idea.annotate.GitFileAnnotation
import git4idea.commands.GitCommand
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Git blame per file, loaded in the background on first request and kept until the repository's
 * HEAD moves (a commit, pull, reset or checkout). Local edits don't invalidate it: callers map
 * edited lines back to committed ones with IntelliJ's line-status tracking.
 */
@Service(Service.Level.PROJECT)
class BlameCache(private val project: Project, private val cs: CoroutineScope) : Disposable {

    sealed interface Entry {
        class Ready(val annotation: GitFileAnnotation) : Entry
        /** Not blameable: untracked, binary, or git refused. Treated as all-uncommitted. */
        data object Unavailable : Entry
    }

    private val entries = ConcurrentHashMap<VirtualFile, Entry>()
    private val inFlight = ConcurrentHashMap.newKeySet<VirtualFile>()
    private val heads = ConcurrentHashMap<VirtualFile, String>()
    private val commitNumbers = ConcurrentHashMap<String, Int>()
    private val numbersInFlight = ConcurrentHashMap.newKeySet<String>()

    /** Called on the EDT after entries were dropped, so decorations can be redrawn. */
    var onInvalidated: () -> Unit = {}

    init {
        project.messageBus.connect(this).subscribe(GitRepository.GIT_REPO_CHANGE, object : GitRepositoryChangeListener {
            override fun repositoryChanged(repository: GitRepository) = onRepositoryChanged(repository)
        })
    }

    fun get(file: VirtualFile): Entry? = entries[file]

    /** Starts loading blame for [file] unless it's cached or already loading; [onReady] runs on the EDT. */
    fun request(file: VirtualFile, onReady: () -> Unit) {
        if (entries.containsKey(file) || !inFlight.add(file)) return
        cs.launch(Dispatchers.IO) {
            val entry = try {
                (GitVcs.getInstance(project).annotationProvider.annotate(file) as? GitFileAnnotation)
                    ?.let { Entry.Ready(it) } ?: Entry.Unavailable
            }
            catch (e: VcsException) {
                Entry.Unavailable
            }
            entries[file] = entry
            inFlight.remove(file)
            withContext(Dispatchers.EDT) { onReady() }
        }
    }

    /**
     * The commit's position in its own history (1 for the first commit), from `git rev-list --count`.
     * Returns null and starts the lookup if it isn't known yet; [onReady] runs on the EDT. A
     * commit's history never changes, so results are kept for the whole session.
     */
    fun commitNumber(repo: GitRepository, hash: String, onReady: () -> Unit): Int? {
        commitNumbers[hash]?.let { return it }
        if (!numbersInFlight.add(hash)) return null
        cs.launch(Dispatchers.IO) {
            val result = tryGit(project, repo, GitCommand.REV_LIST, "--count", hash) { setSilent(true) }
            result.outputAsJoinedString.trim().toIntOrNull()?.takeIf { result.success() }?.let { commitNumbers[hash] = it }
            numbersInFlight.remove(hash)
            withContext(Dispatchers.EDT) { onReady() }
        }
        return null
    }

    private fun onRepositoryChanged(repo: GitRepository) {
        val head = repo.currentRevision.orEmpty()
        if (heads.put(repo.root, head) == head) return
        entries.keys.removeIf { VfsUtilCore.isAncestor(repo.root, it, false) }
        cs.launch(Dispatchers.EDT) { onInvalidated() }
    }

    override fun dispose() = entries.clear()
}
