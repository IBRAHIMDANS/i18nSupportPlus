package com.ibrahimdans.i18n

import com.ibrahimdans.i18n.plugin.factory.FoldingProvider
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.ibrahimdans.i18n.plugin.ide.actions.KeyRenameSites
import com.ibrahimdans.i18n.plugin.parser.CodeNode
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.PsiCodeNode
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference

/**
 * A source language the plugin reads keys from.
 *
 * It is also a [CodeLang]: a [PsiCodeNode] is answered from the element it wraps, any other
 * [CodeNode] holds no key for a language that reads the PSI.
 */
interface Lang : CodeLang {

    fun canExtractKey(element: PsiElement, translationFunctionNames: List<String>): Boolean

    fun extractRawKey(element: PsiElement): RawKey?

    /**
     * Get folding provider
     */
    fun foldingProvider(): FoldingProvider

    /**
     * Get translation extractor object
     */
    fun translationExtractor(): TranslationExtractor

    fun resolveLiteral(entry: PsiElement): PsiElement?

    /**
     * True when the call holding [element] may read an object as well as a string, so a key
     * pointing at an object is resolved rather than a "Reference to object": next-intl's `t.raw`,
     * i18next's `returnObjects: true`.
     */
    fun allowsObjectValue(element: PsiElement): Boolean = false

    /**
     * The text the call holding [element] shows while its key is missing — i18next's
     * `t('key', 'Default text')` — or null when it shows none, which is the default.
     */
    fun defaultTextOf(element: PsiElement): String? = null

    /**
     * The other keys of the call holding [element] when it tries several and shows the first that
     * exists — i18next's `t(['specific', 'generic'])`. Empty by default.
     */
    fun fallbackKeysOf(element: PsiElement): List<PsiElement> = emptyList()

    /**
     * The language the unresolved key inspection reads [element] with: this one or another of its
     * plugin, or null when [element] is none of its languages, which is the default. The inspection
     * is declared once for every language and asks each one, so that no class it loads names the
     * PSI of an optional plugin.
     */
    fun inspectionLangOf(element: PsiElement): Lang? = null

    /**
     * The references [literal] holds: its own, unless the language's keys are tokens the reference
     * contributors never reach — a Twig string, whose token asks none — and the language gives them.
     */
    fun references(literal: PsiElement): List<PsiReference> = literal.references.toList()

    /**
     * Whether [element] bounds a function: the markup above it does not show what the function
     * computes, so a call inside one has no UI role. False for a language without functions in
     * its PSI, which is the default.
     */
    fun isFunctionBoundary(element: PsiElement): Boolean = false

    /**
     * The key [element] names in metadata rather than in its text — the `@@id` of an Angular `i18n`
     * attribute or `$localize` template — or null when [element] is no such marker, or the language
     * has none, which is the default.
     */
    fun metadataKeyOf(element: PsiElement): String? = null

    /** Where this language writes a key outside a translation call's literal, for a rename; null when nowhere, the default. */
    fun renameSites(): KeyRenameSites? = null

    /**
     * Whether the index of the code's usages reads the files of [type] for this language: those
     * carrying its language, directly or through an injection into a template. False by default — a
     * language that says nothing has its files read by no one, so a new one has to say it here.
     */
    fun indexes(type: FileType): Boolean = false

    override fun canExtractKey(node: CodeNode, translationFunctionNames: List<String>): Boolean =
        node is PsiCodeNode && canExtractKey(node.element, translationFunctionNames)

    override fun extractRawKey(node: CodeNode): RawKey? = (node as? PsiCodeNode)?.let { extractRawKey(it.element) }
}

/** Whether [this] is of one of the languages of [ids], a dialect counting for its base: TypeScript for JavaScript. */
fun FileType.isOfLanguage(ids: Set<String>): Boolean {
    val language = (this as? LanguageFileType)?.language ?: return false
    return language.isOfLanguage(ids)
}

/** Whether [this] is of the language [id], a dialect counting for its base — as an annotator declared on [id] sees it. */
fun PsiElement.isOfLanguage(id: String): Boolean = language.isOfLanguage(setOf(id))

private fun Language.isOfLanguage(ids: Set<String>): Boolean = generateSequence(this) { it.baseLanguage }.any { it.id in ids }
