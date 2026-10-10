package com.ibrahimdans.i18n.plugin.ide.settings

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The GitHub issue that suggests a module's file layout as a new preset, pre-filled from the
 * module: framework, path / file / key templates and plugin version.
 *
 * Only the templates leave the machine, and they are relative to the module's root directory.
 * The module's name, its root directory and the project's path are user data: never written.
 * A template that is itself an absolute path is not suggested at all.
 */
object PresetSuggestion {

    private const val NEW_ISSUE = "https://github.com/IBRAHIMDANS/i18nSupportPlus/issues/new"
    private const val TEMPLATE = "preset_request.md"

    /** Under GitHub's URL limit (~8 k characters), with room for the browser. */
    const val MAX_URL_LENGTH = 7500

    private const val MAX_TITLE_LENGTH = 200

    private const val TRUNCATED = "\n\n_(truncated)_"
    private val ABSOLUTE = Regex("""^(/|\\|[A-Za-z]:)""")

    /**
     * Whether [module] has a layout worth suggesting: it left its preset's layout (*Custom*), and
     * its templates resolve without issue and are relative.
     */
    fun canSuggest(module: ModuleConfig): Boolean {
        if (!LayoutPresets.isCustom(module)) return false
        val templates = listOf(module.pathTemplate, module.fileTemplate).map { it.trim() }
        if (templates.any { ABSOLUTE.containsMatchIn(it) }) return false
        val path = templates.filter { it.isNotEmpty() }.joinToString("/")
        return ModuleTemplateResolver.issues(path).isEmpty()
    }

    /** The new-issue URL for [module], or null when [canSuggest] says no. */
    fun url(module: ModuleConfig, pluginVersion: String?): String? {
        if (!canSuggest(module)) return null
        val framework = FrameworkDetector.LABELS[module.preset] ?: module.preset
        val title = "[Preset] $framework: ${module.pathTemplate.trim()}".take(MAX_TITLE_LENGTH)
        return fit(title, body(module, framework, pluginVersion))
    }

    private fun body(module: ModuleConfig, framework: String, pluginVersion: String?): String = """
        |## Framework
        |
        |$framework
        |
        |## File layout
        |
        || Template | Value |
        ||----------|-------|
        || Path template | ${cell(module.pathTemplate)} |
        || File template | ${cell(module.fileTemplate)} |
        || Key template | ${cell(module.keyTemplate)} |
        |
        |## Example call
        |
        |```ts
        |// How a key is written in the code, e.g. t('common:title')
        |```
        |
        |## Framework documentation
        |
        |<!-- A link to the page of the framework's documentation describing this layout. -->
        |
        |## Environment
        |
        || Item | Value |
        ||------|-------|
        || Plugin version | ${pluginVersion.orEmpty()} |
        """.trimMargin()

    private fun cell(template: String) = template.trim().takeIf { it.isNotEmpty() }?.let { "`$it`" }.orEmpty()

    /** The URL for [title] and [body], the body cut at the end until the whole fits [MAX_URL_LENGTH]. */
    private fun fit(title: String, body: String): String {
        var kept = body
        while (true) {
            val url = urlOf(title, if (kept.length < body.length) kept + TRUNCATED else kept)
            if (url.length <= MAX_URL_LENGTH || kept.isEmpty()) return url
            // An encoded character takes up to nine: cut at least the excess, at most what is left.
            kept = kept.dropLast(((url.length - MAX_URL_LENGTH) / 9).coerceIn(1, kept.length))
        }
    }

    private fun urlOf(title: String, body: String) =
        "$NEW_ISSUE?template=$TEMPLATE&title=${encode(title)}&body=${encode(body)}"

    /** Query encoding with spaces as `%20`: GitHub shows a `+` as written in the issue body. */
    private fun encode(text: String): String = URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20")
}
