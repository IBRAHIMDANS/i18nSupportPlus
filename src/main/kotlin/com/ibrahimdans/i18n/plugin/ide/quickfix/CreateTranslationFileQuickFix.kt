package com.ibrahimdans.i18n.plugin.ide.quickfix

import com.ibrahimdans.i18n.ContentGenerator
import com.ibrahimdans.i18n.plugin.factory.TranslationFolderSelector
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileFactory

/**
 * Quick fix for translation file creation
 */
class CreateTranslationFileQuickFix(
    private val fullKey: FullKey,
    private val contentGenerator: ContentGenerator,
    private val fileName: String,
    private val translationValue: String? = null,
    private val onComplete: () -> Unit = {}): QuickFix() {

    private val folderSelector = TranslationFolderSelector()

    override fun getText(): String = contentGenerator.getDescription()

    override fun invoke(project: Project, editor: Editor) =
        ApplicationManager.getApplication().invokeLater {
            doInvoke(project)
        }

    private fun doInvoke(project: Project) {
        val name: String = fileName + "." + contentGenerator.getType().defaultExtension
        folderSelector.select(project) { folders ->
            WriteCommandAction.runWriteCommandAction(project) {
                folders.forEach {
                    // Per folder: the module holding it sets what the content depends on (key separator, reference locale).
                    val content = contentGenerator.generateContent(project, "${it.virtualFile.path}/$name", fullKey, translationValue)
                    it.add(PsiFileFactory.getInstance(project).createFileFromText(name, contentGenerator.getLanguage(), content))
                }
                onComplete()
            }
        }
    }
}
