package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileKeys
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileScope
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import java.awt.datatransfer.StringSelection

/**
 * Copies the i18n key under the caret of a translation file, spelled the way the code writes it.
 *
 * The tool window tree could already copy a key, but finding it there means leaving the file one
 * is reading. With the caret on `"home"` in `fr/common.json`, this puts `common:menu.home` in the
 * clipboard, ready to paste into a `t()` call.
 *
 * The spelling is the code's, not the tool window's (the tree always writes `:`, its own
 * convention): it is [ExistingKeyFinder.spell]'s, the one a key typed in a `t()` call is parsed
 * back with. A default namespace is dropped, since `t('menu.home')` is how such a key is written,
 * and so is any namespace when keys are flat — the code reads `common:menu.home` as one literal key.
 */
class CopyI18nKeyAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    /** Shown in translation files only; greyed out where the caret is on no key (the root object). */
    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.PSI_FILE)
        val isTranslationFile = file != null && TranslationFileScope.sourceOf(file) != null
        e.presentation.isVisible = isTranslationFile
        e.presentation.isEnabled = isTranslationFile && elementAtCaret(e)?.let(::keyAt) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val key = elementAtCaret(e)?.let(::keyAt) ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(key))
    }

    private fun elementAtCaret(e: AnActionEvent): PsiElement? {
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return null
        val offset = e.getData(CommonDataKeys.EDITOR)?.caretModel?.offset ?: return null
        // At the very end of the file there is no element at the caret, only before it.
        return file.findElementAt(offset) ?: file.findElementAt(offset - 1)
    }

    companion object {

        /**
         * The key [element] stands for, as the code writes it, or null when [element] is not in a
         * translation file or sits on no key.
         */
        internal fun keyAt(element: PsiElement): String? {
            val file: PsiFile = element.containingFile ?: return null
            val source = TranslationFileScope.sourceOf(file) ?: return null
            val path = TranslationFileKeys.pathOf(element).takeIf { it.isNotEmpty() } ?: return null
            val config = Settings.getInstance(file.project).config()
            val defaultNamespace = config.defaultNamespaces().first()
            // An empty namespace separator means the code writes no namespace — unless the first
            // key component is one, which spell joins with the key separator instead.
            val namespace =
                if (config.nsSeparator.isEmpty() && !config.firstComponentNs) defaultNamespace
                else TranslationDataLoader.extractNamespace(source, defaultNamespace)
            return ExistingKeyFinder.spell(namespace, path, config)
        }
    }
}
