package com.ibrahimdans.i18n.extensions.localization.json

import com.ibrahimdans.i18n.*
import com.ibrahimdans.i18n.plugin.ide.actions.JsonKeySorter
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.fasterxml.jackson.core.io.JsonStringEncoder
import com.intellij.icons.AllIcons
import com.intellij.json.JsonFileType
import com.intellij.json.JsonLanguage
import com.intellij.json.json5.Json5FileType
import com.intellij.json.psi.*
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import javax.swing.Icon

class JsonLocalization : Localization<JsonStringLiteral> {
    override fun types(): List<LocalizationFileType> = listOf(JsonFileType.INSTANCE, Json5FileType.INSTANCE).map { LocalizationFileType(it) }
    override fun contentGenerator(): ContentGenerator = JsonContentGenerator()
    override fun referenceAssistant(): TranslationReferenceAssistant<JsonStringLiteral> = JsonReferenceAssistant()
    override fun elementsTree(file: PsiElement): Tree<PsiElement>? {
        return if (file is JsonFile) JsonElementTree.create(file)
            else if (file is JsonObject) JsonElementTree(file)
            else null
    }
    override fun matches(localizationFileType: LocalizationFileType, file: VirtualFile?, fileNames: List<String>): Boolean =
        fileNames.any {
            fileName -> localizationFileType.extensions().any { ext -> "$fileName.$ext"==file?.name}
        }
    override fun icon(): Icon = AllIcons.FileTypes.Json
    // No indentation setting: generated JSON is reformatted by the IDE's JSON code style, which
    // already owns it (Settings | Code Style | JSON). A plugin setting would be silently overridden.
    override fun config(): LocalizationConfig = LocalizationConfigImpl("json")
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
     * Adds `"key": value` to [item], a JSON object.
     *
     * The property goes in first and the comma joining it to its neighbour second. It used to
     * be the other way round — comma after the last property, then the property after the
     * comma — so anything failing between the two left the file with a dangling comma and no
     * property: invalid JSON, and every key of that namespace unreadable in that locale. The
     * property is the step that can fail (it is the one built from user text); once it is in,
     * the comma joins two properties that both exist.
     */
    override fun generateTranslationEntry(item: PsiElement, key: String, value: String) {
        val obj = item as JsonObject
        val generator = JsonElementGenerator(item.project)
        val keyValue = generator.createProperty(key, value)
        val props = obj.getPropertyList()
        if (props.isEmpty()) {
            obj.addAfter(keyValue, obj.findElementAt(0))
        } else {
            val before = if (Settings.getInstance(item.project).extractSorted) props.takeWhile { it.name < key } else props
            if (before.isEmpty()) {
                val inserted = obj.addBefore(keyValue, props.first())
                obj.addAfter(generator.createComma(), inserted)
            } else {
                val inserted = obj.addAfter(keyValue, before.last())
                obj.addBefore(generator.createComma(), inserted)
            }
        }
        // When the user keeps files alphabetically sorted, re-sort the whole file after each
        // insertion so the new key lands in order even if the file was not already sorted.
        if (Settings.getInstance(item.project).sortKeysAlphabetically) {
            (item.containingFile as? JsonFile)?.let { JsonKeySorter.sort(it, item.project) }
        }
    }

    override fun generate(element: PsiElement, fullKey: FullKey, unresolved: List<Literal>, translationValue: String?) {
        val first = unresolved.firstOrNull() ?: return
        generateTranslationEntry(
            element,
            first.text,
            generateContent(unresolved.drop(1), translationValue ?: fullKey.source)
        )
    }
}

