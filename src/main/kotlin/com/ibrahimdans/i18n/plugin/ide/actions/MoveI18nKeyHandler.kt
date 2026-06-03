package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.references.code.I18nReference
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
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
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
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
 * For a key like `t('common:user.name')` — or `t('user.name')` with `useTranslation('common')` —
 * the action:
 *  1. Resolves the key's value leaves in every locale file of the source namespace
 *  2. Shows a combo to pick the target namespace (e.g. "profile")
 *  3. Copies each value to the sibling target file in the same locale dir, creating it if missing
 *  4. Deletes the entry from the source files
 *  5. Rewrites all code references with an explicit `targetNs:` prefix
 *
 * The whole operation runs under a single WriteCommandAction (one undo step).
 *
 * The source namespace is derived from the *resolved* translation files (not the literal text),
 * so keys whose namespace comes from a `useTranslation('ns')` hook are handled correctly.
 */
class MoveI18nKeyHandler : AnAction(), CompositeKeyResolver<PsiElement> {

    /**
     * Everything needed to perform a move, collected under a read action before any UI/write.
     *
     * @param sourceLeaves the resolved value PSI nodes (e.g. JsonStringLiteral) in the source locale files
     * @param codeUsages   the code literals referencing the key (including the one under the caret)
     */
    data class MoveContext(
        val sourceCodeElement: PsiElement,
        val sourceNamespace: String,
        val compositeKey: List<Literal>,
        val sourceLeaves: List<PsiElement>,
        val codeUsages: List<PsiElement>,
    )

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

        val context = ReadAction.compute<MoveContext?, RuntimeException> { buildContext(editor, psiFile) }
        if (context == null) {
            Messages.showErrorDialog(
                project,
                "Could not resolve an i18n key at the caret. Place the caret on a key that exists in a translation file.",
                "Move i18n Key"
            )
            return
        }

        val namespaces = DialogViewModel(project).loadNamespaces().filter { it != context.sourceNamespace }
        if (namespaces.isEmpty()) {
            Messages.showErrorDialog(project, "No other namespace found in this project.", "Move i18n Key")
            return
        }

        val keyPath = context.compositeKey.joinToString(".") { it.text }
        val targetNs = showNamespaceCombo(project, "${context.sourceNamespace}:$keyPath", namespaces) ?: return

        if (hasCollision(project, targetNs, context.compositeKey)) {
            val choice = Messages.showYesNoDialog(
                project,
                "Key '$keyPath' already exists in namespace '$targetNs'. Overwrite it?",
                "Move i18n Key",
                Messages.getWarningIcon()
            )
            if (choice != Messages.YES) return
        }

        WriteCommandAction.runWriteCommandAction(project, "Move i18n Key", null, {
            execute(project, context.sourceLeaves, context.codeUsages, context.compositeKey, targetNs)
        })
    }

    // --- Core (testable; must be called inside a write action) ---

    /**
     * Performs the move: copies each source leaf value into the sibling target-namespace file
     * (creating it when absent), deletes the source entries, and rewrites the code references.
     *
     * Pure of any UI; intended to be called from within a [WriteCommandAction].
     */
    internal fun execute(
        project: Project,
        sourceLeaves: List<PsiElement>,
        codeUsages: List<PsiElement>,
        compositeKey: List<Literal>,
        targetNs: String,
    ) {
        // 1. Copy into the target namespace, one sibling file per source locale (created if missing).
        sourceLeaves.forEach { leaf -> insertIntoTarget(project, leaf, compositeKey, targetNs) }

        // 2. Remove the source entries.
        sourceLeaves.mapNotNull { toPropertyElement(it) }.forEach { it.delete() }

        // 3. Rewrite code references with an explicit target-namespace prefix.
        val keyPath = compositeKey.joinToString(".") { it.text }
        val newKey = "$targetNs:$keyPath"
        codeUsages.distinct()
            .groupBy { it.containingFile?.viewProvider?.document }
            .forEach { (doc, elements) ->
                if (doc == null) return@forEach
                // Replace from the end so earlier offsets stay valid across multiple edits in one document.
                elements.sortedByDescending { it.textRange.startOffset }
                    .forEach { rewriteCodeUsage(doc, it, newKey) }
            }
    }

    // --- Read-phase collection ---

    /**
     * Resolves the i18n key at the caret to its source namespace, composite key, value leaves,
     * and code usages. Returns null when there is no resolvable key under the caret.
     */
    internal fun buildContext(editor: Editor, psiFile: PsiFile): MoveContext? {
        val ref = findI18nReference(editor, psiFile) ?: return null

        val maxPath = ref.references.maxOfOrNull { it.reference.path.size } ?: return null
        if (maxPath == 0) return null
        val resolved = ref.references.filter { it.reference.path.size == maxPath && it.reference.unresolved.isEmpty() }
        if (resolved.isEmpty()) return null

        // Source namespace comes from the resolved translation file, not the literal text,
        // so an implicit (hook-provided) namespace resolves correctly.
        val sourceNs = namespaceOf(resolved.first().reference.localizationSource) ?: return null
        val moving = resolved.filter { namespaceOf(it.reference.localizationSource) == sourceNs }

        val compositeKey = moving.first().reference.path
        val sourceLeaves = moving.mapNotNull { it.reference.element?.value() }
        if (sourceLeaves.isEmpty()) return null

        val sourceProps = sourceLeaves.mapNotNull { toPropertyElement(it) }
        val codeUsages = (sourceProps.flatMap { prop ->
            ReferencesSearch.search(prop).mapNotNull { it.element }
        } + ref.element).distinct()

        return MoveContext(ref.element, sourceNs, compositeKey, sourceLeaves, codeUsages)
    }

    // --- Helpers ---

    private fun findI18nReference(editor: Editor, psiFile: PsiFile): I18nReference? {
        val offset = editor.caretModel.offset
        val element = psiFile.findElementAt(offset) ?: return null
        val literal = element.parent ?: element
        return literal.references.filterIsInstance<I18nReference>().firstOrNull()
            ?: element.references.filterIsInstance<I18nReference>().firstOrNull()
    }

    private fun namespaceOf(source: LocalizationSource): String? =
        source.name.substringBeforeLast('.').ifBlank { null }

    /**
     * Inserts [leaf]'s value into the sibling file `<targetNs>.<ext>` in the same locale directory,
     * creating that file when it does not exist yet (so no locale is silently dropped).
     */
    private fun insertIntoTarget(project: Project, leaf: PsiElement, compositeKey: List<Literal>, targetNs: String) {
        val value = readPsiValue(leaf) ?: return
        val sourceVFile = leaf.containingFile?.virtualFile ?: return
        val localeDir = PsiManager.getInstance(project).findDirectory(sourceVFile.parent ?: return) ?: return
        val ext = sourceVFile.extension ?: return

        val localization = Extensions.LOCALIZATION.extensionList
            .find { loc -> loc.types().any { it.extensions().contains(ext) } } ?: return
        val generator = localization.contentGenerator()

        val targetName = "$targetNs.$ext"
        val targetPsi: PsiFile = localeDir.findFile(targetName) ?: run {
            val created = PsiFileFactory.getInstance(project)
                .createFileFromText(targetName, generator.getLanguage(), emptyRootContent(ext))
            localeDir.add(created) as PsiFile
        }

        val tree = localization.elementsTree(targetPsi) ?: return
        val targetSource = LocalizationSource(tree, targetName, localeDir.name, targetPsi.name, localization)
        val targetFullKey = FullKey(
            source = "$targetNs:${compositeKey.joinToString(".") { it.text }}",
            ns = Literal(targetNs),
            compositeKey = compositeKey
        )

        val ref = resolveCompositeKey(compositeKey, targetSource) ?: return
        val rootOrLeaf = ref.element?.value() ?: return
        if (ref.unresolved.isEmpty()) {
            updatePsiValue(rootOrLeaf, value, project)
        } else if (generator.isSuitable(rootOrLeaf)) {
            generator.generate(rootOrLeaf, targetFullKey, ref.unresolved, value)
        }
    }

    private fun toPropertyElement(element: PsiElement): PsiElement? =
        PsiTreeUtil.getParentOfType(element, JsonProperty::class.java)
            ?: PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java)
            ?: (element.parent as? JsonProperty)
            ?: (element.parent as? YAMLKeyValue)

    private fun readPsiValue(element: PsiElement): String? =
        when (element) {
            is JsonStringLiteral -> element.value
            is YAMLScalar -> element.textValue
            is YAMLKeyValue -> element.valueText
            else -> element.text
        }

    private fun updatePsiValue(element: PsiElement, newValue: String, project: Project) {
        when (element) {
            is JsonStringLiteral -> element.replace(JsonElementGenerator(project).createStringLiteral(newValue))
            is YAMLScalar -> {
                val kv = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java) ?: return
                YAMLElementGenerator.getInstance(project).createYamlKeyValue(kv.keyText, newValue).value
                    ?.let { element.replace(it) }
            }
            is YAMLKeyValue -> {
                YAMLElementGenerator.getInstance(project).createYamlKeyValue(element.keyText, newValue).value
                    ?.let { element.value?.replace(it) }
            }
        }
    }

    private fun rewriteCodeUsage(doc: Document, element: PsiElement, newKey: String) {
        val original = element.text
        val updated = if (original.isQuoted()) {
            val quote = original.first()
            "$quote$newKey$quote"
        } else {
            newKey
        }
        if (updated != original) {
            doc.replaceString(element.textRange.startOffset, element.textRange.endOffset, updated)
        }
    }

    private fun hasCollision(project: Project, targetNs: String, compositeKey: List<Literal>): Boolean {
        val keyPath = compositeKey.joinToString(".") { it.text }
        val targetFullKey = FullKey(source = "$targetNs:$keyPath", ns = Literal(targetNs), compositeKey = compositeKey)
        return ReadAction.compute<Boolean, RuntimeException> {
            DialogViewModel(project).loadTranslations(targetFullKey).any { (_, v) -> v != null }
        }
    }

    private fun emptyRootContent(ext: String): String =
        if (ext == "json" || ext == "json5") "{}" else ""

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
