package com.ibrahimdans.i18n.plugin.ide.codevision

import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileScope
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationKeyUsages
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind
import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.codeInsight.hints.codeVision.DaemonBoundCodeVisionProvider
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiTreeUtil

/**
 * "3 usages" above each key of a JSON translation file, as Java shows above a method: the Usage
 * column of the table counted them, but in the file nothing said that a key served three times or
 * never. A click opens the usages, as *Find Usages* on the key does.
 *
 * Counted the way *Unused translation key* decides ([TranslationKeyUsages]): the references found
 * on the property, then — for a key nothing names — whether a key the code builds at runtime
 * (`t(`menu.${'$'}{id}`)`) may reach it, shown as "dynamic usage" rather than "no usages", or
 * whether the project's keep list declares it used, shown as "kept".
 *
 * One reference search per key: past [MAX_KEYS] keys in a file nothing is shown, rather than
 * slowing the daemon down on a catalogue of thousands of keys. Each search stops past [MAX_USAGES]:
 * a key used everywhere reads "99+ usages", and the search does not walk every one of its usages
 * again after each edit of the file. JSON only — YAML is an optional
 * dependency, kept out of the code `plugin.xml` loads.
 */
class TranslationUsagesCodeVisionProvider : DaemonBoundCodeVisionProvider {

    override val id: String = ID
    override val name: String get() = PluginBundle.message("codevision.usages.name")
    override val defaultAnchor: CodeVisionAnchorKind = CodeVisionAnchorKind.Top
    override val relativeOrderings: List<CodeVisionRelativeOrdering> = emptyList()

    override fun computeForEditor(editor: Editor, file: PsiFile): List<Pair<TextRange, CodeVisionEntry>> {
        if (file !is JsonFile || TranslationFileScope.sourceOf(file) == null) return emptyList()
        val leaves = PsiTreeUtil.findChildrenOfType(file, JsonProperty::class.java).filter { it.value is JsonStringLiteral }
        if (leaves.size > MAX_KEYS) return emptyList()
        val heads = mutableMapOf<String, Set<String>>()
        return leaves.map { property ->
            val count = TranslationKeyUsages.count(property, property.nameElement, MAX_USAGES + 1)
            val text = when {
                count > MAX_USAGES -> PluginBundle.message("codevision.usages.many", MAX_USAGES)
                count > 0 -> PluginBundle.message("codevision.usages.count", count)
                TranslationKeyUsages.kept(property.nameElement) -> PluginBundle.message("codevision.usages.kept")
                TranslationKeyUsages.reachedDynamically(property.nameElement, heads) -> PluginBundle.message("codevision.usages.dynamic")
                else -> PluginBundle.message("codevision.usages.none")
            }
            val pointer = SmartPointerManager.createPointer(property)
            property.textRange to ClickableTextCodeVisionEntry(text, id, { _, clicked ->
                pointer.element?.let { GotoDeclarationAction.startFindUsages(clicked, clicked.project ?: return@let, it) }
            })
        }
    }

    internal companion object {
        const val ID = "com.ibrahimdans.i18n.translationUsages"
        // About 14 ms per key search on the benchmark: 500 keys held each pass of the file for 7 s.
        const val MAX_KEYS = 100
        const val MAX_USAGES = 99
    }
}
