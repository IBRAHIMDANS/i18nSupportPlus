package com.ibrahimdans.i18n.extensions.localization.xliff

import com.ibrahimdans.i18n.ContentGenerator
import com.ibrahimdans.i18n.Localization
import com.ibrahimdans.i18n.LocalizationConfig
import com.ibrahimdans.i18n.LocalizationConfigImpl
import com.ibrahimdans.i18n.LocalizationFileType
import com.ibrahimdans.i18n.TranslationReferenceAssistant
import com.ibrahimdans.i18n.WorkspaceFile
import com.ibrahimdans.i18n.plugin.ide.references.translation.TranslationToCodeReferenceContributor
import com.ibrahimdans.i18n.plugin.ide.references.translation.TranslationToCodeReferenceProvider
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PatternCondition
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.util.ProcessingContext
import javax.swing.Icon

/**
 * XLIFF 1.2 and 2.0 files as translation sources: Angular's native i18n (`messages.xlf`, `messages.fr.xlf`).
 *
 * The files are found by the technology reading `angular.json` ([com.ibrahimdans.i18n.extensions.technology.angulari18n.AngularI18nTechnology]),
 * never by their name: an XLIFF file is no namespace, and the plugin's own XLIFF export, written where
 * the user chose, must not become a source.
 */
class XliffLocalization : Localization<PsiElement> {

    override fun types(): List<LocalizationFileType> = listOf(LocalizationFileType(XliffFileType.INSTANCE, listOf("xliff")))

    override fun contentGenerator(): ContentGenerator = XliffContentGenerator()

    override fun referenceAssistant(): TranslationReferenceAssistant<PsiElement> = XliffReferenceAssistant()

    override fun elementsTree(file: PsiElement): Tree<PsiElement>? = XliffTree.of(file)

    /** Never found by name or place: see the class. */
    override fun scannedByName(): Boolean = false

    /**
     * Whether [file] is named after one of [fileNames], as a JSON file is: what tells an edit of a
     * translation file from any other. Finding the sources never asks it ([scannedByName]).
     */
    override fun matches(localizationFileType: LocalizationFileType, file: WorkspaceFile?, fileNames: List<String>): Boolean =
        fileNames.any { name -> localizationFileType.extensions().any { ext -> "$name.$ext" == file?.name } }

    override fun icon(): Icon = AllIcons.FileTypes.Xml

    override fun config(): LocalizationConfig = LocalizationConfigImpl("xliff")
}

/** Registered on XML in `plugin.xml`; [XliffReferenceAssistant] keeps to the units of XLIFF files. */
class XliffReferenceContributor : TranslationToCodeReferenceContributor<PsiElement>(XliffReferenceAssistant())

/** Navigation from a unit's `id` to the code naming it: `i18n="@@id"`, `` $localize`:@@id:…` ``. */
class XliffReferenceAssistant : TranslationReferenceAssistant<PsiElement> {

    private val provider = TranslationToCodeReferenceProvider()

    override fun pattern(): ElementPattern<out PsiElement> =
        PlatformPatterns.psiElement(XmlAttributeValue::class.java).with(object : PatternCondition<XmlAttributeValue>("xliffUnitId") {
            override fun accepts(element: XmlAttributeValue, context: ProcessingContext?): Boolean = unitIdOf(element) != null
        })

    override fun references(element: PsiElement): List<PsiReference> {
        val id = (element as? XmlAttributeValue)?.let(::unitIdOf) ?: return emptyList()
        return provider.getReferences(element, TextRange(1, element.textLength - 1), listOf(id))
    }

    /** The id [value] gives its unit, or null when it is not the `id` of a unit of an XLIFF file. */
    private fun unitIdOf(value: XmlAttributeValue): String? {
        val attribute = value.parent as? XmlAttribute ?: return null
        if (attribute.name != "id") return null
        val tag = attribute.parent ?: return null
        if (!XliffDocument.isUnit(tag) || !XliffTranslationPsi.isXliff(value)) return null
        return value.value.trim().takeIf { it.isNotEmpty() }
    }
}
