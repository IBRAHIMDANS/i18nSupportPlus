package com.ibrahimdans.i18n.extensions.localization.js

import com.ibrahimdans.i18n.SOURCE_ROOT
import com.ibrahimdans.i18n.TranslationReferenceAssistant
import com.ibrahimdans.i18n.extensions.lang.php.PsiUserDataPatterns
import com.ibrahimdans.i18n.plugin.ide.references.translation.TranslationToCodeReferenceProvider
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.intellij.lang.javascript.patterns.JSPatterns
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.openapi.components.service
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil

/**
 * From a key of a TS/JS catalogue to the code using it: a catalogue keyed by locale (i18n-js) or the
 * `resources` of an i18next configuration file.
 */
class TsReferenceAssistant : TranslationReferenceAssistant<PsiElement> {

    override fun pattern(): ElementPattern<out PsiElement> {
        return PlatformPatterns.psiElement().inFile(PsiUserDataPatterns.withUserData(SOURCE_ROOT, true)).and(JSPatterns.jsProperty())
    }

    override fun references(element: PsiElement): List<PsiReference> {
        val property = element as? JSProperty ?: return emptyList()
        val path = keyPath(property) ?: return emptyList()
        val range = nameRange(property) ?: return emptyList()
        // Neither format names a namespace: a catalogue's keys are called without one, which is
        // what a default namespace stands for.
        val namespace = Settings.getInstance(property.project).config().defaultNamespaces().first()
        return property.project.service<TranslationToCodeReferenceProvider>().getReferences(property, range, listOf(namespace) + path)
    }

    /**
     * The names from the catalogue's object for one locale down to [property], or null outside it.
     *
     * The key used to be read from every property above, the file's name included: `fr`, the
     * locale, and for i18next `resources`, `en` and `translation` went into the key looked for
     * (`fr.dashboard.title`), which no call writes — so no reference ever reached the code.
     */
    private fun keyPath(property: JSProperty): List<String>? {
        val project = property.project
        val root = project.service<LocalizationSourceService>().findAllSources(project)
            .filter { it.localization is TsLocalization }
            .mapNotNull { it.tree?.value() }
            .firstOrNull { PsiTreeUtil.isAncestor(it, property, true) }
            ?: return null
        val properties = generateSequence(property) { PsiTreeUtil.getParentOfType(it, JSProperty::class.java, true) }
            .takeWhile { PsiTreeUtil.isAncestor(root, it, true) }
            .toList()
        if (properties.any { it.name == null }) return null
        return properties.mapNotNull { it.name }.reversed()
    }

    /** The key's name inside [property], its quotes left out: what Ctrl+click starts from. */
    private fun nameRange(property: JSProperty): TextRange? {
        val name = property.nameIdentifier ?: return null
        val range = name.textRange.shiftLeft(property.textRange.startOffset)
        val quoted = name.textLength >= 2 && name.text.first() in "'\"`"
        return if (quoted) TextRange(range.startOffset + 1, range.endOffset - 1) else range
    }
}
