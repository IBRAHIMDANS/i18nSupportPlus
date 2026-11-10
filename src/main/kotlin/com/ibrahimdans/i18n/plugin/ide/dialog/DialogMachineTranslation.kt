package com.ibrahimdans.i18n.plugin.ide.dialog

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.actions.MachineFill
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.KeySpelling
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.review.ReviewSource
import com.ibrahimdans.i18n.plugin.translate.KeyUiRoles
import com.ibrahimdans.i18n.plugin.translate.PluralTranslationPlan
import com.ibrahimdans.i18n.plugin.translate.Translation
import com.ibrahimdans.i18n.plugin.translate.TranslationContext
import com.ibrahimdans.i18n.plugin.translate.TranslationRequest
import com.ibrahimdans.i18n.plugin.translate.asCancellation
import com.ibrahimdans.i18n.plugin.tree.PluralCategories
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.ReferenceLocale
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.concurrency.AppExecutorUtil
import javax.swing.JButton
import javax.swing.event.DocumentEvent
import org.jetbrains.concurrency.CancellablePromise

/**
 * The translation dialog's machine translation: *Translate Empty Locales*, each locale's *Translate*,
 * the *Instruction* and *Context* fields, and the proposals waiting for *OK*.
 *
 * Reads the dialog through [typedKey] (the key field as typed), [textAreas] (one field per locale) and
 * [moduleOf] (the module a file belongs to); writes nothing but those fields and its own controls.
 */
internal class DialogMachineTranslation(
    private val project: Project,
    private val fullKey: FullKey,
    private val tabModule: ModuleConfig?,
    /** The namespace a CREATE dialog opened on, for a key with none of its own. */
    private val initialNamespace: String?,
    private val viewModel: DialogViewModel,
    private val typedKey: () -> String,
    private val textAreas: () -> Map<LocalizationSource, JBTextArea>,
    private val moduleOf: (LocalizationSource) -> ModuleConfig?,
) {

    /** Shown once the project opted in to machine translation and configured an engine. */
    val translateButton = JButton(PluginBundle.message("dialog.translation.translate.button")).apply {
        name = "dialog.translation.translate"
        isVisible = TranslationDialog.machineTranslator(project) != null
        addActionListener { translateEmptyLocales() }
    }

    /**
     * What the user asks of the translations (a tone, a length); shown only when an engine of the
     * project reads it, so that a DeepL-only project is not offered a field that changes nothing.
     */
    val instructionField = JBTextField().apply {
        name = "dialog.translation.instruction"
        emptyText.text = PluginBundle.message("dialog.translation.instruction.empty")
        isVisible = translateButton.isVisible && TranslationDialog.machineReadsInstruction(project)
    }

    /**
     * What the engines are told of the key besides its text, as they get it: shown and editable so that
     * nothing leaves the IDE unseen, and a wrong guess can be put right. Follows the key until edited.
     */
    val contextField = JBTextArea(CONTEXT_ROWS, TEXT_AREA_COLUMNS).apply {
        name = "dialog.translation.context"
        lineWrap = true
        wrapStyleWord = true
    }

    /** Why the last machine translation left a locale empty, one line per locale. */
    val translateStatus = JBLabel().apply { foreground = JBColor.RED }

    /** What *Translate* filled each locale with, and the engine it came from; dropped with the rows. */
    val proposals = LinkedHashMap<LocalizationSource, TranslationDialog.MachineProposal>()

    /** Where the code shows the key's text, read once from its usages; empty until then, or without any. */
    private var uiRoles: List<String> = emptyList()

    /** Set once the user typed in [contextField]: the key, or roles found late, no longer rewrite it. */
    private var contextEdited = false

    /** True while this class itself writes [contextField], which is not the user editing it. */
    private var writingContext = false

    /** The search for [uiRoles], for a test to wait on; null when the engine is off. */
    var uiRolesSearch: CancellablePromise<List<String>>? = null
        private set

    val enabled: Boolean get() = translateButton.isVisible

    /**
     * Fills the context, follows the user's edits of it and looks for where the code shows the key,
     * until [parent] is disposed. Nothing when the engine is off.
     */
    fun start(parent: Disposable) {
        if (!enabled) return
        refreshContext()
        contextField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (!writingContext) contextEdited = true
            }
        })
        searchUiRoles(parent)
    }

    /**
     * Fills every locale left empty with the machine translation of the reference locale's value. A
     * value already there is never replaced — [translateLocale] is for that.
     */
    fun translateEmptyLocales() = translate(textAreas().filterValues { it.text.isBlank() }.keys)

    /**
     * Replaces the value shown for [locale], empty or not, with the machine translation of another
     * locale's: the reference, or another one when [locale] is the reference itself.
     */
    fun translateLocale(locale: String) = translate(textAreas().keys.filter { it.localeLabel() == locale }.toSet())

    /**
     * Fills [targetSources] with the machine translation of the reference locale's value, taken among the
     * other locales. Nothing is written to the files before *OK*: the proposals stay editable in their
     * fields, and *Cancel* gives the stored values back.
     *
     * One request per target locale, under a cancellable progress. A locale the engines could not
     * translate keeps its value, and the reason shows under the buttons.
     *
     * A plural form (`file_few`) follows [PluralTranslationPlan]: a locale whose language has no
     * such form (`file_one` in Japanese) is left alone, and when no locale holds the form yet its
     * text comes from the reference locale's `other`, the proposal then flagged for review. An ICU
     * plural is sent with its branches rebuilt for each target language ([PluralTranslationPlan.icu]),
     * a branch copied from `other` flagged for review; one that cannot be rebuilt is not translated.
     */
    private fun translate(targetSources: Set<LocalizationSource>) {
        val provider = TranslationDialog.machineTranslator(project) ?: return
        if (targetSources.isEmpty()) return
        val areas = textAreas()
        val filled = areas.entries.filter { it.key !in targetSources && it.value.text.isNotBlank() }
        // The language translated *from* matters more than for a copy: the reference of the key's
        // module, as the rest of the plugin picks it (its declared one, then the project's: preview
        // locale, else `en`), the fullest locale last. Another module's declared reference is not
        // asked: the source a review mark records has to be the one the table compares.
        val config = Settings.getInstance(project).config()
        val module = moduleOf(targetSources.first())
        // A plural form only when its `other` exists somewhere: `steps.one` is otherwise just a key.
        val plural = pluralFormOf(typedKey(), config.keySeparator, CLDR_SUFFIX_SEPARATOR)
            ?.takeIf { it.first == PluralCategories.OTHER || otherFormSource(it.second, module, config) != null }
        val labels = filled.map { it.key.localeLabel() }.toSet()
        val sourceLocale = ReferenceLocale.of(module, config, labels) ?: viewModel.localeToCopyFrom(areas.keys)
        // Among the files of that locale, the one of the key's module: a neighbour's may say otherwise.
        val donor = filled.sortedWith(
            compareByDescending<Map.Entry<LocalizationSource, JBTextArea>> { it.key.localeLabel() == sourceLocale }
                .thenByDescending { moduleOf(it.key) == module }
        ).firstOrNull()
        val source = donor?.let { it.key.localeLabel() to it.value.text }
            ?: plural?.let { otherFormSource(it.second, module, config) }
        // A locale is never translated into itself: the one whose `other` form is the source is left out.
        val targets = areas.entries.filter { it.key in targetSources && it.key.localeLabel() != source?.first }
            .map { it.key.localeLabel() to it.value }
        if (source == null || targets.isEmpty()) {
            translateStatus.text = PluginBundle.message("dialog.translation.translate.no.source")
            return
        }

        // What each target locale is asked for: the source text, or a note saying why not. The text
        // is the proposal's source, an `other` form standing in for a missing one included: the one
        // the table and this dialog compare the form with since ([ReviewSources]).
        val asSource = { text: String -> ReviewSource.of(source.first, text) }
        val notes = mutableListOf<String>()
        val requests = targets.mapNotNull { (locale, area) ->
            if (plural == null) {
                if (!PluralTranslationPlan.isIcuPlural(source.second)) return@mapNotNull MachineRequest(locale, area, source.second, asSource(source.second))
                // Sent with the target's branches; the review still records the reference's text.
                val icu = PluralTranslationPlan.icu(source.second, locale) ?: run {
                    notes += PluginBundle.message("dialog.translation.translate.icu.plural", locale)
                    return@mapNotNull null
                }
                if (icu.needsReview) notes += PluginBundle.message("dialog.translation.translate.icu.review", locale, icu.copied.joinToString(", "))
                return@mapNotNull MachineRequest(locale, area, icu.source, asSource(source.second))
            }
            val sourceForms = if (donor != null) mapOf(plural.first to source.second) else mapOf(PluralCategories.OTHER to source.second)
            when (val plan = PluralTranslationPlan.of(sourceForms, locale, ordinal = PluralTranslationPlan.isOrdinalGroup(plural.second))) {
                PluralTranslationPlan.Plan.IcuPlural -> {
                    notes += PluginBundle.message("dialog.translation.translate.icu.plural", locale); null
                }
                is PluralTranslationPlan.Plan.Forms -> {
                    val form = plan.forms.firstOrNull { it.category == plural.first }
                    when {
                        form == null -> { notes += PluginBundle.message("dialog.translation.translate.no.such.form", locale, plural.first); null }
                        else -> {
                            if (form.needsReview) notes += PluginBundle.message("dialog.translation.translate.review", locale, plural.first)
                            MachineRequest(locale, area, form.source, asSource(form.source))
                        }
                    }
                }
            }
        }
        // As shown in the dialog, edited or not; the generated one if it was emptied.
        val header = contextField.text.trim().ifEmpty { generatedContext() }
        val path = pathOf(typedKey(), config)
        val shownSources = areas.keys.toList()
        val instruction = instructionField.text.trim().takeIf { instructionField.isVisible }.orEmpty()
        val results = ProgressManager.getInstance().runProcessWithProgressSynchronously<List<Translation>, RuntimeException>(
            {
                val indicator = ProgressManager.getInstance().progressIndicator
                // The files shown, read once in the background: the examples the context picks for each locale.
                val known = TranslationDataLoader.translationsOf(project, shownSources).mapKeys { KeySpelling.pathOf(it.key) }
                requests.map { (locale, _, text) ->
                    indicator?.checkCanceled()
                    indicator?.text = PluginBundle.message("dialog.translation.translate.progress", locale)
                    val context = TranslationContext.of(header, listOf(path), listOf(text), known, source.first, locale, config)
                    provider.translate(TranslationRequest(listOf(text), source.first, locale, context, instruction), indicator?.asCancellation()).single()
                }
            },
            PluginBundle.message("dialog.translation.translate.button"),
            true,
            project
        )
        val failures = requests.zip(results).mapNotNull { (request, result) ->
            when (result) {
                is Translation.Done -> {
                    request.area.text = result.text
                    areas.entries.firstOrNull { it.value === request.area }?.key
                        ?.let { proposals[it] = TranslationDialog.MachineProposal(result.text, MachineFill.originOf(result), request.source) }
                    null
                }
                is Translation.Failed -> "${request.locale}: ${result.reason}"
            }
        } + notes
        translateStatus.text = failures.joinToString("<br>", "<html>", "</html>").takeIf { failures.isNotEmpty() }.orEmpty()
    }

    /**
     * The context the dialog proposes: the key sentence, read by the engine rather than by the user
     * (English whatever the IDE language), then where the code shows the key's text.
     */
    private fun generatedContext(): String =
        TranslationContext.withRoles(TranslationContext.header(listOf(typedKey())), uiRoles)

    /** [contextField] set to [generatedContext], unless the user edited it. */
    fun refreshContext() {
        if (contextEdited || !enabled) return
        writingContext = true
        try {
            contextField.text = generatedContext()
        } finally {
            writingContext = false
        }
    }

    /**
     * Looks for where the code shows the key's text off the EDT, in smart mode, the dialog usable
     * meanwhile. The key the dialog opened on, as the code writes it: a key renamed in a CREATE dialog
     * is still the one its call site names.
     */
    private fun searchUiRoles(parent: Disposable) {
        val key = usageKeyOf(fullKey) ?: return
        val module = tabModule
        uiRolesSearch = ReadAction.nonBlocking<List<String>> { KeyUiRoles.of(project, key, module) }
            .inSmartMode(project)
            .expireWith(parent)
            .finishOnUiThread(ModalityState.any()) { roles ->
                uiRoles = roles
                refreshContext()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /**
     * [key] spelled as the usage searches take it, `ns:path` ([KeySpelling.NAMESPACE_SEPARATOR]):
     * its own namespace, else the one this CREATE dialog opened on; null for a key with no level.
     */
    private fun usageKeyOf(key: FullKey): String? {
        val config = Settings.getInstance(project).config()
        val path = key.compositeKey.takeIf { it.isNotEmpty() }?.fold("") { path, segment -> KeySpelling.child(config, path, segment.text) }
            ?: return null
        val namespace = key.allNamespaces().firstOrNull() ?: initialNamespace
        return namespace?.let { it + KeySpelling.NAMESPACE_SEPARATOR + path } ?: path
    }

    /**
     * [keyText]'s path as [TranslationDataLoader] spells it once its namespace is cut off: the files
     * shown all belong to the key's namespace, so its neighbours are found by path alone, whatever
     * the namespace separator. The text as typed when it does not parse.
     */
    private fun pathOf(keyText: String, config: Config): String =
        viewModel.parseKey(keyText)?.compositeKey?.takeIf { it.isNotEmpty() }
            ?.fold("") { path, segment -> KeySpelling.child(config, path, segment.text) }
            ?: KeySpelling.pathOf(keyText)

    /**
     * The `other` form of the plural being edited, [otherKey], as [module]'s reference locale holds
     * it — any locale holding it when the reference does not — or null when none does.
     */
    private fun otherFormSource(otherKey: String, module: ModuleConfig?, config: Config): Pair<String, String>? {
        val values = viewModel.parseKey(otherKey)?.let(viewModel::loadTranslations).orEmpty()
            .mapNotNull { (source, value) -> value?.takeIf { it.isNotBlank() }?.let { source.localeLabel() to it } }
            .toMap()
        val reference = ReferenceLocale.of(module, config, values.keys)
        return (values.entries.firstOrNull { it.key == reference } ?: values.entries.firstOrNull())?.toPair()
    }

    /** What *Translate* asks for [locale], shown in [area]: [text], kept as the proposal's [source]. */
    private data class MachineRequest(val locale: String, val area: JBTextArea, val text: String, val source: ReviewSource?)

    companion object {
        /**
         * The [proposals] validated as they were: those whose locale was [written] with exactly the
         * proposed text. One retouched, or not written at all, is left out.
         */
        fun <K> untouchedProposals(
            proposals: Map<K, TranslationDialog.MachineProposal>,
            written: Map<K, String>,
        ): Map<K, TranslationDialog.MachineProposal> =
            proposals.filter { (key, proposal) -> written[key] == proposal.text }

        /**
         * The plural category [key] names and the key of its `other` form, or null for a key that is
         * not a plural form: `common:file_few` gives `few` and `common:file_other`, a nested
         * `common:file.few` gives `few` and `common:file.other`.
         */
        fun pluralFormOf(key: String, keySeparator: String, pluralSeparator: String): Pair<String, String>? {
            val lastSegment = key.substringAfterLast(keySeparator)
            val suffix = lastSegment.substringAfterLast(pluralSeparator, "")
            return when {
                lastSegment.contains(pluralSeparator) && suffix in PluralCategories.ALL ->
                    suffix to key.removeSuffix(pluralSeparator + suffix) + pluralSeparator + PluralCategories.OTHER
                lastSegment in PluralCategories.ALL && key.contains(keySeparator) ->
                    lastSegment to key.removeSuffix(keySeparator + lastSegment) + keySeparator + PluralCategories.OTHER
                else -> null
            }
        }

        /**
         * Between a base and its CLDR category in i18next's flat convention (`item_one`), as in
         * `MissingPluralFormsInspection`. Not [Config.pluralSeparator], which is the older numeric
         * plurals' (`item-0`, `item-1`).
         */
        private const val CLDR_SUFFIX_SEPARATOR = "_"

        private const val CONTEXT_ROWS = 2
        private const val TEXT_AREA_COLUMNS = 40
    }
}
