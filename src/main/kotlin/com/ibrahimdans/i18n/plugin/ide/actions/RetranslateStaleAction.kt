package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.review.ReviewLocation
import com.ibrahimdans.i18n.plugin.review.ReviewStore
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.ReferenceLocale
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * *Retranslate Stale Translations*: the values of a module whose source text changed since they were
 * reviewed — the cells the table shows as *Source changed* — machine-translated again from the
 * reference locale, previewed next to the value they replace, then written and marked to review on
 * the new source in one command ([MachineFill.retranslate]).
 *
 * The module and the locales are chosen as *Fill Missing Translations* chooses them. Only offered
 * where reviews are on somewhere in the project: without reviews, nothing records the source a value
 * was translated from, and nothing can be stale.
 */
class RetranslateStaleAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && reviewsOn(project)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val title = PluginBundle.message("action.retranslate.title")
        val provider = FillMissingTranslationsAction.machineTranslator(project) ?: run {
            Messages.showInfoMessage(project, PluginBundle.message("action.fill.disabled"), title)
            return
        }
        val scope = chooseModuleScope(project, title) ?: return
        val root = ReviewLocation.rootOf(project, scope.config)
        if (root == null || !ReviewStore.getInstance(project).isEnabled(root)) {
            Messages.showInfoMessage(project, PluginBundle.message("action.retranslate.noReviews"), title)
            return
        }
        val config = Settings.getInstance(project).config()
        val locales = TranslationDataLoader.discoverLocales(project, scope.config)
        val source = ReferenceLocale.of(scope.config, config, locales) ?: return
        val targets = locales.filter { it != source }.toTypedArray()
        if (targets.isEmpty()) {
            Messages.showInfoMessage(project, PluginBundle.message("action.fill.noTarget", source), title)
            return
        }
        val all = PluginBundle.message("action.fill.target.all")
        val choices = if (targets.size > 1) arrayOf(all) + targets else targets
        val chosen = Messages.showEditableChooseDialog(
            PluginBundle.message("action.retranslate.target", source), title, null, choices, choices.first(), null
        )?.takeIf { it in choices } ?: return
        MachineFill.retranslate(project, scope.config, source, if (chosen == all) targets.toList() else listOf(chosen), provider)
    }

    internal companion object {
        /**
         * True when reviews are on under the project's directory or the root of one of its modules —
         * where [chooseModuleScope] can lead. A few folder lookups, cheap enough for [update].
         */
        fun reviewsOn(project: Project): Boolean {
            val store = ReviewStore.getInstance(project)
            val modules = Settings.getInstance(project).modules
            return (modules.map { ReviewLocation.rootOf(project, it) } + ReviewLocation.rootOf(project, null))
                .any { root -> root != null && store.isEnabled(root) }
        }
    }
}
