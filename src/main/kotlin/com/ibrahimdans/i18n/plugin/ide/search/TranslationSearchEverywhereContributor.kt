package com.ibrahimdans.i18n.plugin.ide.search

import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TableViewModel
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.displayValue
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.Processor
import javax.swing.JList
import javax.swing.ListCellRenderer

/** One translation found by Search Everywhere: [key] (namespace-prefixed as the tool window spells it) in [locale]. */
data class TranslationSearchHit(val key: String, val locale: String, val value: String)

/**
 * The hits for [pattern] in [translations] (`key -> (locale -> value)`, as
 * [TranslationDataLoader.loadAllTranslations] returns it), case-insensitively.
 *
 * A translation matches when its value *or* its key contains the pattern: the question this answers
 * is usually "where does this 'Welcome back' come from?", but a half-remembered key is just as common.
 * Value matches come first — they are the ones the user actually saw on screen — then translations
 * reached by their key only, each group sorted by key then locale so the order is stable as the
 * user types. A blank pattern finds nothing: listing every translation of the project would bury
 * the other tabs' results.
 *
 * Pure so it can be tested without a project; the contributor only feeds it and renders its output.
 */
internal fun findTranslationHits(translations: Map<String, Map<String, String>>, pattern: String): List<TranslationSearchHit> {
    val query = pattern.trim()
    if (query.isEmpty()) return emptyList()
    val byValue = mutableListOf<TranslationSearchHit>()
    val byKey = mutableListOf<TranslationSearchHit>()
    for ((key, values) in translations) {
        val keyMatches = key.contains(query, ignoreCase = true)
        for ((locale, value) in values) {
            when {
                value.contains(query, ignoreCase = true) -> byValue += TranslationSearchHit(key, locale, value)
                keyMatches -> byKey += TranslationSearchHit(key, locale, value)
            }
        }
    }
    val order = compareBy<TranslationSearchHit>({ it.key }, { it.locale })
    return byValue.sortedWith(order) + byKey.sortedWith(order)
}

/**
 * A *Translations* tab in Search Everywhere (Shift Shift), searching every locale's values and keys.
 *
 * Before it, finding which key holds a text seen in the running application meant opening the tool
 * window or running *Find in Files* over `locales/`. Data comes from [TranslationDataLoader], the
 * source the tool window reads, so both always agree; it is loaded once per popup, on the search
 * thread Search Everywhere runs contributors on, never on the EDT.
 *
 * When the popup is opened from a file that belongs to a configured module, only that module's
 * translations are searched — in a monorepo the same text usually lives in several apps, and the one
 * being edited is the one asked about. A module whose translations live outside its root directory
 * yields nothing that way, and the search then falls back to the whole project.
 */
class TranslationSearchEverywhereContributor(
    private val project: Project,
    private val contextFile: VirtualFile?,
) : SearchEverywhereContributor<TranslationSearchHit> {

    /** The translations searched and the module they were scoped to (null: the whole project). */
    private class Scope(val module: ModuleConfig?, val translations: Map<String, Map<String, String>>)

    // Kotlin's lazy does not cache a thrown ProcessCanceledException: a load cancelled by the next
    // keystroke is simply started again by the next search.
    private val scope: Scope by lazy { loadScope() }

    override fun getSearchProviderId(): String = ID

    override fun getGroupName(): String = PluginBundle.message("search.everywhere.translations.tab")

    override fun getSortWeight(): Int = SORT_WEIGHT

    override fun showInFindResults(): Boolean = false

    override fun isShownInSeparateTab(): Boolean = true

    override fun fetchElements(
        pattern: String,
        progressIndicator: ProgressIndicator,
        consumer: Processor<in TranslationSearchHit>,
    ) {
        if (pattern.isBlank()) return
        for (hit in findTranslationHits(scope.translations, pattern)) {
            progressIndicator.checkCanceled()
            if (!consumer.process(hit)) return
        }
    }

    /**
     * Opens the translation file at the key, the way *Open translation file* (F4) does in the table:
     * the key is located through [TableViewModel.locate] with the same module scope the
     * hit was found in, off the EDT, and the editor is opened back on it.
     */
    override fun processSelectedItem(selected: TranslationSearchHit, modifiers: Int, searchText: String): Boolean {
        val module = scope.module
        ApplicationManager.getApplication().executeOnPooledThread {
            val target = ReadAction.compute<Pair<VirtualFile, Int>?, RuntimeException> { locate(selected, module) }
                ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) OpenFileDescriptor(project, target.first, target.second).navigate(true)
            }
        }
        return true
    }

    override fun getElementsRenderer(): ListCellRenderer<in TranslationSearchHit> =
        object : ColoredListCellRenderer<TranslationSearchHit>() {
            override fun customizeCellRenderer(
                list: JList<out TranslationSearchHit>,
                value: TranslationSearchHit?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                if (value == null) return
                append(displayValue(value.value), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append("  — ${value.key} (${value.locale})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }

    override fun getItemDescription(element: TranslationSearchHit): String = "${element.key} (${element.locale})"

    private fun loadScope(): Scope {
        val module = contextModule()
        if (module != null) {
            val scoped = TranslationDataLoader.loadAllTranslations(project, module)
            if (scoped.isNotEmpty()) return Scope(module, scoped)
        }
        return Scope(null, TranslationDataLoader.loadAllTranslations(project))
    }

    /** The configured module whose root directory holds the file the popup was opened from, or null. */
    private fun contextModule(): ModuleConfig? {
        val file = contextFile ?: return null
        val modules = Settings.getInstance(project).config().modules
        if (modules.isEmpty()) return null
        return ModuleSources.owner(modules, ModuleSources.FilePath.of(file, project.basePath ?: ""))
    }

    /**
     * The file and offset where [hit]'s key is declared for its locale — [TableViewModel.locate],
     * the walk *Open translation file* (F4) takes in the table.
     * Internal for tests: [processSelectedItem] only schedules it. Must be called inside a read action.
     */
    internal fun locate(hit: TranslationSearchHit, module: ModuleConfig? = scope.module): Pair<VirtualFile, Int>? =
        TableViewModel().locate(project, hit.key, hit.locale, module)

    /** Registered in `plugin.xml` under `com.intellij.searchEverywhereContributor`. */
    class Factory : SearchEverywhereContributorFactory<TranslationSearchHit> {
        override fun createContributor(initEvent: AnActionEvent): SearchEverywhereContributor<TranslationSearchHit> =
            TranslationSearchEverywhereContributor(
                requireNotNull(initEvent.project) { "Search Everywhere is opened on a project" },
                initEvent.getData(CommonDataKeys.VIRTUAL_FILE),
            )
    }

    companion object {
        const val ID = "I18nTranslationsSearchEverywhereContributor"

        /** After the platform's own tabs (Classes 100, Files 200, Symbols 300, Actions 400). */
        private const val SORT_WEIGHT = 1000
    }
}
