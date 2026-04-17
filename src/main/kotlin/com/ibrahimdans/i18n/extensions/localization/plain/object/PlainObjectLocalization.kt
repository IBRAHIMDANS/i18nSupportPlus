package com.ibrahimdans.i18n.extensions.localization.plain.`object`

import com.ibrahimdans.i18n.*
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement

/**
 * NOT ACTIVE — registration disabled in plainObjectConfig.xml pending availability of
 * org.jetbrains.plugins.localization (GNU GetText plugin) for IntelliJ build 243.x+.
 * PlainObjectTextTree relies on PSI node types (SECTION, ID_LINE) provided by that plugin.
 */
class PlainObjectLocalization : Localization<PsiElement> {
    override fun types(): List<LocalizationFileType> =
        listOf(FileTypeManager
            .getInstance()
            .getStdFileType("Locale"))
            .filter {it != PlainTextFileType.INSTANCE}
            .map {LocalizationFileType(it, listOf("pot"))}
    override fun contentGenerator(): ContentGenerator = PlainObjectContentGenerator()
    override fun referenceAssistant(): TranslationReferenceAssistant<PsiElement> = PlainObjectReferenceAssistant()
    override fun elementsTree(file: PsiElement): Tree<PsiElement> = PlainObjectTextTree(file)
    override fun matches(localizationFileType: LocalizationFileType, file: VirtualFile?, fileNames: List<String>): Boolean {
        val ext = file?.extension?.lowercase()
        return ext == "po" || ext == "pot"
    }
    override fun config(): LocalizationConfig = LocalizationConfigImpl("plainObject")
}