package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.ReferenceLocale
import com.ibrahimdans.i18n.plugin.utils.isLocaleNamedFile
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.intellij.openapi.components.service
import com.intellij.psi.PsiFile

/**
 * What the translation inspections need to know about the file they visit.
 *
 * They used to run on every JSON and YAML file of the project: `package.json`, `tsconfig.json` or
 * an OpenAPI description got placeholder and ICU warnings. A file is now inspected only when the
 * plugin reads it as a translation source — the same scan the tool window and the editor use.
 */
internal object TranslationFileScope {

    /** The source [file] is read as, or null when it is not a translation file. */
    fun sourceOf(file: PsiFile): LocalizationSource? {
        val virtualFile = file.originalFile.virtualFile ?: return null
        return file.project.service<LocalizationSourceService>().findAllSources(file.project)
            .firstOrNull { it.tree?.value()?.containingFile?.virtualFile == virtualFile }
    }

    /**
     * The source holding the same namespace as [source] in [locale], or null.
     *
     * Matched on the path with its locale taken out — `locales/{lang}/common.json`,
     * `locales/{lang}.json` — so the reference is found whatever the layout, and a file in another
     * module or folder is never compared against.
     */
    fun counterpartOf(file: PsiFile, source: LocalizationSource, locale: String): LocalizationSource? {
        val shape = shapeOf(source)
        return file.project.service<LocalizationSourceService>().findAllSources(file.project)
            .firstOrNull { it !== source && it.localeLabel() == locale && shapeOf(it) == shape }
    }

    /**
     * The locale label [source] is compared against: [ReferenceLocale] for the module holding it —
     * the innermost one when roots nest — matched among the locales of [source]'s namespace, so
     * `en` designates `en-US` files. The wanted locale as written when no file of it exists, which
     * [counterpartOf] then finds nothing for.
     */
    fun referenceLocaleFor(file: PsiFile, source: LocalizationSource): String {
        val config = Settings.getInstance(file.project).config()
        val module = ModuleSources.owner(config.modules, TranslationDataLoader.projectPathOf(source))
        val shape = shapeOf(source)
        val labels = file.project.service<LocalizationSourceService>().findAllSources(file.project)
            .filter { shapeOf(it) == shape }
            .map { it.localeLabel() }
            .distinct()
        return ReferenceLocale.of(module, config, labels) ?: ReferenceLocale.wanted(module, config)
    }

    /** [source]'s path with its locale replaced by a placeholder. */
    private fun shapeOf(source: LocalizationSource): String {
        val locale = source.localeLabel()
        val segments = source.displayPath.split('/').toMutableList()
        if (source.isLocaleNamedFile()) {
            segments[segments.lastIndex] = LOCALE_PLACEHOLDER + "." + segments.last().substringAfterLast('.')
        } else {
            val index = segments.dropLast(1).lastIndexOf(locale)
            if (index >= 0) segments[index] = LOCALE_PLACEHOLDER
        }
        return segments.joinToString("/")
    }

    private const val LOCALE_PLACEHOLDER = "{lang}"
}
