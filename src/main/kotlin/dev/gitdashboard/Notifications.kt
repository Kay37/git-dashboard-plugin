package dev.gitdashboard

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil

/** Shows a balloon in the IDE. [content] is plain text (e.g. git output) and is escaped. */
fun notifyUser(
    project: Project,
    type: NotificationType,
    title: String,
    content: String = "",
    actions: List<AnAction> = emptyList(),
) {
    val html = StringUtil.escapeXmlEntities(content.trim()).replace("\n", "<br>")
    NotificationGroupManager.getInstance()
        .getNotificationGroup("Git Dashboard")
        .createNotification(title, html, type)
        .addActions(actions)
        .notify(project)
}

fun pluralize(count: Int, singular: String, plural: String = "${singular}s"): String =
    "$count ${if (count == 1) singular else plural}"
