package com.ibrahimdans.i18n.extensions.localization.json

import com.ibrahimdans.i18n.TranslationReferenceAssistant
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileScope
import com.ibrahimdans.i18n.plugin.ide.references.translation.TranslationToCodeReferenceProvider
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiReference
import com.intellij.psi.util.parents

class JsonReferenceAssistant : TranslationReferenceAssistant<JsonStringLiteral> {

    private val provider = TranslationToCodeReferenceProvider()

    override fun pattern(): ElementPattern<out JsonStringLiteral> =
        PlatformPatterns.psiElement(JsonStringLiteral::class.java)

    override fun references(element: JsonStringLiteral): List<PsiReference> =
        if (element.isPropertyName && element.textLength > 1) {
            provider.getReferences(element, textRange(element), parents(element))
        } else {
            emptyList()
        }

    private fun parents(element: JsonStringLiteral): List<String> =
        element.parents(true).mapNotNull {
            when {
                it is JsonProperty -> it.name
                it is JsonFile -> namespaceOf(it)
                else -> null
            }
        }.toList().reversed()

    /**
     * The namespace [file] holds, as the tool window reads it. Not always the file's name: a file
     * named after its locale (`messages/fr.json`) holds the default namespace, and with its name
     * taken for one the key looked for was `fr:hello`, which no call writes — so no reference.
     * A file the plugin does not read as a translation source keeps its name.
     */
    private fun namespaceOf(file: JsonFile): String {
        val source = TranslationFileScope.sourceOf(file) ?: return file.name.substringBeforeLast(".")
        return TranslationDataLoader.extractNamespace(source, Settings.getInstance(file.project).config().defaultNamespaces().first())
    }

    private fun textRange(element: JsonStringLiteral): TextRange = TextRange(1, element.textLength - 1)
}
