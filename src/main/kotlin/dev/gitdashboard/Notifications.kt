package dev.gitdashboard

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil

/**
 * Shows a balloon in the IDE. [content] is plain text (e.g. git output) and is escaped.
 *
 * Errors and warnings stay until closed, so they can't fade away unread; other messages fade on
 * their own. Long, multi-line text (usually raw git output) shows its first line, with the rest
 * behind "Show Details". A message that points at the GitHub settings gets a button to open them.
 */
fun notifyUser(
    project: Project,
    type: NotificationType,
    title: String,
    content: String = "",
    actions: List<AnAction> = emptyList(),
) {
    val text = content.trim()
    val lines = text.lines().filter { it.isNotBlank() }
    val long = lines.size > 2 || text.length > 300
    val shown = if (long) lines.first().take(200).trimEnd() + "…" else text
    val extra = buildList {
        if ("Version Control > GitHub" in text) {
            add(NotificationAction.createSimpleExpiring("Open GitHub Settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "GitHub")
            })
        }
        if (long) add(NotificationAction.createSimple("Show Details") { Messages.showInfoMessage(project, text, title) })
    }
    val group = if (type == NotificationType.ERROR || type == NotificationType.WARNING) PROBLEMS_GROUP else GROUP
    NotificationGroupManager.getInstance()
        .getNotificationGroup(group)
        .createNotification(title, StringUtil.escapeXmlEntities(shown).replace("\n", "<br>"), type)
        .addActions(actions + extra)
        .notify(project)
}

private const val GROUP = "Git Dashboard"
private const val PROBLEMS_GROUP = "Git Dashboard Problems"

fun pluralize(count: Int, singular: String, plural: String = "${singular}s"): String =
    "$count ${if (count == 1) singular else plural}"
