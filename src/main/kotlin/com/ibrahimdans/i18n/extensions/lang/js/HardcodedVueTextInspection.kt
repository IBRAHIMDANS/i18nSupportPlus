package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText

/**
 * Flags a text written as is in the `<template>` of a Vue component — `<button>Save</button>`,
 * `<input placeholder="Name">` — as [HardcodedJsxTextInspection] does for JSX.
 *
 * Off by default, silent whenever in doubt, and with no quick fix: only *Internationalize…* writes
 * into a Vue template ([VueTemplateTextExtractor]), from the same rules ([isHardcoded]). A text is
 * reported when:
 *  - it sits in the root `<template>` of a `.vue` file — never in `<script>`, `<style>` or `<i18n>`;
 *  - it holds a letter once its `{{ … }}` interpolations and HTML entities are removed
 *    ([HardcodedTextRules.isTranslatable]): `{{ $t('save') }}` alone is not text, `Hello {{ name }}` is;
 *  - its attribute is one a user reads ([HardcodedTextRules.VISIBLE_ATTRIBUTES]) and is static:
 *    `:title` and `v-bind:title` hold an expression, and their name is not `title`;
 *  - its tag is neither code ([HardcodedTextRules.CODE_TAGS]) nor a vue-i18n component (`<i18n-t>`,
 *    `<i18n>`), whose content is the message's slots.
 *
 * A tag holding another tag reports each of its texts: `<p>Hi <b>you</b></p>` reports `Hi` and `you`.
 */
class HardcodedVueTextInspection : LocalInspectionTool() {

    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = "I18nHardcodedVueText"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (!holder.file.name.endsWith(".vue")) return PsiElementVisitor.EMPTY_VISITOR
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                when (element) {
                    is XmlText -> checkText(element, holder)
                    is XmlAttributeValue -> checkAttribute(element, holder)
                }
            }
        }
    }

    private fun checkText(text: XmlText, holder: ProblemsHolder) {
        if (!isHardcoded(text)) return
        val raw = text.text
        val start = raw.indexOfFirst { !it.isWhitespace() }
        val end = raw.indexOfLast { !it.isWhitespace() } + 1
        holder.registerProblem(text, TextRange(start, end), PluginBundle.message("inspection.hardcoded.vue.text.message"))
    }

    private fun checkAttribute(value: XmlAttributeValue, holder: ProblemsHolder) {
        if (!isHardcoded(value)) return
        val range = value.valueTextRange.shiftLeft(value.textRange.startOffset)
        holder.registerProblem(value, range, PluginBundle.message("inspection.hardcoded.vue.text.message"))
    }

    companion object {

        /**
         * Whether [element] — an [XmlText] or an [XmlAttributeValue] of a `.vue` file — is a text
         * this inspection reports.
         */
        fun isHardcoded(element: PsiElement): Boolean {
            if (!element.containingFile.name.endsWith(".vue")) return false
            return when (element) {
                is XmlText -> {
                    val tag = element.parentTag
                    tag != null && isTemplateContent(tag) && HardcodedTextRules.isTranslatable(element.text.replace(INTERPOLATION, ""))
                }
                is XmlAttributeValue -> {
                    val attribute = element.parent as? XmlAttribute
                    val tag = attribute?.parent
                    attribute != null && tag != null && attribute.name in HardcodedTextRules.VISIBLE_ATTRIBUTES &&
                        isTemplateContent(tag) && HardcodedTextRules.isTranslatable(element.value)
                }
                else -> false
            }
        }

        /** A Vue interpolation; its content is code, whatever it holds. */
        internal val INTERPOLATION = Regex("\\{\\{(.*?)}}", RegexOption.DOT_MATCHES_ALL)

        private val SKIPPED_TAGS = HardcodedTextRules.CODE_TAGS + setOf("i18n-t", "i18n")

        /**
         * True when [tag] is inside the root `<template>` and neither it nor a tag around it holds
         * code or a vue-i18n message.
         */
        private fun isTemplateContent(tag: XmlTag): Boolean {
            var current: XmlTag = tag
            while (true) {
                if (current.name in SKIPPED_TAGS) return false
                val parent = current.parentTag ?: return current.name == "template"
                current = parent
            }
        }
    }
}
