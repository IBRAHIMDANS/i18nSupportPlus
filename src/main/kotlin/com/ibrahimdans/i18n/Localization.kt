package com.ibrahimdans.i18n

import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.intellij.icons.AllIcons
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.patterns.ElementPattern
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import javax.swing.Icon

data class LocalizationFileType(val languageFileType: FileType, val auxExtensions: List<String> = listOf()) {
    fun extensions(): List<String> = auxExtensions + languageFileType.defaultExtension
}

interface Localization <T: PsiElement> : TranslationFormat {
    fun types(): List<LocalizationFileType>
    fun contentGenerator(): ContentGenerator
    fun referenceAssistant(): TranslationReferenceAssistant<T>
    fun elementsTree(file: PsiElement): Tree<PsiElement>?
    /**
     * Whether [file], of [localizationFileType], is named after one of [fileNames] — the namespaces
     * looked up — as this format names its files: `common.json` for `common`.
     */
    fun matches(localizationFileType: LocalizationFileType, file: WorkspaceFile?, fileNames: List<String>): Boolean
    fun icon(): Icon = AllIcons.FileTypes.Unknown

    /**
     * The namespace the keys of [file] are written under when this format decides it from the
     * file's place rather than its name, or null to leave it to the file name (the default).
     *
     * Laravel's package overrides need it: `lang/vendor/courier/en/messages.php` holds
     * `courier::messages`, not `messages`, the namespace of the application's own file.
     */
    fun fileNamespace(file: VirtualFile): String? = null

    /**
     * How the shared code reads and writes the values of this format's files, or null when it does
     * so itself ([com.ibrahimdans.i18n.plugin.utils.TranslationPsi]).
     *
     * A format of an optional plugin (PHP) sets it: the shared code is loaded in every IDE, and a
     * reference to one of that plugin's classes from it is reported as a compatibility problem by
     * the Marketplace verifier on the IDEs where the plugin is missing. The implementation is only
     * named from the plugin's own descriptor, which the verifier skips there.
     */
    fun translationValues(): TranslationValues? = null
}

/** The reader and writer of the values of one translation file format: see [Localization.translationValues]. */
interface TranslationValues {
    /** Whether [element] belongs to a file of this format. */
    fun handles(element: PsiElement): Boolean

    /** The name element of the key whose value is [value] — what renaming the key rewrites — or null. */
    fun keyOf(value: PsiElement): PsiElement?

    /** The text of the value [element], its escapes read, or null for any other element. */
    fun readValue(element: PsiElement): String?

    /** Replaces the value [element] stands for with [newValue]; nothing for any other element. */
    fun replaceValue(element: PsiElement, newValue: String, project: Project)
}

val SOURCE_ROOT = Key<Boolean>("i18n#sourceRoot")

interface TranslationReferenceAssistant<T: PsiElement>{
    /**
     * Defines translation reference pattern
     */
    fun pattern(): ElementPattern<out T>

    /**
     * Calculates element's references
     */
    fun references(element: T): List<PsiReference>
}

/**
 * Localization file content generator
 */
interface ContentGenerator {

    /**
     * Generates content by given i18n key
     */
    fun generateContent(fullKey: FullKey, value: String?): String = generateContent(fullKey.compositeKey, value ?: fullKey.source)

    /**
     * The content of a new file, to be created at [path] (absolute), holding [fullKey] = [value]:
     * [project] and [path] reach the settings of the module the file falls in — its key separator,
     * its reference locale. A format whose content depends on neither keeps the default.
     */
    fun generateContent(project: Project, path: String, fullKey: FullKey, value: String?): String = generateContent(fullKey, value)

    /**
     * Generates content by given composite key
     */

    fun generateContent(compositeKey: List<Literal>, value: String): String

    /**
     * Returns localization type
     */
    fun getType(): FileType

    /**
     * Returns language
     */
    fun getLanguage(): Language

    /**
     * Returns description
     */
    fun getDescription(): String

    /**
     * Checks if current generator is suitable for content generation in given node
     *
     * @param {PsiElement} element to check suitability
     */
    fun isSuitable(element: PsiElement): Boolean

    /**
     * Generates translation entry
     *
     * @param {PsiElement} parent element in translation file
     * @param {String} key Translation key
     * @param {String} value Translation value
     */
    fun generateTranslationEntry(item: PsiElement, key: String, value: String)

    /**
     * Generates content and psi element
     */
    fun generate(element: PsiElement, fullKey: FullKey, unresolved: List<Literal>, translationValue: String?)
}