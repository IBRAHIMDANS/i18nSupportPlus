package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.ibrahimdans.i18n.plugin.utils.ModulePresets
import com.ibrahimdans.i18n.plugin.utils.toBoolean
import com.intellij.lang.Language
import com.intellij.lang.javascript.patterns.JSPatterns
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSEmbeddedContent
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSThisExpression
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText

internal class JsxTranslationExtractor : TranslationExtractor {
    /**
     * True for a text or attribute of a JSX tag holding no other tag, in any JavaScript dialect:
     * CRA and Vite projects write JSX in `.js` files, which a test on the `.jsx` / `.tsx` extension
     * left without extraction or inspection. A tag under a JavaScript file can only be JSX; a Vue
     * template or an HTML fragment injected in a string is a file of another language.
     */
    override fun canExtract(element: PsiElement): Boolean {
        val javaScript = Language.findLanguageByID("JavaScript") ?: return false
        if (!element.containingFile.language.isKindOf(javaScript)) return false
        return PsiTreeUtil.getParentOfType(element, XmlTag::class.java)?.let {
            !PsiTreeUtil.findChildOfType(it, XmlTag::class.java).toBoolean()
        } ?: false
    }

    override fun isExtracted(element: PsiElement): Boolean {
        if (!element.isJs()) return false
        if (!JSPatterns.jsArgument("t", 0).accepts(element.parent)) return false
        return isDirectOrConfiguredCall(element)
    }

    private fun isDirectOrConfiguredCall(element: PsiElement): Boolean {
        val callExpr = PsiTreeUtil.getParentOfType(element, JSCallExpression::class.java) ?: return true
        val refExpr = callExpr.methodExpression as? JSReferenceExpression ?: return true
        val qualifier = refExpr.qualifier ?: return true
        if (qualifier is JSThisExpression) return true
        val fnNames = Extensions.TECHNOLOGY.extensionList.flatMap { it.translationFunctionNames() }
        return refExpr.text in fnNames
    }

    override fun text(element: PsiElement): String {
        if (element.parent is XmlAttributeValue) return element.text
        val tag = PsiTreeUtil.getParentOfType(element, XmlTag::class.java) ?: return element.text
        val variables = variables(tag)
        if (variables.isNullOrEmpty()) return tag.value.textElements.joinToString(" ") { it.text }
        // `Hello {name}!` reads `Hello {{name}}!` — or `{name}`, `%{name}`, see [placeholderSyntax]:
        // each expression becomes the placeholder of its variable. Read from the source, as the
        // spaces between the parts are not part of them.
        val parts = content(tag)
        val start = parts.first().textRange.startOffset
        val source = StringBuilder(tag.containingFile.text.substring(start, parts.last().textRange.endOffset))
        val placeholder = placeholderSyntax(tag)
        for (part in parts.filterIsInstance<JSEmbeddedContent>().asReversed()) {
            val name = variables.entries.first { it.value == expression(part)?.text }.key
            source.replace(part.textRange.startOffset - start, part.textRange.endOffset - start, placeholder(name))
        }
        return source.toString().replace(WHITESPACE, " ").trim()
    }

    override fun textRange(element: PsiElement): TextRange {
        if (element.parent is XmlAttributeValue) return element.parent.textRange
        val tag = PsiTreeUtil.getParentOfType(element, XmlTag::class.java) ?: return element.textRange
        // With expressions, the call replaces them along with the text around them.
        val parts = if (variables(tag).isNullOrEmpty()) tag.value.textElements.toList() else content(tag)
        if (parts.isEmpty()) return element.textRange
        return TextRange(parts.first().textRange.startOffset, parts.last().textRange.endOffset)
    }

    override fun template(element: PsiElement): (argument: String) -> String {
        val tag = if (element.parent is XmlAttributeValue) null else PsiTreeUtil.getParentOfType(element, XmlTag::class.java)
        val variables = tag?.let { variables(it) }
        if (variables.isNullOrEmpty()) return { "{i18n.t($it)}" }
        val options = variables.entries.joinToString(", ") { (name, expression) ->
            if (name == expression) name else "$name: $expression"
        }
        return { "{i18n.t($it, { $options })}" }
    }

    /**
     * The JSX expressions of [tag]'s text — `name` for `{name}`, `name` → `user.name` for
     * `{user.name}` — in order, or null when one cannot become a variable of the message: a call,
     * a condition, a comment, or two expressions ending with the same name. Empty without any.
     */
    internal fun variables(tag: XmlTag): Map<String, String>? {
        val variables = linkedMapOf<String, String>()
        for (part in content(tag).filterIsInstance<JSEmbeddedContent>()) {
            val reference = expression(part) as? JSReferenceExpression ?: return null
            if (!reference.isPlainPath()) return null
            val name = reference.referenceName ?: return null
            val previous = variables.putIfAbsent(name, reference.text)
            if (previous != null && previous != reference.text) return null
        }
        return variables
    }

    /**
     * How the technology of the module holding [element] writes a variable in a message: `{name}`
     * where it interpolates single braces (lingui, react-intl, vue-i18n, svelte-i18n — the list
     * [InterpolationArgumentsInspection] reads calls with), `%{name}` for i18n-js, and i18next's
     * `{{name}}` otherwise, a module without a preset included. A placeholder in another
     * technology's syntax is printed as is: `Hello {{name}}` on screen.
     */
    private fun placeholderSyntax(element: PsiElement): (name: String) -> String {
        val preset = ModulePresets.presetOf(element)
        return when {
            preset == I18N_JS -> { name -> "%{$name}" }
            preset != null && preset in InterpolationArgumentsInspection.SINGLE_BRACE_FRAMEWORKS -> { name -> "{$name}" }
            else -> { name -> "{{$name}}" }
        }
    }

    /** The text and the expressions between [tag]'s start and end tags, in order. */
    private fun content(tag: XmlTag): List<PsiElement> =
        tag.children.filter { it is XmlText || it is JSEmbeddedContent }

    private fun expression(embedded: JSEmbeddedContent): JSExpression? =
        PsiTreeUtil.getChildOfType(embedded, JSExpression::class.java)

    /** `name`, `user.name`: references all the way down, so the expression reads as a value. */
    private fun JSReferenceExpression.isPlainPath(): Boolean {
        val qualifier = qualifier ?: return true
        return qualifier is JSReferenceExpression && qualifier.isPlainPath()
    }

    private fun PsiElement.isJs(): Boolean {
        val jsLang = Language.findLanguageByID("JavaScript") ?: return false
        return this.language.isKindOf(jsLang)
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        const val I18N_JS = "i18n-js"
    }
}
