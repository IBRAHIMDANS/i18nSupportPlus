package com.ibrahimdans.i18n.plugin.parser

import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.parser.KeyParserBuilder
import com.intellij.openapi.project.Project

class RawKeyParser(private val project: Project) {
    fun parse(rawKey: RawKey): FullKey? {
        val config = Settings.getInstance(project).config()
        val flatKeys = config.usesFlatKeys()
        val parser = (
                if (flatKeys)
                    KeyParserBuilder.withoutTokenizer()
                else
                    KeyParserBuilder
                        .withSeparators(config.nsSeparator, config.keySeparator)
                        .withDummyNormalizer()
                        .withTemplateNormalizer()
                ).build()
        return parser.parse(rawKey, flatKeys, config.firstComponentNs)
    }
}