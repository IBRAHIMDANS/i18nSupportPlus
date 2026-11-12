package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationFileRouting
import com.ibrahimdans.i18n.plugin.review.ReviewMigration
import com.ibrahimdans.i18n.plugin.review.ReviewSpot
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.TranslationPsi
import com.ibrahimdans.i18n.plugin.utils.deletePropertiesAndSeparators
import com.ibrahimdans.i18n.plugin.utils.sourcesOf
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement

/**
 * Finds a key's properties in the translation files and deletes keys with their reviews: shared by
 * *Clean Up Keys with No Reference* and the AI agents' tools, none of which needs the action itself.
 */
object TranslationKeyDeletion : CompositeKeyResolver<PsiElement> {

    /**
     * Resolves [key] in the sources that may hold it ([TranslationFileRouting.filesOf]) and returns
     * the property elements (JsonProperty / YAMLKeyValue) holding its value, one per locale where
     * the key exists.
     *
     * A key spelled without a namespace used to be resolved in every file of every locale: with
     * `common` as the default namespace, deleting its `menu.home` also deleted `auth`'s `menu.home`
     * — another key, shown on its own row as `auth:menu.home`.
     *
     * Takes the already-loaded [sources]: resolving them per key turned the scan
     * into O(keys × translation files) full PSI rebuilds.
     */
    internal fun leafProperties(sources: List<LocalizationSource>, key: String, config: Config = Config()): List<PsiElement> {
        val fullKey = KeysSynchronizer().buildFullKey(key, config)
        return TranslationFileRouting.filesOf(key, sources, config)
            .mapNotNull { source ->
                val ref = resolveCompositeKey(fullKey.compositeKey, source) ?: return@mapNotNull null
                val element = ref.element
                if (ref.unresolved.isNotEmpty() || element == null) return@mapNotNull null
                val value = element.value()
                TranslationPsi.propertyOf(value)
            }
    }

    /**
     * Deletes every locale's property for each of [keys], in one
     * WriteCommandAction — a single undo restores the whole cleanup, the
     * reviews of the deleted keys included ([ReviewMigration]).
     * Pure of any UI (testable); returns the number of deleted locale entries.
     *
     * Runs on the EDT, where its callers are: the preview's confirmation, the AI agents' tool, tests.
     */
    internal fun deleteKeys(project: Project, keys: List<String>, module: ModuleConfig? = null): Int {
        val (properties, reviews) = ReadAction.compute<Pair<List<PsiElement>, List<ReviewSpot>>, RuntimeException> {
            // One source load for the whole batch, not one per key; [module]'s files only.
            val sources = ModuleSources.sourcesOf(project, module)
            val config = Settings.getInstance(project).config()
            val found = keys.flatMap { leafProperties(sources, it, config) }
            found to ReviewMigration.spotsOf(found)
        }
        WriteCommandAction.writeCommandAction(project)
            .withName(PluginBundle.message("action.cleanup.command"))
            .withGlobalUndo()
            .withUndoConfirmationPolicy(UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION)
            .run<RuntimeException> {
                deletePropertiesAndSeparators(properties)
                ReviewMigration.remove(project, reviews)
            }
        return properties.size
    }
}
