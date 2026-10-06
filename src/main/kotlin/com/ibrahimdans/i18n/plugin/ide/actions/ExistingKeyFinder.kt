package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileKeys
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.hasRecognizedLocale
import com.ibrahimdans.i18n.plugin.utils.hostVirtualFile
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.intellij.psi.PsiElement

/**
 * The keys already holding a given text in the reference locale, so that extracting `"Save"` can
 * offer `common:actions.save` instead of creating its twin.
 *
 * `DuplicateTranslationValueInspection` only compares the values of one file, which is not enough
 * here: the twin usually lives in another namespace. Every source of the reference locale is read,
 * restricted to the module owning [find]'s caller the same way [LocalizationSourceService.findSources]
 * restricts key resolution — a key from another module would not resolve where it is written.
 *
 * Walks the file-type index: never call it on the EDT. It needs a read action.
 */
internal object ExistingKeyFinder {

    /**
     * The keys, spelled the way the code writes them, whose reference-locale value equals [text]
     * once both are trimmed. The comparison is case-sensitive: `"save"` and `"Save"` are two
     * different labels on screen.
     */
    fun find(text: String, caller: PsiElement): List<String> =
        findAll(listOf(text), caller)[text.trim()].orEmpty()

    /**
     * [find] for several texts in a single pass over the reference-locale files, keyed by the
     * trimmed text; a text no key holds is absent. *Batch extract* asks for every literal of a
     * file at once, and one walk per literal would read every translation file each time.
     */
    fun findAll(texts: Collection<String>, caller: PsiElement): Map<String, List<String>> {
        val wanted = texts.map { it.trim() }.filterTo(mutableSetOf()) { it.isNotEmpty() }
        if (wanted.isEmpty()) return emptyMap()
        val project = caller.project
        val config = Settings.getInstance(project).config()
        val module = ownerOf(caller, config)
        val locale = referenceLocale(module, config)
        val defaultNamespace = config.defaultNamespaces().first()
        val found = linkedMapOf<String, LinkedHashSet<String>>()
        ModuleSources.sourcesOf(project, module)
            .filter { it.hasRecognizedLocale() && it.localeLabel() == locale }
            .forEach { source ->
                val file = ModuleSources.readableFile(source) ?: return@forEach
                val namespace = TranslationDataLoader.extractNamespace(source, defaultNamespace)
                TranslationFileKeys.translationLeaves(file).forEach { (path, value) ->
                    val text = value.trim()
                    if (text in wanted) found.getOrPut(text) { linkedSetOf() } += spell(namespace, path, config)
                }
            }
        return found.mapValues { (_, keys) -> keys.toList() }
    }

    /**
     * [path] in [namespace] as the code writes it: the namespace is left out when it is a default
     * one — or when keys are flat and carry none — exactly as [KeyRequest] would parse it back.
     */
    internal fun spell(namespace: String, path: List<String>, config: Config): String {
        val key = path.joinToString(config.keySeparator)
        if (config.usesFlatKeys() || namespace in config.defaultNamespaces()) return key
        val separator = if (config.firstComponentNs) config.keySeparator else config.nsSeparator
        return namespace + separator + key
    }

    /**
     * The locale the module translates from, then the project's preview locale, then the folding
     * language — the fallback order [ModuleConfig.referenceLocale] documents.
     */
    private fun referenceLocale(module: ModuleConfig?, config: Config): String =
        module?.referenceLocale?.takeIf { it.isNotBlank() }
            ?: config.previewLocale.takeIf { it.isNotBlank() }
            ?: config.foldingPreferredLanguage

    private fun ownerOf(caller: PsiElement, config: Config): ModuleConfig? {
        if (config.modules.isEmpty()) return null
        val file = caller.hostVirtualFile() ?: return null
        return ModuleSources.owner(config.modules, ModuleSources.FilePath.of(file, caller.project.basePath ?: ""))
    }
}
