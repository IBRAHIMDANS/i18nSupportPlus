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
    fun delete(fullKey: FullKey, onFinished: () -> Unit = {}): CancellablePromise<List<LocalizationSource>> =
        ReadAction.nonBlocking<List<LocalizationSource>> {
            val sourceService = project.service<LocalizationSourceService>()
            val namespaces = fullKey.allNamespaces()
            sourceService.findSources(namespaces, project)
                .ifEmpty { if (namespaces.isEmpty()) sourceService.findAllSources(project) else emptyList() }
                .let { found -> scopeToModule(found) }
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { sources ->
                if (sources.isNotEmpty()) deleteFromSources(fullKey, sources)
                onFinished()
            }
            .submit(AppExecutorUtil.getAppExecutorService())

    private fun deleteFromSources(fullKey: FullKey, sources: List<LocalizationSource>) {
        // Collect first, then delete — both in one WriteCommandAction: a single undo restores
        // the key in every locale. Collecting inside it is what grants the PSI walk its read
        // access (the EDT no longer carries one) and keeps the lookup atomic with the deletion,
        // so a PSI change in between cannot make it delete the wrong property. The sources
        // themselves come from the background lookup, hence the validity check before deleting.
        // The deletion targets the whole property (not just its value, which used to leave a
        // dangling `"key":`) and removes the separating comma with it.
        WriteCommandAction.runWriteCommandAction(project, PluginBundle.message("toolwindow.table.delete.command"), null, {
            val properties = sources.mapNotNull { source ->
                val ref = resolveCompositeKey(fullKey.compositeKey, source) ?: return@mapNotNull null
                if (ref.unresolved.isNotEmpty() || ref.element == null) return@mapNotNull null
                PsiTreeUtil.getParentOfType(ref.element.value(), JsonProperty::class.java, YAMLKeyValue::class.java)
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
}
