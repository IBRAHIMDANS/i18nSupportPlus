package com.ibrahimdans.i18n.extensions.localization.xliff

import com.ibrahimdans.i18n.ContentGenerator
import com.ibrahimdans.i18n.extensions.technology.symfony.SymfonyTranslations
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.ReferenceLocale
import com.intellij.lang.Language
import com.intellij.lang.xml.XMLLanguage
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlTag

/**
 * Writes a key into an XLIFF file: the `<target>` of its unit in a locale's file, the `<source>` of a
 * new unit in the reference file.
 *
 * A locale's file usually holds the unit already, copied from the reference with its `<source>` and no
 * `<target>`: the target is added after the source. A unit the file lacks is created, its `<source>`
 * copied from the reference file next to it when there is one, as `ng extract-i18n` would have written it.
 *
 * A value written for a new key keeps the interpolations of the template it comes from — `Hello {{ user.name }}!`,
 * an Angular text marked `i18n` — as the placeholders `ng extract-i18n` writes for them ([placeholdersXml]).
 */
class XliffContentGenerator : ContentGenerator {

    override fun generateContent(compositeKey: List<Literal>, value: String): String =
        content(compositeKey, value, DEFAULT_SOURCE_LANGUAGE)

    /**
     * The new file declares the reference locale of the module [path] falls in ([ReferenceLocale.wanted])
     * as its `source-language`: it declared `en` whatever the module translates from.
     */
    override fun generateContent(project: Project, path: String, fullKey: FullKey, value: String?): String {
        val config = Settings.getInstance(project).config()
        val module = ModuleSources.owner(config.modules, ModuleSources.FilePath.of(path, project.basePath ?: ""))
        return content(fullKey.compositeKey, value ?: fullKey.source, ReferenceLocale.wanted(module, config))
    }

    private fun content(compositeKey: List<Literal>, value: String, sourceLanguage: String): String {
        val id = escape(idOf(compositeKey))
        return """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2">
            |  <file source-language="${escape(sourceLanguage)}" datatype="plaintext" original="ng2.template">
            |    <body>
            |      <trans-unit id="$id" datatype="html">
            |        <source>${placeholdersXml(value, version2 = false) ?: escape(value)}</source>
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
        val file = element.containingFile ?: return
        // A Symfony catalogue is read flattened: `user.email` may be found as far as `user.` among the keys
        // written whole, and the key is all of the composite key, never only its unresolved end.
        if (isSymfony(file)) return writeSymfony(file, idOf(fullKey.compositeKey), translationValue ?: "")
        // The id is one key: a dotted id resolves as a single segment, and an unresolved one is all of it.
        val id = idOf(unresolved.ifEmpty { fullKey.compositeKey })
        write(file, id, translationValue ?: "", placeholders = true)
    }

    /**
     * Writes [value] as [id]'s text in [file], in the element the file's role reads: as typed, or, with
     * [placeholders], its interpolations as placeholders ([placeholdersXml]).
     */
    fun write(file: PsiFile, id: String, value: String, placeholders: Boolean = false) {
        if (isSymfony(file)) return writeSymfony(file, id, value)
        val role = XliffDocument.roleOf(file)
        val version2 = XliffDocument.rootOf(file)?.let(XliffDocument::isVersion2) == true
        val xml = (if (placeholders) placeholdersXml(value, version2) else null) ?: escape(value)
        val unit = XliffDocument.units(file)[id]
        if (unit != null) {
            setXml(unit, role, xml)
            return
        }
        val container = XliffDocument.unitContainer(file) ?: return
        // The reference's source is copied as written, inline elements (`<x id="INTERPOLATION"/>`) included.
        val sourceXml = if (role == XliffRole.SOURCE) xml else referenceSourceOf(file, id).orEmpty()
        val targetXml = if (role == XliffRole.TARGET) xml else null
        appendUnit(container) { indent -> unitText(escape(id), sourceXml, targetXml, version2, indent) }
    }

    /** Appends the unit [text] writes at the indentation it is given: that of the units already there, one level below the container when it holds none. */
    private fun appendUnit(container: XmlTag, text: (indent: String) -> String) {
        val last = container.subTags.lastOrNull()
        val containerIndent = XliffTranslationPsi.indentOf(container, "")
        val indent = last?.let { XliffTranslationPsi.indentOf(it, "$containerIndent$STEP") } ?: "$containerIndent$STEP"
        if (last != null) XliffTranslationPsi.insertAfter(last, "\n${text(indent)}")
        else XliffTranslationPsi.insertAtStart(container, "\n${text(indent)}\n$containerIndent")
    }

    /**
     * Writes [value] as the `<target>` of the unit Symfony reads as [key]: its `resname` (1.2) or `name` (2.0),
     * the key written in its `<source>` too, as `translation:extract` writes it.
     */
    private fun writeSymfony(file: PsiFile, key: String, value: String) {
        val unit = XliffDocument.namedUnits(file)[key]
        if (unit != null) return setXml(unit, XliffRole.TARGET, escape(value))
        val container = XliffDocument.unitContainer(file) ?: return
        val version2 = XliffDocument.rootOf(file)?.let(XliffDocument::isVersion2) == true
        val nameAttribute = if (version2) "name" else "resname"
        appendUnit(container) { indent -> unitText(escape(key), escape(key), escape(value), version2, indent, nameAttribute) }
    }

    /** Whether [file] is a Symfony catalogue, keyed by `resname` rather than by `id`. */
    private fun isSymfony(file: PsiFile): Boolean =
        file.virtualFile?.let { SymfonyTranslations.isCatalogue(file.project, it) } == true

    /** Sets the content of [unit] that a file of [role] reads to [xml], creating its `<target>` after the `<source>` when missing. */
    private fun setXml(unit: XliffUnit, role: XliffRole, xml: String) {
        unit.valueTag(role)?.let {
            XliffTranslationPsi.setContent(it, xml)
            return
        }
        val source = unit.source ?: return
        val indent = XliffTranslationPsi.indentOf(source, "")
        XliffTranslationPsi.insertAfter(source, "\n$indent<target>$xml</target>")
    }

    /** A new unit as text, its first line at [indent] and each level [STEP] deeper. */
    private fun unitText(
        id: String,
        sourceXml: String,
        targetXml: String?,
        version2: Boolean,
        indent: String,
        nameAttribute: String? = null,
    ): String {
        val texts = listOfNotNull("<source>$sourceXml</source>", targetXml?.let { "<target>$it</target>" })
        val name = nameAttribute?.let { " $it=\"$id\"" }.orEmpty()
        return if (version2) {
            val inner = "$indent$STEP$STEP"
            "$indent<unit id=\"$id\"$name>\n$indent$STEP<segment>\n" + texts.joinToString("") { "$inner$it\n" } +
                "$indent$STEP</segment>\n$indent</unit>"
        } else {
            val datatype = if (nameAttribute == null) " datatype=\"html\"" else ""
            "$indent<trans-unit id=\"$id\"$name$datatype>\n" + texts.joinToString("") { "$indent$STEP$it\n" } + "$indent</trans-unit>"
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

    internal companion object {
        /** One level of indentation, as `ng extract-i18n` writes it. */
        private const val STEP = "  "

        /** The `source-language` of a file generated with no project at hand. */
        private const val DEFAULT_SOURCE_LANGUAGE = "en"

        /** An Angular interpolation with its default delimiters, the expression as written between them. */
        private val INTERPOLATION = Regex("""\{\{(.*?)}}""")

        /** A value read from an XLIFF file, its inline elements already written: never rewritten. */
        private val INLINE_ELEMENT = Regex("""<(x|ph)\b""")

        /**
         * [value] as XLIFF content, each `{{ … }}` written as the placeholder `ng extract-i18n` writes for an
         * interpolation; null when it holds none, or holds inline elements already:
         *  - XLIFF 1.2: `Hello <x id="INTERPOLATION" equiv-text="{{ user.name }}"/>!`;
         *  - XLIFF 2.0: `Hello <ph id="0" equiv="INTERPOLATION" disp="{{ user.name }}"/>!`.
         *
         * Named as Angular's compiler names them: `INTERPOLATION`, then `INTERPOLATION_1`, `INTERPOLATION_2`…
         * one name per expression as written — blanks included — the same expression reusing its name; in
         * 2.0 each placeholder is numbered in order from 0.
         */
        fun placeholdersXml(value: String, version2: Boolean): String? {
            if (!INTERPOLATION.containsMatchIn(value) || INLINE_ELEMENT.containsMatchIn(value)) return null
            val names = linkedMapOf<String, String>()
            var next = 0
            var start = 0
            val xml = StringBuilder()
            for (match in INTERPOLATION.findAll(value)) {
                xml.append(escape(value.substring(start, match.range.first)))
                val name = names.getOrPut(match.groupValues[1]) { if (names.isEmpty()) "INTERPOLATION" else "INTERPOLATION_${names.size}" }
                val display = escape(match.value)
                xml.append(if (version2) "<ph id=\"${next++}\" equiv=\"$name\" disp=\"$display\"/>" else "<x id=\"$name\" equiv-text=\"$display\"/>")
                start = match.range.last + 1
            }
            return xml.append(escape(value.substring(start))).toString()
        }

        private fun escape(text: String): String = StringUtil.escapeXmlEntities(text)
    }
}
