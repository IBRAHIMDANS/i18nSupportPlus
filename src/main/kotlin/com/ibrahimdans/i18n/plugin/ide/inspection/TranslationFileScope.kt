package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
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
     * The locale placeholders are compared against: the reference locale of the module holding the
     * file, when one declares it, otherwise `en`.
     */
    fun referenceLocaleFor(file: PsiFile, source: LocalizationSource): String {
        val modules = Settings.getInstance(file.project).config().modules
        return modules
            .firstOrNull { it.referenceLocale.isNotBlank() && it.rootDirectory.isNotBlank() && source.displayPath.startsWith(it.rootDirectory.trimEnd('/')) }
            ?.referenceLocale
            ?: DEFAULT_REFERENCE_LOCALE
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
    private const val DEFAULT_REFERENCE_LOCALE = "en"
}
