package com.ibrahimdans.i18n.extensions.localization.yaml

import com.ibrahimdans.i18n.plugin.utils.TranslationEntry
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.YAMLElementGenerator
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Every YAML PSI access of the code that `plugin.xml` loads, behind
 * [com.ibrahimdans.i18n.plugin.utils.TranslationPsi].
 *
 * Only ever called for an element of the YAML language, which exists only when the YAML plugin
 * does: this object, and the YAML classes it names, are therefore never loaded without it. Its
 * members take and return platform types, so that no caller has to name a YAML class either.
 *
 * A YAML file is read up to its first document, as key resolution reads it ([YamlElementTree]).
 */
internal object YamlTranslationPsi {

    fun entryOf(element: PsiElement): TranslationEntry? {
        val keyValue = element as? YAMLKeyValue ?: return null
        val key = keyValue.key ?: return null
        val scalar = keyValue.value as? YAMLScalar
        return TranslationEntry(keyValue, keyValue.keyText, key, scalar, scalar?.textValue)
    }

    fun keyValueOf(element: PsiElement, strict: Boolean): PsiElement? =
        PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java, strict)

    fun keyTextOf(element: PsiElement): String? = (element as? YAMLKeyValue)?.keyText

    fun siblingKeys(element: PsiElement): List<String>? =
        ((element as? YAMLKeyValue)?.parent as? YAMLMapping)?.keyValues?.map { it.keyText }

    fun allEntries(file: PsiFile): List<TranslationEntry> {
        val root = rootMapping(file) ?: return emptyList()
        return PsiTreeUtil.findChildrenOfType(root, YAMLKeyValue::class.java).mapNotNull(::entryOf)
    }

    fun rootMapping(file: PsiFile): PsiElement? =
        (file as? YAMLFile)?.documents?.firstOrNull()?.topLevelValue as? YAMLMapping

    fun isMapping(element: PsiElement): Boolean = element is YAMLMapping

    fun childOf(container: PsiElement, name: String): TranslationEntry? =
        (container as? YAMLMapping)?.getKeyValueByKey(name)?.let(::entryOf)

    fun valueOf(element: PsiElement): PsiElement? = (element as? YAMLKeyValue)?.value

    /** A scalar's text, or a key-value's value text; null for any other element. */
    fun readValue(element: PsiElement): String? = when (element) {
        is YAMLScalar -> element.textValue
        is YAMLKeyValue -> element.valueText
        else -> null
    }

    /** Replaces the value of a scalar or of a key-value with [newValue]. */
    fun replaceValue(element: PsiElement, newValue: String, project: Project) {
        val keyValue = element as? YAMLKeyValue ?: PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java) ?: return
        val replacement = YAMLElementGenerator.getInstance(project).createYamlKeyValue(keyValue.keyText, newValue).value ?: return
        val current = if (element is YAMLScalar) element else keyValue.value
        current?.replace(replacement)
    }

    /** Every string leaf of [file]'s first document into [result], keyed by its path of key names. */
    fun collectLeaves(file: PsiFile, result: MutableMap<List<String>, String>) {
        (rootMapping(file) as? YAMLMapping)?.let { collect(it, emptyList(), result) }
    }

    private fun collect(mapping: YAMLMapping, prefix: List<String>, result: MutableMap<List<String>, String>) {
        for (keyValue in mapping.keyValues) {
            val path = prefix + keyValue.keyText
            when (val value = keyValue.value) {
                is YAMLScalar -> result[path] = value.textValue
                is YAMLMapping -> collect(value, path, result)
                else -> {}
            }
        }
    }
}
