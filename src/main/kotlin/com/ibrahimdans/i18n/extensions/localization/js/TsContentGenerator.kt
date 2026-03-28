package com.ibrahimdans.i18n.extensions.localization.js

import com.ibrahimdans.i18n.ContentGenerator
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.lang.Language
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.impl.JSChangeUtil
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.psi.PsiElement

class TsContentGenerator : ContentGenerator {

    private val tabChar = "  "

    override fun generateContent(compositeKey: List<Literal>, value: String): String =
        compositeKey.foldRightIndexed("\"$value\"", { i, key, acc ->
            val tab = tabChar.repeat(i)
            "{\n$tabChar$tab${key.text}: $acc\n$tab}"
        })

    private fun tsFileType(): FileType =
        FileTypeManager.getInstance().findFileTypeByName("TypeScript") ?: UnknownFileType.INSTANCE

    override fun getType(): FileType = tsFileType()

    override fun getLanguage(): Language = (tsFileType() as? LanguageFileType)?.language ?: Language.ANY

    override fun getDescription(): String = PluginBundle.getMessage("quickfix.create.typeScript.translation.files")

    override fun isSuitable(element: PsiElement): Boolean = element is JSObjectLiteralExpression

    override fun generateTranslationEntry(item: PsiElement, key: String, value: String) {
        val obj = item as JSObjectLiteralExpression
        val newProperty = JSChangeUtil.createExpressionPsiWithContext("({$key: $value})", obj, JSObjectLiteralExpression::class.java)
            ?.properties?.firstOrNull() ?: return
        val props = obj.properties
        val comma = JSChangeUtil.createCommaPsiElement(obj)
        if (props.isEmpty()) {
            obj.addAfter(newProperty, obj.firstChild)
            return
        }
        val (element, anchor) = if (Settings.getInstance(item.project).extractSorted) {
            val before = props.takeWhile { it.name ?: "" < key }
            if (before.isEmpty()) {
                Pair(comma, obj.addBefore(newProperty, props.first()))
            } else {
                Pair(newProperty, obj.addAfter(comma, before.last()))
            }
        } else {
            Pair(newProperty, obj.addAfter(comma, props.last()))
        }
        obj.addAfter(element, anchor)
    }

    override fun generate(element: PsiElement, fullKey: FullKey, unresolved: List<Literal>, translationValue: String?) =
        generateTranslationEntry(
            element,
            unresolved.first().text,
            generateContent(unresolved.drop(1), translationValue ?: fullKey.source)
        )
}
