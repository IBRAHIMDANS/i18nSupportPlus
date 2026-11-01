package com.ibrahimdans.i18n.extensions.localization.arb

import com.ibrahimdans.i18n.extensions.localization.json.JsonInsertion
import com.intellij.json.psi.JsonElementGenerator
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * The `@key` block an ARB catalogue writes after `key` (`"@greeting": {"placeholders": {"name": {}}}`):
 * `flutter gen-l10n` reads the placeholders of a message from it. A key of the plugin's keeps its
 * block: created with it, renamed and deleted with it.
 *
 * A block is created only in a catalogue that already describes its keys this way, the template
 * (`app_en.arb`): the translations (`app_fr.arb`) usually hold none, and gen-l10n ignores theirs.
 */
object ArbMetadata {

    private const val PREFIX = "@"
    private const val EXTENSION = "arb"

    /** `{name}` and `{count, plural, …}` name a placeholder; `{one item}`, an ICU branch, does not. */
    private val PLACEHOLDER = Regex("""\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*[,}]""")

    fun isArb(file: PsiFile?): Boolean = file?.name?.substringAfterLast('.', "") == EXTENSION

    /** The placeholders [value] names, in order, each once. */
    fun placeholdersOf(value: String): List<String> = PLACEHOLDER.findAll(value).map { it.groupValues[1] }.distinct().toList()

    /** The block of a key whose value is [value]: `{}`, or the placeholders it names. */
    fun blockOf(value: String): String {
        val placeholders = placeholdersOf(value)
        if (placeholders.isEmpty()) return "{}"
        return placeholders.joinToString(", ", "{\"placeholders\": {", "}}") { "\"$it\": {}" }
    }

    /**
     * The `@key` block of [property], a key of an ARB catalogue, or null: [property] is no key of a
     * catalogue's top level, is metadata itself, or has no block.
     */
    fun blockOf(property: PsiElement): JsonProperty? {
        val key = catalogueKey(property) ?: return null
        return (key.parent as JsonObject).findProperty(PREFIX + key.name)
    }

    /**
     * Writes the `@key` block of the key [name], just added to [catalogue] with [value], right
     * after it — when [catalogue] is the top level of an ARB file already describing its keys and
     * the key has no block yet. Runs inside a write action.
     */
    fun addAfterNewKey(catalogue: JsonObject, name: String, value: PsiElement?) {
        if (!isArb(catalogue.containingFile) || catalogue.parent !is JsonFile || name.startsWith(PREFIX)) return
        if (catalogue.findProperty(PREFIX + name) != null || !describesItsKeys(catalogue, name)) return
        val text = (value as? JsonStringLiteral)?.value ?: return
        val block = JsonElementGenerator(catalogue.project).createProperty(PREFIX + name, blockOf(text))
        JsonInsertion(catalogue).insert(block) { props ->
            val at = props.indexOfFirst { it.name == name }
            if (at < 0) props else props.take(at + 1)
        }
    }

    /** The new name of [block], the `@key` of a key renamed to [newName]. */
    fun renamedBlock(newName: String): String = PREFIX + newName

    /** Whether [catalogue] holds a block for a key other than [except]: `@@locale` and the like are not one. */
    private fun describesItsKeys(catalogue: JsonObject, except: String): Boolean =
        catalogue.propertyList.any { it.name.startsWith(PREFIX) && !it.name.startsWith(PREFIX + PREFIX) && it.name != PREFIX + except }

    /** [property] when it is a key — no metadata — of an ARB catalogue's top level. */
    private fun catalogueKey(property: PsiElement): JsonProperty? {
        val key = property as? JsonProperty ?: return null
        if (key.name.startsWith(PREFIX) || key.parent !is JsonObject || key.parent.parent !is JsonFile) return null
        return key.takeIf { isArb(it.containingFile) }
    }
}
