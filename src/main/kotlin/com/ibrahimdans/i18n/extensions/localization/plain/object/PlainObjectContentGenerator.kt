package com.ibrahimdans.i18n.extensions.localization.plain.`object`

import com.ibrahimdans.i18n.ContentGenerator
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import javax.swing.Icon

// PO is a flat list of msgid/msgstr entries with no nested blocks. The GNU GetText PSI plugin
// (org.jetbrains.plugins.localization) is unavailable on IntelliJ 243.x+, so we fall back to
// document-level text insertion.
class PlainObjectContentGenerator : ContentGenerator {

    private companion object {
        /** A `msgid` opening an entry. Leading blanks are tolerated: fixtures carry some. */
        val MSGID_LINE = Regex("""^[ \t]*msgid[ \t]+"(.*)"[ \t]*$""")
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

    override fun generateTranslationEntry(item: PsiElement, key: String, value: String) {
        val file = item.containingFile ?: return
        val manager = PsiDocumentManager.getInstance(item.project)
        val document = manager.getDocument(file) ?: return
        val entry = generateContent(listOf(Literal(key)), value)

        val anchor = if (Settings.getInstance(item.project).extractSorted) {
            sortedAnchor(document.text, key)
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
    private fun sortedAnchor(text: String, key: String): Int? {
        val entries = entryOffsets(text)
        // drop(1): the header is not a translation and must not move.
        return entries.drop(1).firstOrNull { (_, msgid) -> msgid > key }?.first
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

    override fun generate(element: PsiElement, fullKey: FullKey, unresolved: List<Literal>, translationValue: String?) {
        val key = unresolved.joinToString(".") { it.text }
        generateTranslationEntry(element, key, translationValue ?: "")
    }

    private fun String.escapePo(): String = replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
