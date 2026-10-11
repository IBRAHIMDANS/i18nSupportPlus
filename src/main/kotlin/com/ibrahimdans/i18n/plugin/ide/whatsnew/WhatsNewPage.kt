package com.ibrahimdans.i18n.plugin.ide.whatsnew

/**
 * Builds the HTML of the "What's New" page from the change notes embedded at build time.
 *
 * The resource holds every released version of CHANGELOG.md, rendered like `<change-notes>` and
 * each preceded by a `<!-- whats-new-version: X.Y.Z -->` marker (see `generateWhatsNew` in
 * build.gradle.kts): nothing is written twice. Free of the platform so it can be tested headlessly.
 */
object WhatsNewPage {

    const val CHANGELOG_URL = "https://github.com/IBRAHIMDANS/i18nSupportPlus/blob/main/CHANGELOG.md"

    private const val RESOURCE = "/com/ibrahimdans/i18n/whats-new.html"
    private val MARKER = Regex("""<!-- whats-new-version: (\S+) -->\r?\n?""")

    // The notes come from our own CHANGELOG, but the page is shown in a browser component:
    // scripts and inline handlers are dropped anyway, and the CSP below forbids any that remain.
    private val SCRIPT = Regex("""<script\b.*?</script\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val INLINE_HANDLER = Regex("""\s+on[a-z]+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)""", RegexOption.IGNORE_CASE)

    data class Section(val version: String, val html: String)

    /** The embedded notes, newest first, or null when the resource is missing or empty. */
    val sections: List<Section>? by lazy {
        val stream = WhatsNewPage::class.java.getResourceAsStream(RESOURCE) ?: return@lazy null
        parse(stream.use { it.readBytes().toString(Charsets.UTF_8) }).takeIf { it.isNotEmpty() }
    }

    /** Splits the resource on its version markers, keeping the file order. */
    fun parse(resource: String): List<Section> {
        val markers = MARKER.findAll(resource).toList()
        return markers.mapIndexed { index, marker ->
            val end = markers.getOrNull(index + 1)?.range?.first ?: resource.length
            Section(marker.groupValues[1], resource.substring(marker.range.last + 1, end).trim())
        }
    }

    /**
     * The sections to show to a user now on [currentVersion] who last saw [previousVersion]: the
     * current version and every version skipped between the two, newest first.
     *
     * With no previous version, one that cannot be compared, or one that is not older (the page
     * reopened from the Help menu, a downgrade), the current version alone. Empty when the
     * current version is not in the change notes — a development build, for instance.
     */
    fun select(sections: List<Section>, previousVersion: String?, currentVersion: String): List<Section> {
        val current = parseVersion(currentVersion) ?: return emptyList()
        if (sections.none { it.version == currentVersion }) return emptyList()
        val previous = previousVersion?.let(::parseVersion)
        val range = if (previous == null || compare(previous, current) >= 0) emptyList() else sections.filter { section ->
            val version = parseVersion(section.version) ?: return@filter false
            compare(version, previous) > 0 && compare(version, current) <= 0
        }
        return range.ifEmpty { sections.filter { it.version == currentVersion } }
            .sortedWith { a, b -> compare(parseVersion(b.version)!!, parseVersion(a.version)!!) }
    }

    /**
     * The page for [currentVersion], or null when there is nothing to show in it — the resource is
     * missing or the version absent from it — so that the caller falls back to [CHANGELOG_URL].
     */
    fun build(previousVersion: String?, currentVersion: String, title: String, fullChangelog: String): String? =
        build(sections ?: return null, previousVersion, currentVersion, title, fullChangelog)

    fun build(
        sections: List<Section>,
        previousVersion: String?,
        currentVersion: String,
        title: String,
        fullChangelog: String,
    ): String? {
        val shown = select(sections, previousVersion, currentVersion).ifEmpty { return null }
        val body = shown.joinToString("\n") { "<section data-version=\"${escape(it.version)}\">\n${sanitize(it.html)}\n</section>" }
        return """
            |<!DOCTYPE html>
            |<html>
            |<head>
            |<meta charset="utf-8">
            |<meta http-equiv="Content-Security-Policy" content="script-src 'none'">
            |<title>${escape(title)}</title>
            |<style>
            |  :root { color-scheme: light dark; }
            |  body { font-family: system-ui, sans-serif; line-height: 1.5; max-width: 60em; margin: 1.5em auto; padding: 0 1.5em; }
            |  section { border-bottom: 1px solid rgba(128, 128, 128, 0.3); padding-bottom: 1em; }
            |  code { font-family: monospace; }
            |</style>
            |</head>
            |<body>
            |<h1>${escape(title)}</h1>
            |$body
            |<p><a href="$CHANGELOG_URL">${escape(fullChangelog)}</a></p>
            |</body>
            |</html>
            |""".trimMargin()
    }

    internal fun sanitize(html: String): String = html.replace(SCRIPT, "").replace(INLINE_HANDLER, "")

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** The numeric parts of a version (`1.10.0-beta.2` → 1, 10, 0), or null when it starts with none. */
    private fun parseVersion(version: String): List<Int>? =
        Regex("""^\d+(\.\d+)*""").find(version.trim())?.value?.split('.')?.map { it.toIntOrNull() ?: 0 }

    private fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val difference = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (difference != 0) return difference
        }
        return 0
    }
}
