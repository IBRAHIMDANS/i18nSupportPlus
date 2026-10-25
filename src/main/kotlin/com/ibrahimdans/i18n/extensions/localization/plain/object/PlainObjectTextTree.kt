package com.ibrahimdans.i18n.extensions.localization.plain.`object`

import com.ibrahimdans.i18n.plugin.utils.type
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.ibrahimdans.i18n.plugin.utils.at
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.psi.PsiElement

/**
 * Plain text file wrapper
 *
 * An entry's `msgid` is looked up among the children of its section, not taken as the first:
 * the origin lines `lingui extract` and `xgettext` write above it (`#: src/App.tsx:12`) are
 * parsed into the section, ahead of the msgid, and every entry carrying one was left unresolved.
 */
class PlainObjectTextTree(val element: PsiElement): Tree<PsiElement> {

    override fun findChild(name: String): Tree<PsiElement>? =
        msgidLines().find { it.toString() == name }?.let { PlainObjectTextTree(it) }

    override fun isTree(): Boolean {
        return element == element.containingFile
    }

    override fun value(): PsiElement =
        element.nextSibling?.nextSibling?.let { it.children.at(0) ?: it } ?: element

    /**
     * The msgids of the catalogue, as the key nodes completion lists: each one's value is the msgid
     * literal, as a JSON key node's is the property name. The header (`msgid ""`) is no key.
     */
    override fun findChildren(prefix: String): List<Tree<PsiElement>> =
        msgidLines()
            .mapNotNull { line -> line.children.firstOrNull { it.type() == STRING_LITERAL } }
            .filter { literal -> literal.text.unQuote().let { it.isNotBlank() && it.startsWith(prefix) } }
            .map(::MsgidName)

    /** The `msgid` line of each section, in document order; none below the file itself. */
    private fun msgidLines(): List<PsiElement> =
        if (!isTree()) emptyList()
        else element.children
            .filter { it.type() == SECTION }
            .mapNotNull { section -> section.children.firstOrNull { it.type() == ID_LINE } }

    /** A msgid as a key name: a leaf whose value is the msgid literal itself. */
    private class MsgidName(private val literal: PsiElement) : Tree<PsiElement> {
        override fun findChild(name: String): Tree<PsiElement>? = null
        override fun isTree(): Boolean = false
        override fun value(): PsiElement = literal
        override fun findChildren(prefix: String): List<Tree<PsiElement>> = emptyList()
    }

    companion object {
        private const val SECTION = "SECTION"
        private const val ID_LINE = "ID_LINE"
        private const val STRING_LITERAL = "STRING_LITERAL_EXPRESSION"

        /**
         * The msgid literal of the entry [value] belongs to — the msgstr a key resolves to — or null
         * outside a PO entry: what renaming a key rewrites in a catalogue, the msgid being the key.
         */
        fun msgidOf(value: PsiElement): PsiElement? {
            val section = generateSequence(value) { it.parent }.take(3).firstOrNull { it.type() == SECTION } ?: return null
            return section.children.firstOrNull { it.type() == ID_LINE }
                ?.children?.firstOrNull { it.type() == STRING_LITERAL }
        }
    }
}
