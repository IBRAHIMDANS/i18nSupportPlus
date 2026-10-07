package com.ibrahimdans.i18n.plugin.ide.inspection

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * The keys a JSON or YAML translation file holds, read straight from its PSI.
 *
 * Several inspections compare a locale file against the same namespace in the reference locale:
 * placeholders that went missing, keys that went missing. They need the same three things — the
 * reference file, its keys with their values, and the key a given element stands for — and used
 * to carry private copies of them. A key here is the plain path of property names from the root,
 * joined with `.`, without namespace: it is a file-local spelling, not the one the code writes.
 */
internal object TranslationFileKeys {

    /**
     * The file holding the same namespace as [file] in the reference locale, or null when [file]
     * is not a translation file, has no counterpart, or is itself the reference.
     *
     * Found through the source scan rather than as a sibling `en.json`: on `locales/fr/common.json`
     * the reference is `locales/en/common.json`, which no sibling lookup could reach.
     */
    fun referenceFileOf(file: PsiFile): PsiFile? {
        val source = TranslationFileScope.sourceOf(file) ?: return null
        val referenceLocale = TranslationFileScope.referenceLocaleFor(file, source)
        val reference = TranslationFileScope.counterpartOf(file, source, referenceLocale) ?: return null
        return reference.tree?.value()?.containingFile
    }

    /** The translations of the same namespace in the reference locale, keyed as [flattenTranslations] does. */
    fun referenceTranslations(file: PsiFile): Map<String, String> =
        referenceFileOf(file)?.let(::flattenTranslations) ?: emptyMap()

    /** Every string leaf of [file], keyed by its `.`-joined path. */
    fun flattenTranslations(file: PsiFile): Map<String, String> =
        translationLeaves(file).mapKeys { (path, _) -> path.joinToString(".") }

    /**
     * Every string leaf of [file], keyed by its path of property names.
     *
     * The path is kept as a list for callers that walk the tree again: a flat key such as
     * `"app.title"` is one segment here, where the joined spelling could not tell it from
     * `app` → `title`. Non-string leaves (numbers, arrays, null) are skipped.
     *
     * A YAML file is read up to its first document only, as key resolution reads it
     * (`YamlElementTree`): a key of a later `---` document resolves nowhere — and i18next's YAML
     * loaders refuse such a file — so listing it would report, offer or type a key the code
     * cannot reach.
     */
    fun translationLeaves(file: PsiFile): Map<List<String>, String> {
        val result = linkedMapOf<List<String>, String>()
        when (file) {
            is JsonFile -> PsiTreeUtil.getChildOfType(file, JsonObject::class.java)
                ?.let { collectJsonProperties(it, emptyList(), result) }
            is YAMLFile -> file.documents.firstOrNull()
                ?.let { PsiTreeUtil.getChildOfType(it, YAMLMapping::class.java) }
                ?.let { collectYamlKeyValues(it, emptyList(), result) }
        }
        return result
    }

    /**
     * The path of property names from the root down to [element], [element] included when it is
     * a property itself — a JSON property, a YAML key-value, or any element inside one (its value
     * literal, its name).
     */
    fun pathOf(element: PsiElement): List<String> {
        val parts = ArrayDeque<String>()
        var current: PsiElement? = element
        while (current != null && current !is PsiFile) {
            when (current) {
                is JsonProperty -> parts.addFirst(current.name)
                is YAMLKeyValue -> parts.addFirst(current.keyText)
            }
            current = current.parent
        }
        return parts.toList()
    }

    /** [pathOf] [element], joined with `.` — the spelling [flattenTranslations] keys its map with. */
    fun keyOf(element: PsiElement): String = pathOf(element).joinToString(".")

    private fun collectJsonProperties(obj: JsonObject, prefix: List<String>, result: MutableMap<List<String>, String>) {
        for (prop in obj.propertyList) {
            val path = prefix + prop.name
            when (val value = prop.value) {
                is JsonStringLiteral -> result[path] = value.value
                is JsonObject -> collectJsonProperties(value, path, result)
                else -> {}
            }
        }
    }

    private fun collectYamlKeyValues(mapping: YAMLMapping, prefix: List<String>, result: MutableMap<List<String>, String>) {
        for (kv in mapping.keyValues) {
            val path = prefix + kv.keyText
            when (val value = kv.value) {
                is YAMLScalar -> result[path] = value.textValue
                is YAMLMapping -> collectYamlKeyValues(value, path, result)
                else -> {}
            }
        }
    }
}
