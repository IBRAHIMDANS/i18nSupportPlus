package com.ibrahimdans.i18n.plugin.ide.hint

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.actions.EditTranslationAtCaretAction
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.tree.PluralGroup
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.ellipsis
import com.ibrahimdans.i18n.plugin.utils.LocaleMatching
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.ibrahimdans.i18n.plugin.utils.renderIcu
import com.ibrahimdans.i18n.plugin.utils.InlangMessageRenderer
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlText

private const val MAX_TRANSLATION_LENGTH = 60

/** Prefix of the *Edit* link: `i18n-edit:<offset>:<file url>`, the literal the hint was shown for. */
internal const val EDIT_LINK_PREFIX = "i18n-edit:"

/** Prefix of a "→" link: `i18n-go:<offset>:<file url>`, the root of the source in its file. */
internal const val NAVIGATE_LINK_PREFIX = "i18n-go:"

/**
 * Shows all available translations (grouped by language folder) as a hint on Ctrl+hover.
 * Each locale is displayed in a table row. Missing translations are shown as "—".
 * Clicking a "→" link navigates to the translation file; the *Edit* link below the table opens
 * the translation dialog on the key, as [EditTranslationAtCaretAction] does.
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
     * Opens what a link of the popup points to: the translation dialog for the *Edit* link, the
     * source's file at its root for a "→" link ([NAVIGATE_LINK_PREFIX]). A "→" link of the older
     * form, a display path, is still resolved against the project's base path — which an inline
     * source (`i18n.ts#translation`) or a module outside it never matched: those get the file URL.
     */
    override fun getDocumentationElementForLink(
        psiManager: PsiManager,
        link: String,
        context: PsiElement?
    ): PsiElement? {
        val project = psiManager.project
        if (link.startsWith(EDIT_LINK_PREFIX)) {
            locate(link.removePrefix(EDIT_LINK_PREFIX))?.let { (virtualFile, offset) -> openEditDialog(psiManager, virtualFile, offset) }
            return null
        }
        val (virtualFile, offset) = if (link.startsWith(NAVIGATE_LINK_PREFIX)) {
            locate(link.removePrefix(NAVIGATE_LINK_PREFIX)) ?: return null
        } else {
            val basePath = project.basePath ?: return null
            val file = LocalFileSystem.getInstance().findFileByPath("$basePath/$link") ?: return null
            file to -1
        }
        // navigate(true) must run on the EDT — schedule it regardless of the calling thread
        ApplicationManager.getApplication().invokeLater {
            OpenFileDescriptor(project, virtualFile, offset).navigate(true)
        }
        // findFile requires a read action when called from a background thread (IntelliJ 2024.3+)
        return ReadAction.compute<PsiFile?, RuntimeException> {
            psiManager.findFile(virtualFile)
        }
    }

    /** The file and offset a `<offset>:<file url>` [target] names; null once the file is gone. */
    private fun locate(target: String): Pair<VirtualFile, Int>? {
        val offset = target.substringBefore(':').toIntOrNull() ?: return null
        val virtualFile = VirtualFileManager.getInstance().findFileByUrl(target.substringAfter(':')) ?: return null
        return virtualFile to offset
    }

    /**
     * Opens the translation dialog on the literal at [offset] of [virtualFile]. The dialog is
     * modal: it waits for the next EDT turn, out of the link handling.
     */
    private fun openEditDialog(psiManager: PsiManager, virtualFile: VirtualFile, offset: Int) {
        val project = psiManager.project
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val element = ReadAction.compute<PsiElement?, RuntimeException> {
                psiManager.findFile(virtualFile)?.findElementAt(offset)
            } ?: return@invokeLater
            EditTranslationAtCaretAction.edit(project, element)
        }
    }

    private fun resolveAllTranslations(hovered: PsiElement): String? {
        val project = hovered.project
        // The hovered token, or the element up to two levels above it holding the key: Paraglide's
        // `m.hello` is claimed as a whole, below which `hello` is a mere identifier.
        val (sourceElement, fullKey) = generateSequence(hovered) { it.parent }.take(3)
            .firstNotNullOfOrNull(EditTranslationAtCaretAction::keyAt) ?: return null

        val sources = project.service<LocalizationSourceService>().findReadSources(fullKey, sourceElement)
        if (sources.isEmpty()) return null

        val config = Settings.getInstance(project).config()
        val pluralSeparator = config.pluralSeparator
        // The preview locale leads the table: it is the one the user reads the code in.
        val previewLocale = LocaleMatching.pick(config.previewLocale.ifBlank { config.foldingPreferredLanguage }, sources.map { it.localeLabel() })

        val rows = sources.sortedByDescending { it.localeLabel() == previewLocale }.mapNotNull { source ->
            val refs = resolve(fullKey, source, pluralSeparator)
            val displayed = refs.asSequence()
                .filter { it.unresolved.isEmpty() }
                .mapNotNull { PluralGroup.displayableValue(it.element) }
                .firstOrNull()
                ?: return@mapNotNull null
            // Escaping comes last, after rendering and truncation: entities must not be
            // counted in the truncated length, nor have their `&` escaped a second time.
            val value = displayed.value().text?.let(InlangMessageRenderer::preview)?.unQuote()?.renderIcu()?.ellipsis(MAX_TRANSLATION_LENGTH)
                ?.let { StringUtil.escapeXmlEntities(it) }
                ?: return@mapNotNull null
            val locale = StringUtil.escapeXmlEntities(source.localeLabel())
            // The target lands in a single-quoted attribute, where one apostrophe would close it.
            val navLink = "<a href='psi_element://${StringUtil.escapeXmlEntities(navigationTarget(source))}'>&#8599;</a>"
            "<tr>" +
                "<td><b>$locale</b></td>" +
                "<td style='padding-left:8px; white-space:nowrap'>$value</td>" +
                "<td style='padding-left:8px'>$navLink</td>" +
            "</tr>"
        }

        if (rows.isEmpty()) return null

        return "<table cellspacing='0' cellpadding='2'>${rows.joinToString("")}</table>" + editLink(sourceElement)
    }

    /**
     * Where the "→" link of [source] leads: `i18n-go:<offset>:<file url>` of the source's root, so
     * that a source inside a configuration file (`i18n.ts#translation`) opens on its object and a
     * module outside the project's base path opens at all; the display path, resolved against the
     * base path, for a source with no element to read a file from.
     */
    private fun navigationTarget(source: LocalizationSource): String {
        val root = source.tree?.value() ?: return source.displayPath
        val url = root.containingFile?.virtualFile?.url ?: return source.displayPath
        return "$NAVIGATE_LINK_PREFIX${root.textRange.startOffset}:$url"
    }

    /** The *Edit* link to the literal [sourceElement], or nothing for a literal in no file on disk. */
    private fun editLink(sourceElement: PsiElement): String {
        val url = sourceElement.containingFile?.virtualFile?.url ?: return ""
        val href = StringUtil.escapeXmlEntities("$EDIT_LINK_PREFIX${sourceElement.textRange.startOffset}:$url")
        val label = StringUtil.escapeXmlEntities(PluginBundle.message("hint.edit.link"))
        return "<div style='padding-top:4px'><a href='psi_element://$href'>$label</a></div>"
    }
}
