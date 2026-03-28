package com.ibrahimdans.i18n.extensions.localization.plain.`object`

import com.ibrahimdans.i18n.ContentGenerator
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.psi.PsiElement

// Plain Object support is not yet implemented — all methods are no-ops to prevent runtime crashes
class PlainObjectContentGenerator : ContentGenerator {
    override fun generateContent(compositeKey: List<Literal>, value: String): String = ""

    override fun getType(): FileType = PlainTextFileType.INSTANCE

    override fun getLanguage(): Language = PlainTextLanguage.INSTANCE

    override fun getDescription(): String = PluginBundle.getMessage("quickfix.create.plainObject.translation.files")

    // isSuitable returns false so this generator is never selected for content creation
    override fun isSuitable(element: PsiElement) = false

    override fun generateTranslationEntry(item: PsiElement, key: String, value: String) {
        // no-op: not yet implemented
    }

    override fun generate(element: PsiElement, fullKey: FullKey, unresolved: List<Literal>, translationValue: String?) {
        // no-op: not yet implemented
    }
}
