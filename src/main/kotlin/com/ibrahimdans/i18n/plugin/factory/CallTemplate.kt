package com.ibrahimdans.i18n.plugin.factory

import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.hostVirtualFile
import com.intellij.psi.PsiElement

/**
 * The call a module writes at extraction instead of its framework's: `translate({key})` turns
 * `t('a.b', { name })` into `translate('a.b', { name })`.
 *
 * Each [TranslationExtractor] writes its framework's call, which leaves a project calling its own
 * wrapper (`translate('…')`, `tr('…')`) rewriting every extraction by hand. The template replaces
 * the call only: what the extractor writes around it — the braces of a JSX expression — and its
 * arguments, the quoted key and the interpolated variables, stay as written.
 */
object CallTemplate {

    /** The placeholder the arguments of the call replace. */
    const val KEY = "{key}"

    private val CALLEE_CHAR = Regex("""[\p{L}\p{N}_$.]""")

    /** Whether [template] holds [KEY] exactly once. */
    fun isValid(template: String): Boolean = template.split(KEY).size == 2

    /**
     * [written], the call an extractor wrote for [argument], with its callee and parentheses
     * replaced by [template]. [written] unchanged when it does not call a function on [argument].
     */
    fun apply(template: String, written: String, argument: String): String {
        val open = written.indexOf("($argument")
        val close = written.lastIndexOf(')')
        if (open < 0 || close < open) return written
        var start = open
        while (start > 0 && CALLEE_CHAR.matches(written[start - 1].toString())) start--
        val arguments = written.substring(open + 1, close)
        return written.substring(0, start) + template.replace(KEY, arguments) + written.substring(close + 1)
    }

    /** The valid template of the module holding [element]'s file, or null: no module, none set, or invalid. */
    fun of(element: PsiElement): String? {
        val project = element.project
        // No Config built for a project without modules, nearly all of them.
        if (Settings.getInstance(project).modules.isEmpty()) return null
        val file = element.hostVirtualFile() ?: return null
        val module = ModuleSources.owner(Settings.getInstance(project).config().modules, ModuleSources.FilePath.of(file, project.basePath ?: ""))
        return module?.callTemplate?.trim()?.takeIf { it.isNotEmpty() && isValid(it) }
    }

    /** [extractor]'s [TranslationExtractor.call] at [element], through its module's template when it has one. */
    fun call(extractor: TranslationExtractor, element: PsiElement): (String, List<MessageVariable>) -> String {
        val call = extractor.call(element)
        val template = of(element) ?: return call
        return { argument, variables -> apply(template, call(argument, variables), argument) }
    }

    /** [extractor]'s [TranslationExtractor.template] at [element], through its module's template when it has one. */
    fun template(extractor: TranslationExtractor, element: PsiElement): (String) -> String {
        val written = extractor.template(element)
        val template = of(element) ?: return written
        return { argument -> apply(template, written(argument), argument) }
    }
}
