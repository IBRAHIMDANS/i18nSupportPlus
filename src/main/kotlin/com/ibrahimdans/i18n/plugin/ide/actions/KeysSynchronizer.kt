package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
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
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Analyzing i18n keys…", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Loading all translations…"
                val allTranslations = TranslationDataLoader.loadAllTranslations(project)
                val allLocales = TranslationDataLoader.discoverLocales(project)

                if (allLocales.isEmpty()) {
                    ApplicationManager.getApplication().invokeLater {
                        JOptionPane.showMessageDialog(
                            null,
                            "No translation files found in this project.",
                            "Sync Keys",
                            JOptionPane.INFORMATION_MESSAGE
                        )
                    }
                    return
                }

                indicator.text = "Computing missing keys…"
                val allSources = project.service<LocalizationSourceService>().findAllSources(project)
                val missing = findMissingEntries(allTranslations, allLocales, allSources)

                ApplicationManager.getApplication().invokeLater {
                    if (missing.isEmpty()) {
                        JOptionPane.showMessageDialog(
                            null,
                            "All keys are present in every locale. Nothing to sync.",
                            "Sync Keys",
                            JOptionPane.INFORMATION_MESSAGE
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
     * Computes missing entries: for every (key, locale) pair where the key exists
     * in at least one other locale but is absent in the target locale, it looks up
     * the LocalizationSource that matches the target locale/namespace so we can write to it.
     */
    private fun findMissingEntries(
        allTranslations: Map<String, Map<String, String>>,
        allLocales: List<String>,
        allSources: List<LocalizationSource>
    ): List<MissingEntry> {
        val missing = mutableListOf<MissingEntry>()

        for ((key, localeValues) in allTranslations) {
            val presentLocales = localeValues.keys
            val absentLocales = allLocales - presentLocales

            for (targetLocale in absentLocales) {
                val source = findSourceForKeyAndLocale(key, targetLocale, allSources) ?: continue
                missing.add(MissingEntry(key, targetLocale, source))
            }
        }

        return missing.sortedWith(compareBy({ it.locale }, { it.key }))
    }

    /**
     * Finds the LocalizationSource that corresponds to the given key's namespace and target locale.
     * The key may be prefixed with a namespace, e.g. "common:menu.home".
     */
    private fun findSourceForKeyAndLocale(
        key: String,
        targetLocale: String,
        allSources: List<LocalizationSource>
    ): LocalizationSource? {
        val namespace = extractNamespaceFromKey(key)
        return allSources.firstOrNull { source ->
            TranslationDataLoader.extractLocale(source) == targetLocale &&
                    (namespace == null || TranslationDataLoader.extractNamespace(source) == namespace)
        }
    }

    /**
     * Returns the namespace portion of a namespaced key like "common:menu.home" → "common",
     * or null if the key has no namespace prefix.
     */
    private fun extractNamespaceFromKey(key: String): String? {
        val colonIdx = key.indexOf(':')
        return if (colonIdx > 0) key.substring(0, colonIdx) else null
    }

    /**
     * Applies all missing entries by inserting empty string values.
     * All PSI writes are batched into a single WriteCommandAction to avoid
     * N EDT round-trips (one per key) and prevent perceptible UI freezes.
     */
    private fun applyMissingKeys(project: Project, missing: List<MissingEntry>) {
        val viewModel = DialogViewModel(project)
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Syncing missing keys…", false) {
            override fun run(indicator: ProgressIndicator) {
                // Phase 1 (background): build all (source, fullKey) pairs — no EDT touch
                indicator.text = "Preparing keys…"
                val operations = missing.mapIndexed { index, entry ->
                    indicator.fraction = index.toDouble() / missing.size * 0.5
                    Triple(entry.source, buildFullKey(entry.key), entry)
                }

                // Phase 2 (EDT): apply all writes in a single WriteCommandAction
                indicator.text = "Writing ${missing.size} keys…"
                indicator.fraction = 0.5
                ApplicationManager.getApplication().invokeAndWait {
                    WriteCommandAction.runWriteCommandAction(project, "Sync i18n keys", null, {
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
     * Builds a FullKey from a flat key string, handling optional namespace prefix.
     * Examples:
     *   "menu.home"        → FullKey(ns=null, compositeKey=[menu, home])
     *   "common:menu.home" → FullKey(ns=common, compositeKey=[menu, home])
     */
    internal fun buildFullKey(key: String): FullKey {
        val colonIdx = key.indexOf(':')
        val (ns, keyPath) = if (colonIdx > 0) {
            val nsText = key.substring(0, colonIdx)
            val path = key.substring(colonIdx + 1)
            Literal(nsText) to path
        } else {
            null to key
        }

        if (keyPath.isBlank()) return FullKey(source = key, ns = ns, compositeKey = emptyList())
        val compositeKey = keyPath.split('.').filter { it.isNotEmpty() }.map { Literal(it) }
        return FullKey(source = key, ns = ns, compositeKey = compositeKey)
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
        title = "Sync Missing Keys — Preview (${missing.size} entries)"
        setOKButtonText("Apply")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val columnNames = arrayOf("Key", "Locale", "File")
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
        val info = JLabel(
            "<html>The following keys are missing in some locales.<br>" +
                    "Clicking <b>Apply</b> will insert them with an empty value.</html>"
        )
        panel.add(info, BorderLayout.NORTH)
        panel.add(scroll, BorderLayout.CENTER)
        return panel
    }
}
