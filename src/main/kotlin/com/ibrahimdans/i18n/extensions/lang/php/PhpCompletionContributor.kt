package com.ibrahimdans.i18n.extensions.lang.php

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.extensions.localization.php.LaravelTranslationFiles
import com.ibrahimdans.i18n.plugin.ide.completion.CompositeKeyCompletionContributor
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionInitializationContext
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder

/**
 * PHP completion contributor.
 *
 * In a Laravel project, a key whose file part is still being typed — `__('cou<caret>')`,
 * `__('courier::<caret>')` — is offered the translation files it can name, followed by the key
 * separator: `messages.` for `lang/en/messages.php`, `courier::messages.` for a package's override
 * `lang/vendor/courier/en/messages.php`. Picking one reopens the popup on that file's keys. The
 * generic completion only knows keys under a namespace already written, and has no file to list
 * the `courier::` of a package from.
 */
class PhpCompletionContributor : CompositeKeyCompletionContributor(PhpLang()) {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        addTranslationFiles(parameters, result)
        super.fillCompletionVariants(parameters, result)
    }

    private fun addTranslationFiles(parameters: CompletionParameters, result: CompletionResultSet) {
        val position = parameters.position
        val text = position.text.unQuote()
        if (DUMMY_KEY !in text || text.substringAfter(DUMMY_KEY).isNotBlank()) return
        val project = position.project
        if (!LaravelProject.isLaravel(project)) return
        val keySeparator = Settings.getInstance(project).config().keySeparator
        val typed = text.substringBefore(DUMMY_KEY)
        // Past the file part (`messages.wel`), the keys of the file are what is offered.
        if (keySeparator.isEmpty() || keySeparator in typed) return
        val lang = PhpLang()
        if (!lang.canExtractKey(position, Extensions.TECHNOLOGY.extensionList.flatMap { it.translationFunctionNames() })) return
        val scopes = Extensions.TECHNOLOGY.extensionList.filterIsInstance<LaravelTranslationFiles>()
            .flatMap { it.keyScopes(project) }
            .distinct()
        if (scopes.isEmpty()) return
        // The whole typed text is the prefix: `courier::` holds characters a word prefix stops at.
        val resultSet = result.withPrefixMatcher(typed).caseInsensitive()
        scopes.sorted().forEach { scope ->
            resultSet.addElement(
                LookupElementBuilder.create(scope + keySeparator)
                    .withInsertHandler { context, _ -> AutoPopupController.getInstance(context.project).scheduleAutoPopup(context.editor) }
            )
        }
    }

    private companion object {
        val DUMMY_KEY: String = CompletionInitializationContext.DUMMY_IDENTIFIER_TRIMMED
    }
}
