package com.ibrahimdans.i18n.extensions.lang.php

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.factory.ReferenceAssistant
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.parser.KeyParserBuilder
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.rules.RuleDecision
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement

internal class PhpReferenceAssistant: ReferenceAssistant {

    override fun pattern(): ElementPattern<out PsiElement> {
        return PhpPatternsExt.phpArgument()
    }

    override fun extractKey(element: PsiElement): FullKey? {
        val config = Settings.getInstance(element.project).config()
        when (phpRuleDecision(element)) {
            RuleDecision.EXCLUDE -> return null
            // The pattern already holds it as a first argument: an including rule needs no published name.
            RuleDecision.INCLUDE -> {}
            RuleDecision.NONE -> if (!isPublishedCall(element, config)) return null
        }
        return parse(element, config)
    }

    private fun isPublishedCall(element: PsiElement, config: Config): Boolean {
        if (config.gettext) {
            if (!gettextPattern(config).accepts(element)) return false
        } else {
            val functionNames = Extensions.TECHNOLOGY.extensionList
                .flatMap { it.translationFunctionNames() }
                .filter { PhpPatternsExt.isValidPhpFunctionName(it) }
            if (functionNames.isEmpty()) return false
            val pattern = PlatformPatterns.or(
                *functionNames.map { PhpPatternsExt.phpArgument(it, 0) }.toTypedArray()
            )
            if (!pattern.accepts(element)) return false
        }
        return true
    }

    private fun parse(element: PsiElement, config: Config): FullKey? {
        val text = element.text.unQuote()
        if (text.isBlank()) return null
        val rawKey = RawKey(listOf(KeyElement.literal(text)))
        if (config.usesFlatKeys()) return KeyParserBuilder.withoutTokenizer().build().parse(rawKey)
        // Like the annotator, so a module's key template reads the key the same way on both sides.
        return RawKeyParser(element.project).parse(rawKey, element)
    }

    private fun gettextPattern(config: Config) =
        PlatformPatterns.or(*config.gettextAliases.split(",").map { PhpPatternsExt.phpArgument(it.trim(), 0) }.toTypedArray())
}