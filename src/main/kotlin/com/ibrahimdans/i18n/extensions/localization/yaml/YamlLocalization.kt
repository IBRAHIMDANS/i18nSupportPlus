package com.ibrahimdans.i18n.extensions.localization.yaml

import com.ibrahimdans.i18n.*
import com.ibrahimdans.i18n.plugin.ConfigurationProperty
import com.ibrahimdans.i18n.plugin.PrimitiveType
import com.ibrahimdans.i18n.plugin.ide.settings.LocalizationSettingsPanel
import com.ibrahimdans.i18n.plugin.ide.references.translation.TranslationToCodeReferenceProvider
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.icons.AllIcons
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.PostprocessReformattingAspect
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parents
import org.jetbrains.yaml.YAMLElementGenerator
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.YAMLUtil
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import javax.swing.Icon

class YamlLocalization : Localization<YAMLKeyValue> {
    override fun types(): List<LocalizationFileType> = listOf(LocalizationFileType(YAMLFileType.YML, listOf("yaml")))
    override fun contentGenerator(): ContentGenerator = YamlContentGenerator()
    override fun referenceAssistant(): TranslationReferenceAssistant<YAMLKeyValue> = YamlReferenceAssistant()
    override fun elementsTree(file: PsiElement): Tree<PsiElement>? = YamlElementTree.create(file)
    override fun matches(localizationFileType: LocalizationFileType, file: VirtualFile?, fileNames: List<String>): Boolean =
        fileNames.any {
            fileName -> localizationFileType.extensions().any { ext -> "$fileName.$ext"==file?.name}
        }
    override fun icon(): Icon = AllIcons.FileTypes.Yaml
    override fun config(): LocalizationConfig = LocalizationConfigImpl("yaml", listOf(indentProperty()))
}

/** Indentation of the keys generated in YAML files, which no code style reformats afterwards. */
private fun indentProperty(): ConfigurationProperty = ConfigurationProperty(
    PluginBundle.message("settings.localization.indent"),
    PrimitiveType.NUMBER,
    "2",
    false,
    LocalizationSettingsPanel.INDENT
)

private class YamlContentGenerator: ContentGenerator {

    /** Default indentation, for content generated with no project at hand (a new file's body). */
    private val defaultIndent = "  "

    override fun generateContent(compositeKey: List<Literal>, value: String): String =
        generateContent(compositeKey, value, defaultIndent)

    private fun generateContent(compositeKey: List<Literal>, value: String, tabChar: String): String =
        compositeKey.foldRightIndexed(YamlScalars.spell(value), { i, key, acc ->
            val caret = if (i == 0) "" else "\n"
            val tab = tabChar.repeat(i)
            val valuePart = if (acc.startsWith("\n")) acc else " $acc"
            "$caret$tab${key.text}:$valuePart"
        })

    override fun getType(): FileType = YAMLFileType.YML
    override fun getLanguage(): Language = YAMLLanguage.INSTANCE
    override fun getDescription(): String = PluginBundle.getMessage("quickfix.create.yaml.translation.files")
    override fun isSuitable(element: PsiElement): Boolean = (element is YAMLMapping) || (element is YAMLDocument)
    /**
     * Adds `key: value` to [item], at the column of the keys already there.
     *
     * [value] is YAML source: a spelled scalar, or the nested keys [generateContent] writes. The
     * entry is built at its final column and inserted with the IDE's reformatting off: left to the
     * YAML code style, the insertion re-indented the mapping's existing lines to two spaces, a diff
     * on lines nobody touched.
     */
    override fun generateTranslationEntry(item: PsiElement, key: String, value: String) {
        val project = item.project
        val generator = YAMLElementGenerator.getInstance(project)
        if (item is YAMLDocument) {
            item.add(entryAt(project, key, value, 0))
            return
        }
        val obj = (item as YAMLMapping)
        val props = obj.keyValues
        if (props.isEmpty()) {
            // An empty flow mapping, `{}`: no key to take the column of, left to the code style.
            obj.addAfter(generator.createYamlKeyValue(key, value), obj.addAfter(generator.createEol(), item.node.firstChildNode.psi))
            return
        }
        val column = YAMLUtil.getIndentToThisElement(props.first())
        val keyValue = entryAt(project, key, value, column)
        val settings = Settings.getInstance(project)
        val before = if (settings.extractSorted) props.takeWhile { settings.keyOrder.comparator.compare(it.name ?: "", key) < 0 } else props
        PostprocessReformattingAspect.getInstance(project).disablePostprocessFormattingInside {
            if (before.isEmpty()) {
                // The first key's indentation sits before the mapping: it now indents the new key.
                val inserted = obj.addBefore(keyValue, props.first())
                val eol = obj.addAfter(generator.createEol(), inserted)
                if (column > 0) obj.addAfter(generator.createIndent(column), eol)
            } else {
                var anchor = obj.addAfter(generator.createEol(), before.last())
                if (column > 0) anchor = obj.addAfter(generator.createIndent(column), anchor)
                obj.addAfter(keyValue, anchor)
            }
        }
    }

    /** `key: value` as it reads at [column]: the lines of a nested [value] shifted under the key. */
    private fun entryAt(project: Project, key: String, value: String, column: Int): YAMLKeyValue {
        val generator = YAMLElementGenerator.getInstance(project)
        val nested = generator.createDummyYamlWithText(value).documents.firstOrNull()?.topLevelValue is YAMLMapping
        val text = if (nested) {
            val indent = " ".repeat(column) + LocalizationSettingsPanel.indent(project, "yaml")
            "$key:" + value.lines().joinToString("") { "\n$indent$it" }
        } else {
            "$key: $value"
        }
        return PsiTreeUtil.findChildOfType(generator.createDummyYamlWithText(text), YAMLKeyValue::class.java)!!
    }

    override fun generate(element: PsiElement, fullKey: FullKey, unresolved: List<Literal>, translationValue: String?) {
        val first = unresolved.firstOrNull() ?: return
        generateTranslationEntry(
            element,
            first.text,
            generateContent(unresolved.drop(1), translationValue ?: fullKey.source, LocalizationSettingsPanel.indent(element.project, "yaml"))
        )
    }
}

class YamlReferenceAssistant : TranslationReferenceAssistant<YAMLKeyValue> {

    private val provider = TranslationToCodeReferenceProvider()

    override fun pattern(): ElementPattern<out YAMLKeyValue> = PlatformPatterns.psiElement(YAMLKeyValue::class.java)

    private fun parents(element: YAMLKeyValue): List<String>? {
        val result = mutableListOf<String>()
        for (psi in element.parents(true)) {
            when {
                psi is YAMLKeyValue -> result.add(psi.key?.text?.unQuote() ?: return null)
                psi is YAMLFile -> result.add(psi.name.substringBeforeLast("."))
            }
        }
        return result.reversed()
    }

    private fun textRange(element: YAMLKeyValue): TextRange {
        val text = element.key?.text ?: return TextRange.EMPTY_RANGE
        return TextRange(if (text.startsWith("\"")) 1 else 0, text.length - (if (text.endsWith("\"")) 1 else 0))
    }

    override fun references(element: YAMLKeyValue): List<PsiReference> {
        val path = parents(element) ?: return emptyList()
        return provider.getReferences(element, textRange(element), path)
    }
}