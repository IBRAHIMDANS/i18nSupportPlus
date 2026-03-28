package com.ibrahimdans.i18n.plugin.ide.hint

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.ellipsis
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlText

private const val MAX_TRANSLATION_LENGTH = 60
private val LOCALE_REGEX = Regex("[a-zA-Z]{2,3}([_\\-][a-zA-Z]{2,4})?")

/**
 * Shows all available translations (grouped by language folder) as a hint on Ctrl+hover.
 * Each locale is displayed in a table row. Missing translations are shown as "—".
 * Clicking a "→" link navigates to the translation file.
 */
class HintProvider : DocumentationProvider, CompositeKeyResolver<PsiElement> {

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        if (element is XmlText) return null
        val translation = originalElement?.let { resolveAllTranslations(it) } ?: return null
        return "<div style='padding:4px'>$translation</div>"
    }

    // Return null to force IntelliJ to use generateDoc (JBPopup with safe area)
    // instead of LightweightHint which disappears on mouse move.
    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? = null

    /**
     * Handles navigation links encoded as "displayPath" (without protocol prefix).
     * Navigates to the translation file when a "→" link is clicked in the doc popup.
     */
    override fun getDocumentationElementForLink(
        psiManager: PsiManager,
        link: String,
        context: PsiElement?
    ): PsiElement? {
        val project = psiManager.project
        val basePath = project.basePath ?: return null
        val virtualFile = LocalFileSystem.getInstance().findFileByPath("$basePath/$link") ?: return null
        // navigate(true) must run on the EDT — schedule it regardless of the calling thread
        ApplicationManager.getApplication().invokeLater {
            OpenFileDescriptor(project, virtualFile).navigate(true)
        }
        // findFile requires a read action when called from a background thread (IntelliJ 2024.3+)
        return ReadAction.compute<com.intellij.psi.PsiFile?, Throwable> {
            psiManager.findFile(virtualFile)
        }
    }

    private fun resolveAllTranslations(sourceElement: PsiElement): String? {
        val project = sourceElement.project
        val translationFunctionNames = Extensions.TECHNOLOGY.extensionList.flatMap { it.translationFunctionNames() }

        val rawKey = Extensions.LANG.extensionList
            .firstOrNull { it.canExtractKey(sourceElement, translationFunctionNames) }
            ?.extractRawKey(sourceElement)
            ?: return null

        val fullKey = RawKeyParser(project).parse(rawKey) ?: return null

        val sources = project.service<LocalizationSourceService>().findSources(fullKey.allNamespaces(), project)
        if (sources.isEmpty()) return null

        val pluralSeparator = Settings.getInstance(project).config().pluralSeparator

        val rows = sources.mapNotNull { source ->
            val refs = resolve(fullKey.compositeKey, source, pluralSeparator)
            val resolvedLeaf = refs.firstOrNull { it.unresolved.isEmpty() && it.element?.isLeaf() == true }
                ?: return@mapNotNull null
            val value = resolvedLeaf.element?.value()?.text?.unQuote()?.ellipsis(MAX_TRANSLATION_LENGTH)
                ?: return@mapNotNull null
            val locale = localeLabel(source)
            val navLink = "<a href='psi_element://${source.displayPath}'>&#8599;</a>"
            "<tr>" +
                "<td><b>$locale</b></td>" +
                "<td style='padding-left:8px; white-space:nowrap'>$value</td>" +
                "<td style='padding-left:8px'>$navLink</td>" +
            "</tr>"
        }

        if (rows.isEmpty()) return null

        return "<table cellspacing='0' cellpadding='2'>${rows.joinToString("")}</table>"
    }

    /** Returns the locale code from the file stem (e.g. "en.json" → "en") or parent dir (e.g. "fr/translation.json" → "fr"). */
    private fun localeLabel(source: LocalizationSource): String {
        val stem = source.name.substringBeforeLast(".")
        return if (LOCALE_REGEX.matches(stem)) stem else source.parent
    }
}
