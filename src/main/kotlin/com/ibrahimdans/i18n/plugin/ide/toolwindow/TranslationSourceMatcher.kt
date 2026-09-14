package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.concurrency.CancellablePromise
import java.util.concurrent.atomic.AtomicReference

/**
 * Decides whether a VFS change concerns a translation file the tool window displays.
 *
 * UI-free on purpose: the rule has to be testable, and the panel is not instantiable
 * headlessly.
 *
 * It never scans anything itself, because it runs for every file the IDE writes. It answers
 * from a snapshot of the paths the last reload displayed, taken by [rememberDisplayedSources]
 * in the background — asking the service from here would rescan the whole project on every
 * keystroke instead, since editing a file invalidates the `findAllSources` cache.
 *
 * A file absent from the snapshot only matches on a *structural* change — created, deleted,
 * moved or renamed — and only for a format a `Localization` declares. Those events are rare,
 * unlike content changes, and are the only way a new translation file can enter the snapshot.
 * Matching an ordinary edit on the extension alone is not an option: the plugin reads `.json`
 * and `.yml`, which projects also use for everything else.
 */
class TranslationSourceMatcher(private val project: Project) {

    private val displayedPaths = AtomicReference<Set<String>>(emptySet())

    /** True when at least one of [events] concerns a displayed translation file. */
    fun matchesAny(events: List<VFileEvent>): Boolean {
        if (events.isEmpty()) return false
        val extensions = localizationExtensions()
        val displayed = displayedPaths.get()
        return events.any { event ->
            val file = event.file ?: return@any false
            matches(file, event is VFileContentChangeEvent, extensions, displayed)
        }
    }

    /**
     * The rule itself, on a file rather than an event so it can be tested without building
     * platform events. [isContentChange] tells an edit apart from a structural change.
     */
    fun matches(file: VirtualFile, isContentChange: Boolean): Boolean =
        matches(file, isContentChange, localizationExtensions(), displayedPaths.get())

    /**
     * Records the translation files currently displayed, so a later content change can be
     * recognised without scanning. Call right after a reload.
     *
     * The scan runs off the EDT: `findAllSources` walks the file-type index, which the platform
     * forbids there as a slow operation, and both callers run on the EDT — the tool window
     * factory and the debounced reload. The cache the scan was once assumed to hit is not warm
     * at that point either, since the panels refresh on pooled threads. The snapshot is an
     * atomic reference, so nothing needs to come back to the EDT; until it lands, the matcher
     * answers from the previous one. A newer request cancels a pending one.
     */
    fun rememberDisplayedSources(): CancellablePromise<Set<String>> =
        ReadAction.nonBlocking<Set<String>> {
            project.service<LocalizationSourceService>().findAllSources(project)
                .mapNotNull { source ->
                    source.tree?.value()
                        ?.takeIf { it.isValid }
                        ?.containingFile
                        ?.virtualFile
                        ?.path
                }
                .toSet()
                .also { displayedPaths.set(it) }
        }
            .inSmartMode(project)
            .expireWith(project)
            .coalesceBy(this)
            .submit(AppExecutorUtil.getAppExecutorService())

    /** Visible for tests: the snapshot the rule answers from. */
    internal fun displayedSourcePaths(): Set<String> = displayedPaths.get()

    private fun matches(
        file: VirtualFile,
        isContentChange: Boolean,
        extensions: Set<String>,
        displayed: Set<String>
    ): Boolean {
        if (file.isDirectory) return false
        // Answered first, and on the path rather than on the type: a source contributed by a
        // Technology — the TypeScript catalogues of React Native projects — carries no
        // extension any Localization declares, and would be missed by a type test.
        if (file.path in displayed) return true
        return !isContentChange && file.extension?.lowercase() in extensions
    }

    /**
     * Every extension a [com.ibrahimdans.i18n.Localization] declares — json, json5, yml, po.
     * Deliberately not the sources a Technology contributes: those are reached through the
     * snapshot, since `TsLocalization` declares no file type at all.
     */
    private fun localizationExtensions(): Set<String> =
        Extensions.LOCALIZATION.extensionList
            .flatMap { it.types() }
            .flatMap { it.extensions() }
            .mapTo(mutableSetOf()) { it.lowercase() }
}
