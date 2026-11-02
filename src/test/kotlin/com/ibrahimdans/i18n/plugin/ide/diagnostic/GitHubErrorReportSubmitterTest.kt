package com.ibrahimdans.i18n.plugin.ide.diagnostic

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.openapi.diagnostic.SubmittedReportInfo.SubmissionStatus
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.project.Project
import com.intellij.util.Consumer
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.swing.JPanel

/**
 * The GitHub issue offered after an error must open on GitHub whatever the size of the trace, and
 * name the error: the URL is what the browser receives, nothing else carries the report.
 */
class GitHubErrorReportSubmitterUrlTest {

    private val environment = ErrorIssue.Environment("1.17.0", "WebStorm 2025.3.4 (WS-253.32098.39)", "Linux 6.8 (amd64)")

    private fun traceOf(lines: Int) = buildString {
        append("java.lang.IllegalStateException: Translation file vanished while reading common.json\n")
        repeat(lines - 1) { append("\tat com.ibrahimdans.i18n.plugin.utils.SomeClass.someMethod(SomeClass.kt:$it)\n") }
    }

    private fun query(url: String, name: String): String =
        URLDecoder.decode(url.substringAfter("&$name=").substringBefore("&"), StandardCharsets.UTF_8)

    @Test
    fun `a trace of 200 lines fits under GitHub's URL limit, its top kept`() {
        val url = ErrorIssue.url(traceOf(200), "Opened the tool window", environment)

        assertTrue(url.length < 8000, "URL of ${url.length} characters")
        assertTrue(url.length <= ErrorIssue.MAX_URL_LENGTH)
        val body = query(url, "body")
        assertTrue(body.contains("java.lang.IllegalStateException: Translation file vanished while reading common.json"))
        assertTrue(body.contains("SomeClass.kt:0)"), "the first frames are kept")
        assertTrue(Regex("""… \d+ more lines""").containsMatchIn(body), "the cut is said")
        assertFalse(body.contains("SomeClass.kt:198)"), "the last frames go first")
    }

    @Test
    fun `the title names the exception and its message`() {
        val url = ErrorIssue.url(traceOf(3), null, environment)

        assertEquals("[Bug] IllegalStateException: Translation file vanished while reading common.json", query(url, "title"))
        assertTrue(url.startsWith("https://github.com/IBRAHIMDANS/i18nSupportPlus/issues/new?labels=bug&title="))
    }

    @Test
    fun `title and body are encoded, spaces as percent-20`() {
        val url = ErrorIssue.url(traceOf(3), "Ça plante & ça #recommence", environment)

        assertFalse(url.contains(' '), "no raw space")
        assertFalse(url.contains('\n'), "no raw line break")
        assertFalse(url.contains('+'), "GitHub would show a + as written")
        assertEquals(2, url.count { it == '&' }, "the user's & is encoded, only the parameters' are left")
        assertTrue(query(url, "body").contains("Ça plante & ça #recommence"))
    }

    @Test
    fun `a short trace is kept whole with the environment`() {
        val body = query(ErrorIssue.url(traceOf(3), null, environment), "body")

        assertFalse(body.contains("more lines"))
        assertTrue(body.contains("| Plugin version | 1.17.0 |"))
        assertTrue(body.contains("| IDE & version | WebStorm 2025.3.4 (WS-253.32098.39) |"))
        assertTrue(body.contains("| OS | Linux 6.8 (amd64) |"))
        assertTrue(body.contains("<!-- What were you doing when the error happened? -->"), "an empty description asks for one")
    }

    @Test
    fun `the user's text keeps a leading pipe`() {
        val body = query(ErrorIssue.url(traceOf(3), "| a table row", environment), "body")

        assertTrue(body.contains("\n| a table row\n"))
    }

    @Test
    fun `a huge message and a long non-ASCII description still fit`() {
        val trace = "java.lang.RuntimeException: " + "é".repeat(20_000) + "\n\tat a.B.c(B.kt:1)"
        val url = ErrorIssue.url(trace, "ü".repeat(5_000), environment)

        assertTrue(url.length <= ErrorIssue.MAX_URL_LENGTH, "URL of ${url.length} characters")
        val body = query(url, "body")
        assertTrue(body.contains("ü".repeat(ErrorIssue.MAX_DESCRIPTION_LENGTH) + "…"), "the description is capped")
        assertTrue(body.contains("java.lang.RuntimeException: é"), "the start of the message is kept")
    }

    @Test
    fun `an error with no text still gets a title`() {
        assertEquals("[Bug] Exception in i18n Support Plus", ErrorIssue.title("  \n"))
    }

    @Test
    fun `a message that is no class name is not shortened`() {
        assertEquals("[Bug] Something went wrong: details", ErrorIssue.title("Something went wrong: details"))
    }

    @Test
    fun `plugin xml declares this submitter as its only error handler`() {
        val handlers = Regex("""<errorHandler\s+implementation="([^"]+)"""")
            .findAll(File("src/main/resources/META-INF/plugin.xml").readText())
            .map { it.groupValues[1] }
            .toList()

        assertEquals(listOf(GitHubErrorReportSubmitter::class.java.name), handlers)
    }
}

/**
 * The Marketplace report must go out as before, and the GitHub issue be offered once it is done,
 * whatever came of it.
 */
class GitHubErrorReportSubmitterTest : PlatformBaseTest() {

    /** Stands for the Marketplace: answers [status], or declines to send when it is null. */
    private class Marketplace(private val status: SubmissionStatus?) : ErrorReportSubmitter() {
        var sent = 0
        override fun getReportActionText() = "Report to JetBrains Marketplace"
        override fun getPrivacyNoticeText() = "Marketplace privacy notice"
        override fun submit(
            events: Array<out IdeaLoggingEvent>,
            additionalInfo: String?,
            parentComponent: Component,
            consumer: Consumer<in SubmittedReportInfo>,
        ): Boolean {
            val answer = status ?: return false
            sent++
            consumer.consume(SubmittedReportInfo(answer))
            return true
        }
    }

    private data class Offer(val project: Project?, val url: String, val sent: Boolean)

    private val offers = mutableListOf<Offer>()
    private val received = mutableListOf<SubmittedReportInfo>()

    private fun submitter(marketplace: ErrorReportSubmitter) =
        GitHubErrorReportSubmitter(marketplace) { project, url, sent -> offers += Offer(project, url, sent) }

    private fun submit(submitter: GitHubErrorReportSubmitter): Boolean = submitter.submit(
        arrayOf(IdeaLoggingEvent("Tool window failed", IllegalStateException("boom"))),
        "I clicked Refresh",
        JPanel(),
    ) { received += it }

    @Test
    fun `a report sent to the Marketplace is then offered on GitHub`() {
        val marketplace = Marketplace(SubmissionStatus.NEW_ISSUE)

        assertTrue(submit(submitter(marketplace)))

        assertEquals(1, marketplace.sent)
        assertEquals(listOf(SubmissionStatus.NEW_ISSUE), received.map { it.status }, "the platform hears the Marketplace's answer")
        assertEquals(1, offers.size)
        assertTrue(offers.single().sent)
        val title = URLDecoder.decode(offers.single().url.substringAfter("&title=").substringBefore("&"), StandardCharsets.UTF_8)
        assertEquals("[Bug] IllegalStateException: boom", title)
    }

    @Test
    fun `a report the Marketplace failed is still offered on GitHub`() {
        assertTrue(submit(submitter(Marketplace(SubmissionStatus.FAILED))))

        assertEquals(listOf(SubmissionStatus.FAILED), received.map { it.status })
        assertFalse(offers.single().sent)
    }

    @Test
    fun `a report the Marketplace declined is offered on GitHub at once`() {
        assertFalse(submit(submitter(Marketplace(null))))

        assertTrue(received.isEmpty())
        assertFalse(offers.single().sent)
    }

    @Test
    fun `the texts are the plugin's own and the plugin is passed on to the Marketplace`() {
        val marketplace = Marketplace(SubmissionStatus.NEW_ISSUE)
        val submitter = submitter(marketplace)
        val plugin = mockk<PluginDescriptor>(relaxed = true)

        submitter.pluginDescriptor = plugin

        assertEquals(PluginBundle.message("error.report.marketplace.action"), submitter.reportActionText)
        assertTrue(submitter.privacyNoticeText.contains("exception_analyzer.html"), "the Marketplace agreement is linked")
        assertSame(plugin, marketplace.pluginDescriptor, "the Marketplace report is filed under this plugin")
    }

    @Test
    fun `the issue names the version of the plugin's descriptor`() {
        val submitter = submitter(Marketplace(SubmissionStatus.NEW_ISSUE))
        submitter.pluginDescriptor = mockk<PluginDescriptor>(relaxed = true) { io.mockk.every { version } returns "9.8.7" }

        submit(submitter)

        val body = URLDecoder.decode(offers.single().url.substringAfter("&body="), StandardCharsets.UTF_8)
        assertTrue(body.contains("| Plugin version | 9.8.7 |"))
    }
}
