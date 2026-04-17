package com.ibrahimdans.i18n.plugin.tree

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.intellij.psi.PsiElement

/**
 * Property reference represents PsiElement and it's path from translation root
 */
data class PropertyReference(
    val path: List<Literal>,
    val element: Tree<PsiElement>?,
    val unresolved: List<Literal>,
    val localizationSource: LocalizationSource,
    val isPlural: Boolean = false
)

/**
 * Key resolving utils
 */
interface CompositeKeyResolver<T> {

    fun resolve(compositeKey: List<Literal>, localizationSource: LocalizationSource, pluralSeparator: String): List<PropertyReference> =
        resolveCompositeKeys(compositeKey, localizationSource).flatMap {
            tryToResolvePlural(it, pluralSeparator, localizationSource)
        }

    /**
     * Resolves a composite key, returning all matching PropertyReferences.
     * When a wildcard `*` is encountered, expands to all children of the current node
     * and continues resolving the remaining key segments on each child.
     */
    fun resolveCompositeKeys(compositeKey: List<Literal>, localizationSource: LocalizationSource): List<PropertyReference> {
        val initial = listOf(PropertyReference(listOf(), localizationSource.tree, listOf(), localizationSource))
        return compositeKey.foldIndexed(initial) { index, refs, key ->
            refs.flatMap { propertyReference ->
                if (propertyReference.element != null && propertyReference.element.isTree() && propertyReference.unresolved.isEmpty()) {
                    if (key.text == "*") {
                        val isLast = index == compositeKey.size - 1
                        if (isLast) {
                            // Terminal wildcard: stay at current node (suppresses errors, keeps parent reference)
                            listOf(propertyReference.copy(path = propertyReference.path + key))
                        } else {
                            // Intermediate wildcard: expand to all children and continue resolution
                            val children = propertyReference.element.findChildren("")
                            if (children.isEmpty()) {
                                listOf(propertyReference.copy(path = propertyReference.path + key))
                            } else {
                                children.map { child ->
                                    propertyReference.copy(path = propertyReference.path + key, element = child)
                                }
                            }
                        }
                    } else {
                        val value = propertyReference.element.findChild(key.text)
                        if (value == null) listOf(propertyReference.copy(unresolved = propertyReference.unresolved + key))
                        else listOf(propertyReference.copy(path = propertyReference.path + key, element = value))
                    }
                } else listOf(propertyReference.copy(unresolved = propertyReference.unresolved + key))
            }
        }
    }

    /**
     * Returns a single PropertyReference by composite key (first match), or null if the key is unresolved in all files.
     * For wildcard-aware multi-result resolution, use [resolveCompositeKeys].
     */
    fun resolveCompositeKey(compositeKey: List<Literal>, localizationSource: LocalizationSource): PropertyReference? {
        return resolveCompositeKeys(compositeKey, localizationSource).firstOrNull()
    }

    /**
     * Fix for plural key reference.
     * #
     * Consider composite key 'sample:root.key.plural'
     * and corresponding tree in sample.json:
     * "root": {
     *   "key": {
     *      "plural-1": "plu1",
     *      "plural-2": "plu2",
     *      "plural-5": "plu5",
     *   }
     * }
     * PropertyReference for this case is PropertyReference(path = ["root", "key"], element[key], unresolved = ["plural"])
     */
    fun tryToResolvePlural(propertyReference: PropertyReference, pluralSeparator: String, localizationSource: LocalizationSource): List<PropertyReference> {
        return if (propertyReference.unresolved.size == 1 && propertyReference.element != null && propertyReference.element.isTree()) {
            val singleUnresolvedKey = propertyReference.unresolved[0]
            // Legacy i18next v3 format: key-1, key-2, key-5
            val numericPlurals = listOf("1", "2", "5").mapNotNull {
                propertyReference.element.findChild("${singleUnresolvedKey.text}${pluralSeparator}$it")
            }.map {
                PropertyReference(propertyReference.path + singleUnresolvedKey, it, listOf(), localizationSource, true)
            }
            // Modern i18next v4+ CLDR format: key_one, key_other, key_zero, key_two, key_few, key_many
            val cldrPlurals = listOf("one", "other", "zero", "two", "few", "many").mapNotNull {
                propertyReference.element.findChild("${singleUnresolvedKey.text}_$it")
            }.map {
                PropertyReference(propertyReference.path + singleUnresolvedKey, it, listOf(), localizationSource, true)
            }
            val plurals = numericPlurals.ifEmpty { cldrPlurals }
            if (plurals.isEmpty()) listOf(propertyReference) else plurals
        } else listOf(propertyReference)
    }

    /**
     * Returns PsiElement by composite key from file's root node
     */
    fun resolveCompositeKeyProperty(compositeKey: List<Literal>, localizationSource: LocalizationSource): Tree<PsiElement>? =
        resolveCompositeKey(compositeKey, localizationSource)?.let { ref -> if (ref.unresolved.isNotEmpty()) null else ref.element }

    /**
     * Returns keys at current composite key position
     */
    fun listCompositeKeyVariants(fixedKey: List<Literal>, prefix: String, localizationSource: LocalizationSource): List<Tree<PsiElement>> =
        resolveCompositeKeyProperty(fixedKey, localizationSource)?.findChildren(prefix) ?: listOf()
}