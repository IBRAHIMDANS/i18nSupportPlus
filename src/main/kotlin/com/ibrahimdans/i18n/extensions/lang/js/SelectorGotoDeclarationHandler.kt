package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.extensions.lang.js.extractors.SelectorKeyExtractor
import com.ibrahimdans.i18n.plugin.ide.references.code.I18nGotoDeclarationHandler
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement

/**
 * Ctrl+click on i18next's selector `t($ => $.button.save)` opens `button.save` in the preview locale,
 * as for a string key ([I18nGotoDeclarationHandler.targetsOf]).
 *
 * The members carry TypeScript's own reference, to the declaration of the resources' type: no
 * `I18nReference` can be added beside it, so this handler resolves the key itself, and runs first. A key
 * no translation file holds gives null, and the TypeScript navigation applies.
 */
class SelectorGotoDeclarationHandler : GotoDeclarationHandler {

    private val extractor = SelectorKeyExtractor()

    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        val source = sourceElement ?: return null
        // The caret is on a member's name, or in the index literal of `$['a-b']`: the selector's body is above it.
        val selector = generateSequence(source) { it.parent }.take(MAX_DEPTH).firstOrNull(extractor::canExtract) ?: return null
        return I18nGotoDeclarationHandler.targetsOf(selector, extractor.extract(selector))
    }

    private companion object {
        /** Leaf, member, then the members above it: `$.a.b.c` with the caret on `a` is three levels below its body. */
        const val MAX_DEPTH = 8
    }
}
