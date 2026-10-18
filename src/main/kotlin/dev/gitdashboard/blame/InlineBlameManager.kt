package dev.gitdashboard.blame

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.InlayProperties
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vcs.impl.UpToDateLineNumberProviderImpl
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import git4idea.GitUserRegistry
import git4idea.annotate.GitFileAnnotation
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Color
import java.awt.Cursor
import java.awt.event.MouseEvent

/**
 * Draws the editor decorations for the line under the caret: the blame text at the end of the
 * line, and a tint on every other line that came from the same commit. Only the caret line of the
 * focused editor is decorated, and only once the caret settles. The focused editor also gets
 * commit-age stripes in the gutter for the whole file.
 */
@Service(Service.Level.PROJECT)
class InlineBlameManager(private val project: Project, private val cs: CoroutineScope) : Disposable {
    private val updates = MutableSharedFlow<Editor>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var started = false

    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true

        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (event.editor.project != project) return
                // Typing moves the line end; re-measure now so the pill doesn't drift until the next update.
                realign(event.editor)
                updates.tryEmit(event.editor)
            }
        }, this)
        multicaster.addVisibleAreaListener(object : VisibleAreaListener {
            override fun visibleAreaChanged(e: VisibleAreaEvent) {
                // Only horizontal changes (resize, sideways scroll) move the right-aligned pill.
                val old = e.oldRectangle
                if (e.editor.project == project && (old == null || old.x != e.newRectangle.x || old.width != e.newRectangle.width)) {
                    realign(e.editor)
                }
            }
        }, this)
        multicaster.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(e: EditorMouseEvent) = updateCursor(e)
        }, this)
        multicaster.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseClicked(e: EditorMouseEvent) = onClick(e)
        }, this)
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = refreshAll()
        })
        project.service<BlameCache>().onInvalidated = ::refreshAll
        project.service<CommitBadges>().onChanged = ::refreshAll

        cs.launch {
            updates.debounce(120).collect { editor -> withContext(Dispatchers.EDT) { update(editor) } }
        }
        refreshAll()
    }

    /** Redraws the selected editor and clears decorations everywhere else. Call on the EDT. */
    fun refreshAll() {
        val selected = FileEditorManager.getInstance(project).selectedTextEditor
        EditorFactory.getInstance().allEditors
            .filter { it.project == project && it != selected }
            .forEach(::clear)
        selected?.let(::update)
    }

    private fun update(editor: Editor) {
        if (editor.isDisposed) return
        if (editor.editorKind != EditorKind.MAIN_EDITOR ||
            (!BlameSettings.inlineBlame && !BlameSettings.commitLineMarks && !BlameSettings.ageStripes)) return clear(editor)
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return clear(editor)
        val repo = GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(file) ?: return clear(editor)

        val cache = project.service<BlameCache>()
        val annotation = when (val entry = cache.get(file)) {
            null -> {
                cache.request(file) { update(editor) }
                return
            }
            is BlameCache.Entry.Ready -> entry.annotation
            BlameCache.Entry.Unavailable -> null
        }

        val document = editor.document
        val caretLine = editor.caretModel.logicalPosition.line
        if (caretLine >= document.lineCount) return clear(editor)

        val lines = UpToDateLineNumberProviderImpl(document, project)
        showAgeStripes(editor, annotation, lines)
        val yourEmail = GitUserRegistry.getInstance(project).getUser(repo.root)?.email
        val blame = when (val described = describeLine(annotation, lines, caretLine, yourEmail)) {
            is BlameLine.Committed -> described.copy(
                number = cache.commitNumber(repo, described.hash) { update(editor) },
                badges = project.service<CommitBadges>().badgesFor(repo, described.hash) { update(editor) }.orEmpty(),
            )
            BlameLine.Uncommitted -> described
        }

        // The whole-commit tint is on demand (double-click or shortcut) and lasts while the caret
        // stays on that commit's lines; moving to another commit's line drops it.
        val hash = (blame as? BlameLine.Committed)?.hash
        if (editor.getUserData(HIGHLIGHT_REQUESTED) == true) {
            editor.putUserData(HIGHLIGHT_REQUESTED, null)
            editor.putUserData(HIGHLIGHTED_COMMIT, hash)
        }
        else if (editor.getUserData(HIGHLIGHTED_COMMIT) != hash) {
            editor.putUserData(HIGHLIGHTED_COMMIT, null)
        }

        showInlay(editor, caretLine, blame)
        showSameCommitLines(editor, annotation, lines, editor.getUserData(HIGHLIGHTED_COMMIT))
    }

    /** Lights up every line of the caret line's commit, or turns that off if it's already on. Call on the EDT. */
    fun toggleCommitHighlight(editor: Editor) {
        if (editor.getUserData(HIGHLIGHTED_COMMIT) != null) editor.putUserData(HIGHLIGHTED_COMMIT, null)
        else editor.putUserData(HIGHLIGHT_REQUESTED, true)
        update(editor)
    }

    private fun showInlay(editor: Editor, line: Int, blame: BlameLine) {
        val existing = editor.getUserData(INLAY)
        if (BlameSettings.inlineBlame && existing != null && existing.isValid &&
            editor.document.getLineNumber(existing.offset) == line && existing.renderer.blame == blame) {
            existing.update()
            return
        }

        existing?.let(Disposer::dispose)
        editor.putUserData(INLAY, null)
        if (!BlameSettings.inlineBlame) return

        val properties = InlayProperties().relatesToPrecedingText(true).disableSoftWrapping(true)
        val inlay = editor.inlayModel.addAfterLineEndElement(editor.document.getLineEndOffset(line), properties, BlameInlayRenderer(blame))
        editor.putUserData(INLAY, inlay)
    }

    /** Tints (and marks on the scrollbar) every line that belongs to commit [hash]; null clears the tint. */
    private fun showSameCommitLines(editor: Editor, annotation: GitFileAnnotation?, lines: UpToDateLineNumberProviderImpl, hash: String?) {
        val document = editor.document
        val key = hash?.let { "$it@${document.modificationStamp}" }
        val existing = editor.getUserData(COMMIT_LINES)
        if (BlameSettings.commitLineMarks && existing != null && existing.key == key) return

        existing?.highlighters?.forEach { editor.markupModel.removeHighlighter(it) }
        editor.putUserData(COMMIT_LINES, null)
        if (!BlameSettings.commitLineMarks || hash == null || annotation == null || document.lineCount > MAX_LINES_FOR_MARKS) return

        val sameCommit = (0 until document.lineCount).filter { line ->
            val committed = lines.getLineNumber(line)
            committed >= 0 && committed < annotation.lineCount && annotation.getLineRevisionNumber(committed)?.asString() == hash
        }
        if (sameCommit.size < 2) return

        val attributes = TextAttributes().apply {
            backgroundColor = SAME_COMMIT_BACKGROUND
            errorStripeColor = SAME_COMMIT_STRIPE
        }
        val highlighters = sameCommit.map { line ->
            editor.markupModel.addLineHighlighter(line, HighlighterLayer.CARET_ROW - 1, attributes).apply {
                errorStripeTooltip = "Changed in the same commit"
            }
        }
        editor.putUserData(COMMIT_LINES, CommitLines(key!!, highlighters))
    }

    /** Gutter stripes for every commit block in the file, coloured by age; redrawn when the text or blame changes. */
    private fun showAgeStripes(editor: Editor, annotation: GitFileAnnotation?, lines: UpToDateLineNumberProviderImpl) {
        val document = editor.document
        val key = annotation?.let { "${System.identityHashCode(it)}@${document.modificationStamp}" }
        val existing = editor.getUserData(AGE_STRIPES)
        if (BlameSettings.ageStripes && existing != null && existing.key == key) return

        existing?.highlighters?.forEach { editor.markupModel.removeHighlighter(it) }
        editor.putUserData(AGE_STRIPES, null)
        if (!BlameSettings.ageStripes || annotation == null || document.lineCount > MAX_LINES_FOR_STRIPES) return

        val highlighters = CommitAgeStripes.blocks(annotation, lines, document.lineCount).map { block ->
            CommitAgeStripes.addHighlighter(editor, block) { clicked, event -> onStripeClicked(editor, clicked, event) }
        }
        editor.putUserData(AGE_STRIPES, CommitLines(key!!, highlighters))
    }

    private fun onStripeClicked(editor: Editor, block: CommitAgeStripes.Block, event: MouseEvent) {
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val repo = GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(file) ?: return
        val yourEmail = GitUserRegistry.getInstance(project).getUser(repo.root)?.email
        val info = block.info
        val hash = info.revisionNumber.asString()
        val blame = BlameLine.Committed(
            info,
            byYou = yourEmail != null && info.authorUser?.email.equals(yourEmail, ignoreCase = true),
            number = project.service<BlameCache>().commitNumber(repo, hash) {},
            badges = project.service<CommitBadges>().badgesFor(repo, hash) {}.orEmpty(),
        )
        showBlamePopup(project, repo, blame, RelativePoint(event))
    }

    /** Re-measures the blame pill's position (its width depends on the line end and window size). */
    private fun realign(editor: Editor) {
        editor.getUserData(INLAY)?.takeIf { it.isValid }?.update()
    }

    private fun clear(editor: Editor) {
        editor.getUserData(INLAY)?.let(Disposer::dispose)
        editor.putUserData(INLAY, null)
        editor.getUserData(COMMIT_LINES)?.highlighters?.forEach { editor.markupModel.removeHighlighter(it) }
        editor.putUserData(COMMIT_LINES, null)
        editor.getUserData(AGE_STRIPES)?.highlighters?.forEach { editor.markupModel.removeHighlighter(it) }
        editor.putUserData(AGE_STRIPES, null)
        editor.putUserData(HIGHLIGHTED_COMMIT, null)
        editor.putUserData(HIGHLIGHT_REQUESTED, null)
    }

    private fun blameUnderMouse(e: EditorMouseEvent): BlameLine.Committed? =
        (e.inlay?.renderer as? BlameInlayRenderer)?.blame as? BlameLine.Committed

    private fun updateCursor(e: EditorMouseEvent) {
        val editor = e.editor as? EditorEx ?: return
        if (editor.project != project) return
        editor.setCustomCursor(this, if (blameUnderMouse(e) != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else null)
    }

    private fun onClick(e: EditorMouseEvent) {
        if (e.editor.project != project) return
        // Double-clicking code lights up its commit. Not consumed, so the word still gets selected.
        if (e.mouseEvent.clickCount == 2 && e.mouseEvent.button == MouseEvent.BUTTON1 &&
            e.area == EditorMouseEventArea.EDITING_AREA && e.inlay == null) {
            e.editor.putUserData(HIGHLIGHTED_COMMIT, null)
            e.editor.putUserData(HIGHLIGHT_REQUESTED, true)
            update(e.editor)
            return
        }
        val blame = blameUnderMouse(e) ?: return
        val file = FileDocumentManager.getInstance().getFile(e.editor.document) ?: return
        val repo = GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(file) ?: return
        e.consume()
        showBlamePopup(project, repo, blame, RelativePoint(e.mouseEvent))
    }

    override fun dispose() {
        EditorFactory.getInstance().allEditors.filter { it.project == project }.forEach(::clear)
    }

    private class CommitLines(val key: String, val highlighters: List<RangeHighlighter>)

    private companion object {
        val INLAY = Key.create<Inlay<BlameInlayRenderer>>("gitDashboard.blameInlay")
        val COMMIT_LINES = Key.create<CommitLines>("gitDashboard.sameCommitLines")
        val AGE_STRIPES = Key.create<CommitLines>("gitDashboard.ageStripes")
        /** Commit whose lines are lit up on request, while the caret stays on it. */
        val HIGHLIGHTED_COMMIT = Key.create<String>("gitDashboard.highlightedCommit")
        /** Set by a double-click or the shortcut; the next update resolves it to the caret line's commit. */
        val HIGHLIGHT_REQUESTED = Key.create<Boolean>("gitDashboard.highlightRequested")

        /** Scanning every line on each caret move is cheap for normal files; skip huge ones. */
        const val MAX_LINES_FOR_MARKS = 5000
        /** Stripes are one highlighter per commit block, so they're fine on much larger files. */
        const val MAX_LINES_FOR_STRIPES = 30_000

        val SAME_COMMIT_BACKGROUND = JBColor(Color(64, 128, 255, 20), Color(90, 150, 255, 24))
        val SAME_COMMIT_STRIPE = JBColor(Color(64, 128, 255), Color(90, 150, 255))
    }
}
