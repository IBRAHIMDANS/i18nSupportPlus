package com.ibrahimdans.i18n.plugin.ide

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.Lang
import com.ibrahimdans.i18n.plugin.ide.quickfix.CreateKeyDialogQuickFix
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import java.awt.event.MouseEvent
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
    }

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // A marker goes on a leaf, as the platform requires. It used to be returned for the literal
        // expression and anchored on its token, which a plain JS file accepts; but a marker found in
        // an injected fragment — a Vue template's `{{ $t('key') }}` — is rebuilt on the element the
        // platform asked, and the literal tripped its leaf-only check there. The key is read from
        // the element this leaf starts ([claimedFrom]): the literal for `'key'`, `m.hello` for `m`.
        if (element.firstChild != null) return null
        val project = element.project
        if (!Settings.getInstance(project).config().gutterIconsEnabled) return null

        val translationFunctionNames = Extensions.TECHNOLOGY.extensionList
            .flatMap { it.translationFunctionNames() }
        val (keyElement, lang) = claimedFrom(element, translationFunctionNames) ?: return null

        val rawKey = lang.extractRawKey(keyElement) ?: return null
        val fullKey = RawKeyParser(project).parse(rawKey, keyElement) ?: return null
        if (fullKey.compositeKey.isEmpty()) return null
        // A runtime segment cannot be verified statically: the annotator reports nothing on
        // such a key, and this provider used to show it as missing in every locale — with a
        // click that created a property literally named `${status}`.
        if (fullKey.isDynamic) return null

        val config = Settings.getInstance(project).config()
        val sourceService = project.service<LocalizationSourceService>()
        // The namespaces are passed as the key carries them, and the whole-scan fallback is
        // left to findSources. This provider used to substitute the default namespaces first
        // and fall back on its own — which was the only thing making the "one file per locale"
        // layout work anywhere, until #159 moved that fallback into the service. Keeping the
        // local one would now be *wider* than the shared rule rather than redundant: it fires
        // for an explicit namespace matching no file too, so `t('common:user.name')` with no
        // common.json would show per-locale statuses computed against unrelated files while
        // the annotator reports an unresolved namespace — two verdicts on one key.
        val sources = sourceService.findReadSources(fullKey, keyElement)
        if (sources.isEmpty()) return null

        val pluralSeparator = config.pluralSeparator

        // A source is a (locale × namespace) pair: a file declaring several namespaces gets one
        // source per namespace per locale. Counting sources would report `dashboard:title` as
        // 2/4 next to `common.json`, so sources are grouped by locale, and a locale is resolved
        // when any of its namespaces holds the key — the same rule the annotator applies.
        val statuses = sources.groupBy { it.localeLabel() }.map { (label, localeSources) ->
            val isResolved = localeSources.any { source ->
                resolve(fullKey.compositeKey, source, pluralSeparator)
                    .any { it.unresolved.isEmpty() && it.element != null }
            }
            LocaleRow(label, isResolved, !config.ignoresLocale(label))
        }

        // A locale not checked for missing keys is listed but never counted: the annotator's rule.
        val checked = statuses.filter { it.checked }
        val resolvedCount = checked.count { it.resolved }
        val total = checked.size

        val (icon, statusLabel) = when {
            resolvedCount == total -> ICON_RESOLVED to "All locales resolved ($total/$total)"
            resolvedCount == 0    -> ICON_MISSING  to "Missing in all locales (0/$total)"
            else                  -> ICON_PARTIAL  to "Partial translation ($resolvedCount/$total locales)"
        }

        val tooltip = buildTooltip(statusLabel, statuses)

        // Navigation handler: trigger CreateKeyQuickFix when icon is clicked and key is missing/partial
        val navHandler: GutterIconNavigationHandler<PsiElement>? = if (resolvedCount < total) {
            GutterIconNavigationHandler { _: MouseEvent, psiElement: PsiElement ->
                val editor = FileEditorManager.getInstance(project).selectedEditor
                    ?.let { it as? TextEditor }
                    ?.editor
                    ?: return@GutterIconNavigationHandler
                CreateKeyDialogQuickFix(fullKey, PluginBundle.getMessage("quickfix.create.key")).invoke(project, editor)
            }
        } else null

        return LineMarkerInfo(
            element,
            element.textRange,
            icon,
            { _: PsiElement -> tooltip },
            navHandler,
            GutterIconRenderer.Alignment.LEFT,
            { tooltip }
        )
    }

    /**
     * The outermost element starting with [leaf] that a [Lang] reads a key from, with that Lang. A
     * literal expression and its token are both claimed: the expression owns the key, and its token
     * the one badge. Each element starts with exactly one leaf, so every element is still asked once.
     */
    private fun claimedFrom(leaf: PsiElement, translationFunctionNames: List<String>): Pair<PsiElement, Lang>? {
        var claimed: Pair<PsiElement, Lang>? = null
        var current = leaf
        while (true) {
            Extensions.LANG.extensionList.firstOrNull { it.canExtractKey(current, translationFunctionNames) }
                ?.let { claimed = current to it }
            val parent = current.parent ?: break
            if (parent is PsiFile || parent.firstChild !== current) break
            current = parent
        }
        return claimed
    }

    /**
     * Builds an HTML tooltip summarising the resolution status per locale.
     */
    private fun buildTooltip(statusLabel: String, locales: List<LocaleRow>): String {
        val rows = locales.joinToString("") { (label, resolved, checked) ->
            val mark = if (resolved) "✓" else if (checked) "✗" else "–"
            val color = if (resolved) "green" else if (checked) "red" else "gray"
            val note = if (checked) "" else " (not checked)"
            "<tr><td><font color='$color'>$mark</font></td><td style='padding-left:4px'>$label$note</td></tr>"
        }
        return "<html><b>i18n: $statusLabel</b><table cellspacing='0' cellpadding='1'>$rows</table></html>"
    }
}

/** One locale of the tooltip: [checked] false for a locale not checked for missing keys. */
private data class LocaleRow(val label: String, val resolved: Boolean, val checked: Boolean)
