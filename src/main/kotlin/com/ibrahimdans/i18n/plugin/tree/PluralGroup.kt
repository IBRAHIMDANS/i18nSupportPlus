package com.ibrahimdans.i18n.plugin.tree

import com.intellij.psi.PsiElement

/**
 * Recognises a node whose children are *all* CLDR plural categories:
 *
 * ```json
 * { "box": { "one": "1 boîte", "other": "%{count} boîtes" } }
 * ```
 *
 * i18n-js, ruby-i18n and vue-i18n pluralize through such a nested object, where i18next
 * uses flat suffixes (`box_one`, `box_other`) that [CompositeKeyResolver.tryToResolvePlural]
 * already handles. Without this, `t('common.box')` resolves onto the object and is reported
 * as "Reference to object" — a false positive on every plural key of those frameworks.
 *
 * Requiring *every* child to be a category is what keeps ordinary objects out: a namespace
 * holding a key named `other` stays a namespace as long as it holds anything else.
 */
object PluralGroup {

    /**
     * The six CLDR categories. `CompositeKeyResolver` carries the same list for the flat
     * `key_one` form; the two are deliberately not shared, as its order drives which
     * reference wins there and changing it would alter resolution.
     */
    private val CLDR_CATEGORIES = listOf("zero", "one", "two", "few", "many", "other")

    /** The branch that stands for the group when one has to be picked, as ICU rendering does. */
    private const val PREFERRED_CATEGORY = "other"

    /** True when [node] is an object made only of CLDR plural categories. */
    fun isPluralGroup(node: Tree<PsiElement>?): Boolean {
        if (node == null || !node.isTree()) return false
        val children = node.findChildren("")
        if (children.isEmpty()) return false
        return CLDR_CATEGORIES.count { node.findChild(it) != null } == children.size
    }

    /**
     * The node to *display* for [node]: itself when it already holds a value, or the branch
     * standing for the whole group when it is a plural object — `other`, falling back to the
     * first category declared. That is the rule `IcuMessageRenderer` applies to ICU messages,
     * so both pluralization styles read the same way in folding, hints and inlay hints.
     *
     * Returns null when there is nothing sensible to show — an ordinary object, or a group
     * whose branch is itself an object: a value is displayed, never a sub-tree.
     *
     * Folding, hints and inlay hints all go through here rather than testing `isLeaf`
     * themselves, so a key resolved as a plural group cannot end up annotated as resolved
     * while showing nothing — which is exactly what happened when i18n-js plurals started
     * resolving.
     */
    fun displayableValue(node: Tree<PsiElement>?): Tree<PsiElement>? {
        if (node == null) return null
        if (node.isLeaf()) return node
        if (!isPluralGroup(node)) return null
        val branch = node.findChild(PREFERRED_CATEGORY)
            ?: CLDR_CATEGORIES.firstNotNullOfOrNull { node.findChild(it) }
        return branch?.takeIf { it.isLeaf() }
    }
}
