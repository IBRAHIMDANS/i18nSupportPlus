package com.ibrahimdans.i18n.plugin.factory

import com.ibrahimdans.i18n.plugin.key.FullKey
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.ElementPattern
import com.intellij.psi.PsiElement

/**
 * A value a message interpolates: [placeholder] stands for it in the translation, [expression]
 * computes it in the code, and [name] links both — `{{name}}`, `user.name`, `name`.
 */
data class MessageVariable(val name: String, val expression: String, val placeholder: String) {
    /** The same variable under [newName], its placeholder renamed in the syntax it is written in. */
    fun renamed(newName: String): MessageVariable = copy(name = newName, placeholder = placeholder.replace(name, newName))
}

/**
 * Defines translation text extraction
 */
interface TranslationExtractor {

    /**
     * Checks if it is possible to extract translation from given element
     */
    fun canExtract(element: PsiElement): Boolean

    /**
     * Checks if translation already extracted
     */
    fun isExtracted(element: PsiElement): Boolean

    /**
     * Get text of translation
     */
    fun text(element: PsiElement): String

    /**
     * Get translation textRange
     */
    fun textRange(element: PsiElement): TextRange = element.parent.textRange

    /**
     * Get template to substitute translation with
     */
    fun template(element: PsiElement): (argument: String) -> String = {"i18n.t($it)"}

    /**
     * The values the [text] interpolates, in order, their placeholders already standing in it:
     * `Hello {{name}}` for `<p>Hello {user.name}</p>`. The extraction dialog lets them be renamed.
     */
    fun variables(element: PsiElement): List<MessageVariable> = emptyList()

    /**
     * The call replacing the text, given the key [argument] and the [variables] it passes, as
     * named in the extraction dialog — [template] passes those of [variables] itself.
     */
    fun call(element: PsiElement): (argument: String, variables: List<MessageVariable>) -> String =
        template(element).let { template -> { argument, _ -> template(argument) } }

    /**
     * The namespaces an unqualified key resolves against where [element] stands — those of a
     * `useTranslation('account')` whose `t` the [template] calls — the first one by default.
     * Empty when the call resolves against the project's default namespaces.
     */
    fun scopeNamespaces(element: PsiElement): List<String> = emptyList()

    fun postProcess(editor: Editor, offset: Int) {}
}

/**
 * Folding provider interface
 */
interface FoldingProvider {
    /**
     * First step of folding - collecting list of elements where foldable elements may reside.
     */
    fun collectContainers(root: PsiElement): List<PsiElement>

    /**
     * Second step of folding - collect i18n keys inside container
     *
     * return pair of list of collected i18n key elements and offset relative to container start
     */
    fun collectLiterals(container: PsiElement): Pair<List<PsiElement>, Int>

    /**
     * Returns folding range
     */
    fun getFoldingRange(container: PsiElement, offset: Int, psiElement: PsiElement): TextRange
}

/**
 * Reference assistant
 */
interface ReferenceAssistant {

    /**
     * Pattern for reference application
     */
    fun pattern(): ElementPattern<out PsiElement>

    /**
     * Extract i18n key from element
     */
    fun extractKey(element: PsiElement): FullKey?
}

