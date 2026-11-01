package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.factory.MessageVariable
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.intellij.openapi.editor.Document
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
 * The store is the name the component gives `_` when it imports it from svelte-i18n — `$tr` after
 * `import { _ as tr }` —, else the one it gives `t`, else `$_`.
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
    override fun postProcess(editor: Editor, offset: Int) = addImport(editor.document)

    internal companion object {
        private const val DEFAULT_STORE = "_"
        private const val T_STORE = "t"
        private const val IMPORT = "import { _ } from 'svelte-i18n';"
        private val SVELTE_I18N_IMPORT = Regex("""import\s*\{([^}]*)}\s*from\s*['"]svelte-i18n['"]""")
        private val AS = Regex("""\s+as\s+""")

        /** The component's own script, not its module script (`context="module"`, Svelte 5's `module`). */
        private val INSTANCE_SCRIPT = Regex("""<script(?![^>]*module)[^>]*>""")

        /**
         * Imports `_` from svelte-i18n into the component [document] holds, at the top of its own
         * script — a script created when it has none —, unless it imports `_` or `t` already.
         */
        fun addImport(document: Document) {
            val text = document.charsSequence.toString()
            if (importedNames(text).any { it == DEFAULT_STORE || it == T_STORE }) return
            val script = INSTANCE_SCRIPT.find(text)
            if (script != null) document.insertString(script.range.last + 1, "\n\t$IMPORT")
            else document.insertString(0, "<script>\n\t$IMPORT\n</script>\n\n")
        }

        /** The names a component imports from svelte-i18n, as svelte-i18n exports them: `_`, `t`, `locale`. */
        fun importedNames(text: String): Set<String> = imports(text).keys

        /** What the component imports from svelte-i18n, exported name → local name: `_ as tr` gives `_` → `tr`. */
        private fun imports(text: String): Map<String, String> =
            SVELTE_I18N_IMPORT.findAll(text)
                .flatMap { it.groupValues[1].split(',') }
                .map { it.trim().split(AS) }
                .filter { it.first().isNotEmpty() }
                .associate { it.first() to it.last() }

        /** The local name of `_` when the component imports it from svelte-i18n, else of `t`, else `_`. */
        fun storeOf(text: String): String = imports(text).let { it[DEFAULT_STORE] ?: it[T_STORE] ?: DEFAULT_STORE }
    }
}
