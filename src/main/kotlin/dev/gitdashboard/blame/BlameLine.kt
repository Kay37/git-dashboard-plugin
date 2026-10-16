package dev.gitdashboard.blame

import com.intellij.openapi.vcs.impl.UpToDateLineNumberProviderImpl
import dev.gitdashboard.pluralize
import git4idea.annotate.GitFileAnnotation
import java.util.Date

/** What the editor shows for one line: a description, plus the commit's ID when there is one. */
sealed interface BlameLine {
    val label: String
    val commitId: String?

    data class Committed(
        val info: GitFileAnnotation.LineInfo,
        val byYou: Boolean,
        /** Position of the commit in its history (1 = first commit); null until looked up. */
        val number: Int? = null,
    ) : BlameLine {
        val hash: String get() = info.revisionNumber.asString()
        val shortHash: String get() = hash.take(7)
        val author: String get() = if (byYou) "You" else info.author
        override val label: String
            get() = "$author, ${relativeTime(info.authorDate)} • ${info.subject.ellipsize(60)}"
        override val commitId: String
            get() = listOfNotNull(number?.let { "#$it" }, shortHash).joinToString(" ")
    }

    data object Uncommitted : BlameLine {
        override val label = "You • Not committed yet"
        override val commitId: String? = null
    }
}

/**
 * Blame for [editorLine] of the current document. Lines edited since the last commit, and files
 * with no blame at all, count as uncommitted.
 */
fun describeLine(annotation: GitFileAnnotation?, lines: UpToDateLineNumberProviderImpl, editorLine: Int, yourEmail: String?): BlameLine {
    if (annotation == null) return BlameLine.Uncommitted
    val committedLine = lines.getLineNumber(editorLine)
    if (committedLine < 0 || committedLine >= annotation.lineCount) return BlameLine.Uncommitted
    val info = annotation.getLineInfo(committedLine) ?: return BlameLine.Uncommitted
    val byYou = yourEmail != null && info.authorUser?.email.equals(yourEmail, ignoreCase = true)
    return BlameLine.Committed(info, byYou)
}

fun relativeTime(date: Date, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - date.time) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${pluralize(minutes.toInt(), "minute")} ago"
        minutes < 60 * 24 -> "${pluralize((minutes / 60).toInt(), "hour")} ago"
        minutes < 60 * 24 * 30 -> "${pluralize((minutes / (60 * 24)).toInt(), "day")} ago"
        minutes < 60 * 24 * 365 -> "${pluralize((minutes / (60 * 24 * 30)).toInt(), "month")} ago"
        else -> "${pluralize((minutes / (60 * 24 * 365)).toInt(), "year")} ago"
    }
}

private fun String.ellipsize(max: Int): String = if (length <= max) this else take(max - 1).trimEnd() + "…"
