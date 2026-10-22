package dev.gitdashboard

import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import kotlin.coroutines.cancellation.CancellationException

private val LOG = Logger.getInstance("dev.gitdashboard.Guards")

/**
 * Writes every edited file to disk, so git works on what's on screen: a commit includes the
 * latest typing, and a pull never rewrites a file under unsaved edits. IntelliJ's own commit and
 * update do the same. Call on the EDT, right before starting the git work.
 *
 * Saving needs IntelliJ's write-intent lock, which a Swing button click doesn't hold on its own
 * (clicking Commit and Push crashed with "Access is allowed from write thread only"), so take it here.
 */
fun saveOpenFiles() = WriteIntentReadAction.run { FileDocumentManager.getInstance().saveAllDocuments() }

/**
 * Runs one round of a refresh loop. An unexpected error (say, a Wi-Fi sign-in page answering
 * instead of GitHub) is logged and skipped, so the loop keeps going and the next round tries
 * again. Cancellation still stops the loop. Returns null when the round failed.
 */
suspend fun <T> refreshRound(what: String, round: suspend () -> T): T? =
    try {
        round()
    }
    catch (e: Exception) {
        if (e is CancellationException || e is ControlFlowException) throw e
        LOG.warn("Git Dashboard couldn't refresh $what; trying again on the next change", e)
        null
    }
