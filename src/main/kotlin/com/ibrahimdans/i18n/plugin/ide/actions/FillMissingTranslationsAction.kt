package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.dialog.MachineTranslationPreviewDialog
import com.ibrahimdans.i18n.plugin.ide.dialog.TranslationDialog
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
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
        MachineFill.run(project, scope.config, source, target, provider)
    }

    companion object {
        /** The project's machine translation, null when it has not opted in; a test swaps it. */
        internal var machineTranslator: (Project) -> TranslationProvider? = { MachineTranslationSettings.getInstance(it).provider() }
    }
}

/** The steps of *Fill Missing Translations*, apart from the UI. */
internal object MachineFill {

    /** At most this many provider calls at once: an engine's rate limit is easy to reach with a whole module. */
    const val PARALLEL_REQUESTS = 4

    /**
     * At most this many keys per provider call. An engine with a batch form sends them in one request,
     * cut again at its own limit (#438); one without sends them one by one.
     */
    const val KEYS_PER_CALL = 50

    /**
     * *Fill Missing Translations* for [module] from [source] to [target]: translated in the background,
     * previewed, then written in one command — whether asked from the Tools menu or from a locale
     * column of the table. [onWritten] runs on the EDT once the values are written.
     */
    fun run(project: Project, module: ModuleConfig?, source: String, target: String, provider: TranslationProvider, onWritten: () -> Unit = {}) {
        val title = PluginBundle.message("action.fill.title")
        val keySeparator = Settings.getInstance(project).config().keySeparator
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                val translations = TranslationDataLoader.loadAllTranslations(project, module)
                val items = itemsOf(translations, source, target, keySeparator)
                if (items.isEmpty()) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showInfoMessage(project, PluginBundle.message("action.fill.nothing", target), title)
                    }
                    return
                }
                val proposals = translate(items, provider, source, target, indicator) ?: return
                ApplicationManager.getApplication().invokeLater {
                    val dialog = MachineTranslationPreviewDialog(project, target, proposals)
                    if (dialog.showAndGet()) {
                        write(project, module, target, dialog.accepted())
                        onWritten()
                    }
                }
            }
        })
    }

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
     * Each item translated, [KEYS_PER_CALL] per provider call and [PARALLEL_REQUESTS] calls at a time,
     * the keys of a call sent as its context. A skipped item is never sent. Null when the user
     * cancelled: nothing is then proposed, and nothing written.
     */
    fun translate(items: List<Item>, provider: TranslationProvider, source: String, target: String, indicator: ProgressIndicator?): List<Proposal>? {
        val sent = items.filter { it.skipped == null }
        val calls = sent.chunked(KEYS_PER_CALL)
        val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("i18n machine translation", PARALLEL_REQUESTS)
        try {
            val futures: List<Future<List<Translation>>> = calls.map { call ->
                executor.submit<List<Translation>> {
                    if (indicator?.isCanceled == true) return@submit call.map { Translation.Failed("") }
                    provider.translate(TranslationRequest(call.map { it.source }, source, target, contextOf(call)), indicator)
                }
            }
            val translated = mutableMapOf<Item, Translation>()
            futures.forEachIndexed { index, future ->
                indicator?.fraction = index.toDouble() / calls.size
                val results = try {
                    future.get()
                } catch (e: ExecutionException) {
                    if (e.cause is ProcessCanceledException) return null
                    calls[index].map { Translation.Failed(e.cause?.message.orEmpty()) }
                }
                calls[index].zip(results).forEach { (item, result) -> translated[item] = result }
            }
            if (indicator?.isCanceled == true) return null
            return items.map { item -> Proposal(item, item.skipped?.let { Translation.Failed(it) } ?: translated.getValue(item)) }
        } finally {
            executor.shutdownNow()
        }
    }

    /** What the engine is told about [call]: UI strings, and their keys in order — a hint to each one's meaning. */
    internal fun contextOf(call: List<Item>): String =
        if (call.size == 1) "A UI string of an application, under the i18n key ${call.single().key}"
        else "UI strings of an application, under the i18n keys, in order: ${call.joinToString(", ") { it.key }}"

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
                val file = translationFileFor(key, target, sources) ?: return@forEach
                viewModel.saveTranslation(file, synchronizer.buildFullKey(key, config), value)
            }
        })
    }
}
