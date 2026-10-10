package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.dialog.MachineTranslationPreviewDialog
import com.ibrahimdans.i18n.plugin.ide.dialog.TranslationDialog
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.KeySpelling
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.tree.PluralCategories
import com.ibrahimdans.i18n.plugin.translate.MachineTranslationSettings
import com.ibrahimdans.i18n.plugin.translate.PluralTranslationPlan
import com.ibrahimdans.i18n.plugin.translate.Translation
import com.ibrahimdans.i18n.plugin.translate.TranslationProvider
import com.ibrahimdans.i18n.plugin.translate.TranslationRequest
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.ReferenceLocale
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future

/**
 * *Fill Missing Translations*: every key of a module whose target locale is missing or empty is
 * machine-translated from the reference locale, shown in a preview, then written in one command.
 *
 * Nothing is written without the preview, a value already there is never replaced — checked again
 * right before writing — and a proposal the engine got wrong (a variable or tag lost) comes
 * unchecked with its reason. A plural key is translated by the forms the *target* language has
 * ([PluralTranslationPlan]): `few` and `many` for Russian, `other` alone for Japanese.
 */
class FillMissingTranslationsAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val title = PluginBundle.message("action.fill.title")
        val provider = machineTranslator(project) ?: run {
            Messages.showInfoMessage(project, PluginBundle.message("action.fill.disabled"), title)
            return
        }
        val scope = chooseModuleScope(project, title) ?: return
        val config = Settings.getInstance(project).config()
        val locales = TranslationDataLoader.discoverLocales(project, scope.config)
        val source = ReferenceLocale.of(scope.config, config, locales) ?: locales.firstOrNull() ?: return
        val targets = locales.filter { it != source }.toTypedArray()
        if (targets.isEmpty()) {
            Messages.showInfoMessage(project, PluginBundle.message("action.fill.noTarget", source), title)
            return
        }
        val target = Messages.showEditableChooseDialog(
            PluginBundle.message("action.fill.target", source), title, null, targets, targets.first(), null
        )?.takeIf { it in targets } ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                val translations = TranslationDataLoader.loadAllTranslations(project, scope.config)
                val items = MachineFill.itemsOf(translations, source, target, config.keySeparator)
                if (items.isEmpty()) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showInfoMessage(project, PluginBundle.message("action.fill.nothing", target), title)
                    }
                    return
                }
                val proposals = MachineFill.translate(items, provider, source, target, indicator) ?: return
                ApplicationManager.getApplication().invokeLater {
                    val dialog = MachineTranslationPreviewDialog(project, target, proposals)
                    if (dialog.showAndGet()) MachineFill.write(project, scope.config, target, dialog.accepted())
                }
            }
        })
    }

    companion object {
        /** The project's machine translation, null when it has not opted in; a test swaps it. */
        internal var machineTranslator: (Project) -> TranslationProvider? = { MachineTranslationSettings.getInstance(it).provider() }
    }
}

/** The steps of *Fill Missing Translations*, apart from the UI. */
internal object MachineFill {

    /** At most this many requests at once: an engine's rate limit is easy to reach with a whole module. */
    const val PARALLEL_REQUESTS = 4

    /**
     * A key to translate, with its [source] text. [needsReview]: a plural form translated from the
     * source's `other`, the source having no form of its category. [skipped]: why it is not sent at all.
     */
    data class Item(val key: String, val source: String, val needsReview: Boolean = false, val skipped: String? = null)

    /** What the engine proposed for [item]. */
    data class Proposal(val item: Item, val result: Translation)

    /**
     * What to translate in [translations] (`key -> locale -> value`) from [source] to [target], sorted:
     * - a plain key whose target value is missing or blank and whose source value is not;
     * - for a plural group (`file_one` / `file_other`, or `steps.one` / `steps.other` — a key is a
     *   plural form only when its group has an `other`), each form the target language needs and
     *   lacks, from the source form of the same category or, marked for review, from `other`;
     * - an ICU plural (`{count, plural, …}`), as a line saying why it is not translated.
     */
    fun itemsOf(translations: Map<String, Map<String, String>>, source: String, target: String, keySeparator: String): List<Item> {
        val items = mutableListOf<Item>()
        // other key -> category -> key, for the keys reading as plural forms of a group holding an `other`.
        val groups = mutableMapOf<String, MutableMap<String, String>>()
        for ((key, values) in translations) {
            val form = TranslationDialog.pluralFormOf(key, keySeparator, CLDR_SUFFIX_SEPARATOR)
            if (form != null && form.second in translations) {
                groups.getOrPut(form.second) { mutableMapOf() }[form.first] = key
                continue
            }
            val text = values[source]
            if (text.isNullOrBlank() || !values[target].isNullOrBlank()) continue
            items += if (PluralTranslationPlan.isIcuPlural(text)) Item(key, text, skipped = PluginBundle.message("action.fill.icu"))
            else Item(key, text)
        }
        for ((otherKey, forms) in groups) {
            val sourceForms = forms.mapNotNull { (category, key) -> translations[key]?.get(source)?.let { category to it } }.toMap()
            val existing = forms.mapNotNull { (category, key) -> translations[key]?.get(target)?.let { category to it } }.toMap()
            when (val plan = PluralTranslationPlan.of(sourceForms, target, existing)) {
                PluralTranslationPlan.Plan.IcuPlural ->
                    if (existing.values.all { it.isBlank() }) {
                        items += Item(otherKey, sourceForms[PluralCategories.OTHER].orEmpty(), skipped = PluginBundle.message("action.fill.icu"))
                    }
                is PluralTranslationPlan.Plan.Forms -> plan.forms.forEach { form ->
                    items += Item(otherKey.removeSuffix(PluralCategories.OTHER) + form.category, form.source, form.needsReview)
                }
            }
        }
        return items.sortedBy { it.key }
    }

    /** Between a base and its CLDR category in i18next's flat convention, as [TranslationDialog] reads it. */
    private const val CLDR_SUFFIX_SEPARATOR = "_"

    /**
     * Each item translated, [PARALLEL_REQUESTS] at a time, the key sent as context. Null when the
     * user cancelled: nothing is then proposed, and nothing written.
     */
    fun translate(items: List<Item>, provider: TranslationProvider, source: String, target: String, indicator: ProgressIndicator?): List<Proposal>? {
        val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("i18n machine translation", PARALLEL_REQUESTS)
        try {
            val futures: List<Future<Translation>> = items.map { item ->
                executor.submit<Translation> {
                    item.skipped?.let { return@submit Translation.Failed(it) }
                    if (indicator?.isCanceled == true) return@submit Translation.Failed("")
                    val context = "A UI string of an application, under the i18n key ${item.key}"
                    provider.translate(TranslationRequest(listOf(item.source), source, target, context), indicator).single()
                }
            }
            val results = futures.mapIndexed { index, future ->
                indicator?.fraction = index.toDouble() / items.size
                try {
                    future.get()
                } catch (e: ExecutionException) {
                    if (e.cause is ProcessCanceledException) return null
                    Translation.Failed(e.cause?.message.orEmpty())
                }
            }
            if (indicator?.isCanceled == true) return null
            return items.zip(results) { item, result -> Proposal(item, result) }
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * Writes [accepted] (`key -> value`) into [target]'s files of [module], in one command: one Ctrl+Z
     * undoes it all. A value filled since the preview opened is left as it is.
     */
    fun write(project: Project, module: ModuleConfig?, target: String, accepted: Map<String, String>) {
        if (accepted.isEmpty()) return
        val config = Settings.getInstance(project).config()
        val synchronizer = KeysSynchronizer()
        val sources = ReadAction.compute<List<LocalizationSource>, RuntimeException> { TranslationDataLoader.findSources(project, module) }
        val current = TranslationDataLoader.loadAllTranslations(project, module)
        val viewModel = DialogViewModel(project)
        WriteCommandAction.runWriteCommandAction(project, PluginBundle.message("action.fill.command"), null, {
            accepted.forEach { (key, value) ->
                if (!current[key]?.get(target).isNullOrBlank()) return@forEach
                val file = sourceFor(key, target, sources) ?: return@forEach
                viewModel.saveTranslation(file, synchronizer.buildFullKey(key, config), value)
            }
        })
    }

    /** [target]'s file for [key]'s namespace, routed as the CSV import routes it. */
    private fun sourceFor(key: String, target: String, sources: List<LocalizationSource>): LocalizationSource? {
        val namespace = KeySpelling.namespaceOf(key)
        return sources.firstOrNull { source ->
            TranslationDataLoader.extractLocale(source) == target &&
                (namespace == null || TranslationDataLoader.extractNamespace(source) == namespace)
        }
    }
}
