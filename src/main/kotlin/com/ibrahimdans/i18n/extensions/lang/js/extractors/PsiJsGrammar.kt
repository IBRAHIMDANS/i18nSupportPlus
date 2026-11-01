package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.parser.CodeNode
import com.ibrahimdans.i18n.plugin.rules.CodeGrammar
import com.ibrahimdans.i18n.plugin.utils.PsiCodeNode
import com.ibrahimdans.i18n.plugin.utils.hostFile
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.lang.javascript.psi.JSArgumentList
import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSConditionalExpression
import com.intellij.lang.javascript.psi.JSEmbeddedContent
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSNewExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSParenthesizedExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag

/**
 * [CodeGrammar] over the JavaScript PSI, JSX included: what `RuleKeyExtractor` asks, answered off the
 * elements a [PsiCodeNode] wraps — never off the generic node tree, which the PSI answers faster
 * itself. `i18n-check`'s `TsxGrammar` answers the same questions off tree-sitter.
 *
 * Asked of every element the annotator visits: nothing here walks further than the parent, the
 * call or the tag at hand, and the file's imports are cached on the file.
 */
internal object PsiJsGrammar : CodeGrammar {

    private fun psi(node: CodeNode?): PsiElement? = (node as? PsiCodeNode)?.element

    override fun isStringLiteral(node: CodeNode): Boolean = (psi(node) as? JSLiteralExpression)?.isQuotedLiteral == true

    override fun stringValue(node: CodeNode): String = (psi(node) as JSLiteralExpression).let { it.stringValue ?: it.text.unQuote() }

    override fun argumentOf(node: CodeNode): CodeGrammar.CallArgument? {
        var child = psi(node) ?: return null
        var current = child.parent
        while (current != null && current !is JSArgumentList) {
            when (current) {
                is JSParenthesizedExpression -> {}
                is JSConditionalExpression -> if (current.condition === child) return null
                else -> return null
            }
            child = current
            current = current.parent
        }
        val arguments = current as? JSArgumentList ?: return null
        val index = arguments.arguments.indexOfFirst { it === child }.takeIf { it >= 0 } ?: return null
        val call = arguments.parent as? JSCallExpression ?: return null
        if (call is JSNewExpression) return null
        return CodeGrammar.CallArgument(PsiCodeNode(call), index)
    }

    override fun arguments(call: CodeNode): List<CodeNode> = (psi(call) as? JSCallExpression)?.arguments?.map(::PsiCodeNode).orEmpty()

    override fun callee(call: CodeNode): String? = ((psi(call) as? JSCallExpression)?.methodExpression as? JSReferenceExpression)?.text

    override fun propertyOf(node: CodeNode): CodeGrammar.ObjectProperty? {
        val element = psi(node) ?: return null
        val property = element.parent as? JSProperty ?: return null
        if (property.value !== element) return null
        val owner = property.parent as? JSObjectLiteralExpression ?: return null
        return CodeGrammar.ObjectProperty(PsiCodeNode(owner), property.name ?: return null)
    }

    override fun property(obj: CodeNode?, name: String): CodeNode? {
        val property = (psi(obj) as? JSObjectLiteralExpression)?.findProperty(name) ?: return null
        return PsiCodeNode(property.value ?: property)
    }

    override fun strings(node: CodeNode?): List<String> = when (val element = psi(node)) {
        is JSLiteralExpression -> if (element.isQuotedLiteral) listOfNotNull(element.stringValue) else emptyList()
        is JSArrayLiteralExpression -> element.expressions.filterIsInstance<JSLiteralExpression>().filter { it.isQuotedLiteral }.mapNotNull { it.stringValue }
        else -> emptyList()
    }

    /** The specifiers the host file imports — the host, for a fragment injected into a Svelte or Vue component — cached until the file changes. */
    override fun imports(node: CodeNode): Set<String> {
        val file = psi(node)?.hostFile() ?: return emptySet()
        return CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(IMPORT_SPECIFIER.findAll(file.text).mapTo(mutableSetOf()) { it.groupValues[1] }, file)
        }
    }

    override fun attributeOf(node: CodeNode): CodeGrammar.Attribute? {
        val value = psi(node) as? XmlAttributeValue ?: return null
        val attribute = value.parent as? XmlAttribute ?: return null
        val tag = attribute.parent as? XmlTag ?: return null
        return CodeGrammar.Attribute(PsiCodeNode(tag), tag.name, attribute.name)
    }

    override fun attributeText(node: CodeNode): String = (psi(node) as XmlAttributeValue).value

    override fun attribute(tag: CodeNode, name: String): CodeGrammar.AttributeValue? {
        val attribute = (psi(tag) as? XmlTag)?.getAttribute(name) ?: return null
        val value = attribute.valueElement ?: return CodeGrammar.AttributeValue.Bare
        val embedded = value.children.firstOrNull { it is JSEmbeddedContent } ?: return CodeGrammar.AttributeValue.Text(value.value)
        return CodeGrammar.AttributeValue.Expression(PsiCodeNode(PsiTreeUtil.getChildOfType(embedded, JSExpression::class.java) ?: embedded))
    }

    private val IMPORT_SPECIFIER = Regex("""(?:from|require\(|import)\s*['"]([^'"]+)['"]""")
}
