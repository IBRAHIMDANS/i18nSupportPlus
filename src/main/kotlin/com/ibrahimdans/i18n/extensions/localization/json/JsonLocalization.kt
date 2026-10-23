package com.ibrahimdans.i18n.extensions.localization.json

import com.fasterxml.jackson.core.io.JsonStringEncoder
import com.ibrahimdans.i18n.*
import com.ibrahimdans.i18n.plugin.edit.JsonTranslationEditor
import com.ibrahimdans.i18n.plugin.ide.actions.JsonKeySorter
import com.ibrahimdans.i18n.plugin.ide.edit.TranslationEditorBridge
import com.ibrahimdans.i18n.plugin.ide.edit.TranslationEditorProvider
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileKeys
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.icons.AllIcons
import com.intellij.json.JsonFileType
import com.intellij.json.JsonLanguage
import com.intellij.json.json5.Json5FileType
import com.intellij.json.psi.*
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.Icon

class JsonLocalization : Localization<JsonStringLiteral>, TranslationEditorProvider {
    override fun types(): List<LocalizationFileType> = listOf(JsonFileType.INSTANCE, Json5FileType.INSTANCE).map { LocalizationFileType(it) }
    override fun contentGenerator(): ContentGenerator = JsonContentGenerator()
    override fun referenceAssistant(): TranslationReferenceAssistant<JsonStringLiteral> = JsonReferenceAssistant()
    override fun elementsTree(file: PsiElement): Tree<PsiElement>? {
        return if (file is JsonFile) JsonElementTree.create(file)
            else if (file is JsonObject) JsonElementTree(file)
            else null
    }
    override fun matches(localizationFileType: LocalizationFileType, file: WorkspaceFile?, fileNames: List<String>): Boolean =
        fileNames.any {
            fileName -> localizationFileType.extensions().any { ext -> "$fileName.$ext"==file?.name}
        }
    override fun icon(): Icon = AllIcons.FileTypes.Json
    // No indentation setting: a key added to an existing file takes the layout of the file itself
    // (see JsonInsertion), and a new file is written with 2 spaces.
    override fun config(): LocalizationConfig = LocalizationConfigImpl("json")
    override fun translationEditor(project: Project) = JsonTranslationEditor
}

/**
 * Generates JSON translation content
 */
private class JsonContentGenerator: ContentGenerator {

    private val tabChar = "  "

    override fun generateContent(compositeKey: List<Literal>, value: String): String {
        val escapedValue = String(JsonStringEncoder.getInstance().quoteAsString(value))
        return compositeKey.foldRightIndexed("\"$escapedValue\"", { i, key, acc ->
            val tab = tabChar.repeat(i)
            "{\n$tabChar$tab\"${key.text}\": $acc\n$tab}"
        })
    }

    override fun getType(): FileType = JsonFileType.INSTANCE
    override fun getLanguage(): Language = JsonLanguage.INSTANCE
    override fun getDescription(): String = PluginBundle.getMessage("quickfix.create.json.translation.files")
    override fun isSuitable(element: PsiElement): Boolean = element is JsonObject
    /**
     * Adds `"key": value` to [item], a JSON object, laid out like the object around it: the
     * indent of its properties (spaces or tabs, whatever their width), on one line when the
     * object is on one line. A multi-line [value] — the chain of objects of a nested key — is
     * laid out the same way. Nothing else in the file changes.
     *
     * The text is written as built, with the IDE's formatter kept out: it used to reformat the
     * insertion with the IDE's JSON code style (2 spaces by default), whatever the file's own.
     *
     * The comma, the whitespace and the property go in as one range, in a single tree change: the
     * file never holds a dangling comma without its property — it once did, and an invalid JSON
     * file loses every key of that namespace in that locale. The property, the step built from
     * user text, is created before the file is touched.
     */
    override fun generateTranslationEntry(item: PsiElement, key: String, value: String) {
        val obj = item as JsonObject
        val project = item.project
        val property = JsonElementGenerator(project).createProperty(key, value)
        val settings = Settings.getInstance(project)
        CodeStyleManager.getInstance(project).performActionWithFormatterDisabled {
            JsonInsertion(obj).insert(property) { props ->
                if (settings.extractSorted) props.takeWhile { settings.keyOrder.comparator.compare(it.name, key) < 0 } else props
            }
        }
        // When the user keeps files alphabetically sorted, re-sort the whole file after each
        // insertion so the new key lands in order even if the file was not already sorted.
        if (settings.sortKeysAlphabetically) {
            (item.containingFile as? JsonFile)?.let { JsonKeySorter.sort(it, project) }
        }
    }

    override fun generate(element: PsiElement, fullKey: FullKey, unresolved: List<Literal>, translationValue: String?) {
        val first = unresolved.firstOrNull() ?: return
        val value = translationValue ?: fullKey.source
        val settings = Settings.getInstance(element.project)
        if (TranslationEditorBridge.insert(
                element.containingFile ?: return,
                JsonTranslationEditor,
                // From the file root down: a source tree may start below it (one per locale in a Vue `<i18n>` block).
                TranslationFileKeys.pathOf(element) + unresolved.map { it.text },
                value,
                order = settings.keyOrder.comparator.takeIf { settings.extractSorted },
                sort = settings.keyOrder.comparator.takeIf { settings.sortKeysAlphabetically },
            )
        ) return
        generateTranslationEntry(
            element,
            first.text,
            generateContent(unresolved.drop(1), value)
        )
    }
}

/**
 * Writes a property into [obj] in the layout [obj] already has, read from the file's text: the
 * indent its properties start at, whether it holds them on one line, and what separates them
 * there. Runs inside a write action, with the formatter disabled.
 */
internal class JsonInsertion(private val obj: JsonObject) {

    private val text = obj.containingFile.text
    private val generator = JsonElementGenerator(obj.project)
    private val layout = layoutOf(obj)

    /**
     * Inserts [property] after the last of the existing properties [preceding] keeps, or in front
     * of them all when it keeps none.
     */
    fun insert(property: JsonProperty, preceding: (List<JsonProperty>) -> List<JsonProperty>) {
        val props = obj.propertyList
        val entry = render(property, layout.childIndent)
        if (props.isEmpty()) {
            insertIntoEmpty(entry)
            return
        }
        val before = preceding(props)
        if (before.isEmpty()) {
            // `"key": value,<separator>` in front of the first property, after the whitespace
            // that precedes it.
            val dummy = generator.createValue<JsonObject>("{$entry,${layout.separator}\"_\": 0}")
            obj.addRangeBefore(dummy.propertyList.first(), dummy.propertyList.last().prevSibling, props.first())
        } else {
            // `,<separator>"key": value` right after the property it follows.
            val dummy = generator.createValue<JsonObject>("{\"_\": 0,${layout.separator}$entry}")
            obj.addRangeAfter(dummy.propertyList.first().nextSibling, dummy.propertyList.last(), before.last())
        }
    }

    /** `{}` becomes `{<open>"key": value<close>}`, whatever whitespace it held. */
    private fun insertIntoEmpty(entry: String) {
        val open = obj.firstChild
        val close = obj.lastChild
        if (open.nextSibling != close) obj.deleteChildRange(open.nextSibling, close.prevSibling)
        val dummy = generator.createValue<JsonObject>("{${layout.open}$entry${layout.close}}")
        obj.addRangeAfter(dummy.firstChild.nextSibling, dummy.lastChild.prevSibling, open)
    }

    /** [property]'s text, its value laid out like [obj], on a line indented by [indent]. */
    private fun render(property: JsonProperty, indent: String): String =
        "${property.nameElement.text}: ${render(property.value, indent)}"

    private fun render(value: JsonValue?, indent: String): String {
        val props = (value as? JsonObject)?.propertyList
        if (props.isNullOrEmpty()) return value?.text ?: "null"
        if (!layout.multiLine) {
            return props.joinToString(",${layout.separator}", "{${layout.open}", "${layout.close}}") { render(it, indent) }
        }
        val inner = indent + layout.unit
        return props.joinToString(",\n", "{\n", "\n$indent}") { inner + render(it, inner) }
    }

    /**
     * The layout of [target]'s properties:
     * - on several lines: each on its own line, indented like the existing ones;
     * - on one line: joined by the whitespace that follows their commas, inside braces padded
     *   like [target]'s own;
     * - none yet: the layout of the object holding [target] when that one is on one line,
     *   otherwise several lines one indent unit of the file deeper than the line of `{`.
     */
    private fun layoutOf(target: JsonObject): Layout {
        val props = target.propertyList
        val indent = lineIndent(target.textRange.startOffset)
        if (props.isEmpty()) {
            val parent = PsiTreeUtil.getParentOfType(target, JsonObject::class.java)
            if (parent != null && !parent.textContains('\n')) return layoutOf(parent)
            val child = indent + fileUnit()
            return Layout(true, child, fileUnit(), "\n$child", "\n$child", "\n$indent")
        }
        if (!target.textContains('\n')) {
            val separator = props.getOrNull(1)?.let { whitespaceBefore(it) } ?: " "
            val open = whitespaceBefore(props.first())
            val close = (target.lastChild.prevSibling as? PsiWhiteSpace)?.text ?: ""
            return Layout(false, "", "", separator, open, close)
        }
        // The indent of the last property that starts a line: one sharing the line of `{` says
        // nothing of where the others go.
        val child = props.lastOrNull { whitespaceBefore(it).contains('\n') }
            ?.let { whitespaceBefore(it).substringAfterLast('\n') }
            ?: (indent + fileUnit())
        val unit = child.removePrefix(indent).takeIf { child.startsWith(indent) && it.isNotEmpty() } ?: fileUnit()
        return Layout(true, child, unit, "\n$child", "\n$child", "\n$indent")
    }

    private fun whitespaceBefore(property: JsonProperty): String = (property.prevSibling as? PsiWhiteSpace)?.text ?: ""

    /** The leading spaces and tabs of the line holding [offset]. */
    private fun lineIndent(offset: Int): String {
        val start = text.lastIndexOf('\n', offset - 1) + 1
        return text.substring(start, offset).takeWhile { it == ' ' || it == '\t' }
    }

    /**
     * One level of indent in this file: a tab when lines are indented with tabs, otherwise the
     * narrowest indent of any line; 2 spaces in a file without an indented line.
     */
    private fun fileUnit(): String {
        val indents = text.lineSequence()
            .filter { it.isNotBlank() }
            .map { line -> line.takeWhile { it == ' ' || it == '\t' } }
            .filter { it.isNotEmpty() }
            .toList()
        if (indents.any { it.startsWith('\t') }) return "\t"
        return indents.minOfOrNull { it.length }?.let { " ".repeat(it) } ?: DEFAULT_UNIT
    }

    /**
     * [childIndent] starts each property line of a multi-line object, [unit] is one level deeper;
     * [separator] goes between a comma and the next property; [open] and [close] go inside the
     * braces of an object written from scratch.
     */
    private data class Layout(
        val multiLine: Boolean,
        val childIndent: String,
        val unit: String,
        val separator: String,
        val open: String,
        val close: String,
    )

    private companion object {
        const val DEFAULT_UNIT = "  "
    }
}
