package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.factory.MessageVariable
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

/**
 * Flags a text written as is in the template of an Astro component — `<button>Save</button>`,
 * `<input placeholder="Name">` — with a quick fix writing i18next's `t` ([AstroMarkupTextExtractor]).
 * The rules are those of [MarkupTexts]: a `{name}` expression is a variable, any other expression
 * keeps its tag's texts silent, and neither the `---` frontmatter nor `<script>` and `<style>` is
 * read. Needs the Astro plugin, without which a `.astro` file is text.
 */
class HardcodedAstroTextInspection :
    HardcodedMarkupTextInspection("I18nHardcodedAstroText", "inspection.hardcoded.jsx.text.message", AstroMarkup, AstroMarkupTextExtractor())

/** The template of an Astro component: `{ … }` expressions, i18next's `{{name}}` placeholders. */
internal object AstroMarkup : MarkupDialect {

    private const val LANGUAGE = "Astro"

    override fun applies(element: PsiElement): Boolean =
        generateSequence(element.containingFile?.language) { it.baseLanguage }.any { it.id == LANGUAGE }

    /** `{name}` in the template: its braces are part of the element. */
    override fun isInterpolation(element: PsiElement): Boolean =
        element.node?.elementType?.toString() == "JS:EMBEDDED_EXPRESSION"

    override fun placeholder(name: String): String = "{{$name}}"
}

/**
 * Extracts a text of an Astro component's template — one [HardcodedAstroTextInspection] reports —
 * into i18next's `t` (astro-i18next), imported in the frontmatter when the component has no `t`:
 *  - `<p>Save</p>` becomes `<p>{t('save')}</p>`;
 *  - `<p>Hello {user.name}!</p>` becomes `{t('hello', { name: user.name })}`, its message
 *    `Hello {{name}}!`;
 *  - `placeholder="Name"` becomes `placeholder={t('name')}`.
 *
 * The function is the name the component gives `t` when it imports it from i18next — `tr` after
 * `import { t as tr }` —, else `t`.
 */
internal class AstroMarkupTextExtractor : TranslationExtractor {

    override fun canExtract(element: PsiElement): Boolean = MarkupTexts.of(element, AstroMarkup) != null

    override fun isExtracted(element: PsiElement): Boolean = false

    override fun text(element: PsiElement): String = MarkupTexts.of(element, AstroMarkup)?.text.orEmpty()

    override fun textRange(element: PsiElement): TextRange = MarkupTexts.of(element, AstroMarkup)?.range ?: element.textRange

    override fun variables(element: PsiElement): List<MessageVariable> =
        (MarkupTexts.of(element, AstroMarkup) as? MarkupText.Content)?.variables.orEmpty()

    override fun template(element: PsiElement): (argument: String) -> String {
        val call = call(element)
        val variables = variables(element)
        return { call(it, variables) }
    }

    override fun call(element: PsiElement): (argument: String, variables: List<MessageVariable>) -> String {
        val function = functionOf(element.containingFile.text)
        val attribute = (MarkupTexts.of(element, AstroMarkup) as? MarkupText.Attribute)?.attribute
        if (attribute != null) return { argument, _ -> "${attribute.name}={$function($argument)}" }
        return { argument, variables -> "{$function($argument${MessageVariables.options(variables)})}" }
    }

    /** Imports `t` from i18next when the component has none. */
    override fun postProcess(editor: Editor, offset: Int) = addImport(editor.document)

    internal companion object {
        private const val T = "t"
        private const val IMPORT = "import { t } from 'i18next';"
        private val I18NEXT_IMPORT = Regex("""import\s*\{([^}]*)}\s*from\s*['"](?:astro-)?i18next['"]""")
        private val AS = Regex("""\s+as\s+""")

        /** A `t` the frontmatter has already: `const { t } = …`, `function t(…)`, imported from another library. */
        private val DECLARED_T = Regex("""\b(?:const|let|var|function)\s+t\b|\{[^}]*\bt\b[^}]*}\s*=|import\s*\{[^}]*\bt\b[^}]*}""")

        /** The opening `---` of the frontmatter, on the component's first line. */
        private val FRONTMATTER = Regex("""\A\s*---[^\n]*\n""")

        /**
         * Imports `t` from i18next into the component [document] holds, at the top of its
         * frontmatter — one created when it has none —, unless it imports or declares `t` already.
         */
        fun addImport(document: Document) {
            val text = document.charsSequence.toString()
            if (T in imports(text) || DECLARED_T.containsMatchIn(text)) return
            val frontmatter = FRONTMATTER.find(text)
            if (frontmatter != null) document.insertString(frontmatter.range.last + 1, "$IMPORT\n")
            else document.insertString(0, "---\n$IMPORT\n---\n")
        }

        /** What the component imports from i18next, exported name → local name: `t as tr` gives `t` → `tr`. */
        private fun imports(text: String): Map<String, String> =
            I18NEXT_IMPORT.findAll(text)
                .flatMap { it.groupValues[1].split(',') }
                .map { it.trim().split(AS) }
                .filter { it.first().isNotEmpty() }
                .associate { it.first() to it.last() }

        /** The local name of `t` when the component imports it from i18next, else `t`. */
        fun functionOf(text: String): String = imports(text)[T] ?: T
    }
}
