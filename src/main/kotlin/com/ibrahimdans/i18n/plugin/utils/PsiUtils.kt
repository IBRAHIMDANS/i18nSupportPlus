package com.ibrahimdans.i18n.plugin.utils

import com.intellij.json.psi.JsonProperty
import com.intellij.psi.PsiElement

/**
 * Gets element's type string
 */
fun PsiElement.type(): String = this.node?.elementType.toString()

/**
 * Deletes a translation property together with its separating comma —
 * plain JsonProperty.delete() leaves `{,"b":…}` / `{…,}` behind, corrupting
 * the file. YAML entries have no separator and are deleted as-is.
 * Must be called inside a write action.
 */
fun deletePropertyAndSeparator(property: PsiElement) {
    if (property is JsonProperty) {
        val prev = generateSequence(property.prevSibling) { it.prevSibling }.firstOrNull { it.text.isNotBlank() }
        val next = generateSequence(property.nextSibling) { it.nextSibling }.firstOrNull { it.text.isNotBlank() }
        when {
            prev?.text == "," -> prev.delete()
            next?.text == "," -> next.delete()
        }
    }
    property.delete()
}