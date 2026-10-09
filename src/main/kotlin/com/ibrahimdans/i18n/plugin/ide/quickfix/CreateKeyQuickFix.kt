package com.ibrahimdans.i18n.plugin.ide.quickfix

import com.ibrahimdans.i18n.ContentGenerator
import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.dialog.BatchPlaceholderDialog
import com.ibrahimdans.i18n.plugin.ide.dialog.PlaceholderStrategy
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.AppExecutorUtil

/**
 * Quick fix for missing key creation.
 *
 * After creating the key in the selected locale(s), proposes to fill the remaining locales
 * with a placeholder value chosen by the user via [BatchPlaceholderDialog].
 */
class CreateKeyQuickFix(
    private val fullKey: FullKey,
    private val selector: SourcesSelector,
    private val commandCaption: String,
    private val defaultTranslationValue: String? = null,
    private val onComplete: () -> Unit = {}): QuickFix(), CompositeKeyResolver<PsiElement> {

    override fun getText(): String = commandCaption

    override fun invoke(project: Project, editor: Editor) {
        val fallback = defaultTranslationValue ?: fullKey.source
        // Dialog must be shown outside the write action lock (invokeLater ensures this)
        ApplicationManager.getApplication().invokeLater {
            val inputValue = Messages.showInputDialog(
                project,
                String.format(PluginBundle.getMessage("quickfix.create.key.value.hint"), fullKey.source),
                PluginBundle.getMessage("quickfix.create.key.value.title"),
                Messages.getQuestionIcon()
            )
            val translationValue = if (inputValue.isNullOrEmpty()) fallback else inputValue
            // findSources reaches FileTypeIndex, which the platform forbids on the EDT as a slow
            // operation: the read access it takes is fine, the thread is not. The lookup runs in
            // the background, and only the popup, dialog and writes come back to the EDT.
            ReadAction.nonBlocking<List<LocalizationSource>> {
                // In a monorepo, the key goes to the module of the file it is written in.
                val service = project.service<LocalizationSourceService>()
                val caller = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
                if (caller != null) service.findSources(fullKey.allNamespaces(), caller)
                else service.findSources(fullKey.allNamespaces(), project)
            }
                .inSmartMode(project)
                .expireWith(project)
                .expireWhen { editor.isDisposed }
                .finishOnUiThread(ModalityState.defaultModalityState()) { allSources ->
                    createKeyInSources(project, editor, allSources, translationValue)
                }
                .submit(AppExecutorUtil.getAppExecutorService())
        }
    }

    /** Writes the key into [allSources], asking which ones when there are several. Runs on the EDT. */
    private fun createKeyInSources(
        project: Project,
        editor: Editor,
        allSources: List<LocalizationSource>,
        translationValue: String
    ) {
        if (allSources.size == 1) {
            writeKey(project, allSources, translationValue, onComplete)
        } else if (allSources.size > 1) {
            selector.select(
                allSources,
                { selectedSources ->
                    writeKey(project, selectedSources, translationValue, onComplete)
                    // After writing to selected sources, offer to fill the remaining ones
                    val remainingSources = remainingSources(allSources, selectedSources)
                    if (remainingSources.isNotEmpty()) {
                        offerPlaceholderForRemainingLocales(project, remainingSources, translationValue)
                    }
                },
                editor
            )
        }
    }

    /**
     * Shows [BatchPlaceholderDialog] and applies the chosen placeholder strategy to [remainingSources].
     * Must be called from the EDT (guaranteed by `finishOnUiThread`).
     */
    private fun offerPlaceholderForRemainingLocales(
        project: Project,
        remainingSources: List<LocalizationSource>,
        primaryValue: String
    ) {
        val remainingDisplayPaths = remainingSources.map { it.displayPath }
        val dialog = BatchPlaceholderDialog(project, fullKey.source, primaryValue, remainingDisplayPaths)
        if (!dialog.showAndGet()) return  // user cancelled — leave remaining locales untouched

        val placeholderValue = when (dialog.selectedStrategy()) {
            PlaceholderStrategy.EMPTY_STRING -> ""
            PlaceholderStrategy.KEY_NAME -> fullKey.source
            PlaceholderStrategy.COPY_FROM_DEFAULT -> primaryValue
        }
        writeKey(project, remainingSources, placeholderValue) {}
    }

    /**
     * Writes the key into [targets], then runs [afterWrite] once if at least one was written —
     * all in one command, so one Ctrl+Z undoes the files and the code together.
     *
     * [afterWrite] replaces the extracted text in the editor, at the range it had before the
     * extraction. It used to run after each file: extracting to 26 files replaced that range
     * 26 times, each time over the call the previous one had inserted, and left
     * `{i18n.t('key')}y')}y')}…` in the code.
     *
     * The key is resolved inside the write action rather than before it: [resolveCompositeKey]
     * walks the translation file's PSI, and every caller reaches this method from the EDT, which
     * no longer holds read access implicitly. The write action grants it, and makes resolution
     * and generation atomic: the tree cannot change between the lookup and the write.
     */
    private fun writeKey(project: Project, targets: List<LocalizationSource>, translationValue: String, afterWrite: () -> Unit) {
        CommandProcessor.getInstance().executeCommand(
            project,
            {
                ApplicationManager.getApplication().runWriteAction {
                    val written = targets.count { createPropertyInFile(it, translationValue) }
                    if (written > 0) afterWrite()
                }
            },
            commandCaption,
            UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION
        )
    }

    /**
     * Writes the key into [target]; false when the file offers nowhere to write it. A key the
     * file already holds counts as written: the code still points at it, its value is kept.
     */
    private fun createPropertyInFile(target: LocalizationSource, translationValue: String): Boolean {
        val ref = resolveCompositeKey(fullKey.compositeKey, target) ?: return false
        val element = ref.element ?: return false
        createPropertiesChain(element.value(), ref.unresolved, target.localization.contentGenerator(), translationValue)
        return true
    }

    private fun createPropertiesChain(element: PsiElement, unresolved: List<Literal>, generator: ContentGenerator, translationValue: String) {
        if(generator.isSuitable(element)) {
            generator.generate(element, fullKey, unresolved, translationValue)
        }
    }

    companion object {
        /**
         * The sources [selected] left out that hold the same namespaces: the other locales of the
         * files the key was written to.
         *
         * A key written without a namespace is looked up in every file of the project, so
         * `allSources` can span every namespace. Offering all the files left out wrote the key into
         * `account.json`, `auth.json`, `errors.json`… of every locale, where nothing reads it.
         */
        internal fun remainingSources(
            allSources: List<LocalizationSource>,
            selected: List<LocalizationSource>
        ): List<LocalizationSource> {
            val namespaces = selected.mapTo(mutableSetOf()) { TranslationDataLoader.extractNamespace(it) }
            return allSources.filter { it !in selected && TranslationDataLoader.extractNamespace(it) in namespaces }
        }
    }
}