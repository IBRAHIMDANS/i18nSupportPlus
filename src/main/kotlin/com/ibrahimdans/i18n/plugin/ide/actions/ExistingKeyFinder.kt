package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileKeys
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.ReferenceLocale
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
        val found = linkedMapOf<String, LinkedHashSet<String>>()
        referenceEntries(caller).forEach { (key, value) ->
            val text = value.trim()
            if (text in wanted) found.getOrPut(text) { linkedSetOf() } += key
        }
        return found.mapValues { (_, keys) -> keys.toList() }
    }

    /**
     * Every key of the reference locale in the module owning [caller], spelled the way the code writes
     * it, with its value — in file order. *Internationalize…* reads it to never give a new text the
     * name of a key holding another one. A key spelled the same in two files keeps its first value.
     */
    fun referenceValues(caller: PsiElement): Map<String, String> =
        referenceEntries(caller).fold(linkedMapOf()) { values, (key, value) -> values.apply { putIfAbsent(key, value) } }

    /** Every `key -> value` of the reference locale in [caller]'s module, a key appearing once per file holding it. */
    private fun referenceEntries(caller: PsiElement): List<Pair<String, String>> {
        val project = caller.project
        val config = Settings.getInstance(project).config()
        val module = ownerOf(caller, config)
        val sources = ModuleSources.sourcesOf(project, module).filter { it.hasRecognizedLocale() }
        val locale = ReferenceLocale.of(module, config, sources.map { it.localeLabel() }.distinct()) ?: return emptyList()
        val defaultNamespace = config.defaultNamespaces().first()
        val entries = mutableListOf<Pair<String, String>>()
        sources
            .filter { it.localeLabel() == locale }
            .forEach { source ->
                val file = ModuleSources.readableFile(source) ?: return@forEach
                val namespace = TranslationDataLoader.extractNamespace(source, defaultNamespace)
                TranslationFileKeys.translationLeaves(file).forEach { (path, value) ->
                    entries += spell(namespace, path, config) to value
                }
            }
        return entries
    }

    /**
     * [path] in [namespace] as the code writes it: the namespace is left out when it is a default
     * one — or when keys are flat, or the namespace separator empty, and the code writes none —
     * exactly as [KeyRequest] would parse it back. A first-component namespace is joined with the
     * key separator, whatever the namespace separator.
     */
    internal fun spell(namespace: String, path: List<String>, config: Config): String {
        val key = path.joinToString(config.keySeparator)
        if (config.usesFlatKeys() || namespace in config.defaultNamespaces()) return key
        if (config.firstComponentNs) return namespace + config.keySeparator + key
        if (config.nsSeparator.isEmpty()) return key
        return namespace + config.nsSeparator + key
    }

    private fun ownerOf(caller: PsiElement, config: Config): ModuleConfig? {
        if (config.modules.isEmpty()) return null
        val file = caller.hostVirtualFile() ?: return null
        return ModuleSources.owner(config.modules, ModuleSources.FilePath.of(file, caller.project.basePath ?: ""))
    }
}
