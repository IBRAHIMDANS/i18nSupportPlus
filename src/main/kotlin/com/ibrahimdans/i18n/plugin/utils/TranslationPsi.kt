package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.extensions.localization.yaml.YamlTranslationPsi
import com.intellij.json.psi.JsonElementGenerator
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

/**
 * A key-value of a translation file, whatever its format: a JSON property or a YAML key-value.
 *
 * [literal] and [text] are the value when it is a string — a JSON string literal, a YAML scalar —
 * and null for an object, a number, an array.
 */
class TranslationEntry(
    val property: PsiElement,
    val name: String,
    val keyElement: PsiElement,
    val literal: PsiElement?,
    val text: String?,
)

/**
 * The PSI of a JSON or YAML translation file, read and written the same way by the code that
 * `plugin.xml` loads.
 *
 * YAML is an optional dependency (`ymlConfig.xml`): without the YAML plugin, its classes cannot be
 * loaded, and a single reference to one — an `is YAMLKeyValue`, a `YAMLKeyValue::class.java`, or a
 * `when` on types, which Kotlin compiles to a `typeSwitch` resolving **every** class it names on
 * its first call — throws NoClassDefFoundError, on JSON files as well. So nothing here names a
 * YAML class: an element is told YAML by its language id, which loads nothing, and only then
 * handed to [YamlTranslationPsi], which holds every YAML access and is never loaded otherwise.
 */
object TranslationPsi {

    /** The id of the YAML language — registered by the YAML plugin only. */
    private const val YAML_LANGUAGE = "yaml"

    fun isYaml(element: PsiElement): Boolean = element.language.id == YAML_LANGUAGE

    /** [element] read as a key-value of a translation file, or null when it is none. */
    fun entryOf(element: PsiElement): TranslationEntry? = when {
        element is JsonProperty -> {
            val literal = element.value as? JsonStringLiteral
            TranslationEntry(element, element.name, element.nameElement, literal, literal?.value)
        }
        isYaml(element) -> YamlTranslationPsi.entryOf(element)
        else -> null
    }

    /** The key-value holding [element] — itself too unless [strict] — or null. */
    fun propertyOf(element: PsiElement, strict: Boolean = true): PsiElement? =
        if (isYaml(element)) YamlTranslationPsi.keyValueOf(element, strict)
        else PsiTreeUtil.getParentOfType(element, JsonProperty::class.java, strict)

    /** The name of [element] when it is a key-value, else null. */
    fun nameOf(element: PsiElement): String? = when {
        element is JsonProperty -> element.name
        isYaml(element) -> YamlTranslationPsi.keyTextOf(element)
        else -> null
    }

    /** The names of the key-values next to [property], itself included, in order; null at the root. */
    fun siblingNames(property: PsiElement): List<String>? = when {
        property is JsonProperty -> (property.parent as? JsonObject)?.propertyList?.map { it.name }
        isYaml(property) -> YamlTranslationPsi.siblingKeys(property)
        else -> null
    }

    /** The key-values of the whole file, at every depth, in document order. */
    fun allEntries(file: PsiFile): List<TranslationEntry> = when {
        file is JsonFile -> PsiTreeUtil.findChildrenOfType(file, JsonProperty::class.java).mapNotNull(::entryOf)
        isYaml(file) -> YamlTranslationPsi.allEntries(file)
        else -> emptyList()
    }

    /** The top-level object of [file]: a JSON object, or the mapping of a YAML file's first document. */
    fun rootOf(file: PsiFile): PsiElement? = when {
        file is JsonFile -> file.topLevelValue as? JsonObject
        isYaml(file) -> YamlTranslationPsi.rootMapping(file)
        else -> null
    }

    /** Whether [element] is an object that can hold keys: a JSON object, a YAML mapping. */
    fun isContainer(element: PsiElement?): Boolean = when {
        element == null -> false
        element is JsonObject -> true
        isYaml(element) -> YamlTranslationPsi.isMapping(element)
        else -> false
    }

    /** The key-value named [name] directly inside [container], or null. */
    fun childOf(container: PsiElement, name: String): TranslationEntry? = when {
        container is JsonObject -> container.findProperty(name)?.let(::entryOf)
        isYaml(container) -> YamlTranslationPsi.childOf(container, name)
        else -> null
    }

    /** The value element of [property]: what [childOf] descends into. */
    fun valueOf(property: PsiElement): PsiElement? = when {
        property is JsonProperty -> property.value
        isYaml(property) -> YamlTranslationPsi.valueOf(property)
        else -> null
    }

    /**
     * The text of a value element — a JSON string literal, a YAML scalar or key-value — or the
     * element's raw text for anything else.
     */
    fun readValue(element: PsiElement): String? = when {
        element is JsonStringLiteral -> element.value
        isYaml(element) -> YamlTranslationPsi.readValue(element) ?: element.text
        else -> element.text
    }

    /** Replaces the value [element] stands for with [newValue]; nothing for an unknown element. */
    fun replaceValue(element: PsiElement, newValue: String, project: Project) {
        when {
            element is JsonStringLiteral -> element.replace(JsonElementGenerator(project).createStringLiteral(newValue))
            isYaml(element) -> YamlTranslationPsi.replaceValue(element, newValue, project)
        }
    }
}
