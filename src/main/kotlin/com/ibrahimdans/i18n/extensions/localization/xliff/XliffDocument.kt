package com.ibrahimdans.i18n.extensions.localization.xliff

import com.ibrahimdans.i18n.plugin.utils.LocaleNames
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText

/**
 * Which text of a unit a file holds as its translations: the `<source>` of the reference file
 * Angular's `ng extract-i18n` writes (`messages.xlf`), the `<target>` of a locale's (`messages.fr.xlf`).
 */
enum class XliffRole { SOURCE, TARGET }

/**
 * One translation unit of an XLIFF file: `<trans-unit id>` in XLIFF 1.2, `<unit id>` in 2.0, at any
 * depth of `<group>`s. [source] and [target] are those of its first segment in 2.0.
 */
class XliffUnit(val id: String, val tag: XmlTag, val idValue: XmlAttributeValue?, val source: XmlTag?, val target: XmlTag?) {

    /** The tag holding this unit's text in a file of [role]; null when the unit has none, a translation still missing. */
    fun valueTag(role: XliffRole): XmlTag? = if (role == XliffRole.SOURCE) source else target
}

/**
 * Reading an XLIFF file through the XML PSI, as the IDE parses it: the trees need PSI elements to
 * navigate to and edit, which the export's DOM codec (`XliffTranslationCodec`) does not give.
 * Both read the same elements: a unit's `id`, its `<source>`, its `<target>`.
 */
object XliffDocument {

    private const val TRANS_UNIT = "trans-unit"
    private const val UNIT = "unit"
    private const val GROUP = "group"
    private const val FILE = "file"
    private const val BODY = "body"
    private const val SEGMENT = "segment"
    const val SOURCE = "source"
    const val TARGET = "target"

    /** The `<xliff>` root of [file], or null when it is no XLIFF document. */
    fun rootOf(file: PsiFile?): XmlTag? = (file as? XmlFile)?.rootTag?.takeIf { it.localName == "xliff" }

    /** Whether [root] is XLIFF 2: its units are `<unit>`s and its languages are attributes of the root. */
    fun isVersion2(root: XmlTag): Boolean = root.getAttributeValue("version")?.startsWith("2") == true

    /** The language a file declares for its translations: 1.2's `target-language`, 2.0's `trgLang`; null when none. */
    fun targetLanguage(file: PsiFile?): String? {
        val root = rootOf(file) ?: return null
        return if (isVersion2(root)) root.getAttributeValue("trgLang")?.takeIf { it.isNotBlank() }
        else childrenOf(root, FILE).firstNotNullOfOrNull { it.getAttributeValue("target-language")?.takeIf { lang -> lang.isNotBlank() } }
    }

    /** The language a file declares for its source text: 1.2's `source-language`, 2.0's `srcLang`; null when none. */
    fun sourceLanguage(file: PsiFile?): String? {
        val root = rootOf(file) ?: return null
        return if (isVersion2(root)) root.getAttributeValue("srcLang")?.takeIf { it.isNotBlank() }
        else childrenOf(root, FILE).firstNotNullOfOrNull { it.getAttributeValue("source-language")?.takeIf { lang -> lang.isNotBlank() } }
    }

    /**
     * The role of [file] read from the file itself, for a file no configuration describes: a file
     * declaring a target language, holding a `<target>`, or named after a locale as Angular names them
     * (`messages.fr.xlf`) is a locale's; any other the reference. The name counts for a fresh copy of the
     * reference, not translated yet.
     */
    fun roleOf(file: PsiFile?): XliffRole =
        if (targetLanguage(file) != null || localeSuffixOf(file?.name) != null || units(file).values.any { it.target != null }) XliffRole.TARGET
        else XliffRole.SOURCE

    /** The locale ending [fileName] before its extension, as `messages.fr.xlf` names `fr`; null when there is none. */
    fun localeSuffixOf(fileName: String?): String? {
        val stem = fileName?.substringBeforeLast('.') ?: return null
        if ('.' !in stem) return null
        return stem.substringAfterLast('.').takeIf(LocaleNames::looksLikeLocale)
    }

    /**
     * The units of [file] by id, in document order, the first winning on a duplicate id; read once per
     * version of the file, as completion asks for every id it offers.
     */
    fun units(file: PsiFile?): Map<String, XliffUnit> {
        if (rootOf(file) == null) return emptyMap()
        return CachedValuesManager.getCachedValue(file!!) {
            CachedValueProvider.Result.create(collectUnits(rootOf(file)), file)
        }
    }

    /**
     * The units of [file] by the key Symfony reads them under: 1.2's `resname`, 2.0's `name`, else the
     * text of their `<source>` — `translation:extract` writes `<trans-unit id="hash" resname="key">`.
     * Each unit's id is that key, and its id value the attribute naming it, when there is one.
     */
    fun namedUnits(file: PsiFile?): Map<String, XliffUnit> {
        if (rootOf(file) == null) return emptyMap()
        return CachedValuesManager.getCachedValue(file!!) {
            CachedValueProvider.Result.create(collectUnits(rootOf(file), ::namedUnitOf), file)
        }
    }

    private fun collectUnits(root: XmlTag?, read: (XmlTag) -> XliffUnit? = ::unitOf): Map<String, XliffUnit> {
        val units = linkedMapOf<String, XliffUnit>()
        fun visit(tag: XmlTag) {
            for (child in tag.subTags) {
                when (child.localName) {
                    TRANS_UNIT, UNIT -> read(child)?.let { units.putIfAbsent(it.id, it) }
                    FILE, BODY, GROUP -> visit(child)
                }
            }
        }
        root?.let(::visit)
        return units
    }

    private fun unitOf(tag: XmlTag): XliffUnit? {
        val attribute = tag.getAttribute("id") ?: return null
        val id = attribute.value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val (source, target) = textsOf(tag)
        return XliffUnit(id, tag, attribute.valueElement, source, target)
    }

    private fun namedUnitOf(tag: XmlTag): XliffUnit? {
        val (source, target) = textsOf(tag)
        val attribute = tag.getAttribute(if (tag.localName == UNIT) "name" else "resname")
        val name = attribute?.value?.trim()?.takeIf { it.isNotEmpty() }
        val key = name ?: source?.value?.trimmedText?.takeIf { it.isNotEmpty() } ?: return null
        return XliffUnit(key, tag, attribute?.valueElement?.takeIf { name != null }, source, target)
    }

    /** The `<source>` and `<target>` of a unit: its own in 1.2, those of its first `<segment>` in 2.0. */
    private fun textsOf(tag: XmlTag): Pair<XmlTag?, XmlTag?> {
        val holder = if (tag.localName == UNIT) tag.subTags.firstOrNull { it.localName == SEGMENT } ?: tag else tag
        return holder.subTags.firstOrNull { it.localName == SOURCE } to holder.subTags.firstOrNull { it.localName == TARGET }
    }

    /** The unit [element] lies in, or null outside one. */
    fun unitTagOf(element: PsiElement): XmlTag? =
        generateSequence(element) { it.parent }.filterIsInstance<XmlTag>().firstOrNull { it.localName == TRANS_UNIT || it.localName == UNIT }

    /** Whether [tag] is a translation unit, which [unitOf] reads. */
    fun isUnit(tag: XmlTag): Boolean = tag.localName == TRANS_UNIT || tag.localName == UNIT

    /**
     * The element standing for the text of [tag] in the trees: its only text node when it holds plain
     * text, so that the hints and folding show the text rather than the markup around it; the tag itself
     * when it is empty or holds inline elements (`<x id="INTERPOLATION"/>`).
     */
    fun valueElementOf(tag: XmlTag): PsiElement {
        val content = tag.value.children
        return (content.singleOrNull() as? XmlText) ?: tag
    }

    /** The `<xliff>`, `<file>`, `<body>` (1.2) or `<file>` (2.0) a new unit goes in, or null when the file has none. */
    fun unitContainer(file: PsiFile?): XmlTag? {
        val root = rootOf(file) ?: return null
        val fileTag = childrenOf(root, FILE).firstOrNull() ?: return null
        return if (isVersion2(root)) fileTag else childrenOf(fileTag, BODY).firstOrNull() ?: fileTag
    }

    /**
     * The child tags of [tag] named [localName], whatever their namespace: an XLIFF document puts its
     * elements in the XLIFF namespace by default, which a lookup by qualified name would have to spell.
     */
    private fun childrenOf(tag: XmlTag, localName: String): List<XmlTag> = tag.subTags.filter { it.localName == localName }
}
