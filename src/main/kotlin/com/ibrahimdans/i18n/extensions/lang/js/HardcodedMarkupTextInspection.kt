package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlText

/**
 * Flags a text written as is in a template's markup, as [MarkupTexts] reads it for [dialect], with
 * an *Extract i18n key* quick fix writing the call [extractor] builds. Off by default, like
 * [HardcodedJsxTextInspection]: silent whenever in doubt.
 *
 * A tag's content is underlined once, over its whole message — `Hello {{ name }}!` —, an attribute
 * over its value.
 */
abstract class HardcodedMarkupTextInspection internal constructor(
    private val shortName: String,
    private val messageKey: String,
    private val dialect: MarkupDialect,
    private val extractor: TranslationExtractor,
) : LocalInspectionTool() {

    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = shortName

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (!dialect.applies(holder.file)) return PsiElementVisitor.EMPTY_VISITOR
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is XmlText && element !is XmlAttributeValue) return
                val found = MarkupTexts.of(element, dialect) ?: return
                val fix = ExtractTextQuickFix(element, extractor)
                val message = PluginBundle.message(messageKey)
                when (found) {
                    is MarkupText.Content -> {
                        val container = element.parent
                        holder.registerProblem(container, found.range.shiftLeft(container.textRange.startOffset), message, fix)
                    }
                    is MarkupText.Attribute -> {
                        val value = found.element
                        holder.registerProblem(value, value.valueTextRange.shiftLeft(value.textRange.startOffset), message, fix)
                    }
                }
            }
        }
    }
}
