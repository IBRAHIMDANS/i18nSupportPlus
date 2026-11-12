package com.ibrahimdans.i18n.plugin.ide.usages

import com.ibrahimdans.i18n.extensions.localization.arb.ArbCatalogues
import com.ibrahimdans.i18n.extensions.localization.properties.PropertiesBundleKeys
import com.ibrahimdans.i18n.plugin.ide.IdeKeyUsageIndex
import com.ibrahimdans.i18n.plugin.ide.inspection.KeepList
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationRow
import com.intellij.openapi.project.Project

/**
 * How many times the code uses each key: the count the table shows, *Clean Up Keys with No
 * Reference* deletes on and the AI agents' tools report — one place for all three.
 */
object KeyUsageCounts {

    /**
     * Counts usages of each key in the project's source files through [IdeKeyUsageIndex], in
     * [moduleConfig]'s code only when given — the module the rows were loaded from.
     * Returns a new list of rows with [TranslationRow.usageCount] filled in.
     *
     * This method performs PSI reads and must be called from a background thread.
     */
    fun count(project: Project, rows: List<TranslationRow>, moduleConfig: ModuleConfig? = null): List<TranslationRow> {
        val config = Settings.getInstance(project).config()
        val index = IdeKeyUsageIndex(project, moduleConfig)
        val counts = index.usageCounts(rows.map { it.key })
        // Flutter reads an ARB key as a getter of its generated class, which no search here sees:
        // such a key stays unscanned rather than reported unused, and *Cleanup* never offers it.
        val arbKeys = ArbCatalogues.keys(project)
        // A resource bundle's key is read by `getString`, `getMessage` or `#{key}`, which the text scan
        // does not see: the platform's references to its properties count it.
        val bundleUsages = PropertiesBundleKeys.usageCounts(project)
        val withPrefixed = rows.map { row ->
            val count = maxOf(counts[row.key] ?: 0, bundleUsages[row.key] ?: 0)
            row.copy(usageCount = if (count == 0 && row.key in arbKeys) NOT_SCANNED else count)
        }

        // A key the project keeps is used by declaration, whatever the code shows: no search for it.
        val keepList = KeepList.of(config)
        val withKept = if (keepList.isEmpty()) withPrefixed
            else withPrefixed.map { row -> if (row.usageCount == 0 && keepList.matches(row.key)) row.copy(usageCount = KEPT_USAGE) else row }

        // Only what the text scan left at zero can be reached dynamically, and asking on
        // behalf of the whole batch is what keeps this to one search per distinct prefix.
        val orphanKeys = withKept.filter { it.usageCount == 0 }.map { it.key }
        val reached = index.dynamicallyReached(orphanKeys)
        if (reached.isEmpty()) return withKept
        return withKept.map { row ->
            if (row.key in reached) row.copy(usageCount = DYNAMIC_USAGE) else row
        }
    }

    /**
     * [TranslationRow.usageCount] for a key no text search can find, but that a key built
     * at runtime reaches — see [com.ibrahimdans.i18n.plugin.ide.toolwindow.UsageStatus.DYNAMIC].
     *
     * A sentinel rather than a field of its own, because the table model carries the count
     * itself into the *Usage* cell; `-1` already stands for "never scanned" there, and
     * adding a column the renderer would have to look up sideways buys nothing. Every
     * reading of it goes through [com.ibrahimdans.i18n.plugin.ide.toolwindow.TableViewModel.usageStatus].
     */
    internal const val DYNAMIC_USAGE = -2
    /** [TranslationRow.usageCount] for a key whose usages cannot be searched, as before any scan. */
    internal const val NOT_SCANNED = -1
    internal const val KEPT_USAGE = -3
}
