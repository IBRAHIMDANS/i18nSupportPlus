package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.factory.MessageVariable
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

/**
 * Flags a text written as is in the markup of a Svelte component — `<button>Save</button>`,
 * `<input placeholder="Name">` — with a quick fix writing svelte-i18n's store
 * ([SvelteMarkupTextExtractor]). The rules are those of [MarkupTexts]: a `{name}` expression is a
 * variable, a block (`{#if}`) or any other expression keeps its tag's texts silent, and neither
 * `<script>` nor `<style>` is read. Needs the Svelte plugin, without which a `.svelte` file is text.
 */
class HardcodedSvelteTextInspection :
    HardcodedMarkupTextInspection("I18nHardcodedSvelteText", "inspection.hardcoded.jsx.text.message", SvelteMarkup, SvelteMarkupTextExtractor())

/** The markup of a Svelte component: `{ … }` expressions, svelte-i18n's `{name}` placeholders. */
internal object SvelteMarkup : MarkupDialect {

    private const val LANGUAGE = "SvelteHTML"

    override fun applies(element: PsiElement): Boolean =
        generateSequence(element.containingFile?.language) { it.baseLanguage }.any { it.id == LANGUAGE }

    /** `{name}` in the markup: its braces are part of the element. */
    override fun isInterpolation(element: PsiElement): Boolean =
        element.node?.elementType?.toString().orEmpty().endsWith("CONTENT_EXPRESSION")

    override fun placeholder(name: String): String = "{$name}"
}

/**
 * Extracts a text of a Svelte component's markup — one [HardcodedSvelteTextInspection] reports —
 * into svelte-i18n's store, imported from `svelte-i18n` when the component does not import it yet:
 *  - `<p>Save</p>` becomes `<p>{$_('save')}</p>`;
 *  - `<p>Hello {user.name}!</p>` becomes `{$_('hello', { values: { name: user.name } })}`, its
 *    message `Hello {name}!`;
 *  - `placeholder="Name"` becomes `placeholder={$_('name')}`.
 *
 * The store is `$t` when the component imports `t` from svelte-i18n and not `_`, `$_` otherwise.
 */
internal class SvelteMarkupTextExtractor : TranslationExtractor {

    override fun canExtract(element: PsiElement): Boolean = MarkupTexts.of(element, SvelteMarkup) != null

    override fun isExtracted(element: PsiElement): Boolean = false

    override fun text(element: PsiElement): String = MarkupTexts.of(element, SvelteMarkup)?.text.orEmpty()

    override fun textRange(element: PsiElement): TextRange = MarkupTexts.of(element, SvelteMarkup)?.range ?: element.textRange

    override fun variables(element: PsiElement): List<MessageVariable> =
        (MarkupTexts.of(element, SvelteMarkup) as? MarkupText.Content)?.variables.orEmpty()

    override fun template(element: PsiElement): (argument: String) -> String {
        val call = call(element)
        val variables = variables(element)
        return { call(it, variables) }
    }

    override fun call(element: PsiElement): (argument: String, variables: List<MessageVariable>) -> String {
        val store = "$" + storeOf(element.containingFile.text)
        val attribute = (MarkupTexts.of(element, SvelteMarkup) as? MarkupText.Attribute)?.attribute
        if (attribute != null) return { argument, _ -> "${attribute.name}={$store($argument)}" }
        return { argument, variables ->
            val inputs = MessageVariables.inputs(variables)
            if (inputs.isEmpty()) "{$store($argument)}" else "{$store($argument, { values: $inputs })}"
        }
    }

    /** Imports `_` from svelte-i18n when the component imports neither it nor `t`. */
    override fun postProcess(editor: Editor, offset: Int) {
        val document = editor.document
        val text = document.charsSequence.toString()
        if (importedNames(text).any { it == DEFAULT_STORE || it == T_STORE }) return
        val script = INSTANCE_SCRIPT.find(text)
        if (script != null) document.insertString(script.range.last + 1, "\n\t$IMPORT")
        else document.insertString(0, "<script>\n\t$IMPORT\n</script>\n\n")
    }

    internal companion object {
        private const val DEFAULT_STORE = "_"
        private const val T_STORE = "t"
        private const val IMPORT = "import { _ } from 'svelte-i18n';"
        private val SVELTE_I18N_IMPORT = Regex("""import\s*\{([^}]*)}\s*from\s*['"]svelte-i18n['"]""")

        /** The component's own script, not its module script (`context="module"`, Svelte 5's `module`). */
        private val INSTANCE_SCRIPT = Regex("""<script(?![^>]*module)[^>]*>""")

        /** The names a component imports from svelte-i18n, aliases left out: `_`, `t`, `locale`. */
        fun importedNames(text: String): Set<String> =
            SVELTE_I18N_IMPORT.findAll(text).flatMap { match ->
                match.groupValues[1].split(',').map { it.trim().substringBefore(' ').trim() }
            }.filter { it.isNotEmpty() }.toSet()

        /** `t` when the component imports it from svelte-i18n and not `_`, else `_`. */
        fun storeOf(text: String): String =
            importedNames(text).let { if (T_STORE in it && DEFAULT_STORE !in it) T_STORE else DEFAULT_STORE }
    }
}
