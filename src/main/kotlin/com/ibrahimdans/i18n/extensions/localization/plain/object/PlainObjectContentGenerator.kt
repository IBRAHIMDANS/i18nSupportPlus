package com.ibrahimdans.i18n.extensions.localization.plain.`object`

import com.ibrahimdans.i18n.ContentGenerator
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.lang.Language
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import javax.swing.Icon

// PO is a flat list of msgid/msgstr entries with no nested blocks, so entries are appended as text
// at document level rather than built as PSI. The GNU GetText plugin
// (org.jetbrains.plugins.localization) is declared in build.gradle.kts and its PSI does resolve —
// PlainObjectTextTree and PlainObjectReferenceAssistant both read it.
class PlainObjectContentGenerator : ContentGenerator {

    private companion object {
        /** A `msgid` opening an entry. Leading blanks are tolerated: fixtures carry some. */
        val MSGID_LINE = Regex("""^[ \t]*msgid[ \t]+"(.*)"[ \t]*$""")

        /**
         * The comment `lingui extract` writes above an entry whose msgid is an id the code chose
         * (`i18n._("nav.home")`), not the message itself. Without it, `lingui compile` takes the
         * msgid for a source message and catalogues it under a hash of it: the call finds nothing.
         */
        const val LINGUI_EXPLICIT_ID = "#. js-lingui-explicit-id"

        /** The header `lingui extract` writes: `"X-Generator: @lingui/cli\n"`. */
        const val LINGUI_GENERATOR = "X-Generator: @lingui/"

        val PO_EXTENSIONS = setOf("po", "pot")
    }

    private object PoFileType : FileType {
        override fun getName(): String = "PO"
        override fun getDescription(): String = "GNU GetText PO file"
        override fun getDefaultExtension(): String = "po"
        override fun getIcon(): Icon? = null
        override fun isBinary(): Boolean = false
    }

    override fun generateContent(compositeKey: List<Literal>, value: String): String {
        val key = compositeKey.joinToString(".") { it.text }
        return "msgid \"${key.escapePo()}\"\nmsgstr \"${value.escapePo()}\"\n"
    }

    override fun getType(): FileType {
        val localeFileType = FileTypeManager.getInstance().getStdFileType("Locale")
        return if (localeFileType != PlainTextFileType.INSTANCE) localeFileType else PoFileType
    }

    override fun getLanguage(): Language = PlainTextLanguage.INSTANCE

    override fun getDescription(): String = PluginBundle.getMessage("quickfix.create.plainObject.translation.files")

    override fun isSuitable(element: PsiElement): Boolean {
        val ext = element.containingFile?.virtualFile?.extension?.lowercase()
        return ext == "po" || ext == "pot"
    }

    override fun generateTranslationEntry(item: PsiElement, key: String, value: String) =
        insertEntry(item, key, value, explicitId = false)

    /** Writes [key]'s entry, flagged as a Lingui explicit id when [explicitId]. */
    private fun insertEntry(item: PsiElement, key: String, value: String, explicitId: Boolean) {
        val file = item.containingFile ?: return
        val manager = PsiDocumentManager.getInstance(item.project)
        val document = manager.getDocument(file) ?: return
        val marker = if (explicitId) "$LINGUI_EXPLICIT_ID\n" else ""
        val entry = marker + generateContent(listOf(Literal(key)), value)

        val settings = Settings.getInstance(item.project)
        val anchor = if (settings.extractSorted) {
            sortedAnchor(document.text, key, settings.keyOrder.comparator)
        } else null

        if (anchor != null) {
            // The blank line goes after the new entry, so the one it displaces keeps the blank
            // line that already separated it from what came before.
            document.insertString(anchor, entry + "\n")
        } else {
            val existing = document.text
            val separator = when {
                existing.isEmpty() -> ""
                existing.endsWith("\n\n") -> ""
                existing.endsWith("\n") -> "\n"
                else -> "\n\n"
            }
            document.insertString(document.textLength, separator + entry)
        }
        // Appending always leaves the catalogue ending on a newline; inserting before an entry
        // leaves the last one exactly as it was, which may be mid-line.
        if (!document.text.endsWith("\n")) document.insertString(document.textLength, "\n")
        manager.commitDocument(document)
    }

    /**
     * Offset [key]'s entry must be inserted at to keep the catalogue in order, or null when it
     * sorts after everything already there and belongs at the end.
     *
     * PO is flat: an entry is a `msgid` line plus what follows it, so the whole msgid is compared
     * as one string — the key is never split into segments the way a JSON or YAML path is.
     *
     * The first entry of a catalogue is its header (`msgid ""` in a real one, and the fixtures
     * open on `msgid "Project-Id-Version"`). It carries the metadata, never a translation, so it
     * is neither an anchor nor something to sort against.
     */
    private fun sortedAnchor(text: String, key: String, order: Comparator<String>): Int? {
        val entries = entryOffsets(text)
        // drop(1): the header is not a translation and must not move.
        return entries.drop(1).firstOrNull { (_, msgid) -> order.compare(msgid, key) > 0 }?.first
    }

    /**
     * Each entry of [text] as its start offset and its msgid, in document order.
     *
     * The offset points at the first non-blank character of the entry — its leading comment lines
     * (`#: src/app.php`, `#, fuzzy`) when it has any, otherwise its `msgid`. Inserting there keeps
     * a comment attached to the entry it documents, and lands the new entry at the indentation of
     * the one it displaces rather than at column zero.
     */
    private fun entryOffsets(text: String): List<Pair<Int, String>> {
        val lines = text.split("\n")
        val lineOffsets = IntArray(lines.size)
        var offset = 0
        for ((index, line) in lines.withIndex()) {
            lineOffsets[index] = offset
            offset += line.length + 1
        }

        return lines.withIndex().mapNotNull { (index, line) ->
            val msgid = MSGID_LINE.find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            // Walk back over the comment lines that belong to this entry.
            var start = index
            while (start > 0 && lines[start - 1].trimStart().startsWith("#")) start--
            val startLine = lines[start]
            Pair(lineOffsets[start] + (startLine.length - startLine.trimStart().length), msgid)
        }
    }

    /**
     * In a Lingui catalogue, the entry is flagged as an explicit id the way the same entry is in
     * another locale's catalogue; written in none yet, as the key's origin says ([FullKey.sourceText]).
     *
     * *Sync Keys*, *Fill missing* and the import build their key from its text alone: a `<Trans>`
     * source text read that way looks like an id, and flagging it would leave the macro without
     * its message. The catalogue it is copied from knows which it is.
     */
    override fun generate(element: PsiElement, fullKey: FullKey, unresolved: List<Literal>, translationValue: String?) {
        val key = unresolved.joinToString(".") { it.text }
        val explicitId = isLinguiCatalogue(element) && (flaggedElsewhere(element, key) ?: !fullKey.sourceText)
        insertEntry(element, key, translationValue ?: "", explicitId)
    }

    /**
     * Whether [key]'s entry is flagged as an explicit id in the catalogue of another locale, null
     * when no other catalogue holds it. The other locales are the same file name in the sibling
     * folders (`locales/fr/messages.po` for `locales/en/messages.po`) or the sibling PO files
     * (`locales/fr.po` for `locales/en.po`).
     */
    private fun flaggedElsewhere(element: PsiElement, key: String): Boolean? {
        val file = element.containingFile?.virtualFile ?: return null
        val folder = file.parent ?: return null
        val siblings = folder.children.filter { it.extension?.lowercase() in PO_EXTENSIONS } +
            folder.parent?.children.orEmpty().mapNotNull { it.takeIf { dir -> dir.isDirectory && dir != folder }?.findChild(file.name) }
        val msgid = key.escapePo()
        return siblings.asSequence()
            .filter { it != file }
            .mapNotNull { FileDocumentManager.getInstance().getDocument(it)?.text }
            .firstNotNullOfOrNull { text -> flagOf(text, msgid) }
    }

    /** Whether the entry of [msgid] (escaped) in [text] carries the explicit-id flag, null when [text] has no such entry. */
    private fun flagOf(text: String, msgid: String): Boolean? {
        val lines = text.split("\n")
        val index = lines.indexOfFirst { MSGID_LINE.find(it)?.groupValues?.get(1) == msgid }
        if (index < 0) return null
        return lines.subList(0, index).asReversed().takeWhile { it.trimStart().startsWith("#") }
            .any { it.trim() == LINGUI_EXPLICIT_ID }
    }

    /**
     * Whether [element]'s catalogue is Lingui's: its header names `@lingui/cli`, or an entry already
     * carries the explicit-id flag. A gettext catalogue has no use for the flag.
     */
    private fun isLinguiCatalogue(element: PsiElement): Boolean {
        val text = element.containingFile?.text ?: return false
        return text.contains(LINGUI_GENERATOR) || text.contains(LINGUI_EXPLICIT_ID)
    }

    private fun String.escapePo(): String = replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
