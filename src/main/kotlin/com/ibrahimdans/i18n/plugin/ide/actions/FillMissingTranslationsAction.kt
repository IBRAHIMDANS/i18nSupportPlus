package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.dialog.MachineTranslationPreviewDialog
import com.ibrahimdans.i18n.plugin.ide.dialog.TranslationDialog
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TableReviews
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TableViewModel
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.review.Review
import com.ibrahimdans.i18n.plugin.review.ReviewKey
import com.ibrahimdans.i18n.plugin.review.ReviewLocation
import com.ibrahimdans.i18n.plugin.review.ReviewSource
import com.ibrahimdans.i18n.plugin.review.ReviewSources
import com.ibrahimdans.i18n.plugin.review.ReviewStore
import com.ibrahimdans.i18n.plugin.review.ShownReview
import com.ibrahimdans.i18n.plugin.tree.PluralCategories
import com.ibrahimdans.i18n.plugin.translate.MachineTranslationSettings
import com.ibrahimdans.i18n.plugin.translate.PluralTranslationPlan
import com.ibrahimdans.i18n.plugin.translate.Translation
import com.ibrahimdans.i18n.plugin.translate.TranslationProvider
import com.ibrahimdans.i18n.plugin.translate.TranslationRequest
import com.ibrahimdans.i18n.plugin.translate.asCancellation
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.PluginNotifications
import com.ibrahimdans.i18n.plugin.utils.ReferenceLocale
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
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
            PluginNotifications.notify(project, PluginBundle.message("action.fill.noTarget", source), title = title)
            return
        }
        val all = PluginBundle.message("action.fill.target.all")
        val choices = if (targets.size > 1) arrayOf(all) + targets else targets
        val chosen = Messages.showEditableChooseDialog(
            PluginBundle.message("action.fill.target", source), title, null, choices, choices.first(), null
        )?.takeIf { it in choices } ?: return
        MachineFill.run(project, scope.config, source, if (chosen == all) targets.toList() else listOf(chosen), provider)
    }

    companion object {
        /** The project's machine translation, null when it has not opted in; a test swaps it. */
        internal var machineTranslator: (Project) -> TranslationProvider? = { MachineTranslationSettings.getInstance(it).provider() }
    }
}

/** The steps of *Fill Missing Translations* and *Retranslate Stale Translations*, apart from the UI. */
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
    fun run(project: Project, module: ModuleConfig?, source: String, target: String, provider: TranslationProvider, onWritten: () -> Unit = {}) =
        run(project, module, source, listOf(target), provider, onWritten)

    /**
     * The same for every locale of [targets] at once: one preview, a *Locale* column telling them
     * apart, and one command writing them all.
     */
    fun run(project: Project, module: ModuleConfig?, source: String, targets: List<String>, provider: TranslationProvider, onWritten: () -> Unit = {}) {
        val keySeparator = Settings.getInstance(project).config().keySeparator
        preview(project, module, source, targets, provider, Pass.FILL, onWritten) { translations, target ->
            itemsOf(translations, source, target, keySeparator)
        }
    }

    /**
     * *Retranslate Stale Translations* for [module] from [source] — its reference locale — to
     * [targets]: the values whose source text changed since they were reviewed
     * ([ShownReview.SOURCE_CHANGED], see [staleItemsOf]), translated again, previewed next to the
     * value they replace, then written — each one only while it still holds that value — and marked
     * to review on the new source, in one command. Nothing is proposed where reviews are off.
     */
    fun retranslate(project: Project, module: ModuleConfig?, source: String, targets: List<String>, provider: TranslationProvider, onWritten: () -> Unit = {}) {
        val config = Settings.getInstance(project).config()
        // Read in the background, once for all the targets.
        val reviews by lazy {
            TableViewModel().loadReviews(project, ReviewLocation.rootOf(project, module), (listOf(source) + targets).distinct(), config, module)
        }
        preview(project, module, source, targets, provider, Pass.RETRANSLATE, onWritten) { translations, target ->
            staleItemsOf(translations, reviews, target)
        }
    }

    /** What tells a fill from a retranslation apart: the bundle keys of its title, its empty case and its undo. */
    private enum class Pass(val title: String, val nothing: String, val command: String) {
        FILL("action.fill.title", "action.fill.nothing", "action.fill.command"),
        RETRANSLATE("action.retranslate.title", "action.retranslate.nothing", "action.retranslate.command"),
    }

    /**
     * The items [itemsFor] picks in each locale of [targets] (`translations, target -> items`, run
     * in the background), translated, previewed, then written with their marks in one command: what
     * a fill and a retranslation share.
     */
    private fun preview(
        project: Project, module: ModuleConfig?, source: String, targets: List<String>, provider: TranslationProvider,
        pass: Pass, onWritten: () -> Unit, itemsFor: (Map<String, Map<String, String>>, String) -> List<Item>,
    ) {
        val title = PluginBundle.message(pass.title)
        val label = targets.joinToString(", ")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                val translations = TranslationDataLoader.loadAllTranslations(project, module)
                val proposals = mutableListOf<Proposal>()
                for (target in targets) {
                    indicator.text = PluginBundle.message("action.fill.progress.locale", target)
                    val items = itemsFor(translations, target)
                    if (items.isNotEmpty()) proposals += translate(items, provider, source, target, indicator) ?: return
                }
                if (proposals.isEmpty()) {
                    PluginNotifications.notify(project, PluginBundle.message(pass.nothing, label), title = title)
                    return
                }
                ApplicationManager.getApplication().invokeLater {
                    val reviewRoot = ReviewLocation.rootOf(project, module)
                    val dialog = MachineTranslationPreviewDialog(project, label, proposals, listOfNotNull(reviewRoot))
                    if (dialog.showAndGet()) {
                        val marks = reviewRoot?.let { root ->
                            dialog.reviewOrigins()?.let { ReviewMarks(root, it, reviewSourcesOf(proposals, source)) }
                        }
                        write(project, module, dialog.acceptedByLocale(), marks, replacedOf(proposals), PluginBundle.message(pass.command))
                        onWritten()
                    }
                }
            }
        })
    }

    /**
     * A key to translate, with its [source] text. [needsReview]: a plural form translated from the
     * source's `other`, the source having no form of its category. [skipped]: why it is not sent at all.
     * [current]: the value the translation replaces, as read for the preview — null for a value to fill.
     */
    data class Item(
        val key: String, val source: String, val needsReview: Boolean = false, val skipped: String? = null,
        val current: String? = null,
    )

    /** What the engine proposed for [item], in the [locale] it fills. */
    data class Proposal(val item: Item, val result: Translation, val locale: String = "")

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

    /**
     * The stale translations of [target] in [translations] (`key -> locale -> value`), sorted: each
     * key whose [target] value is the one its review was decided on while its source — the key it is
     * translated from in [reviews]' reference locale, see [ReviewSources] — changed since
     * ([ShownReview.SOURCE_CHANGED], as the table shows it). Each one is translated again from that
     * source, the value it replaces kept as [Item.current]; an ICU plural is listed with why it is not
     * translated, as *Fill* does. None where reviews are off, nor in the reference locale itself.
     *
     * A plural form the source lacks (`item_few` against an English `item_one` / `item_other`) is
     * translated again from the source's `other`, and [needs review][Item.needsReview] as *Fill* has it.
     */
    fun staleItemsOf(translations: Map<String, Map<String, String>>, reviews: TableReviews, target: String): List<Item> {
        val reference = reviews.referenceLocale
        if (!reviews.enabled || reference == null || reference == target) return emptyList()
        return translations.mapNotNull { (key, values) ->
            val current = values[target]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val source = ReviewSources.sourceTextOf(key, reviews.pluralSeparator) { translations[it]?.get(reference) }
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (reviews.shownOf(key, target, current, source) != ShownReview.SOURCE_CHANGED) return@mapNotNull null
            if (PluralTranslationPlan.isIcuPlural(source)) Item(key, source, skipped = PluginBundle.message("action.fill.icu"), current = current)
            else Item(key, source, needsReview = values[reference].isNullOrEmpty(), current = current)
        }.sortedBy { it.key }
    }

    /**
     * The values [proposals] replace, `locale -> key -> value read for the preview`: a write replaces
     * each one only while it is still that value. Empty for a fill.
     */
    fun replacedOf(proposals: List<Proposal>): Map<String, Map<String, String>> =
        proposals.mapNotNull { proposal -> proposal.item.current?.let { proposal to it } }
            .groupBy({ it.first.locale }, { it.first.item.key to it.second })
            .mapValues { it.value.toMap() }

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
                    provider.translate(TranslationRequest(call.map { it.source }, source, target, contextOf(call)), indicator?.asCancellation())
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
            return items.map { item -> Proposal(item, item.skipped?.let { Translation.Failed(it) } ?: translated.getValue(item), target) }
        } finally {
            executor.shutdownNow()
        }
    }

    /** What the engine is told about [call]: UI strings, and their keys in order — a hint to each one's meaning. */
    internal fun contextOf(call: List<Item>): String =
        if (call.size == 1) "A UI string of an application, under the i18n key ${call.single().key}"
        else "UI strings of an application, under the i18n keys, in order: ${call.joinToString(", ") { it.key }}"

    /** Where a translation by the machine comes from, as a review records it: `machine:<engine>`. */
    fun originOf(result: Translation.Done): String = Review.machineOrigin(result.engine?.name ?: UNKNOWN_ENGINE)

    /** The engine named for a text no engine saw — a blank one kept as is. */
    private const val UNKNOWN_ENGINE = "unknown"

    /**
     * The source [item] was translated from, read in [locale]: its text, as sent to the engine — for a
     * plural form translated from `other`, that form's, the source [ReviewSources] compares it with
     * since. None for an item never sent.
     */
    fun reviewSourceOf(item: Item, locale: String): ReviewSource? =
        if (item.skipped != null) null else ReviewSource.of(locale, item.source)

    /** The source each of [proposals] was translated from, `locale -> key -> source`, read in [source]. See [reviewSourceOf]. */
    fun reviewSourcesOf(proposals: List<Proposal>, source: String): Map<String, Map<String, ReviewSource>> =
        proposals.mapNotNull { proposal -> reviewSourceOf(proposal.item, source)?.let { proposal to it } }
            .groupBy({ it.first.locale }, { it.first.item.key to it.second })
            .mapValues { it.value.toMap() }

    /**
     * The values a write is to mark for review, under [root] (the module's, see [ReviewLocation]):
     * the origin of each one, `locale -> key -> machine:<engine>`, and the source it was translated
     * from, `locale -> key -> source` — a key without one is marked without source.
     */
    data class ReviewMarks(
        val root: VirtualFile,
        val origins: Map<String, Map<String, String>>,
        val sources: Map<String, Map<String, ReviewSource>> = emptyMap()
    )

    /**
     * Writes [accepted] (`key -> value`) into [target]'s files of [module], in one command: one Ctrl+Z
     * undoes it all. A value filled since the preview opened is left as it is.
     */
    fun write(project: Project, module: ModuleConfig?, target: String, accepted: Map<String, String>) =
        write(project, module, mapOf(target to accepted))

    /**
     * [accepted] (`locale -> key -> value`) written in one command, whatever the number of locales.
     * A key of [replaced] (`locale -> key -> value read for the preview`) replaces its value only
     * while it is still that one — edited since, it is left as it is; any other key only fills a
     * missing or blank value. With [marks], each value written — not one left as it is — is marked
     * to review in the same command, reviews turned on under the root first: one Ctrl+Z, named
     * [command], undoes the values and their marks.
     */
    fun write(
        project: Project, module: ModuleConfig?, accepted: Map<String, Map<String, String>>, marks: ReviewMarks? = null,
        replaced: Map<String, Map<String, String>> = emptyMap(), command: String = PluginBundle.message("action.fill.command"),
    ) {
        if (accepted.values.all { it.isEmpty() }) return
        val config = Settings.getInstance(project).config()
        val synchronizer = KeysSynchronizer()
        val sources = ReadAction.compute<List<LocalizationSource>, RuntimeException> { TranslationDataLoader.findSources(project, module) }
        val current = TranslationDataLoader.loadAllTranslations(project, module)
        val viewModel = DialogViewModel(project)
        WriteCommandAction.writeCommandAction(project)
            .withName(command)
            .withGlobalUndo()
            .withUndoConfirmationPolicy(UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION)
            .run<RuntimeException> {
                val written = accepted.mapValues { (target, values) ->
                    values.filter { (key, value) ->
                        val now = current[key]?.get(target)
                        val seen = replaced[target]?.get(key)
                        if (if (seen == null) !now.isNullOrBlank() else now != seen) return@filter false
                        val file = translationFileFor(key, target, sources) ?: return@filter false
                        viewModel.saveTranslation(file, synchronizer.buildFullKey(key, config), value)
                        true
                    }
                }
                if (marks != null) {
                    val defaultNamespace = config.defaultNamespaces().first()
                    markWritten(project, marks.root, written, marks.origins, marks.sources) { ReviewKey.fromSpelling(it, defaultNamespace) }
                }
            }
    }

    /**
     * Under [root], [written] (`locale -> key -> value`) marked to review with its origin in [origins]
     * (`locale -> key -> machine:<engine>`); a key without one is not marked. The source each value was
     * translated from, in [sources] (`locale -> key -> source`), is recorded with its mark, so that a
     * source changed since shows. Reviews are turned on under [root] when anything is to be marked.
     * In the caller's write command, for its undo.
     */
    fun markWritten(
        project: Project, root: VirtualFile, written: Map<String, Map<String, String>>,
        origins: Map<String, Map<String, String>>, sources: Map<String, Map<String, ReviewSource>> = emptyMap(),
        keyOf: (String) -> ReviewKey
    ) {
        val toMark = written.mapValues { (locale, values) -> values.filterKeys { origins[locale]?.containsKey(it) == true } }
            .filterValues { it.isNotEmpty() }
        if (toMark.isEmpty()) return
        val store = ReviewStore.getInstance(project)
        store.enable(root)
        toMark.forEach { (locale, values) ->
            values.entries.groupBy { origins.getValue(locale).getValue(it.key) }.forEach { (origin, entries) ->
                val translatedFrom = sources[locale].orEmpty()
                store.mark(
                    root, locale, entries.associate { keyOf(it.key) to it.value }, origin,
                    entries.mapNotNull { entry -> translatedFrom[entry.key]?.let { keyOf(entry.key) to it } }.toMap()
                )
            }
        }
    }
}
