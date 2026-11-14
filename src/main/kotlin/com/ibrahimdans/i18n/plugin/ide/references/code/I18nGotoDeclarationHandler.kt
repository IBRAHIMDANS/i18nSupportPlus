package com.ibrahimdans.i18n.plugin.ide.references.code

import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.intellij.openapi.components.service
import com.intellij.openapi.util.TextRange
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement

/**
 * Ctrl+click on a key opens its translation in the preview locale (the folding locale when none is set).
 *
 * A key resolves to one target per translation file and plural form, so Ctrl+click always asked to
 * choose between `en` and `fr` — four entries for a plural key. The other locales stay one hover away.
 * When the preview locale lacks the key, nothing is returned and the platform shows every target.
 *
 * The reference itself keeps every target: Find Usages and Rename from a translation file rely on it.
 */
class I18nGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        val source = sourceElement ?: return null
        // The key's literal holds the reference; the caret is on its token, or on a template part below it.
        val reference = generateSequence(source) { it.parent }.take(MAX_DEPTH)
            .flatMap { it.references.asSequence() }
            .filterIsInstance<I18nReference>()
            .firstOrNull() ?: return null
        return previewTarget(reference)
    }

    internal companion object {
        private const val MAX_DEPTH = 3

        /**
         * [reference]'s target in the preview locale (the folding locale when none is set), or null when
         * that locale lacks the key. Shared with the handlers of keys no `I18nReference` holds (Paraglide's `m.hello`).
         */
        fun previewTarget(reference: I18nReference): Array<PsiElement>? {
            val config = Settings.getInstance(reference.element.project).config()
            val locale = config.previewLocale.ifBlank { config.foldingPreferredLanguage }
            // The first form in the file: `_one` before `_other` for a plural key.
            return reference.targetsIn(locale).minByOrNull { it.textOffset }?.let { arrayOf(it) }
        }

        /**
         * Where Ctrl+click on [element] leads when it names [rawKey] without holding an `I18nReference` —
         * Paraglide's `m.hello`, an i18next selector `$ => $.button.save`, which already carry the IDE's own
         * JavaScript reference: the key in the preview locale, else in every locale holding it. Null when no
         * translation file holds it, so the JavaScript navigation applies.
         */
        fun targetsOf(element: PsiElement, rawKey: RawKey): Array<PsiElement>? {
            val project = element.project
            val fullKey = RawKeyParser(project).parse(rawKey, element) ?: return null
            val pluralSeparator = Settings.getInstance(project).config().pluralSeparator
            val descriptors = project.service<LocalizationSourceService>().findReadSources(fullKey, element)
                .flatMap { source -> KeyResolver.resolve(fullKey, source, pluralSeparator).map { ReferenceDescriptor(it, source.host) } }
                .filter { it.reference.path.isNotEmpty() }
            if (descriptors.isEmpty()) return null
            val reference = I18nReference(element, TextRange(0, element.textLength), descriptors)
            return previewTarget(reference)
                ?: reference.multiResolve(false).mapNotNull { it.element }.toTypedArray().takeIf { it.isNotEmpty() }
        }

        private object KeyResolver : CompositeKeyResolver<PsiElement>
    }
}
