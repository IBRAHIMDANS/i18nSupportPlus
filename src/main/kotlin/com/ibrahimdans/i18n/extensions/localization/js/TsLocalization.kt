package com.ibrahimdans.i18n.extensions.localization.js

import com.ibrahimdans.i18n.*
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement

class TsLocalization : Localization<PsiElement> {
    override fun types(): List<LocalizationFileType> = listOf()
    override fun contentGenerator(): ContentGenerator = TsContentGenerator()
    override fun referenceAssistant(): TranslationReferenceAssistant<PsiElement> = TsReferenceAssistant()
    override fun elementsTree(file: PsiElement): Tree<PsiElement>? = TsLocalizationTree.create(file)
    override fun matches(localizationFileType: LocalizationFileType, file: VirtualFile?, fileNames: List<String>): Boolean {
        val tsFileType = FileTypeManager.getInstance().findFileTypeByName("TypeScript")
        return tsFileType != null && localizationFileType.languageFileType == tsFileType
    }
    override fun config(): LocalizationConfig = LocalizationConfigImpl("ts")
}
