package com.ibrahimdans.i18n.extensions.localization.properties

import com.ibrahimdans.i18n.ContentGenerator
import com.ibrahimdans.i18n.Localization
import com.ibrahimdans.i18n.LocalizationConfig
import com.ibrahimdans.i18n.LocalizationConfigImpl
import com.ibrahimdans.i18n.LocalizationFileType
import com.ibrahimdans.i18n.TranslationReferenceAssistant
import com.ibrahimdans.i18n.WorkspaceFile
import com.ibrahimdans.i18n.plugin.format.PropertiesBundle
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.icons.AllIcons
import com.intellij.lang.Language
import com.intellij.lang.properties.PropertiesFileType
import com.intellij.lang.properties.PropertiesLanguage
import com.intellij.lang.properties.psi.PropertiesElementFactory
import com.intellij.lang.properties.psi.PropertiesFile
import com.intellij.lang.properties.psi.PropertyKeyValueFormat
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import javax.swing.Icon

/**
 * Java resource bundles (`messages.properties`, `messages_fr.properties`) as translation files, read
 * through the PSI of the Properties plugin: declared in `propertiesConfig.xml`, loaded only with it.
 *
 * Never found by name or place: [PropertiesBundles] declares the bundle files, with the locale their
 * name ends with — a `.properties` file is configuration far more often than a translation.
 */
class PropertiesLocalization : Localization<PsiElement> {

    override fun types(): List<LocalizationFileType> = listOf(LocalizationFileType(PropertiesFileType.INSTANCE))

    override fun contentGenerator(): ContentGenerator = PropertiesContentGenerator()

    // The keys are read by the code through `getString` / `getMessage`, which the plugin does not read yet.
    override fun referenceAssistant(): TranslationReferenceAssistant<PsiElement> = NoReferences

    override fun elementsTree(file: PsiElement): Tree<PsiElement>? = PropertiesElementTree.create(file)

    override fun scannedByName(): Boolean = false

    /** Whether [file] belongs to the bundle named after one of [fileNames]: `messages_fr.properties` to `messages`. */
    override fun matches(localizationFileType: LocalizationFileType, file: WorkspaceFile?, fileNames: List<String>): Boolean =
        file?.extension == PropertiesBundle.EXTENSION && PropertiesBundle.nameOf(file.nameWithoutExtension).base in fileNames

    override fun icon(): Icon = AllIcons.FileTypes.Properties

    override fun config(): LocalizationConfig = LocalizationConfigImpl("properties")

    private object NoReferences : TranslationReferenceAssistant<PsiElement> {
        override fun pattern(): ElementPattern<out PsiElement> = PlatformPatterns.not(PlatformPatterns.psiElement())
        override fun references(element: PsiElement): List<PsiReference> = emptyList()
    }
}

/**
 * Writes a key into a `.properties` file: one line, the whole key (`user.name=…`) with its value
 * escaped as the format needs, placed after the last key sharing its longest prefix — next to its
 * siblings — or at the end of the file.
 */
internal class PropertiesContentGenerator : ContentGenerator {

    override fun generateContent(compositeKey: List<Literal>, value: String): String =
        content(compositeKey, value, DEFAULT_SEPARATOR)

    /**
     * The key joined by the project's separator, as [generate] writes it into an existing file: a
     * new file used to get `user.name` in a project whose code reads `user/name`.
     */
    override fun generateContent(project: Project, path: String, fullKey: FullKey, value: String?): String =
        content(fullKey.compositeKey, value ?: fullKey.source, Settings.getInstance(project).config().keySeparator)

    private fun content(compositeKey: List<Literal>, value: String, separator: String): String =
        escapeKey(compositeKey.joinToString(separator) { it.text }) + "=" +
            PropertiesElementFactory.escapeValue(value, '=', PropertyKeyValueFormat.MEMORY)

    override fun getType(): FileType = PropertiesFileType.INSTANCE

    override fun getLanguage(): Language = PropertiesLanguage.INSTANCE

    override fun getDescription(): String = PluginBundle.getMessage("quickfix.create.properties.translation.files")

    override fun isSuitable(element: PsiElement): Boolean = element.containingFile is PropertiesFile

    override fun generateTranslationEntry(item: PsiElement, key: String, value: String) {
        val file = item.containingFile as? PropertiesFile ?: return
        val separator = Settings.getInstance(item.project).config().keySeparator
        val property = PropertiesElementFactory.createProperty(item.project, key, value, '=', PropertyKeyValueFormat.MEMORY)
        val anchor = anchorFor(file.properties.mapNotNull { it.unescapedKey }, key, separator)
            ?.let(file::findPropertyByKey)
        if (anchor != null) file.addPropertyAfter(property, anchor) else file.addProperty(property)
    }

    /** [fullKey] written whole, its levels joined by the key separator, whatever part of it the file already holds. */
    override fun generate(element: PsiElement, fullKey: FullKey, unresolved: List<Literal>, translationValue: String?) {
        if (unresolved.isEmpty()) return
        val separator = Settings.getInstance(element.project).config().keySeparator
        val key = fullKey.compositeKey.joinToString(separator) { it.text }
        generateTranslationEntry(element, key, translationValue ?: fullKey.source)
    }

    companion object {
        private const val DEFAULT_SEPARATOR = "."

        /**
         * The key [key] goes after among [keys]: the last one sharing its longest prefix of whole
         * levels (`user.email` after `user.name`), null when it shares none.
         */
        internal fun anchorFor(keys: List<String>, key: String, separator: String): String? {
            if (separator.isEmpty()) return null
            val levels = key.split(separator)
            for (count in levels.size - 1 downTo 1) {
                val prefix = levels.take(count).joinToString(separator) + separator
                keys.lastOrNull { it.startsWith(prefix) }?.let { return it }
            }
            return null
        }

        private fun escapeKey(key: String): String =
            key.replace("\\", "\\\\").replace(" ", "\\ ").replace("=", "\\=").replace(":", "\\:")
    }
}
