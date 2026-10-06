package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.LocalizationSource
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
import com.intellij.openapi.components.service
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

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
    fun find(text: String, caller: PsiElement): List<String> {
        val wanted = text.trim()
        if (wanted.isEmpty()) return emptyList()
        val project = caller.project
        val config = Settings.getInstance(project).config()
        val module = ownerOf(caller, config)
        val locale = referenceLocale(module, config)
        val defaultNamespace = config.defaultNamespaces().first()
        return sourcesOf(caller, module)
            .filter { it.hasRecognizedLocale() && it.localeLabel() == locale }
            .flatMap { source ->
                val file = readableFile(source) ?: return@flatMap emptyList()
                val namespace = TranslationDataLoader.extractNamespace(source, defaultNamespace)
                TranslationFileKeys.translationLeaves(file)
                    .filterValues { it.trim() == wanted }
                    .keys
                    .map { path -> spell(namespace, path, config) }
            }
            .distinct()
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

    /**
     * Every source of the project, or only [module]'s. A module whose translations all live outside
     * its root directory (a shared package) keeps the project-wide list, as key resolution does.
     */
    private fun sourcesOf(caller: PsiElement, module: ModuleConfig?): List<LocalizationSource> {
        val project = caller.project
        val all = project.service<LocalizationSourceService>().findAllSources(project)
        if (module == null) return all
        val basePath = project.basePath ?: ""
        val scoped = all.filter { source ->
            val file = (source.tree?.value() ?: source.host)?.containingFile?.virtualFile ?: return@filter false
            ModuleSources.contains(module, ModuleSources.FilePath.of(file, basePath))
        }
        return scoped.ifEmpty { all }
    }

    /**
     * The JSON or YAML file behind [source], or null for any other format (PO, a JS object, …),
     * whose values are not read here: they are offered no reuse and extract as before.
     *
     * Checked on the language id rather than on the PSI class: YAML is an optional dependency, and
     * [TranslationFileKeys.translationLeaves] only touches YAML classes for a file that is YAML.
     */
    private fun readableFile(source: LocalizationSource): PsiFile? {
        val file = source.tree?.value()?.containingFile ?: return null
        return file.takeIf { it.language.isKindOf(JSON_LANGUAGE) || it.language.id == YAML_LANGUAGE }
    }

    private const val JSON_LANGUAGE = "JSON"
    private const val YAML_LANGUAGE = "yaml"
}
