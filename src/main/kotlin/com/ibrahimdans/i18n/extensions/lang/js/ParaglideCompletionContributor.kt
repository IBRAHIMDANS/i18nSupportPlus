package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.ide.preview.PreviewLocaleSwitcher
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.tree.PluralGroup
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.ibrahimdans.i18n.plugin.utils.LocaleMatching
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.ModulePresets
import com.ibrahimdans.i18n.plugin.utils.displayValue
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.components.service
import com.intellij.psi.PsiElement

/**
 * Completion after Paraglide's `m.`: every message of the message files, with its value in the
 * preview locale.
 *
 * The IDE's own JavaScript completion lists the functions Paraglide generated, so a message added
 * to `messages/en.json` since the last compilation was missing, and none showed its text. The keys
 * come from the message files instead; a generated function of the same name is dropped, so each
 * message is offered once, with its value. A key that is no identifier (`user.profile.title`) is
 * inserted as `m["user.profile.title"]`, the way Paraglide exposes it.
 *
 * Only after a message object the file imports from Paraglide ([ParaglideImports]), and where the
 * module's preset lets Paraglide's syntax be read; anywhere else the JavaScript completion is untouched.
 */
class ParaglideCompletionContributor : CompletionContributor() {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val member = parameters.position.parent as? JSReferenceExpression ?: return
        val qualifier = member.qualifier as? JSReferenceExpression ?: return
        if (qualifier.qualifier != null || qualifier.referenceName !in ParaglideImports.messageObjectNames(member)) return
        if (!ModulePresets.allows(ModulePresets.presetOf(member), PARAGLIDE)) return

        val messages = messagesOf(member)
        if (messages.isEmpty()) return
        // The generated functions bear the messages' names: dropped, ours carry the value.
        result.runRemainingContributors(parameters) { other ->
            if (other.lookupElement.lookupString !in messages) result.passResult(other)
        }
        messages.forEach { (key, value) -> result.addElement(lookupOf(key, value)) }
        result.stopHere()
    }

    /** Every message key of the files [member]'s module reads, to its preview-locale value (null when none). */
    private fun messagesOf(member: PsiElement): Map<String, String?> {
        val project = member.project
        val config = Settings.getInstance(project).config()
        val sources = project.service<LocalizationSourceService>().findSources(emptyList(), member)
        val previewLocale = LocaleMatching.pick(PreviewLocaleSwitcher.effective(config), sources.map { it.localeLabel() })
        val messages = sortedMapOf<String, String?>()
        sources.forEach { source ->
            val tree = source.tree ?: return@forEach
            leaves(tree, "").forEach { (key, value) ->
                if (source.localeLabel() == previewLocale) messages[key] = value
                else messages.putIfAbsent(key, null)
            }
        }
        return messages
    }

    /** The messages under [node], keyed by their dotted path below [prefix], with their displayable text. */
    private fun leaves(node: Tree<PsiElement>, prefix: String): List<Pair<String, String?>> =
        node.entries().orEmpty().flatMap { (name, child) ->
            val key = if (prefix.isEmpty()) name else "$prefix.$name"
            if (child.isTree() && !PluralGroup.isPluralGroup(child)) leaves(child, key)
            else listOf(key to PluralGroup.displayableValue(child)?.value()?.text?.unQuote())
        }

    private fun lookupOf(key: String, value: String?): LookupElement {
        val lookup = LookupElementBuilder.create(key)
            .let { if (IDENTIFIER.matches(key)) it else it.withInsertHandler(::insertIndexed) }
        // A message with variants is a JSON array: no single text to show here.
        return if (value == null || value.trimStart().startsWith("[")) lookup else lookup.withTypeText(displayValue(value), true)
    }

    /** `m.user.profile.title` → `m["user.profile.title"]`: the `.` before the key becomes the index. */
    private fun insertIndexed(context: InsertionContext, item: LookupElement) {
        val document = context.document
        val start = context.startOffset
        if (start == 0 || document.charsSequence[start - 1] != '.') return
        document.replaceString(start - 1, context.tailOffset, "[\"${item.lookupString}\"]")
    }

    private companion object {
        const val PARAGLIDE = "paraglide"
        val IDENTIFIER = Regex("[A-Za-z_$][\\w$]*")
    }
}
