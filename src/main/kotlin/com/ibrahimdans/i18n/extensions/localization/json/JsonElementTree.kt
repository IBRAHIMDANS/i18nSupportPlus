package com.ibrahimdans.i18n.extensions.localization.json

import com.ibrahimdans.i18n.plugin.tree.Tree
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.json.JsonElementTypes
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil

/**
 * Tree wrapper around json psi tree
 *
 * The root object's `$schema` property is no key: a Paraglide (inlang) message file starts with
 * `"$schema": "https://inlang.com/schema/inlang-message-format"`, a JSON Schema convention any file
 * may follow. Listing it put a `$schema` key in the tool window, the completion, the statistics.
 * [entries] and [findChildren] skip it at the root only; [findChild] still finds it when asked by
 * name, and a nested `$schema` stays a key like any other.
 */
class JsonElementTree(val element: PsiElement): Tree<PsiElement> {
    override fun value(): PsiElement = element
    override fun isTree(): Boolean = element is JsonObject
    override fun findChild(name: String): Tree<PsiElement>? =
        (element as JsonObject).findProperty(name)?.value?.let{ JsonElementTree(it) }
    override fun findChildren(prefix: String): List<Tree<PsiElement>> =
        element
            .node
            .getChildren(TokenSet.create(JsonElementTypes.PROPERTY))
            .asList()
            .map {item -> item.firstChildNode.psi}
            .filter {it.text.unQuote().startsWith(prefix)}
            .filterNot { isSchema(it.text.unQuote()) }
            .map {JsonElementTree(it)}

    // Names spelled as findChildren spells them (the key's text, unquoted), so both line up.
    override fun entries(): List<Pair<String, Tree<PsiElement>>> =
        (element as? JsonObject)
            ?.propertyList
            ?.mapNotNull { property ->
                val name = property.nameElement.text.unQuote()
                if (isSchema(name)) null else property.value?.let { name to JsonElementTree(it) }
            }
            ?: emptyList()

    /** Whether [name] is the root's `$schema`, which [entries] and [findChildren] leave out. */
    private fun isSchema(name: String): Boolean = name == SCHEMA && element.parent is JsonFile

    companion object {
        private const val SCHEMA = "\$schema"

        /**
         * Whether [property] is the `$schema` of a file's root object: no key, for the readers that
         * walk the JSON PSI themselves rather than this tree.
         */
        fun isRootSchema(property: JsonProperty): Boolean = property.name == SCHEMA && property.parent?.parent is JsonFile

        /**
         * Creates instance of JsonElementTree
         */
        fun create(file: PsiElement): JsonElementTree? =
            PsiTreeUtil
                .getChildOfType(file, JsonObject::class.java)
                ?.let{ JsonElementTree(it)}
    }
}


/**
 * Tree wrapper around js psi tree
 */
//class JsElementTree(val element: PsiElement): PsiElementTree() {
//    override fun value(): PsiElement = element
//    override fun isTree(): Boolean = element is JSObjectLiteralExpression
//    override fun findChild(name: String): Tree<PsiElement>? {
//        return (element as? JSObjectLiteralExpression)
//            ?.findProperty(name)
//            ?.value
//            ?.let { child ->
//                if (child is JSReferenceExpression) {
//                    resolveReferenceChain(child, 10)?.let {
//                        JsElementTree(it)
//                    }
//                } else {
//                    JsElementTree(child)
//                }
//            }
//    }
//    private fun resolveReferenceChain(element: JSReferenceExpression, depth: Int):JSObjectLiteralExpression? {
//        val res = element.reference?.resolve()
//        if (res is JSVariable) {
//            val rvalue = res.children[0]
//            if (rvalue is JSReferenceExpression && depth > 0) {
//                return resolveReferenceChain(rvalue, depth-1)
//            }
//            return rvalue as? JSObjectLiteralExpression
//        } else {
//            return res as? JSObjectLiteralExpression
//        }
//    }
//    override fun findChildren(regex: Regex): List<Tree<PsiElement>> {
//        return listOf(element.node)
//            .map { item -> item.firstChildNode.psi }
//            .filter { item -> item != null && item.text.unQuote().matches(regex) }
//            .map { item -> JsElementTree(item) }
//    }
//    companion object {
//        /**
//         * Creates instance of JsElementTree
//         */
//        fun create(file: PsiElement): JsElementTree? = JsElementTree(file)
//    }
//}
