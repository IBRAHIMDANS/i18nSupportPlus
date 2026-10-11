package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.dialog.Mode
import com.ibrahimdans.i18n.plugin.ide.dialog.TranslationDialog
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileKeys
import com.ibrahimdans.i18n.plugin.ide.preview.EditorRefresh
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.tree.PluralGroup
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlAttributeValue

/**
 * Opens the translation dialog on the i18n key under the caret in the code (`t('menu.home')`).
 *
 * Correcting a translation meant leaving the code for the JSON file or the tool window. A key that
 * resolves opens in EDIT mode, every locale's value filled in; a key no file holds yet opens in
 * CREATE mode, as the *Create i18n key* quick fix does. Once a value is written, the open editors
 * are refreshed so folding, inlay hints and hints show it at once.
 *
 * The hint popup (Ctrl+hover) offers the same through its *Edit* link, see [HintProvider].
 */
class EditTranslationAtCaretAction : AnAction() {

    /** What the dialog opens on: [fullKey] in [mode], scoped to the module of [caller]. */
    internal data class Target(val fullKey: FullKey, val mode: Mode, val caller: PsiFile?)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    /** Shown on an i18n key of the code only: elsewhere there is nothing to edit. */
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = elementAtCaret(e)?.let(::targetAt) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val element = elementAtCaret(e) ?: return
        edit(project, element)
    }

    private fun elementAtCaret(e: AnActionEvent): PsiElement? {
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return null
        val offset = e.getData(CommonDataKeys.EDITOR)?.caretModel?.offset ?: return null
        // The caret right after the closing quote is still on the key for the user.
        return file.findElementAt(offset) ?: file.findElementAt(offset - 1)
    }

    internal companion object : CompositeKeyResolver<PsiElement> {

        /**
         * Opens the dialog and returns whether a value was written. A test swaps it for something
         * headless — a `DialogWrapper` cannot be shown in a test container.
         */
        var opener: (Project, Target) -> Boolean = { project, target ->
            TranslationDialog(project, target.fullKey, target.mode, target.caller).showAndGet()
        }

        /** Opens the dialog on the key [element] belongs to, then refreshes the editors. Runs on the EDT. */
        fun edit(project: Project, element: PsiElement) {
            val target = ReadAction.compute<Target?, RuntimeException> { targetAt(element) } ?: return
            if (opener(project, target)) EditorRefresh.afterSettingsChange(project)
        }

        /**
         * The code literal [element] belongs to and the key it holds: the leaf of a JS string is
         * the key itself, an attribute value's (`<Trans i18nKey="…">`) is not — its key is the
         * [XmlAttributeValue] above. Null outside an i18n call. Needs a read action.
         */
        fun keyAt(element: PsiElement): Pair<PsiElement, FullKey>? {
            val functionNames = Extensions.TECHNOLOGY.extensionList.flatMap { it.translationFunctionNames() }
            val candidates = listOfNotNull(element, element.parent as? XmlAttributeValue)
            val (literal, lang) = candidates.firstNotNullOfOrNull { candidate ->
                Extensions.LANG.extensionList.firstOrNull { it.canExtractKey(candidate, functionNames) }?.let { candidate to it }
            } ?: return null
            val rawKey = lang.extractRawKey(literal) ?: return null
            val fullKey = RawKeyParser(element.project).parse(rawKey, literal) ?: return null
            return literal to fullKey
        }

        /**
         * What the dialog opens on for [element], or null when it is on no key the dialog can
         * write: outside an i18n call, or on a key only known at runtime (`` t(`status.${kind}`) ``).
         * Needs a read action.
         */
        fun targetAt(element: PsiElement): Target? {
            val (literal, fullKey) = keyAt(element) ?: return null
            if (fullKey.isDynamic || fullKey.compositeKey.isEmpty()) return null
            val caller = literal.containingFile
            val resolved = resolvedTranslation(literal, fullKey)
                ?: return Target(fullKey, Mode.CREATE, caller)
            return Target(editedKey(fullKey, resolved.first, resolved.second), Mode.EDIT, caller)
        }

        /** The translation the hint shows for [fullKey], with the file holding it; null when none does. */
        private fun resolvedTranslation(literal: PsiElement, fullKey: FullKey): Pair<LocalizationSource, PsiElement>? {
            val project = literal.project
            val pluralSeparator = Settings.getInstance(project).config().pluralSeparator
            return project.service<LocalizationSourceService>().findReadSources(fullKey, literal)
                .firstNotNullOfOrNull { source ->
                    resolve(fullKey.compositeKey, source, pluralSeparator).asSequence()
                        .filter { it.unresolved.isEmpty() }
                        .mapNotNull { PluralGroup.displayableValue(it.element) }
                        .firstOrNull()
                        ?.let { source to it.value() }
                }
        }

        /**
         * The key as the dialog reads it: the path of the translation found — a plural `t('item')`
         * resolves to `item_one`, a `keyPrefix` is part of the path — in the namespace of its file
         * when the code took it from its hook (`useTranslation('dashboard')`) rather than writing
         * it. The dialog only reads the key's own namespace, so without it every file was offered.
         */
        private fun editedKey(fullKey: FullKey, source: LocalizationSource, translation: PsiElement): FullKey {
            val path = TranslationFileKeys.pathOf(translation)
            val compositeKey = if (path.isEmpty()) fullKey.compositeKey else path.map(::Literal)
            val ns = fullKey.ns ?: fullKey.allNamespaces().takeIf { it.isNotEmpty() }?.let {
                val config = Settings.getInstance(translation.project).config()
                Literal(TranslationDataLoader.extractNamespace(source, config.defaultNamespaces().first()))
            }
            return fullKey.copy(ns = ns, compositeKey = compositeKey)
        }
    }
}
