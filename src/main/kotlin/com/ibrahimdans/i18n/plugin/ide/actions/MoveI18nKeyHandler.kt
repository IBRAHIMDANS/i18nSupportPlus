package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.references.code.I18nReference
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.utils.deletePropertyAndSeparator
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
     * The i18n key resolved at the caret, collected under a read action before any UI/write.
     *
     * @param leavesByNamespace the resolved value PSI nodes grouped by source namespace; more than one
     *        entry means the same key resolves in several namespaces (e.g. a `useTranslation(['a','b'])`
     *        hook where both files define the key) and the user must pick which one to move.
     */
    data class ResolvedKey(
        val sourceCodeElement: PsiElement,
        val compositeKey: List<Literal>,
        val leavesByNamespace: Map<String, List<PsiElement>>,
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

        val resolved = ReadAction.compute<ResolvedKey?, RuntimeException> { resolveKey(editor, psiFile) }
        if (resolved == null) {
            Messages.showErrorDialog(
                project,
                "Could not resolve an i18n key at the caret. Place the caret on a key that exists in a translation file.",
                "Move i18n Key"
            )
            return
        }

        val keyPath = resolved.compositeKey.joinToString(".") { it.text }

        // When the key resolves in several namespaces (multi-namespace hook), ask which one to move.
        val sourceNs = if (resolved.leavesByNamespace.size > 1) {
            showNamespaceCombo(
                project,
                "Key '$keyPath' exists in several namespaces. Move it from:",
                resolved.leavesByNamespace.keys.sorted()
            ) ?: return
        } else {
            // resolveKey guarantees leavesByNamespace is non-empty, but that invariant
            // lives in another function: fail closed rather than throw if it ever breaks.
            resolved.leavesByNamespace.keys.firstOrNull() ?: return
        }
        val sourceLeaves = resolved.leavesByNamespace[sourceNs] ?: return

        val namespaces = DialogViewModel(project).loadNamespaces().filter { it != sourceNs }
        if (namespaces.isEmpty()) {
            Messages.showErrorDialog(project, "No other namespace found in this project.", "Move i18n Key")
            return
        }

        val targetNs = showNamespaceCombo(project, "Move '$sourceNs:$keyPath' to namespace:", namespaces) ?: return

        if (hasCollision(project, targetNs, resolved.compositeKey)) {
            val choice = Messages.showYesNoDialog(
                project,
                "Key '$keyPath' already exists in namespace '$targetNs'. Overwrite it?",
                "Move i18n Key",
                Messages.getWarningIcon()
            )
            if (choice != Messages.YES) return
        }

        val codeUsages = ReadAction.compute<List<PsiElement>, RuntimeException> {
            collectCodeUsages(sourceLeaves, resolved.sourceCodeElement)
        }

        WriteCommandAction.runWriteCommandAction(project, "Move i18n Key", null, {
            execute(project, sourceLeaves, codeUsages, resolved.compositeKey, targetNs)
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

        // 2. Remove the source entries, separating comma included: a bare delete()
        //    leaves `{,"sibling":…}` behind and corrupts the file whenever the moved
        //    key has a sibling — i.e. almost always in a real translation file.
        sourceLeaves.mapNotNull { toPropertyElement(it) }.forEach { deletePropertyAndSeparator(it) }

        // 3. Rewrite code references with an explicit target-namespace prefix.
        val keyPath = compositeKey.joinToString(".") { it.text }
        val newKey = "$targetNs:$keyPath"
        codeUsages.distinct()
            .groupBy { it.containingFile?.viewProvider?.document }
            .forEach { (doc, elements) ->
                if (doc == null) return@forEach
                // Replace from the end so earlier offsets stay valid across multiple edits in one document.
                elements.sortedByDescending { it.textRange.startOffset }
                    .forEach { rewriteCodeUsage(doc, it, keyPath, newKey) }
            }
    }

    // --- Read-phase collection ---

    /**
     * Resolves the i18n key at the caret to its composite key and value leaves grouped by
     * source namespace. Returns null when there is no resolvable key under the caret.
     *
     * The namespace comes from the *resolved* translation files (not the literal text), so a key
     * whose namespace is provided by a `useTranslation('ns')` / `useTranslation(['a','b'])` hook
     * resolves correctly. Read-only — performs no UI and no writes.
     */
    internal fun resolveKey(editor: Editor, psiFile: PsiFile): ResolvedKey? {
        val ref = findI18nReference(editor, psiFile) ?: return null

        val maxPath = ref.references.maxOfOrNull { it.reference.path.size } ?: return null
        if (maxPath == 0) return null
        val resolved = ref.references.filter { it.reference.path.size == maxPath && it.reference.unresolved.isEmpty() }
        if (resolved.isEmpty()) return null

        val compositeKey = resolved.first().reference.path
        val leavesByNamespace = resolved
            .mapNotNull { d ->
                val ns = namespaceOf(d.reference.localizationSource) ?: return@mapNotNull null
                val leaf = d.reference.element?.value() ?: return@mapNotNull null
                ns to leaf
            }
            .groupBy({ it.first }, { it.second })
        if (leavesByNamespace.isEmpty()) return null

        return ResolvedKey(ref.element, compositeKey, leavesByNamespace)
    }

    /**
     * Collects the code literals referencing the source entries (via the translation properties)
     * plus the literal under the caret. Read-only — must run inside a read action.
     */
    internal fun collectCodeUsages(sourceLeaves: List<PsiElement>, sourceCodeElement: PsiElement): List<PsiElement> {
        val sourceProps = sourceLeaves.mapNotNull { toPropertyElement(it) }
        return (sourceProps.flatMap { prop ->
            ReferencesSearch.search(prop).mapNotNull { it.element }
        } + sourceCodeElement).distinct()
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

    private fun rewriteCodeUsage(doc: Document, element: PsiElement, keyPath: String, newKey: String) {
        val original = element.text
        // Safety: only rewrite a literal that actually carries this key — bare ("key.path")
        // or namespace-prefixed ("<ns>:key.path"). This prevents overwriting an unexpectedly
        // broad host element should reference search ever return one.
        val unquoted = original.unQuote()
        if (unquoted != keyPath && !unquoted.endsWith(":$keyPath")) return

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

    private fun showNamespaceCombo(project: Project, label: String, namespaces: List<String>): String? {
        val combo = ComboBox(namespaces.toTypedArray())
        val panel = JPanel(BorderLayout(0, 8)).apply {
            preferredSize = Dimension(380, 60)
            add(JLabel(label), BorderLayout.NORTH)
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
