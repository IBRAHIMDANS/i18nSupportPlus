package com.ibrahimdans.i18n.extensions.lang.php

import com.ibrahimdans.i18n.plugin.factory.ReferenceAssistant
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.parser.KeyParserBuilder
import com.ibrahimdans.i18n.plugin.parser.RawKey
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
        if (config.gettext) {
            if (!gettextPattern(Settings.getInstance(element.project).config()).accepts(element)) return null
        }
        val parser = (
            if (config.gettext) {
                KeyParserBuilder.withoutTokenizer()
            } else
                KeyParserBuilder.withSeparators(config.nsSeparator, config.keySeparator)
        ).build()
        val text = element.text.unQuote()
        if (text.isBlank()) return null
        return parser.parse(RawKey(listOf(KeyElement.literal(text))))
    }

    private fun gettextPattern(config: Config) =
        PlatformPatterns.or(*config.gettextAliases.split(",").map { PhpPatternsExt.phpArgument(it.trim(), 0) }.toTypedArray())
}