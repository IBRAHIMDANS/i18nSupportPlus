package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.factory.MessageVariable
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.ibrahimdans.i18n.plugin.utils.ModulePresets
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlText

/**
 * Extracts a text of a Vue `<template>` — one [HardcodedVueTextInspection] reports — into a
 * `$t` call, which a template reaches without any import:
 *  - `<p>Save</p>` becomes `<p>{{ $t('save') }}</p>`;
 *  - `<p>Hello {{ user.name }}!</p>` becomes `{{ $t('hello', { name: user.name }) }}`, its message
 *    `Hello {name}!` — each interpolation a variable, when it reads as a value (`name`, `user.name`);
 *  - `placeholder="Name"` becomes `:placeholder="$t('name')"`.
 *
 * A text interpolating anything else — a call, a condition, a filter — is not extracted: its
 * message would lose what the expression computes.
 */
internal class VueTemplateTextExtractor : TranslationExtractor {

    override fun canExtract(element: PsiElement): Boolean =
        (element is XmlText || element is XmlAttributeValue) && HardcodedVueTextInspection.isHardcoded(element) &&
            (element !is XmlText || interpolations(element) != null)

    override fun isExtracted(element: PsiElement): Boolean = false

    override fun text(element: PsiElement): String {
        if (element is XmlAttributeValue) return StringUtil.unescapeXmlEntities(element.value).trim()
        val placeholders = variables(element).associate { it.expression to it.placeholder }
        val text = HardcodedVueTextInspection.INTERPOLATION.replace(element.text) { placeholders.getValue(it.groupValues[1].trim()) }
        return StringUtil.unescapeXmlEntities(text).replace(WHITESPACE, " ").trim()
    }

    /** The text without the blanks around it, or the whole attribute, which becomes a bound one. */
    override fun textRange(element: PsiElement): TextRange {
        if (element is XmlAttributeValue) return element.parent.textRange
        val raw = element.text
        val start = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        val end = raw.indexOfLast { !it.isWhitespace() } + 1
        return TextRange(start, maxOf(start, end)).shiftRight(element.textRange.startOffset)
    }

    override fun template(element: PsiElement): (argument: String) -> String {
        val call = call(element)
        val variables = variables(element)
        return { call(it, variables) }
    }

    override fun call(element: PsiElement): (argument: String, variables: List<MessageVariable>) -> String {
        val attribute = (element as? XmlAttributeValue)?.parent as? XmlAttribute
        if (attribute != null) return { argument, _ -> ":${attribute.name}=\"$FUNCTION($argument)\"" }
        return { argument, variables -> "{{ $FUNCTION($argument${MessageVariables.options(variables)}) }}" }
    }

    override fun variables(element: PsiElement): List<MessageVariable> {
        if (element !is XmlText) return emptyList()
        val placeholder = placeholderSyntax(element)
        return interpolations(element).orEmpty().map { (name, expression) -> MessageVariable(name, expression, placeholder(name)) }
    }

    /**
     * The interpolations of [text], name → expression, in order — `name` → `user.name` for
     * `{{ user.name }}` — or null when one cannot become a variable: not a plain path, or two
     * paths ending with the same name.
     */
    private fun interpolations(text: XmlText): Map<String, String>? {
        val variables = linkedMapOf<String, String>()
        for (match in HardcodedVueTextInspection.INTERPOLATION.findAll(text.text)) {
            val expression = match.groupValues[1].trim()
            if (!PLAIN_PATH.matches(expression)) return null
            val previous = variables.putIfAbsent(expression.substringAfterLast('.'), expression)
            if (previous != null && previous != expression) return null
        }
        return variables
    }

    /** vue-i18n's `{name}`, unless the module's preset says otherwise. */
    private fun placeholderSyntax(element: PsiElement): (String) -> String =
        if (ModulePresets.presetOf(element) == null) { name -> "{$name}" } else MessageVariables.placeholderSyntax(element)

    private companion object {
        const val FUNCTION = "\$t"
        val PLAIN_PATH = Regex("""[A-Za-z_$][\w$]*(\.[A-Za-z_$][\w$]*)*""")
        val WHITESPACE = Regex("\\s+")
    }
}
