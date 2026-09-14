package com.ibrahimdans.i18n.plugin.ide.references.code

import com.ibrahimdans.i18n.plugin.ide.settings.Settings
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
        val config = Settings.getInstance(source.project).config()
        val locale = config.previewLocale.ifBlank { config.foldingPreferredLanguage }
        // The first form in the file: `_one` before `_other` for a plural key.
        return reference.targetsIn(locale).minByOrNull { it.textOffset }?.let { arrayOf(it) }
    }

    private companion object {
        const val MAX_DEPTH = 3
    }
}
