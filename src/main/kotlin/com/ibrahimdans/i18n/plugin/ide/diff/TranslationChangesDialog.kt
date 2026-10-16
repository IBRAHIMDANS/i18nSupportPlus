package com.ibrahimdans.i18n.plugin.ide.diff

import com.ibrahimdans.i18n.plugin.ide.actions.FillMissingTranslationsAction
import com.ibrahimdans.i18n.plugin.ide.actions.MachineFill
import com.ibrahimdans.i18n.plugin.ide.dialog.MachineTranslationPreviewDialog
import com.ibrahimdans.i18n.plugin.ide.dialog.TranslationDialog
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.KeySpelling
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.review.ReviewLocation
import com.ibrahimdans.i18n.plugin.translate.PluralTranslationPlan
import com.ibrahimdans.i18n.plugin.translate.TranslationProvider
import com.ibrahimdans.i18n.plugin.tree.PluralCategories
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.ReferenceLocale
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.DefaultTableModel

/**
 * The translation changes as a table — key, locale, change, before, after — with a switch to the
 * locales left behind the reference, the follow-up a review usually asks for. On that view,
 * *Retranslate Lagging…* machine-translates them again ([LaggingRetranslation]).
 *
 * [dialogTitle] says what the changes are compared with: the working copy by default, a branch
 * when they come from one.
 */
class TranslationChangesDialog(
    private val project: Project,
    private val found: TranslationChanges,
    dialogTitle: String = PluginBundle.message("diff.translation.title"),
) : DialogWrapper(project) {

    private val model = object : DefaultTableModel() {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val laggingOnly = JBCheckBox(PluginBundle.message("diff.translation.lagging.only", found.lagging.size))

    /** Shown on the lagging view only: what it lists is what it retranslates. */
    internal val retranslate = JButton(PluginBundle.message("diff.translation.retranslate")).apply {
        toolTipText = PluginBundle.message("diff.translation.retranslate.tooltip", found.referenceLocale ?: "")
        addActionListener { LaggingRetranslation.start(project, found) }
    }

    init {
        title = dialogTitle
        laggingOnly.isEnabled = found.lagging.isNotEmpty()
        laggingOnly.addActionListener { fill() }
        fill()
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        add(JPanel(BorderLayout()).apply {
            add(laggingOnly, BorderLayout.WEST)
            add(retranslate, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(JBScrollPane(JBTable(model)).apply { preferredSize = Dimension(820, 420) }, BorderLayout.CENTER)
    }

    /** Switches between all the changes and the lagging locales, as the checkbox does. */
    internal fun showLaggingOnly(only: Boolean) {
        laggingOnly.isSelected = only
        fill()
    }

    private fun fill() {
        model.setRowCount(0)
        retranslate.isVisible = laggingOnly.isSelected && found.lagging.isNotEmpty() && found.referenceLocale != null
        if (laggingOnly.isSelected) {
            model.setColumnIdentifiers(arrayOf(keyColumn(), localeColumn(), changeColumn()))
            val behind = PluginBundle.message("diff.translation.kind.lagging", found.referenceLocale ?: "")
            found.lagging.forEach { model.addRow(arrayOf(keyOf(it.namespace, it.path), it.locale, behind)) }
        } else {
            model.setColumnIdentifiers(
                arrayOf(
                    keyColumn(), localeColumn(), changeColumn(),
                    PluginBundle.message("diff.translation.column.before"),
                    PluginBundle.message("diff.translation.column.after")
                )
            )
            found.changes.forEach {
                model.addRow(arrayOf(keyOf(it.namespace, it.path), it.locale, kindOf(it.kind), it.before.orEmpty(), it.after.orEmpty()))
            }
        }
    }

    // Keys stay literal so PluginBundleTest and the IDE can check them against the bundle.
    private fun keyColumn() = PluginBundle.message("diff.translation.column.key")

    private fun localeColumn() = PluginBundle.message("diff.translation.column.locale")

    private fun changeColumn() = PluginBundle.message("diff.translation.column.change")

    private fun kindOf(kind: TranslationChange.Kind) = when (kind) {
        TranslationChange.Kind.ADDED -> PluginBundle.message("diff.translation.kind.added")
        TranslationChange.Kind.REMOVED -> PluginBundle.message("diff.translation.kind.removed")
        TranslationChange.Kind.MODIFIED -> PluginBundle.message("diff.translation.kind.modified")
    }

    private fun keyOf(namespace: String, path: List<String>) = "$namespace:${path.joinToString(".")}"
}

/**
 * *Retranslate Lagging…*: the keys the reference locale changed and a locale did not follow
 * ([TranslationDiff.lagging]) machine-translated again from the reference text of now, previewed
 * next to the value each one replaces, then written — each one only while it still holds that
 * value — in one command per module, as *Retranslate Stale Translations* does ([MachineFill]).
 *
 * The one way to catch up a stale translation that needs no reviews: the diff itself says what
 * changed. Values are marked to review only where the module keeps reviews (the preview's box,
 * checked and locked there; unchecked elsewhere, as for a fill).
 *
 * Keys go by module ([ModuleSources.owner] of the reference file holding them), each module with
 * its own preview and its own write: a module's files are written from its own sources. A key is
 * proposed only while its reference text is still the one the diff compared: a namesake in
 * another module, or a reference edited since, is left out.
 */
internal object LaggingRetranslation {

    /** The keys to retranslate in one [module] — null for the project as a whole — `locale -> items`. */
    data class Batch(val module: ModuleConfig?, val itemsByLocale: Map<String, List<MachineFill.Item>>)

    /**
     * What [batchesOf] proposes, and the modules left out of it: each translates from another
     * locale than the reference the comparison took for the project, so the diff cannot say which
     * of their locales lag ([TranslationChanges.referenceLocale] is project-wide).
     */
    data class Plan(val batches: List<Batch>, val otherReference: List<ModuleConfig>)

    /** What one module's preview shows. */
    data class Preview(val module: ModuleConfig?, val proposals: List<MachineFill.Proposal>)

    /** Asks for the engine, then [run]s. From the EDT. */
    fun start(project: Project, found: TranslationChanges) {
        val title = PluginBundle.message("diff.translation.retranslate.title")
        val provider = FillMissingTranslationsAction.machineTranslator(project) ?: run {
            Messages.showInfoMessage(project, PluginBundle.message("action.fill.disabled"), title)
            return
        }
        run(project, found, provider)
    }

    /**
     * The lagging keys of [found] translated by [provider] under a modal progress — it runs from the
     * changes dialog, itself modal — then previewed and written module by module.
     */
    fun run(project: Project, found: TranslationChanges, provider: TranslationProvider) {
        val reference = found.referenceLocale ?: return
        val title = PluginBundle.message("diff.translation.retranslate.title")
        ProgressManager.getInstance().run(object : Task.Modal(project, title, true) {
            private var previews: List<Preview>? = null
            private var otherReference: List<ModuleConfig> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                val plan = planOf(project, found)
                otherReference = plan.otherReference
                previews = translate(plan.batches, provider, reference, indicator)
            }

            override fun onSuccess() {
                val ready = previews ?: return
                if (otherReference.isNotEmpty()) {
                    val names = otherReference.joinToString(", ") { moduleName(it) }
                    Messages.showInfoMessage(project, PluginBundle.message("diff.translation.retranslate.other.reference", reference, names), title)
                }
                if (ready.isEmpty()) {
                    Messages.showInfoMessage(project, PluginBundle.message("diff.translation.retranslate.nothing", reference), title)
                    return
                }
                ready.forEach { preview -> previewAndWrite(project, preview, reference, several = ready.size > 1) }
            }
        })
    }

    /** Each batch translated, in the order given; null when the user cancelled. */
    fun translate(batches: List<Batch>, provider: TranslationProvider, reference: String, indicator: ProgressIndicator?): List<Preview>? =
        batches.map { batch ->
            Preview(batch.module, batch.itemsByLocale.flatMap { (target, items) ->
                indicator?.text = PluginBundle.message("action.fill.progress.locale", target)
                MachineFill.translate(items, provider, reference, target, indicator) ?: return null
            })
        }.filter { it.proposals.isNotEmpty() }

    private fun previewAndWrite(project: Project, preview: Preview, reference: String, several: Boolean) {
        val locales = preview.proposals.map { it.locale }.distinct().joinToString(", ")
        val label = if (several && preview.module != null) {
            PluginBundle.message("diff.translation.retranslate.module", locales, moduleName(preview.module))
        } else locales
        val dialog = MachineTranslationPreviewDialog(
            project, label, preview.proposals, listOfNotNull(ReviewLocation.rootOf(project, preview.module))
        )
        if (dialog.showAndGet()) writeChecked(project, preview, reference, dialog)
    }

    /**
     * What [dialog] keeps of [preview], written in one command: a replaced value only while it is
     * still the one previewed, marked to review with its [reference] source only when the box is checked.
     */
    fun writeChecked(project: Project, preview: Preview, reference: String, dialog: MachineTranslationPreviewDialog) {
        val marks = ReviewLocation.rootOf(project, preview.module)?.let { root ->
            dialog.reviewOrigins()?.let { MachineFill.ReviewMarks(root, it, MachineFill.reviewSourcesOf(preview.proposals, reference)) }
        }
        MachineFill.write(
            project, preview.module, dialog.acceptedByLocale(), marks, MachineFill.replacedOf(preview.proposals),
            PluginBundle.message("diff.translation.retranslate.command")
        )
    }

    private fun moduleName(module: ModuleConfig) = module.name.ifBlank { module.rootDirectory }

    /** [planOf]'s batches. */
    fun batchesOf(project: Project, found: TranslationChanges): List<Batch> = planOf(project, found).batches

    /**
     * [found]'s lagging keys by module, each with what to translate per locale. With fewer than two
     * modules the project is one scope, as *Fill Missing Translations* takes it. Reads the
     * translation files: not on the EDT for a real project.
     *
     * The lagging keys were found against the project-wide reference: a module whose own reference
     * ([ReferenceLocale.of] on its locales) is another one is left out and named in
     * [Plan.otherReference] — retranslating it would overwrite its source locale from a translation.
     */
    fun planOf(project: Project, found: TranslationChanges): Plan {
        val reference = found.referenceLocale ?: return Plan(emptyList(), emptyList())
        if (found.lagging.isEmpty()) return Plan(emptyList(), emptyList())
        val config = Settings.getInstance(project).config()
        val modules = Settings.getInstance(project).modules.filter { it.rootDirectory.isNotBlank() }
        val owners: List<ModuleConfig?> = if (modules.size < 2) listOf(null) else ownersOf(project, found, reference, modules, config)
        val (scopes, otherReference) = owners.partition { module -> module == null || referenceOf(project, module, config) == reference }
        // The reference text each lagging key had after the change: a module holding another one is not where it changed.
        val changed = found.changes
            .filter { it.locale == reference && it.kind != TranslationChange.Kind.REMOVED }
            .associate { (it.namespace to it.path) to it.after.orEmpty() }
        val batches = scopes.mapNotNull { module ->
            val translations = TranslationDataLoader.loadAllTranslations(project, module)
            val items = itemsOf(found.lagging, changed, translations, reference, config)
            if (items.isEmpty()) null else Batch(module, items)
        }
        return Plan(batches, otherReference.filterNotNull())
    }

    /** The locale [module] translates from, among its own files. */
    private fun referenceOf(project: Project, module: ModuleConfig, config: Config): String? =
        ReadAction.compute<String?, RuntimeException> {
            ReferenceLocale.of(module, config, TranslationDataLoader.discoverLocales(project, module))
        }

    /**
     * The modules owning a [reference] file of a namespace some key lags in — null among them for a
     * file no module holds.
     */
    private fun ownersOf(
        project: Project, found: TranslationChanges, reference: String, modules: List<ModuleConfig>, config: Config,
    ): List<ModuleConfig?> {
        val namespaces = found.lagging.mapTo(HashSet()) { it.namespace }
        val defaultNamespace = config.defaultNamespaces().first()
        val basePath = project.basePath ?: ""
        return ReadAction.compute<List<ModuleConfig?>, RuntimeException> {
            project.service<LocalizationSourceService>().findAllSources(project)
                .filter { TranslationDataLoader.extractLocale(it) == reference }
                .filter { TranslationDataLoader.extractNamespace(it, defaultNamespace) in namespaces }
                .mapNotNull { source -> (source.tree?.value() ?: source.host)?.containingFile?.virtualFile }
                .map { file -> ModuleSources.owner(modules, ModuleSources.FilePath.of(file, basePath)) }
                .distinct()
        }
    }

    /**
     * What to translate again in [translations] (`key -> locale -> value`, one module's) for each
     * locale of [lagging], `locale -> items` sorted by key: each lagging key whose [reference] text is
     * still the one [changed] records (`(namespace, path) -> text after the change`), with the value
     * it replaces as [MachineFill.Item.current] — none for a key the locale lacks, which is filled.
     *
     * A plural form brings its whole group: every form the target language needs, from the source
     * form of its category or, for review, from `other` — [TranslationDiff.lagging] already left out
     * a group the locale followed through any of its forms. An ICU plural is listed, not translated.
     */
    fun itemsOf(
        lagging: List<LaggingLocale>,
        changed: Map<Pair<String, List<String>>, String>,
        translations: Map<String, Map<String, String>>,
        reference: String,
        config: Config,
    ): Map<String, List<MachineFill.Item>> {
        val defaultNamespaces = config.defaultNamespaces()
        val icu = PluginBundle.message("action.fill.icu")
        val byLocale = mutableMapOf<String, MutableMap<String, MachineFill.Item>>()
        for (entry in lagging) {
            if (entry.locale == reference) continue
            val expected = changed[entry.namespace to entry.path]?.takeIf { it.isNotBlank() } ?: continue
            val key = keyOf(entry.namespace, entry.path, defaultNamespaces, config)
            val values = translations[key] ?: continue
            if (values[reference] != expected) continue
            val items = byLocale.getOrPut(entry.locale) { sortedMapOf<String, MachineFill.Item>() }
            val form = TranslationDialog.pluralFormOf(key, config.keySeparator, CLDR_SUFFIX_SEPARATOR)
            if (form == null || form.second !in translations) {
                val current = values[entry.locale]?.takeIf { it.isNotEmpty() }
                items[key] = if (PluralTranslationPlan.isIcuPlural(expected)) MachineFill.Item(key, expected, skipped = icu, current = current)
                else MachineFill.Item(key, expected, current = current)
                continue
            }
            val otherKey = form.second
            val base = otherKey.removeSuffix(PluralCategories.OTHER)
            val group = PluralCategories.ALL.associateWith { base + it }.filterValues { it in translations }
            val sourceForms = group.mapNotNull { (category, k) -> translations.getValue(k)[reference]?.let { category to it } }.toMap()
            val existing = group.mapNotNull { (category, k) ->
                translations.getValue(k)[entry.locale]?.takeIf { it.isNotEmpty() }?.let { category to it }
            }.toMap()
            when (val plan = PluralTranslationPlan.of(sourceForms, entry.locale)) {
                PluralTranslationPlan.Plan.IcuPlural ->
                    items[otherKey] = MachineFill.Item(otherKey, sourceForms[PluralCategories.OTHER].orEmpty(), skipped = icu, current = existing[PluralCategories.OTHER])
                is PluralTranslationPlan.Plan.Forms -> plan.forms.forEach { planned ->
                    val formKey = base + planned.category
                    items[formKey] = MachineFill.Item(formKey, planned.source, planned.needsReview, current = existing[planned.category])
                }
            }
        }
        return byLocale.mapValues { it.value.values.toList() }.filterValues { it.isNotEmpty() }
    }

    /** [path] in [namespace] as the table spells it: `common:menu.home`, no prefix in a default namespace. */
    private fun keyOf(namespace: String, path: List<String>, defaultNamespaces: List<String>, config: Config): String {
        val prefix = if (namespace in defaultNamespaces) "" else namespace + KeySpelling.NAMESPACE_SEPARATOR
        return prefix + path.fold("") { spelled, segment -> KeySpelling.child(config, spelled, segment) }
    }

    /** Between a base and its CLDR category in i18next's flat convention, as [MachineFill] reads it. */
    private const val CLDR_SUFFIX_SEPARATOR = "_"
}
