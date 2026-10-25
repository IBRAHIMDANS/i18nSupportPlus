package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.extensions.lang.js.extractors.TypesafeI18nExtractor
import com.ibrahimdans.i18n.plugin.ide.preview.PreviewLocaleSwitcher
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.utils.LocaleMatching
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.ModulePresets
import com.ibrahimdans.i18n.plugin.utils.displayValue
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.components.service

/**
 * Completion after typesafe-i18n's `LL.` and after each level of it, `LL.home.`: the members of
 * that level in the locale modules, each message with its value in the preview locale.
 *
 * The IDE's own completion lists what the generator last wrote into `i18n-types.ts`, so a message
 * added since was missing and none showed its text. A generated member of the same name is dropped,
 * so each one is offered once. Only in a file importing typesafe-i18n's code, and where the module's
 * preset lets typesafe-i18n's syntax be read; anywhere else the JavaScript completion is untouched.
 */
class TypesafeI18nCompletionContributor : CompletionContributor() {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val member = parameters.position.parent as? JSReferenceExpression ?: return
        val qualifier = member.qualifier as? JSReferenceExpression ?: return
        val path = levelOf(qualifier) ?: return
        if (!TypesafeI18nExtractor.importsTypesafeI18n(member)) return
        if (!ModulePresets.allows(ModulePresets.presetOf(member), TYPESAFE_I18N)) return

        val members = membersOf(member, path)
        if (members.isEmpty()) return
        result.runRemainingContributors(parameters) { other ->
            if (other.lookupElement.lookupString !in members) result.passResult(other)
        }
        members.forEach { (name, value) ->
            val lookup = LookupElementBuilder.create(name)
            result.addElement(if (value == null) lookup else lookup.withTypeText(displayValue(value), true))
        }
        result.stopHere()
    }

    /** The level [qualifier] stands for: empty for `LL` itself, `[home]` for `LL.home`; null for anything else. */
    private fun levelOf(qualifier: JSReferenceExpression): List<String>? =
        if (qualifier.qualifier == null) emptyList<String>().takeIf { qualifier.referenceName in TypesafeI18nExtractor.ROOTS }
        else TypesafeI18nExtractor.pathOf(qualifier)

    /** The members at [path] in every locale module [member]'s module reads, to their preview-locale text (null for a level). */
    private fun membersOf(member: JSReferenceExpression, path: List<String>): Map<String, String?> {
        val project = member.project
        val config = Settings.getInstance(project).config()
        val sources = project.service<LocalizationSourceService>().findSources(emptyList(), member)
        val previewLocale = LocaleMatching.pick(PreviewLocaleSwitcher.effective(config), sources.map { it.localeLabel() })
        val members = sortedMapOf<String, String?>()
        sources.forEach { source ->
            val level = path.fold(source.tree) { tree, name -> tree?.findChild(name) }?.value() as? JSObjectLiteralExpression
            level?.properties.orEmpty().forEach { property ->
                val name = property.name ?: return@forEach
                val text = (property.value as? JSLiteralExpression)?.stringValue
                if (source.localeLabel() == previewLocale && text != null) members[name] = text
                else members.putIfAbsent(name, null)
            }
        }
        return members
    }

    private companion object {
        const val TYPESAFE_I18N = "typesafe-i18n"
    }
}
