package com.ibrahimdans.i18n.plugin.ide.quickfix

import com.ibrahimdans.i18n.ContentGenerator
import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.dialog.BatchPlaceholderDialog
import com.ibrahimdans.i18n.plugin.ide.dialog.PlaceholderStrategy
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement

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
            val sourceService = project.service<LocalizationSourceService>()
            val allSources = sourceService.findSources(fullKey.allNamespaces(), project)

            if (allSources.size == 1) {
                createPropertyInFile(project, allSources.first(), translationValue)
            } else if (allSources.size > 1) {
                // Track which sources were actually written by the selector
                val writtenSources = mutableListOf<LocalizationSource>()
                selector.select(
                    allSources,
                    { selectedSources ->
                        selectedSources.forEach { source ->
                            createPropertyInFile(project, source, translationValue)
                            writtenSources.add(source)
                        }
                        // After writing to selected sources, offer to fill the remaining ones
                        val remainingSources = allSources.filter { it !in writtenSources }
                        if (remainingSources.isNotEmpty()) {
                            offerPlaceholderForRemainingLocales(project, remainingSources, translationValue)
                        }
                    },
                    editor
                )
            }
        }
    }

    /**
     * Shows [BatchPlaceholderDialog] and applies the chosen placeholder strategy to [remainingSources].
     * Must be called from the EDT (already guaranteed by [invokeLater]).
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
        remainingSources.forEach { source ->
            createPropertyInFile(project, source, placeholderValue)
        }
    }

    private fun createPropertyInFile(project: Project, target: LocalizationSource, translationValue: String) {
        val ref = resolveCompositeKey(
            fullKey.compositeKey,
            target
        ) ?: return
        if (ref.element != null) {
            CommandProcessor.getInstance().executeCommand(
                project,
                {
                    ApplicationManager.getApplication().runWriteAction {
                        createPropertiesChain(ref.element.value(), ref.unresolved, target.localization.contentGenerator(), translationValue)
                        onComplete()
                    }
                },
                commandCaption,
                UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION
            )
        }
    }

    private fun createPropertiesChain(element: PsiElement, unresolved: List<Literal>, generator: ContentGenerator, translationValue: String) {
        if(generator.isSuitable(element)) {
            generator.generate(element, fullKey, unresolved, translationValue)
        }
    }
}