package com.ibrahimdans.i18n.extensions.localization.xliff

import com.ibrahimdans.i18n.ContentGenerator
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.lang.Language
import com.intellij.lang.xml.XMLLanguage
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

/**
 * Writes a key into an XLIFF file: the `<target>` of its unit in a locale's file, the `<source>` of a
 * new unit in the reference file.
 *
 * A locale's file usually holds the unit already, copied from the reference with its `<source>` and no
 * `<target>`: the target is added after the source. A unit the file lacks is created, its `<source>`
 * copied from the reference file next to it when there is one, as `ng extract-i18n` would have written it.
 */
class XliffContentGenerator : ContentGenerator {

    override fun generateContent(compositeKey: List<Literal>, value: String): String {
        val id = escape(idOf(compositeKey))
        return """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2">
            |  <file source-language="en" datatype="plaintext" original="ng2.template">
            |    <body>
            |      <trans-unit id="$id" datatype="html">
            |        <source>${escape(value)}</source>
            |      </trans-unit>
            |    </body>
            |  </file>
            |</xliff>
            |""".trimMargin()
    }

    override fun getType(): FileType = XliffFileType.INSTANCE

    override fun getLanguage(): Language = XMLLanguage.INSTANCE

    override fun getDescription(): String = PluginBundle.getMessage("quickfix.create.xliff.translation.files")

    override fun isSuitable(element: PsiElement): Boolean =
        element.containingFile?.virtualFile?.extension?.lowercase() in XliffFileType.EXTENSIONS

    override fun generateTranslationEntry(item: PsiElement, key: String, value: String) {
        write(item.containingFile ?: return, key, value)
    }

    override fun generate(element: PsiElement, fullKey: FullKey, unresolved: List<Literal>, translationValue: String?) {
        // The id is one key: a dotted id resolves as a single segment, and an unresolved one is all of it.
        val id = idOf(unresolved.ifEmpty { fullKey.compositeKey })
        write(element.containingFile ?: return, id, translationValue ?: "")
    }

    /** Writes [value] as [id]'s text in [file], in the element the file's role reads. */
    fun write(file: PsiFile, id: String, value: String) {
        val role = XliffDocument.roleOf(file)
        val unit = XliffDocument.units(file)[id]
        if (unit != null) {
            setText(unit, role, value)
            return
        }
        val container = XliffDocument.unitContainer(file) ?: return
        val version2 = XliffDocument.rootOf(file)?.let(XliffDocument::isVersion2) == true
        // The reference's source is copied as written, inline elements (`<x id="INTERPOLATION"/>`) included.
        val sourceXml = if (role == XliffRole.SOURCE) escape(value) else referenceSourceOf(file, id).orEmpty()
        val targetXml = if (role == XliffRole.TARGET) escape(value) else null
        // Laid out like the units already there, one level below the container when it holds none.
        val last = container.subTags.lastOrNull()
        val containerIndent = XliffTranslationPsi.indentOf(container, "")
        val indent = last?.let { XliffTranslationPsi.indentOf(it, "$containerIndent$STEP") } ?: "$containerIndent$STEP"
        val text = unitText(escape(id), sourceXml, targetXml, version2, indent)
        if (last != null) XliffTranslationPsi.insertAfter(last, "\n$text")
        else XliffTranslationPsi.insertAtStart(container, "\n$text\n$containerIndent")
    }

    /** Sets the text of [unit] that a file of [role] reads, creating its `<target>` after the `<source>` when missing. */
    private fun setText(unit: XliffUnit, role: XliffRole, value: String) {
        unit.valueTag(role)?.let {
            XliffTranslationPsi.setContent(it, escape(value))
            return
        }
        val source = unit.source ?: return
        val indent = XliffTranslationPsi.indentOf(source, "")
        XliffTranslationPsi.insertAfter(source, "\n$indent<target>${escape(value)}</target>")
    }

    /** A new unit as text, its first line at [indent] and each level [STEP] deeper. */
    private fun unitText(id: String, sourceXml: String, targetXml: String?, version2: Boolean, indent: String): String {
        val texts = listOfNotNull("<source>$sourceXml</source>", targetXml?.let { "<target>$it</target>" })
        return if (version2) {
            val inner = "$indent$STEP$STEP"
            "$indent<unit id=\"$id\">\n$indent$STEP<segment>\n" + texts.joinToString("") { "$inner$it\n" } +
                "$indent$STEP</segment>\n$indent</unit>"
        } else {
            "$indent<trans-unit id=\"$id\" datatype=\"html\">\n" + texts.joinToString("") { "$indent$STEP$it\n" } + "$indent</trans-unit>"
        }
    }

    /**
     * The `<source>` of [id] in the reference file beside [file] — `messages.xlf` next to `messages.fr.xlf` —
     * as XML text, inline elements included; null when no file of the folder is a reference holding it.
     */
    private fun referenceSourceOf(file: PsiFile, id: String): String? {
        val folder = file.virtualFile?.parent ?: return null
        val manager = PsiManager.getInstance(file.project)
        return folder.children.asSequence()
            .filter { it != file.virtualFile && it.extension?.lowercase() in XliffFileType.EXTENSIONS }
            .mapNotNull { manager.findFile(it) }
            .filter { XliffDocument.roleOf(it) == XliffRole.SOURCE }
            .firstNotNullOfOrNull { reference -> XliffDocument.units(reference)[id]?.source?.value?.text }
    }

    private fun idOf(literals: List<Literal>): String = literals.joinToString(".") { it.text }

    private fun escape(text: String): String = StringUtil.escapeXmlEntities(text)

    private companion object {
        /** One level of indentation, as `ng extract-i18n` writes it. */
        const val STEP = "  "
    }
}
