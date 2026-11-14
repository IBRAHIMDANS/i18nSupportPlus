package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.extensions.lang.js.extractors.ParaglideMessageExtractor
import com.ibrahimdans.i18n.plugin.ide.references.code.I18nGotoDeclarationHandler
import com.ibrahimdans.i18n.plugin.utils.ModulePresets
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement

/**
 * Ctrl+click on Paraglide's `m.hello` (or `m["a.b"]`) opens the message in the preview locale, as for
 * any key ([I18nGotoDeclarationHandler.previewTarget]).
 *
 * The member already carries the IDE's own JavaScript reference, to the function Paraglide generates
 * (`paraglide/messages/hello.js`): rewritten at every compilation, never what one wants to edit. No
 * `I18nReference` can be added on an element that is no string, so this handler resolves the key
 * itself, and runs first. A key no translation file holds gives null: the JavaScript navigation
 * applies. A key missing from the preview locale offers the locales holding it, not the generated file.
 */
class ParaglideGotoDeclarationHandler : GotoDeclarationHandler {

    private val extractor = ParaglideMessageExtractor()

    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        val source = sourceElement ?: return null
        // The caret is on the member's name, or on the index literal of `m["a.b"]`, below the member access.
        val member = generateSequence(source) { it.parent }.take(MAX_DEPTH).firstOrNull(extractor::canExtract) ?: return null
        if (!ModulePresets.allows(ModulePresets.presetOf(member), PARAGLIDE)) return null
        return I18nGotoDeclarationHandler.targetsOf(member, extractor.extract(member))
    }

    private companion object {
        const val MAX_DEPTH = 3
        const val PARAGLIDE = "paraglide"
    }
}
