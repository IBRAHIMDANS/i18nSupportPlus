package com.ibrahimdans.i18n.plugin.utils

import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pure XLIFF 1.2 codec for translation export/import — the format CAT tools (Trados, memoQ,
 * Phrase, Crowdin's manual import) read, where [CsvTranslationCodec] is a format of our own.
 *
 * One document per reference → target pair: a `<file>` whose `<trans-unit id="key">` holds the
 * reference value as `<source>` and the target value as `<target>`, marked
 * `state="needs-translation"` when empty. A plural form is a key of its own, so it is a unit of its
 * own. Values are text: `<b>` in a value is escaped, never written as XML markup.
 *
 * Parsing yields the same records as [CsvTranslationCodec.parse] — a `key` header, one column per
 * target language — so the import plan ([CsvTranslationCodec.computeImportPlan]) is shared. It
 * reads what another tool wrote back: unknown attributes and elements are ignored, and the text of
 * inline markup inside a `<target>` is kept. External entities and DTDs are refused.
 *
 * No IntelliJ dependency on purpose — everything here is unit-testable.
 */
object XliffTranslationCodec {

    /** The extensions an XLIFF file is recognised by. */
    val EXTENSIONS = setOf("xlf", "xliff")

    private const val NEEDS_TRANSLATION = "needs-translation"

    // ── Encoding ──────────────────────────────────────────────────────────────

    /**
     * Encodes the keys of [translations] (key → locale → value) that have a value in
     * [sourceLocale], with their value in [targetLocale]. Units are sorted by key for stable diffs.
     */
    fun encode(sourceLocale: String, targetLocale: String, translations: Map<String, Map<String, String>>): String =
        buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
            append("""<xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2">""").append('\n')
            append("""  <file original="translations" datatype="plaintext" """)
            append("""source-language="${escape(sourceLocale)}" target-language="${escape(targetLocale)}">""").append('\n')
            append("    <body>\n")
            for (key in translations.keys.sorted()) {
                val source = translations[key]?.get(sourceLocale)?.takeIf { it.isNotEmpty() } ?: continue
                val target = translations[key]?.get(targetLocale).orEmpty()
                append("""      <trans-unit id="${escape(key)}">""").append('\n')
                append("        <source>").append(escape(source)).append("</source>\n")
                if (target.isEmpty()) append("""        <target state="$NEEDS_TRANSLATION"></target>""").append('\n')
                else append("        <target>").append(escape(target)).append("</target>\n")
                append("      </trans-unit>\n")
            }
            append("    </body>\n")
            append("  </file>\n")
            append("</xliff>\n")
        }

    private fun escape(text: String): String = buildString(text.length) {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    /**
     * Parses XLIFF [text] into CSV-shaped records: `key` then one column per target language,
     * then one row per unit id. A unit without a `<target>`, or an empty one, leaves its cell
     * empty — "no change" for the import plan.
     *
     * @throws IllegalArgumentException when the text is not well-formed XML, or holds no unit.
     */
    fun parse(text: String): List<List<String>> {
        val document = try {
            builderFactory().newDocumentBuilder()
                .apply { setErrorHandler(SilentErrorHandler) }
                .parse(InputSource(StringReader(text)))
        } catch (e: SAXException) {
            throw IllegalArgumentException(PluginBundle.message("xliff.error.malformed", e.message ?: ""), e)
        }
        val values = linkedMapOf<String, MutableMap<String, String>>()
        val locales = linkedSetOf<String>()
        val files = document.getElementsByTagNameNS("*", "file")
        for (i in 0 until files.length) {
            val file = files.item(i) as Element
            val locale = file.getAttribute("target-language").takeIf { it.isNotBlank() } ?: continue
            locales += locale
            val units = file.getElementsByTagNameNS("*", "trans-unit")
            for (j in 0 until units.length) {
                val unit = units.item(j) as Element
                val key = unit.getAttribute("id").takeIf { it.isNotBlank() } ?: continue
                val target = firstChild(unit, "target")?.textContent.orEmpty()
                values.getOrPut(key) { linkedMapOf() }[locale] = target
            }
        }
        require(values.isNotEmpty()) { PluginBundle.message("xliff.error.no.unit") }
        val header = listOf(CsvTranslationCodec.KEY_COLUMN) + locales
        return listOf(header) + values.map { (key, byLocale) -> listOf(key) + locales.map { byLocale[it].orEmpty() } }
    }

    private fun firstChild(parent: Element, localName: String): Element? {
        var node = parent.firstChild
        while (node != null) {
            if (node is Element && (node.localName ?: node.nodeName) == localName) return node
            node = node.nextSibling
        }
        return null
    }

    /** Turns every parse problem into the exception [parse] reports, instead of printing it. */
    private object SilentErrorHandler : ErrorHandler {
        override fun warning(exception: SAXParseException) = Unit
        override fun error(exception: SAXParseException): Unit = throw exception
        override fun fatalError(exception: SAXParseException): Unit = throw exception
    }

    /** A namespace-aware parser that refuses DTDs and external entities (XXE). */
    private fun builderFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }
}
