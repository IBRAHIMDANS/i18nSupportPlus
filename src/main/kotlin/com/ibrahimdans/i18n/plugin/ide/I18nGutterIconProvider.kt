package com.ibrahimdans.i18n.plugin.ide

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.ide.quickfix.AllSourcesSelector
import com.ibrahimdans.i18n.plugin.ide.quickfix.CreateKeyQuickFix
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import java.awt.event.MouseEvent
import java.util.Collections
import javax.swing.Icon

/**
 * Provides gutter badges in the editor margin for i18n keys.
 *
 * Three states:
 *  - resolved : key found in ALL locales     → green checkmark
 *  - partial  : key found in SOME locales    → yellow warning
 *  - missing  : key found in NO locale       → red cross
 *
 * Tooltip shows per-locale resolution status.
 * Clicking the gutter icon on a missing/partial key triggers CreateKeyQuickFix.
 */
class I18nGutterIconProvider : LineMarkerProvider, CompositeKeyResolver<PsiElement> {

    companion object {
        private val ICON_RESOLVED: Icon = IconLoader.getIcon("/icons/gutter_resolved.svg", I18nGutterIconProvider::class.java)
        private val ICON_PARTIAL: Icon  = IconLoader.getIcon("/icons/gutter_partial.svg",  I18nGutterIconProvider::class.java)
        private val ICON_MISSING: Icon  = IconLoader.getIcon("/icons/gutter_missing.svg",  I18nGutterIconProvider::class.java)

        private val LOCALE_REGEX = Regex("[a-zA-Z]{2,3}([_\\-][a-zA-Z]{2,4})?")

        // Prevents duplicate markers when multiple language providers (JS, JSX, TS, TSX) are
        // each invoked by IntelliJ for the same underlying file content.
        // The Long is the document modificationStamp so the set is invalidated on each edit.
        private val MARKER_CACHE_KEY = Key.create<Pair<Long, MutableSet<TextRange>>>("i18n.gutter.cache")
    }

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val project = element.project
        if (!Settings.getInstance(project).config().gutterIconsEnabled) return null

        val translationFunctionNames = Extensions.TECHNOLOGY.extensionList
            .flatMap { it.translationFunctionNames() }

        // Only process elements that a known Lang can handle
        val lang = Extensions.LANG.extensionList.firstOrNull {
            it.canExtractKey(element, translationFunctionNames)
        } ?: return null

        val rawKey = lang.extractRawKey(element) ?: return null
        val fullKey = RawKeyParser(project).parse(rawKey) ?: return null
        if (fullKey.compositeKey.isEmpty()) return null

        val config = Settings.getInstance(project).config()
        val sourceService = project.service<LocalizationSourceService>()
        val namespaces = fullKey.allNamespaces().ifEmpty { config.defaultNamespaces() }
        val sources = sourceService.findSources(namespaces, project)
            .ifEmpty { sourceService.findAllSources(project) }
        if (sources.isEmpty()) return null

        val pluralSeparator = config.pluralSeparator

        // Resolve the key in each source and build per-locale status
        data class LocaleStatus(val label: String, val resolved: Boolean)
        val statuses = sources.map { source ->
            val refs = resolve(fullKey.compositeKey, source, pluralSeparator)
            val isResolved = refs.any { it.unresolved.isEmpty() && it.element != null }
            LocaleStatus(localeLabel(source.name, source.parent), isResolved)
        }

        val resolvedCount = statuses.count { it.resolved }
        val total = statuses.size

        val (icon, statusLabel) = when {
            resolvedCount == total -> ICON_RESOLVED to "All locales resolved ($total/$total)"
            resolvedCount == 0    -> ICON_MISSING  to "Missing in all locales (0/$total)"
            else                  -> ICON_PARTIAL  to "Partial translation ($resolvedCount/$total locales)"
        }

        val tooltip = buildTooltip(statusLabel, statuses.map { (it.label to it.resolved) })

        // Anchor on the leaf token to position the badge correctly
        val anchor = element.firstChild ?: element

        // Deduplicate: skip if another language provider already produced a marker for this range.
        // This happens when IntelliJ invokes all registered providers (JS, JSX, TS, TSX) for the same file.
        val document = element.containingFile?.viewProvider?.document
        if (document != null) {
            val modStamp = document.modificationStamp
            val processedRanges = synchronized(document) {
                val cached = document.getUserData(MARKER_CACHE_KEY)
                if (cached != null && cached.first == modStamp) {
                    cached.second
                } else {
                    val newSet = Collections.synchronizedSet(mutableSetOf<TextRange>())
                    document.putUserData(MARKER_CACHE_KEY, Pair(modStamp, newSet))
                    newSet
                }
            }
            if (!processedRanges.add(anchor.textRange)) return null
        }

        // Navigation handler: trigger CreateKeyQuickFix when icon is clicked and key is missing/partial
        val navHandler: GutterIconNavigationHandler<PsiElement>? = if (resolvedCount < total) {
            GutterIconNavigationHandler { _: MouseEvent, psiElement: PsiElement ->
                val editor = FileEditorManager.getInstance(project).selectedEditor
                    ?.let { it as? TextEditor }
                    ?.editor
                    ?: return@GutterIconNavigationHandler
                CreateKeyQuickFix(
                    fullKey,
                    AllSourcesSelector(),
                    "Create missing i18n key"
                ).invoke(project, editor)
            }
        } else null

        return LineMarkerInfo(
            anchor,
            anchor.textRange,
            icon,
            { _: PsiElement -> tooltip },
            navHandler,
            GutterIconRenderer.Alignment.LEFT,
            { tooltip }
        )
    }

    /**
     * Builds an HTML tooltip summarising the resolution status per locale.
     */
    private fun buildTooltip(statusLabel: String, locales: List<Pair<String, Boolean>>): String {
        val rows = locales.joinToString("") { (label, resolved) ->
            val mark = if (resolved) "✓" else "✗"
            val color = if (resolved) "green" else "red"
            "<tr><td><font color='$color'>$mark</font></td><td style='padding-left:4px'>$label</td></tr>"
        }
        return "<html><b>i18n: $statusLabel</b><table cellspacing='0' cellpadding='1'>$rows</table></html>"
    }

    /**
     * Returns the locale label: file stem if it looks like a locale code (e.g. "en", "fr-FR"),
     * otherwise falls back to the parent directory name.
     */
    private fun localeLabel(fileName: String, parentDir: String): String {
        val stem = fileName.substringBeforeLast(".")
        return if (LOCALE_REGEX.matches(stem)) stem else parentDir
    }
}
