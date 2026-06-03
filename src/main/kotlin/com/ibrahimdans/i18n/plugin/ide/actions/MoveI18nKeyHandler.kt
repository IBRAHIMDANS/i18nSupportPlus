package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.references.code.I18nReference
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.json.psi.JsonElementGenerator
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.YAMLElementGenerator
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Moves an i18n key from its current namespace to another namespace.
 *
 * For a key like `t('common:user.name')`, the action:
 *  1. Shows a combo to pick the target namespace (e.g. "profile")
 *  2. Copies the value to the target namespace in every locale file
 *  3. Deletes the entry from the source namespace in every locale file
 *  4. Rewrites the namespace prefix in all code references
 *
 * The whole operation runs under a single WriteCommandAction (one undo step).
 */
class MoveI18nKeyHandler : AnAction(), CompositeKeyResolver<PsiElement> {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        e.presentation.isEnabledAndVisible =
            editor != null && psiFile != null && findI18nReference(editor, psiFile) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val ref = findI18nReference(editor, psiFile) ?: return

        val currentKey = ref.element.text.unQuote()
        val viewModel = DialogViewModel(project)
        val fullKey = viewModel.parseKey(currentKey) ?: return
        val sourceNs = fullKey.ns?.text ?: run {
            Messages.showErrorDialog(
                project,
                "This key has no namespace prefix. Use the form 'namespace:key.path' (e.g. 'common:user.name').",
                "Move i18n Key"
            )
            return
        }

        val namespaces = viewModel.loadNamespaces().filter { it != sourceNs }
        if (namespaces.isEmpty()) {
            Messages.showErrorDialog(project, "No other namespace found in this project.", "Move i18n Key")
            return
        }

        val targetNs = showNamespaceCombo(project, currentKey, namespaces) ?: return
        val compositeKeyPath = fullKey.compositeKey.joinToString(".") { it.text }
        val targetKeyStr = "$targetNs:$compositeKeyPath"
        val targetFullKey = viewModel.parseKey(targetKeyStr) ?: return

        // --- Read phase (no write yet) ---

        val sourceTranslations = viewModel.loadTranslations(fullKey)
        val targetSourcesMap = viewModel.loadSourcesForNamespace(targetNs)

        val collisionExists = viewModel.loadTranslations(targetFullKey).any { (_, v) -> v != null }
        if (collisionExists) {
            val choice = Messages.showYesNoDialog(
                project,
                "Key '$compositeKeyPath' already exists in namespace '$targetNs'. Overwrite it?",
                "Move i18n Key",
                Messages.getWarningIcon()
            )
            if (choice != Messages.YES) return
        }

        val sourceElements = ReadAction.compute<List<PsiElement>, RuntimeException> {
            ref.multiResolve(false).mapNotNull { it.element }
        }
        val allCodeUsages = ReadAction.compute<List<PsiElement>, RuntimeException> {
            sourceElements
                .mapNotNull { el -> toPropertyElement(el) }
                .flatMap { prop -> ReferencesSearch.search(prop).mapNotNull { it.element } }
                .distinct()
        }

        // --- Write phase: insert to target, delete from source, update code refs ---

        val newKey = "$targetNs:$compositeKeyPath"
        WriteCommandAction.runWriteCommandAction(project, "Move i18n Key", null, {
            for (targetSource in targetSourcesMap.keys) {
                val value = sourceTranslations.entries
                    .find { (src, _) -> src.parent == targetSource.parent }
                    ?.value ?: continue
                val propRef = resolveCompositeKey(targetFullKey.compositeKey, targetSource) ?: continue
                val generator = targetSource.localization.contentGenerator()
                if (propRef.unresolved.isEmpty() && propRef.element != null) {
                    updatePsiValue(propRef.element.value(), value, project)
                } else if (propRef.element != null && generator.isSuitable(propRef.element.value())) {
                    generator.generate(propRef.element.value(), targetFullKey, propRef.unresolved, value)
                }
            }

            sourceElements.forEach { el -> toPropertyElement(el)?.delete() }

            (allCodeUsages + ref.element).distinct().forEach { codeEl ->
                replaceKeyInElement(codeEl, currentKey, newKey)
            }
        })
    }

    // --- Helpers ---

    private fun findI18nReference(editor: Editor, psiFile: PsiFile): I18nReference? {
        val offset = editor.caretModel.offset
        val element = psiFile.findElementAt(offset) ?: return null
        val literal = element.parent ?: element
        return literal.references.filterIsInstance<I18nReference>().firstOrNull()
            ?: element.references.filterIsInstance<I18nReference>().firstOrNull()
    }

    private fun toPropertyElement(element: PsiElement): PsiElement? =
        PsiTreeUtil.getParentOfType(element, JsonProperty::class.java)
            ?: PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java)
            ?: (element.parent as? JsonProperty)
            ?: (element.parent as? YAMLKeyValue)

    private fun updatePsiValue(element: PsiElement, newValue: String, project: Project) {
        when (element) {
            is JsonStringLiteral -> element.replace(JsonElementGenerator(project).createStringLiteral(newValue))
            is YAMLScalar -> {
                val kv = element.parent as? YAMLKeyValue ?: return
                val gen = YAMLElementGenerator.getInstance(project)
                gen.createYamlKeyValue(kv.keyText, newValue).value?.let { element.replace(it) }
            }
        }
    }

    private fun replaceKeyInElement(element: PsiElement, oldKey: String, newKey: String) {
        val doc = element.containingFile?.viewProvider?.document ?: return
        val original = element.text
        val updated = if (original.isQuoted()) {
            val q = original.first()
            "$q$newKey$q"
        } else {
            newKey
        }
        if (updated != original) {
            doc.replaceString(element.textRange.startOffset, element.textRange.endOffset, updated)
        }
    }

    private fun showNamespaceCombo(project: Project, currentKey: String, namespaces: List<String>): String? {
        val combo = ComboBox(namespaces.toTypedArray())
        val panel = JPanel(BorderLayout(0, 8)).apply {
            preferredSize = Dimension(380, 60)
            add(JLabel("Move '$currentKey' to namespace:"), BorderLayout.NORTH)
            add(combo, BorderLayout.CENTER)
        }
        val dialog = object : DialogWrapper(project, true) {
            init {
                title = "Move i18n Key"
                init()
            }
            override fun createCenterPanel(): JComponent = panel
        }
        return if (dialog.showAndGet()) combo.selectedItem as? String else null
    }

    private fun String.isQuoted(): Boolean =
        length > 1 && (startsWith('"') && endsWith('"')
                || startsWith('\'') && endsWith('\'')
                || startsWith('`') && endsWith('`'))
}
