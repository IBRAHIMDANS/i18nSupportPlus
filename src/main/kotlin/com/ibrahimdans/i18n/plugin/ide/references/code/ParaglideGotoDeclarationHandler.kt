package com.ibrahimdans.i18n.plugin.ide.references.code

import com.ibrahimdans.i18n.extensions.lang.js.extractors.ParaglideMessageExtractor
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.ModulePresets
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
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
class ParaglideGotoDeclarationHandler : GotoDeclarationHandler, CompositeKeyResolver<PsiElement> {

    private val extractor = ParaglideMessageExtractor()

    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        val source = sourceElement ?: return null
        // The caret is on the member's name, or on the index literal of `m["a.b"]`, below the member access.
        val member = generateSequence(source) { it.parent }.take(MAX_DEPTH).firstOrNull(extractor::canExtract) ?: return null
        if (!ModulePresets.allows(ModulePresets.presetOf(member), PARAGLIDE)) return null
        val project = member.project
        val fullKey = RawKeyParser(project).parse(extractor.extract(member), member) ?: return null
        val pluralSeparator = Settings.getInstance(project).config().pluralSeparator
        val descriptors = project.service<LocalizationSourceService>().findReadSources(fullKey, member)
            .flatMap { source -> resolve(fullKey.compositeKey, source, pluralSeparator).map { ReferenceDescriptor(it, source.host) } }
            .filter { it.reference.path.isNotEmpty() }
        if (descriptors.isEmpty()) return null
        val reference = I18nReference(member, TextRange(0, member.textLength), descriptors)
        return I18nGotoDeclarationHandler.previewTarget(reference)
            ?: reference.multiResolve(false).mapNotNull { it.element }.toTypedArray().takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val MAX_DEPTH = 3
        const val PARAGLIDE = "paraglide"
    }
}
