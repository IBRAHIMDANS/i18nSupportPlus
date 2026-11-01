package com.ibrahimdans.i18n.plugin.ide.dialog

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.ReviewBadge
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.review.ReviewKey
import com.ibrahimdans.i18n.plugin.review.ReviewSource
import com.ibrahimdans.i18n.plugin.review.ReviewSources
import com.ibrahimdans.i18n.plugin.review.ReviewStore
import com.ibrahimdans.i18n.plugin.review.ShownReview
import com.ibrahimdans.i18n.plugin.review.shown
import com.ibrahimdans.i18n.plugin.review.ui.ReviewActions
import com.ibrahimdans.i18n.plugin.review.ui.ReviewCell
import com.ibrahimdans.i18n.plugin.review.ui.ReviewThreadDialog
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.ReferenceLocale
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.ui.components.JBLabel

/**
 * The translation dialog's reviews: the state shown under each locale of a module that keeps reviews,
 * the *Review…* thread it opens, and the *to review* marks *OK* leaves on machine proposals.
 *
 * [sources] are the values stored when the dialog opened: a review speaks of what is stored, never of
 * what is being typed.
 */
internal class DialogReviews(
    private val project: Project,
    private val fullKey: FullKey,
    private val sources: Map<LocalizationSource, String?>,
    private val viewModel: DialogViewModel,
    private val caller: PsiFile?,
    private val tabModule: ModuleConfig?,
) {

    /** The review state shown under each locale; only for the locales of a module that keeps reviews. */
    val badges = LinkedHashMap<LocalizationSource, JBLabel>()

    /** Where each source's reviews live, null when its module keeps none; read once per dialog. */
    private val roots = HashMap<LocalizationSource, VirtualFile?>()

    /** The module each source belongs to, null when no module of the settings holds it; read once per dialog. */
    private val owners = HashMap<LocalizationSource, ModuleConfig?>()

    /**
     * The directory [source]'s reviews hang under — its module's, see [ReviewActions.enabledRoot] —
     * or null when that module keeps no reviews. Never creates anything.
     */
    fun rootOf(source: LocalizationSource): VirtualFile? {
        if (source in roots) return roots[source]
        return TranslationDialog.reviewRoot(project, moduleOf(source)).also { roots[source] = it }
    }

    /** The module [source]'s file belongs to, or null when no module of the settings holds it. */
    fun moduleOf(source: LocalizationSource): ModuleConfig? {
        if (source in owners) return owners[source]
        val modules = Settings.getInstance(project).config().modules
        val module = if (modules.isEmpty()) null else ModuleSources.owner(modules, TranslationDataLoader.projectPathOf(source))
        return module.also { owners[source] = it }
    }

    /** A badge showing what the review of [source]'s stored value says, kept to be refreshed. */
    fun badgeOf(source: LocalizationSource, root: VirtualFile): JBLabel =
        JBLabel().also {
            badges[source] = it
            show(source, root, it)
        }

    /**
     * What [source]'s stored value is compared against: the key it is translated from ([ReviewSources]:
     * the same key, or a plural form's `other`), as stored, in the reference locale of [source]'s
     * module — [ReferenceLocale.of] over that module's locales, as the table reads it — as its locale
     * and its text. Null for the reference locale itself, and when the reference holds no value.
     */
    private fun currentSourceOf(source: LocalizationSource): Pair<String, String>? {
        val module = moduleOf(source)
        val inModule = sources.keys.filter { moduleOf(it) == module }
        val config = Settings.getInstance(project).config()
        val reference = ReferenceLocale.of(module, config, inModule.map { it.localeLabel() }.distinct()) ?: return null
        if (source.localeLabel() == reference) return null
        val last = fullKey.compositeKey.lastOrNull() ?: return null
        val text = ReviewSources.sourceTextOf(last.text, config.pluralSeparator) { segment ->
            val values = if (segment == last.text) sources
            else viewModel.loadTranslations(fullKey.copy(compositeKey = fullKey.compositeKey.dropLast(1) + Literal(segment)), caller, tabModule)
            values.entries.firstOrNull { it.key.localeLabel() == reference && moduleOf(it.key) == module }?.value
        }
        return text?.let { reference to it }
    }

    /**
     * [key] in [source] as the tool window spells it — its namespace left out when it is a default
     * one — so that the dialog and the table read and write the same review.
     */
    private fun spellingOf(source: LocalizationSource, key: FullKey): String {
        val defaults = Settings.getInstance(project).config().defaultNamespaces()
        val namespace = TranslationDataLoader.extractNamespace(source, defaults.first())
        return ReviewKey(namespace, key.compositeKey.joinToString(".") { it.text }).spelling(defaults)
    }

    private fun keyOf(source: LocalizationSource, key: FullKey): ReviewKey =
        ReviewKey.fromSpelling(spellingOf(source, key), Settings.getInstance(project).config().defaultNamespaces().first())

    /** What the review of [source]'s stored value says, in [badge]. */
    private fun show(source: LocalizationSource, root: VirtualFile, badge: JBLabel) {
        val review = ReviewStore.getInstance(project).reviews(root, source.localeLabel())[keyOf(source, fullKey)]
        val current = currentSourceOf(source)?.let { (locale, text) -> ReviewSource.of(locale, text) }
        val state = shown(review, sources[source], current)
        badge.icon = ReviewBadge.icon(state)
        badge.text = if (state == ShownReview.NONE) PluginBundle.message("review.dialog.state.none") else ReviewBadge.label(state)
    }

    /**
     * The review thread of [source]'s locale, on the value stored — not the one being typed — and on
     * the source stored in its module's reference locale, which every decision taken there records.
     */
    fun open(source: LocalizationSource, root: VirtualFile) {
        val current = currentSourceOf(source)
        val cell = ReviewCell(spellingOf(source, fullKey), source.localeLabel(), sources[source].orEmpty(), current?.second)
        TranslationDialog.reviewThread(ReviewThreadDialog(project, root, cell, referenceLocale = current?.first))
        badges[source]?.let { show(source, root, it) }
    }

    /** Clicks the *Review…* link of [locale]; false when it has none (reviews off, or CREATE mode). */
    fun openOf(locale: String): Boolean {
        val source = badges.keys.firstOrNull { it.localeLabel() == locale } ?: return false
        open(source, rootOf(source) ?: return false)
        return true
    }

    /** The review state shown under [locale], or null when it shows none — nor a *Review…* link. */
    fun stateOf(locale: String): String? = badges.entries.firstOrNull { it.key.localeLabel() == locale }?.value?.text

    /**
     * Marks *to review* each locale of [written] holding *Translate*'s proposal unchanged, with the
     * engine it came from and the source it was translated from — in the current command, for its
     * undo. A module keeping no reviews gets nothing, not even a folder: only an explicit choice
     * turns reviews on.
     */
    fun markMachineValues(
        key: FullKey,
        written: Map<LocalizationSource, String>,
        proposals: Map<LocalizationSource, TranslationDialog.MachineProposal>,
    ) {
        val toMark = DialogMachineTranslation.untouchedProposals(proposals, written)
            .mapNotNull { (source, proposal) -> rootOf(source)?.let { Triple(source, it, proposal) } }
        if (toMark.isEmpty()) return
        val store = ReviewStore.getInstance(project)
        ApplicationManager.getApplication().runWriteAction {
            toMark.forEach { (source, root, proposal) ->
                val reviewKey = keyOf(source, key)
                val sources = proposal.source?.let { mapOf(reviewKey to it) }.orEmpty()
                store.mark(root, source.localeLabel(), mapOf(reviewKey to proposal.text), proposal.origin, sources)
            }
        }
        // No editor holds the review file: a global command is what Ctrl+Z reaches from anywhere.
        CommandProcessor.getInstance().markCurrentCommandAsGlobal(project)
    }
}
