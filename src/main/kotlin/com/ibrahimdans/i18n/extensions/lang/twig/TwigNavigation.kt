package com.ibrahimdans.i18n.extensions.lang.twig

import com.ibrahimdans.i18n.plugin.ide.references.code.I18nGotoDeclarationHandler
import com.ibrahimdans.i18n.plugin.ide.references.code.I18nReference
import com.ibrahimdans.i18n.plugin.ide.references.code.ReferenceDescriptor
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.util.elementType
import com.intellij.psi.xml.XmlTokenType
import com.intellij.util.ProcessingContext
import com.jetbrains.twig.TwigLanguage
import com.jetbrains.twig.TwigTokenTypes

/**
 * Ctrl+click on the key of `'key'|trans` opens its translation in the preview locale, as for any key
 * ([I18nGotoDeclarationHandler.previewTarget]). The key is a bare token of the Twig tree, and a Twig
 * token asks no reference contributor for references: the handler resolves the key itself.
 */
class TwigGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        val key = sourceElement?.takeIf { it.elementType == TwigTokenTypes.STRING_TEXT } ?: return null
        val reference = TwigKeyReferences.of(key, key).firstOrNull() as? I18nReference ?: return null
        return I18nGotoDeclarationHandler.previewTarget(reference)
            ?: reference.multiResolve(false).mapNotNull { it.element }.toTypedArray().takeIf { it.isNotEmpty() }
    }
}

/**
 * Ctrl+click from the key of `{% trans %}key{% endtrans %}`. The body of a tag is the template's
 * markup: the editor finds the HTML tree's text there, not the Twig tree's, so the reference is put on
 * that text, the key being read from the Twig tree at the same place.
 */
class TwigTagReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(XmlTokenType.XML_DATA_CHARACTERS),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                    val twig = element.containingFile?.viewProvider?.getPsi(TwigLanguage.INSTANCE) ?: return PsiReference.EMPTY_ARRAY
                    val body = twig.node.findLeafElementAt(element.textRange.startOffset)?.psi
                        ?.takeIf { it.elementType == TwigTokenTypes.TEMPLATE_HTML_TEXT } ?: return PsiReference.EMPTY_ARRAY
                    return TwigKeyReferences.of(element, body).toTypedArray()
                }
            }
        )
    }
}

/** The references a key of a Twig template gives. */
private object TwigKeyReferences : CompositeKeyResolver<PsiElement> {

    private val lang = TwigLang()

    /** The reference on [host] to the key [keyElement] holds, over the part of the key [host] covers. */
    fun of(host: PsiElement, keyElement: PsiElement): List<PsiReference> {
        val fullKey = lang.extractRawKey(keyElement)?.let { RawKeyParser(keyElement.project).parse(it, keyElement) } ?: return emptyList()
        val pluralSeparator = Settings.getInstance(keyElement.project).config().pluralSeparator
        val references = keyElement.project.service<LocalizationSourceService>()
            .findReadSources(fullKey, keyElement)
            .flatMap { source -> resolve(fullKey, source, pluralSeparator).map { ReferenceDescriptor(it, source.host) } }
            .filter { it.reference.path.isNotEmpty() }
        if (references.isEmpty()) return emptyList()
        val start = keyElement.textRange.startOffset + keyElement.text.indexOf(fullKey.source).coerceAtLeast(0)
        val key = TextRange(start, start + fullKey.source.length).intersection(host.textRange) ?: return emptyList()
        if (key.isEmpty) return emptyList()
        return listOf(I18nReference(host, key.shiftLeft(host.textRange.startOffset), references))
    }
}
