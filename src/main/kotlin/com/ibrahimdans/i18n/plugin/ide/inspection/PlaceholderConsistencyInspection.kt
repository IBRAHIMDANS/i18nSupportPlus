package com.ibrahimdans.i18n.plugin.ide.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

private val PLACEHOLDER_REGEX = Regex("""\{[\w\d_]+\}|%[0-9]*\$?[sd]""")
private val UNCLOSED_BRACE_REGEX = Regex("""\{[^}]*$""", RegexOption.MULTILINE)
private val UNOPENED_BRACE_REGEX = Regex("""^[^{]*\}""", RegexOption.MULTILINE)

private fun extractPlaceholders(text: String): Set<String> =
    PLACEHOLDER_REGEX.findAll(text).map { it.value }.toSet()

private fun isSyntacticallyValid(text: String): Boolean =
    !UNCLOSED_BRACE_REGEX.containsMatchIn(text) && !UNOPENED_BRACE_REGEX.containsMatchIn(text)

private fun siblingFileTranslations(file: PsiFile, referenceName: String): Map<String, String> {
    val dir = file.virtualFile?.parent ?: return emptyMap()
    val refVFile = dir.findChild("$referenceName.json")
        ?: dir.findChild("$referenceName.yaml")
        ?: dir.findChild("$referenceName.yml")
        ?: return emptyMap()
    val refPsiFile = PsiManager.getInstance(file.project).findFile(refVFile) ?: return emptyMap()
    return flattenTranslations(refPsiFile)
}

private fun flattenTranslations(file: PsiFile): Map<String, String> {
    val result = mutableMapOf<String, String>()
    when (file) {
        is com.intellij.json.psi.JsonFile -> {
            val root = PsiTreeUtil.getChildOfType(file, com.intellij.json.psi.JsonObject::class.java)
            root?.let { collectJsonProperties(it, "", result) }
        }
        is YAMLFile -> {
            for (doc in file.documents) {
                val mapping = PsiTreeUtil.getChildOfType(doc, YAMLMapping::class.java)
                mapping?.let { collectYamlKeyValues(it, "", result) }
            }
        }
    }
    return result
}

private fun collectJsonProperties(
    obj: com.intellij.json.psi.JsonObject,
    prefix: String,
    result: MutableMap<String, String>
) {
    for (prop in obj.propertyList) {
        val key = if (prefix.isEmpty()) prop.name else "$prefix.${prop.name}"
        when (val value = prop.value) {
            is JsonStringLiteral -> result[key] = value.value
            is com.intellij.json.psi.JsonObject -> collectJsonProperties(value, key, result)
            else -> {}
        }
    }
}

private fun collectYamlKeyValues(mapping: YAMLMapping, prefix: String, result: MutableMap<String, String>) {
    for (kv in mapping.keyValues) {
        val keyText = kv.keyText
        val key = if (prefix.isEmpty()) keyText else "$prefix.$keyText"
        when (val value = kv.value) {
            is YAMLScalar -> result[key] = value.textValue
            is YAMLMapping -> collectYamlKeyValues(value, key, result)
            else -> {}
        }
    }
}

private fun buildJsonKey(element: PsiElement): String {
    val parts = mutableListOf<String>()
    var current: PsiElement? = element
    while (current != null) {
        if (current is JsonProperty) parts.add(0, current.name)
        current = current.parent
    }
    return parts.joinToString(".")
}

private fun buildYamlKey(element: PsiElement): String {
    val parts = mutableListOf<String>()
    var current: PsiElement? = element.parent
    while (current != null) {
        if (current is YAMLKeyValue) parts.add(0, current.keyText)
        current = current.parent
    }
    return parts.joinToString(".")
}

class PlaceholderConsistencyInspection : LocalInspectionTool() {

    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = "I18nPlaceholderConsistency"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file
        val refTranslations: Map<String, String> by lazy { siblingFileTranslations(file, "en") }

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                when (element) {
                    is JsonProperty -> checkJsonProperty(element, holder, refTranslations)
                    is YAMLKeyValue -> checkYamlKeyValue(element, holder, refTranslations)
                }
            }
        }
    }

    private fun checkJsonProperty(
        property: JsonProperty,
        holder: ProblemsHolder,
        refTranslations: Map<String, String>
    ) {
        val literal = property.value as? JsonStringLiteral ?: return
        val value = literal.value
        if (!isSyntacticallyValid(value)) {
            holder.registerProblem(literal, "Placeholder syntax error: unbalanced braces in translation value")
            return
        }
        val currentPlaceholders = extractPlaceholders(value)
        if (currentPlaceholders.isEmpty()) return
        val key = buildJsonKey(property)
        val refValue = refTranslations[key] ?: return
        val refPlaceholders = extractPlaceholders(refValue)
        for (missing in refPlaceholders - currentPlaceholders) {
            holder.registerProblem(literal, "Placeholder '$missing' present in reference locale is missing here")
        }
    }

    private fun checkYamlKeyValue(
        keyValue: YAMLKeyValue,
        holder: ProblemsHolder,
        refTranslations: Map<String, String>
    ) {
        val scalar = keyValue.value as? YAMLScalar ?: return
        val value = scalar.textValue
        if (!isSyntacticallyValid(value)) {
            holder.registerProblem(scalar, "Placeholder syntax error: unbalanced braces in translation value")
            return
        }
        val currentPlaceholders = extractPlaceholders(value)
        if (currentPlaceholders.isEmpty()) return
        val key = buildYamlKey(scalar)
        val refValue = refTranslations[key] ?: return
        val refPlaceholders = extractPlaceholders(refValue)
        for (missing in refPlaceholders - currentPlaceholders) {
            holder.registerProblem(scalar, "Placeholder '$missing' present in reference locale is missing here")
        }
    }
}
