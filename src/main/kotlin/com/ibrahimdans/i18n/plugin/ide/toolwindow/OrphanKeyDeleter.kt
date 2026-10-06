package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.deletePropertyAndSeparator
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.concurrency.CancellablePromise
import org.jetbrains.yaml.psi.YAMLKeyValue

/** A key to delete, with the localization sources it was looked up in. */
internal typealias KeySources = Pair<FullKey, List<LocalizationSource>>

/**
 * Resolves and deletes a translation key from the matching localization sources.
 * Implements [CompositeKeyResolver] to reuse the existing key resolution logic.
 *
 * [moduleConfig] must be the module the rows were loaded with: the namespace-based
 * lookup alone is not enough, since two modules commonly own a file with the same
 * namespace. Without the scope, deleting an orphan key from one module's table also
 * removed it from the other module's file.
 */
internal class OrphanKeyDeleter(
    private val project: Project,
    private val moduleConfig: ModuleConfig? = null,
) : CompositeKeyResolver<PsiElement> {

    /**
     * Looks the sources up in a non-blocking read action, then deletes the key on the EDT and
     * runs [onFinished] there. The lookup reaches `FileTypeIndex`, which the platform refuses
     * on the EDT ("Slow operations are prohibited on EDT") — the context-menu action used to
     * run it there. The returned promise completes once the deletion and [onFinished] have run.
     */
    fun delete(fullKey: FullKey, onFinished: () -> Unit = {}): CancellablePromise<List<KeySources>> =
        delete(listOf(fullKey), onFinished)

    /**
     * Deletes every key of [fullKeys] the same way, in a single command: one undo restores
     * them all. The promise yields each key with the sources it was looked up in.
     */
    fun delete(fullKeys: List<FullKey>, onFinished: () -> Unit = {}): CancellablePromise<List<KeySources>> =
        ReadAction.nonBlocking<List<KeySources>> {
            fullKeys.map { fullKey -> fullKey to findSources(fullKey) }
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { sourcesByKey ->
                if (sourcesByKey.any { it.second.isNotEmpty() }) deleteFromSources(sourcesByKey)
                onFinished()
            }
            .submit(AppExecutorUtil.getAppExecutorService())

    private fun findSources(fullKey: FullKey): List<LocalizationSource> {
        val sourceService = project.service<LocalizationSourceService>()
        val namespaces = fullKey.allNamespaces()
        return sourceService.findSources(namespaces, project)
            .ifEmpty { if (namespaces.isEmpty()) sourceService.findAllSources(project) else emptyList() }
            .let { found -> scopeToModule(found) }
    }

    private fun deleteFromSources(sourcesByKey: List<KeySources>) {
        // Collect first, then delete — both in one WriteCommandAction: a single undo restores
        // every key in every locale. Collecting inside it is what grants the PSI walk its read
        // access (the EDT no longer carries one) and keeps the lookup atomic with the deletion,
        // so a PSI change in between cannot make it delete the wrong property. The sources
        // themselves come from the background lookup, hence the validity check before deleting
        // (it also skips a key nested in another key deleted just before).
        // The deletion targets the whole property (not just its value, which used to leave a
        // dangling `"key":`) and removes the separating comma with it.
        val command = if (sourcesByKey.size > 1) "toolwindow.table.delete.orphans.command" else "toolwindow.table.delete.command"
        WriteCommandAction.runWriteCommandAction(project, PluginBundle.message(command), null, {
            val properties = sourcesByKey.flatMap { (fullKey, sources) ->
                sources.mapNotNull { source ->
                    val ref = resolveCompositeKey(fullKey.compositeKey, source) ?: return@mapNotNull null
                    if (ref.unresolved.isNotEmpty() || ref.element == null) return@mapNotNull null
                    PsiTreeUtil.getParentOfType(ref.element.value(), JsonProperty::class.java, YAMLKeyValue::class.java)
                }
            }
            properties.forEach { if (it.isValid) deletePropertyAndSeparator(it) }
        })
    }

    /** Keeps only the sources belonging to [moduleConfig], like TranslationDataLoader does for reads. */
    private fun scopeToModule(sources: List<LocalizationSource>): List<LocalizationSource> {
        val root = moduleConfig?.rootDirectory?.trimEnd('/')
        if (root.isNullOrBlank()) return sources
        return sources.filter { it.displayPath.startsWith(root) }
    }

    companion object {
        /**
         * The keys the context menu deletes for a selection of rows, given as `key to usageCount`:
         * the orphan ones only, so a selection spanning used or unscanned keys never deletes them.
         */
        fun orphanKeys(rows: List<Pair<String, Int>>, viewModel: TableViewModel = TableViewModel()): List<String> =
            rows.filter { (_, usageCount) -> viewModel.usageStatus(usageCount) == UsageStatus.ORPHAN }
                .map { it.first }
                .distinct()
    }
}
