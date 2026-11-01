package com.ibrahimdans.i18n.extensions.localization.xliff

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText

/**
 * The value elements of an XLIFF tree ([XliffDocument.valueElementOf]) read and written as text: a
 * `<target>`'s plain text node, or the tag itself when it is empty or holds inline elements.
 *
 * Written through the document, never through the XML formatter: a unit's text is mixed content, and
 * reformatting `Hello <x id="name"/>!` drops the space the translation needs.
 */
object XliffTranslationPsi {

    /**
     * Whether [element] lies in an XLIFF file. Told by the virtual file: the PSI file of an `.xlf` is an
     * XML file, and names the XML type.
     */
    fun isXliff(element: PsiElement): Boolean =
        element.containingFile?.originalFile?.virtualFile?.fileType == XliffFileType.INSTANCE

    /** The text [element] holds, its entities decoded; null for an element of no XLIFF file. */
    fun readValue(element: PsiElement): String? = when {
        !isXliff(element) -> null
        element is XmlText -> StringUtil.unescapeXmlEntities(element.text)
        element is XmlTag -> StringUtil.unescapeXmlEntities(element.value.text)
        else -> null
    }

    /** Replaces the text [element] holds with [newValue], escaped; false for an element of no XLIFF file. */
    fun replaceValue(element: PsiElement, newValue: String): Boolean {
        if (!isXliff(element)) return false
        val tag = (element as? XmlText)?.parentTag ?: element as? XmlTag ?: return false
        setContent(tag, StringUtil.escapeXmlEntities(newValue))
        return true
    }

    /** Sets the content of [tag] to the XML text [xml]; an empty tag (`<target/>`) is opened to hold it. */
    fun setContent(tag: XmlTag, xml: String) {
        edit(tag) { document ->
            if (tag.isEmpty) {
                val range = tag.textRange
                val open = tag.text.removeSuffix("/>").trimEnd()
                document.replaceString(range.startOffset, range.endOffset, "$open>$xml</${tag.name}>")
            } else {
                val range = tag.value.textRange
                document.replaceString(range.startOffset, range.endOffset, xml)
            }
        }
    }

    /** Inserts [text] right after [element]. */
    fun insertAfter(element: PsiElement, text: String) {
        edit(element) { document -> document.insertString(element.textRange.endOffset, text) }
    }

    /** Inserts [text] right after the start tag of [tag], before its first child. */
    fun insertAtStart(tag: XmlTag, text: String) {
        edit(tag) { document -> document.insertString(tag.value.textRange.startOffset, text) }
    }

    /** The blanks before [element] on its line, or [fallback] when other text precedes it there. */
    fun indentOf(element: PsiElement, fallback: String): String {
        val document = documentOf(element) ?: return fallback
        val start = element.textRange.startOffset
        val lineStart = document.getLineStartOffset(document.getLineNumber(start))
        val before = document.charsSequence.subSequence(lineStart, start).toString()
        return before.takeIf { it.isBlank() } ?: fallback
    }

    private fun documentOf(element: PsiElement): Document? =
        element.containingFile?.let { PsiDocumentManager.getInstance(element.project).getDocument(it) }

    private fun edit(element: PsiElement, change: (Document) -> Unit) {
        val manager = PsiDocumentManager.getInstance(element.project)
        val document = documentOf(element) ?: return
        manager.doPostponedOperationsAndUnblockDocument(document)
        change(document)
        manager.commitDocument(document)
    }
}
