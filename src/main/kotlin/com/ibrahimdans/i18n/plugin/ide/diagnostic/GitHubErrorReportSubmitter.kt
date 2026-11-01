package com.ibrahimdans.i18n.plugin.ide.diagnostic

import com.ibrahimdans.i18n.plugin.ide.whatsnew.PluginVersion
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.PluginNotifications
import com.intellij.diagnostic.JetBrainsMarketplaceErrorReportSubmitter
import com.intellij.ide.BrowserUtil
import com.intellij.ide.DataManager
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.Consumer
import java.awt.Component
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Reports an exception of the plugin to the JetBrains Marketplace, as the platform's own submitter
 * did, then offers a GitHub issue pre-filled with it.
 *
 * The Marketplace report is kept on purpose: its *Exceptions* tab is the only place that shows the
 * IDE builds the plugin's users run. But it stays private to the vendor, and the user who hit the
 * error cannot follow it or say how to reproduce it. The plugin may declare a single
 * `errorHandler`, so this one sends to the Marketplace first, through
 * [JetBrainsMarketplaceErrorReportSubmitter], and once that is done — sent or failed — shows a
 * notification whose link opens the issue form in the browser. Nothing reaches GitHub until the
 * user clicks it and submits the form, which they can review: a stack trace may hold file paths.
 *
 * [marketplace] and [offer] are swapped by the tests: the real ones send a report over the network
 * and open a browser.
 */
class GitHubErrorReportSubmitter internal constructor(
    private val marketplace: ErrorReportSubmitter,
    private val offer: (project: Project?, url: String, sentToMarketplace: Boolean) -> Unit,
) : ErrorReportSubmitter() {

    constructor() : this(JetBrainsMarketplaceErrorReportSubmitter(), ::offerIssue)

    override fun getReportActionText(): String = marketplace.reportActionText

    override fun getPrivacyNoticeText(): String? = marketplace.privacyNoticeText

    /** Passed on, so that the Marketplace report is filed under this plugin. */
    override fun setPluginDescriptor(plugin: PluginDescriptor) {
        super.setPluginDescriptor(plugin)
        marketplace.setPluginDescriptor(plugin)
    }

    override fun submit(
        events: Array<out IdeaLoggingEvent>,
        additionalInfo: String?,
        parentComponent: Component,
        consumer: Consumer<in SubmittedReportInfo>,
    ): Boolean {
        val project = CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(parentComponent))
        val url = ErrorIssue.url(errorText(events), additionalInfo, environment())
        val submitted = marketplace.submit(events, additionalInfo, parentComponent) { info ->
            consumer.consume(info)
            offer(project, url, info.status != SubmittedReportInfo.SubmissionStatus.FAILED)
        }
        // The Marketplace declined to send anything: the issue is still worth offering.
        if (!submitted) offer(project, url, false)
        return submitted
    }

    private fun errorText(events: Array<out IdeaLoggingEvent>): String {
        val event = events.firstOrNull() ?: return ""
        return event.throwableText.takeIf { it.isNotBlank() } ?: event.message.orEmpty()
    }

    private fun environment() = ErrorIssue.Environment(
        pluginVersion = pluginDescriptor?.version ?: PluginVersion.current.orEmpty(),
        ide = ApplicationInfo.getInstance().let { "${it.fullApplicationName} (${it.build.asString()})" },
        os = "${SystemInfo.OS_NAME} ${SystemInfo.OS_VERSION} (${SystemInfo.OS_ARCH})",
    )

    private companion object {
        fun offerIssue(project: Project?, url: String, sentToMarketplace: Boolean) {
            val content = if (sentToMarketplace) "error.report.github.content.sent" else "error.report.github.content.failed"
            NotificationGroupManager.getInstance()
                .getNotificationGroup(PluginNotifications.STICKY)
                .createNotification(
                    PluginBundle.message("error.report.github.title"),
                    PluginBundle.message(content),
                    if (sentToMarketplace) NotificationType.INFORMATION else NotificationType.WARNING
                )
                .addAction(NotificationAction.createSimpleExpiring(PluginBundle.message("error.report.github.action")) {
                    BrowserUtil.browse(url)
                })
                .notify(project)
        }
    }
}

/**
 * The new-issue URL of an exception: a `[Bug]` title naming the exception, and a body following
 * the repository's bug report template — what the user said, the environment, the stack trace.
 *
 * The whole fits [MAX_URL_LENGTH], under GitHub's limit (~8 k characters): the user's text is
 * capped at [MAX_DESCRIPTION_LENGTH] and the title at [MAX_TITLE_LENGTH], whose encoded size is
 * then bounded, and the stack trace gives up its last lines first, then the end of its first one.
 * The top of a trace is what names the error and where it was raised.
 */
internal object ErrorIssue {

    private const val NEW_ISSUE = "https://github.com/IBRAHIMDANS/i18nSupportPlus/issues/new"

    /** Under GitHub's URL limit, with room for the browser — the limit [com.ibrahimdans.i18n.plugin.ide.settings.PresetSuggestion] keeps too. */
    const val MAX_URL_LENGTH = 7500

    const val MAX_TITLE_LENGTH = 150

    /** At up to nine encoded characters each, the user's text cannot crowd the trace out. */
    const val MAX_DESCRIPTION_LENGTH = 500

    private const val NO_DESCRIPTION = "<!-- What were you doing when the error happened? -->"

    data class Environment(val pluginVersion: String, val ide: String, val os: String)

    fun url(errorText: String, additionalInfo: String?, environment: Environment): String {
        val title = title(errorText)
        val description = description(additionalInfo)
        val lines = errorText.trimEnd().lines().filter { it.isNotBlank() }
        var kept = lines.size
        while (true) {
            val url = urlOf(title, body(description, environment, trace(lines, kept)))
            if (url.length <= MAX_URL_LENGTH) return url
            if (kept <= 1) return fitFirstLine(title, description, environment, lines)
            // Lines differ in size: drop at least one, at most the share of the excess.
            val share = kept.toLong() * MAX_URL_LENGTH / url.length
            kept = minOf(kept - 1, share.toInt()).coerceAtLeast(1)
        }
    }

    /** `[Bug] IllegalStateException: message`, from the first line of the trace. */
    fun title(errorText: String): String {
        val first = errorText.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: return "[Bug] Exception in i18n Support Plus"
        val exception = first.substringBefore(": ")
        val message = first.substringAfter(": ", "")
        // A class name is shortened, a message that only looks like one is left as it is.
        val name = if (exception.contains(' ')) exception else exception.substringAfterLast('.')
        val text = if (message.isEmpty()) name else "$name: $message"
        return "[Bug] $text".take(MAX_TITLE_LENGTH)
    }

    private fun description(additionalInfo: String?): String {
        val text = additionalInfo?.trim().orEmpty()
        if (text.isEmpty()) return NO_DESCRIPTION
        return if (text.length <= MAX_DESCRIPTION_LENGTH) text else text.take(MAX_DESCRIPTION_LENGTH) + "…"
    }

    private fun trace(lines: List<String>, kept: Int): String {
        val shown = lines.take(kept).joinToString("\n")
        val left = lines.size - kept
        return if (left > 0) "$shown\n\t… $left more lines" else shown
    }

    /** A single line too long for the URL, an exception message holding a whole file: cut its end. */
    private fun fitFirstLine(title: String, description: String, environment: Environment, lines: List<String>): String {
        var line = lines.firstOrNull().orEmpty()
        val more = lines.size - 1
        while (true) {
            val trace = line + "…" + if (more > 0) "\n\t… $more more lines" else ""
            val url = urlOf(title, body(description, environment, trace))
            if (url.length <= MAX_URL_LENGTH || line.isEmpty()) return url
            // An encoded character takes up to nine: cut at least the excess, at most what is left.
            line = line.dropLast(((url.length - MAX_URL_LENGTH) / 9).coerceIn(1, line.length))
        }
    }

    /** Joined line by line rather than through `trimMargin`, which would also eat a `|` starting a line of the user's text. */
    private fun body(description: String, environment: Environment, trace: String): String = listOf(
        "## Description",
        "",
        description,
        "",
        "## Steps to Reproduce",
        "",
        "1. ",
        "",
        "## Environment",
        "",
        "| Item | Value |",
        "|------|-------|",
        "| Plugin version | ${environment.pluginVersion} |",
        "| IDE & version | ${environment.ide} |",
        "| OS | ${environment.os} |",
        "",
        "## Stack trace",
        "",
        "```",
        trace,
        "```",
    ).joinToString("\n")

    private fun urlOf(title: String, body: String) = "$NEW_ISSUE?labels=bug&title=${encode(title)}&body=${encode(body)}"

    /** Query encoding with spaces as `%20`: GitHub shows a `+` as written in the issue body. */
    private fun encode(text: String): String = URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20")
}
