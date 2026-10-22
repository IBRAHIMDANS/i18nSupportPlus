package com.ibrahimdans.i18n.plugin.ide.quickfix

import com.ibrahimdans.i18n.Localization
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.tree.PropertyReference
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement

/**
 * Quick fix for missing keys creation
 */
class CreateMissingKeysQuickFix(
        private val fullKey: FullKey,
        private val references: List<PropertyReference<PsiElement>>,
        private val commandCaption: String): QuickFix(), CompositeKeyResolver<PsiElement> {

    override fun getText(): String = commandCaption

    override fun invoke(project: Project, editor: Editor) =
        ApplicationManager.getApplication().invokeLater {
            references.forEach { createPropertyInFile(project, it) }
        }

    private fun createPropertyInFile(project: Project, ref: PropertyReference<PsiElement>) {
        val element = ref.element
        // Every source read through the PSI carries a Localization, the format that writes into it.
        val localization = ref.localizationSource.localization as? Localization<*> ?: return
        if (element != null && !element.isLeaf()) {
            CommandProcessor.getInstance().executeCommand(
                project,
                {ApplicationManager.getApplication().runWriteAction {
                    localization.contentGenerator().generate(element.value(), fullKey, ref.unresolved, fullKey.source) }
                },
                commandCaption,
                UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION
            )
        }
    }
}