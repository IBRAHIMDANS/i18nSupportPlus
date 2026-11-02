package com.ibrahimdans.i18n.extensions.localization.properties

import com.ibrahimdans.i18n.plugin.utils.TranslationEntry
import com.intellij.lang.properties.parsing.PropertiesTokenTypes
import com.intellij.lang.properties.psi.PropertiesFile
import com.intellij.lang.properties.psi.Property
import com.intellij.lang.properties.psi.PropertyKeyValueFormat
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

/**
 * The `.properties` half of [com.ibrahimdans.i18n.plugin.utils.TranslationPsi], loaded only once an
 * element is known to be of the Properties language: the Properties plugin is an optional
 * dependency, and naming one of its classes from the shared code would fail without it.
 */
internal object PropertiesTranslationPsi {

    /** The id of the Properties language — registered by the Properties plugin only. */
    const val LANGUAGE = "Properties"

    /**
     * [element] read as a key of a `.properties` file: the whole key (`user.name`) as its name, its
     * value element — the key itself when the value is empty — as the literal the inspections mark.
     */
    fun entryOf(element: PsiElement): TranslationEntry? {
        val property = element as? Property ?: return null
        val key = property.unescapedKey ?: return null
        val keyElement = property.node.findChildByType(PropertiesTokenTypes.KEY_CHARACTERS)?.psi ?: property
        val literal = PropertiesElementTree.valueOf(property) ?: property
        return TranslationEntry(property, key, keyElement, literal, property.unescapedValue.orEmpty())
    }

    /** Every key of [file] with its value, each key whole: one level, as `.`-joined paths spell it. */
    fun collectLeaves(file: PsiFile, result: MutableMap<List<String>, String>) {
        (file as? PropertiesFile)?.properties?.forEach { property ->
            val key = property.unescapedKey ?: return@forEach
            result.putIfAbsent(listOf(key), property.unescapedValue.orEmpty())
        }
    }

    /** The key [element] belongs to, whole, or null outside a key. */
    fun keyOf(element: PsiElement): String? = propertyOf(element)?.unescapedKey

    /** The text of the key [element] belongs to, its escapes and continuations read; null outside a key. */
    fun readValue(element: PsiElement): String? = propertyOf(element)?.unescapedValue

    /** Replaces the value of the key [element] belongs to with [newValue], escaped as the format needs. */
    fun replaceValue(element: PsiElement, newValue: String) {
        propertyOf(element)?.setValue(newValue, PropertyKeyValueFormat.MEMORY)
    }

    private fun propertyOf(element: PsiElement): Property? = PsiTreeUtil.getParentOfType(element, Property::class.java, false)
}
