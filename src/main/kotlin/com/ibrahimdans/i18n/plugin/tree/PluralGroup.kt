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

    /** True when [node] is an object made only of CLDR plural categories. */
    fun isPluralGroup(node: Tree<PsiElement>?): Boolean {
        if (node == null || !node.isTree()) return false
        val children = node.findChildren("")
        if (children.isEmpty()) return false
        return CLDR_CATEGORIES.count { node.findChild(it) != null } == children.size
    }
}
