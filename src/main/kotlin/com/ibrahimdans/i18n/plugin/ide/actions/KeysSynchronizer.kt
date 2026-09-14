package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.KeySpelling
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.tree.PluralKey
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*
import javax.swing.table.DefaultTableModel

/**
 * Analyzes all locales, finds missing keys, shows a preview dialog,
 * and applies missing keys with empty value when the user confirms.
 */
class KeysSynchronizer {

    /**
     * Represents a single missing key entry: key path, target locale, and the source file to update.
     */
    data class MissingEntry(val key: String, val locale: String, val source: LocalizationSource)

    /**
     * Entry point. Collects missing keys, shows the preview dialog, and applies on confirm.
     */
    fun sync(project: Project) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, PluginBundle.message("action.sync.progress.analyzing"), false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = PluginBundle.message("action.sync.progress.loading")
                val allTranslations = TranslationDataLoader.loadAllTranslations(project)
                val allLocales = TranslationDataLoader.discoverLocales(project)

                if (allLocales.isEmpty()) {
                    ApplicationManager.getApplication().invokeLater {
                        // `project` is the one this Task.Backgroundable was built with:
                        // passing it as parent anchors the dialog to the IDE frame.
                        Messages.showInfoMessage(
                            project,
                            PluginBundle.message("action.sync.none.sources"),
                            PluginBundle.message("action.sync.title")
                        )
                    }
                    return
                }

                indicator.text = PluginBundle.message("action.sync.progress.computing")
                val allSources = project.service<LocalizationSourceService>().findAllSources(project)
                val missing = findMissingEntries(allTranslations, allLocales, allSources, Settings.getInstance(project).config().defaultNamespaces())

                ApplicationManager.getApplication().invokeLater {
                    if (missing.isEmpty()) {
                        Messages.showInfoMessage(
                            project,
                            PluginBundle.message("action.sync.none.missing"),
                            PluginBundle.message("action.sync.title")
                        )
                        return@invokeLater
                    }
                    val dialog = SyncPreviewDialog(project, missing)
                    if (dialog.showAndGet()) {
                        applyMissingKeys(project, missing)
                    }
                }
            }
        })
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Computes missing entries: for every key present in at least one locale and absent from
     * another, the source matching the target locale and namespace to write it to.
     *
     * Plural forms are compared as a group ([PluralKey.groupForms]): a locale holding any form of
     * `cart.item` has the plural, whatever categories its language uses, and a locale lacking the
     * whole group receives `cart.item_other` alone — the one form every language defines. Comparing
     * forms one by one proposed `cart.item_few` for English and `cart.item_one` for Japanese.
     *
     * [defaultNamespaces] says where a key spelled without a prefix lives — see
     * [findSourceForKeyAndLocale].
     */
    internal fun findMissingEntries(
        allTranslations: Map<String, Map<String, String>>,
        allLocales: List<String>,
        allSources: List<LocalizationSource>,
        defaultNamespaces: List<String> = Config().defaultNamespaces(),
    ): List<MissingEntry> {
        val missing = mutableListOf<MissingEntry>()

        for ((key, forms) in PluralKey.groupForms(allTranslations.keys)) {
            val presentLocales = forms.flatMapTo(mutableSetOf()) { allTranslations[it].orEmpty().keys }
            val absentLocales = allLocales - presentLocales
            val keyToCreate = if (forms.size == 1 && forms.single() == key) key else PluralKey.defaultForm(key)

            for (targetLocale in absentLocales) {
                val source = findSourceForKeyAndLocale(keyToCreate, targetLocale, allSources, defaultNamespaces) ?: continue
                missing.add(MissingEntry(keyToCreate, targetLocale, source))
            }
        }

        return missing.sortedWith(compareBy({ it.locale }, { it.key }))
    }

    /**
     * The source [key] is written to for [targetLocale]: the file of the namespace the key is
     * prefixed with, or, for a key spelled without one, a file of a default namespace — that is
     * where the loader read it from, since it strips exactly that prefix.
     *
     * A prefix-less key used to take the **first** file of the locale, whatever its namespace:
     * with `common` as the default namespace, `actions.save` was routed to `fr/auth.json`,
     * the first name in alphabetical order. A locale holding a single file keeps it as the
     * fallback — the one-file-per-locale layout names it after the locale, not the namespace.
     */
    private fun findSourceForKeyAndLocale(
        key: String,
        targetLocale: String,
        allSources: List<LocalizationSource>,
        defaultNamespaces: List<String>,
    ): LocalizationSource? {
        val wanted = KeySpelling.namespaceOf(key)?.let { listOf(it) } ?: defaultNamespaces
        val ofLocale = allSources.filter { TranslationDataLoader.extractLocale(it) == targetLocale }
        return ofLocale.firstOrNull { TranslationDataLoader.extractNamespace(it, defaultNamespaces.first()) in wanted }
            ?: ofLocale.singleOrNull()
    }

    /**
     * Applies all missing entries by inserting empty string values.
     * All PSI writes are batched into a single WriteCommandAction to avoid
     * N EDT round-trips (one per key) and prevent perceptible UI freezes.
     */
    private fun applyMissingKeys(project: Project, missing: List<MissingEntry>) {
        val viewModel = DialogViewModel(project)
        val config = Settings.getInstance(project).config()
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, PluginBundle.message("action.sync.progress.title"), false) {
            override fun run(indicator: ProgressIndicator) {
                // Phase 1 (background): build all (source, fullKey) pairs — no EDT touch
                indicator.text = PluginBundle.message("action.sync.progress.preparing")
                val operations = missing.mapIndexed { index, entry ->
                    indicator.fraction = index.toDouble() / missing.size * 0.5
                    Triple(entry.source, buildFullKey(entry.key, config), entry)
                }

                // Phase 2 (EDT): apply all writes in a single WriteCommandAction
                indicator.text = PluginBundle.message("action.sync.progress.writing", missing.size)
                indicator.fraction = 0.5
                ApplicationManager.getApplication().invokeAndWait {
                    WriteCommandAction.runWriteCommandAction(
                        project, PluginBundle.message("action.sync.command"), null,
                    {
                        for ((source, fullKey, _) in operations) {
                            viewModel.saveTranslation(source, fullKey, "")
                        }
                    })
                }
                indicator.fraction = 1.0
            }
        })
    }

    /**
     * Builds a FullKey from a key as [KeySpelling] writes it, namespace prefix included.
     *   "menu.home"        → FullKey(ns=null, compositeKey=[menu, home])
     *   "common:menu.home" → FullKey(ns=common, compositeKey=[menu, home])
     *   "app.title"        → FullKey(ns=null, compositeKey=[app.title]) when keys are flat
     *
     * [config] defaults to the plugin's own defaults for callers holding no project; every write
     * path passes the project's, or a flat key is split into levels it does not have.
     */
    internal fun buildFullKey(key: String, config: Config = Config()): FullKey {
        val namespace = KeySpelling.namespaceOf(key)
        val path = KeySpelling.pathOf(key)
        if (path.isBlank()) return FullKey(source = key, ns = namespace?.let(::Literal), compositeKey = emptyList())
        val compositeKey = KeySpelling.segmentsOf(key, config).filter { it.isNotEmpty() }.map { Literal(it) }
        return FullKey(source = key, ns = namespace?.let(::Literal), compositeKey = compositeKey)
    }
}

// ── Preview Dialog ────────────────────────────────────────────────────────────

/**
 * Modal dialog showing missing keys before applying them.
 * Displays a table with columns: Key | Target Locale | File.
 * Confirms on "Apply" (OK), cancels on "Cancel".
 */
private class SyncPreviewDialog(
    project: Project,
    private val missing: List<KeysSynchronizer.MissingEntry>
) : DialogWrapper(project) {

    init {
        title = PluginBundle.message("action.sync.preview.title", missing.size)
        setOKButtonText(PluginBundle.message("action.sync.preview.ok"))
        init()
    }

    override fun createCenterPanel(): JComponent {
        val columnNames = arrayOf(
            PluginBundle.message("action.sync.preview.column.key"),
            PluginBundle.message("action.sync.preview.column.locale"),
            PluginBundle.message("action.sync.preview.column.file")
        )
        val data: Array<Array<Any>> = missing.map { entry ->
            arrayOf<Any>(entry.key, entry.locale, entry.source.displayPath)
        }.toTypedArray()

        val tableModel = object : DefaultTableModel(data, columnNames) {
            override fun isCellEditable(row: Int, column: Int) = false
        }

        val table = JBTable(tableModel)
        table.setShowGrid(true)
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN

        // Adjust column widths for readability
        table.columnModel.getColumn(0).preferredWidth = 260
        table.columnModel.getColumn(1).preferredWidth = 80
        table.columnModel.getColumn(2).preferredWidth = 200

        val scroll = JBScrollPane(table)
        scroll.preferredSize = Dimension(620, 380)

        val panel = JPanel(BorderLayout(0, 8))
        val info = JLabel("<html>" + PluginBundle.message("action.sync.preview.note") + "</html>")
        panel.add(info, BorderLayout.NORTH)
        panel.add(scroll, BorderLayout.CENTER)
        return panel
    }
}
