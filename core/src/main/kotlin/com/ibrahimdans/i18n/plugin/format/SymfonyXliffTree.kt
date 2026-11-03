package com.ibrahimdans.i18n.plugin.format

import com.ibrahimdans.i18n.plugin.utils.TextSpan
import java.io.StringReader
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/**
 * A Symfony XLIFF catalogue (`validators.fr.xlf`, XLIFF 1.2 or 2.0) read outside the IDE, keyed as
 * Symfony keys it and as the plugin's `XliffTree` reads it in `XliffNaming.SYMFONY`: one leaf per
 * unit, named by its `resname` (`name` in 2.0), else by the text of its `<source>`; its value is its
 * `<target>`, else its `<source>`, Symfony's fallback. A unit holding neither is no key; the first of
 * two units under one key wins. In 2.0 a unit's texts are those of its first `<segment>`.
 *
 * The text of inline markup (`<x/>`, `<g>`) inside a `<source>` or `<target>` is kept. DTDs and
 * external entities are refused.
 *
 * Read with an Angular [XliffKeying], a unit is keyed by its `id` instead, as `ng extract-i18n` names it
 * (`@@home.title` gives `home.title`) and as the plugin's `XliffTree` reads it in `XliffNaming.ID`.
 */
class SymfonyXliffTree private constructor(node: ParsedNode, children: List<SymfonyXliffTree>?) : ParsedNodeTree(node, children) {

    companion object {
        private val factory: XMLInputFactory = XMLInputFactory.newFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
        }

        private const val ROOT = "xliff"
        private const val TRANS_UNIT = "trans-unit"
        private const val UNIT = "unit"
        private const val SEGMENT = "segment"
        private const val SOURCE = "source"
        private const val TARGET = "target"

        /** The tree of [text], its units keyed by [keying]; null when it is not well-formed XML or its root is no `<xliff>`. */
        fun parse(text: String, keying: XliffKeying = XliffKeying.SYMFONY): SymfonyXliffTree? =
            try {
                Reader(text, factory.createXMLStreamReader(StringReader(text)), keying).read()
            } catch (e: XMLStreamException) {
                null
            }
    }

    /** One unit being read: its name attribute, and the text and span of its `<source>` and `<target>`. */
    private class Unit(val name: String?, val id: String?, val start: Int) {
        var source: String? = null
        var target: String? = null
        var targetSpan: TextSpan? = null
        var sourceSpan: TextSpan? = null
    }

    /**
     * Walks the document once. StAX reports where an event ends by line and column; [lineStarts] turns
     * them into offsets, which locate a unit and its text closely enough: `i18n-check` keeps no line.
     */
    private class Reader(private val text: String, private val xml: XMLStreamReader, private val keying: XliffKeying) {
        private val lineStarts: IntArray = buildList {
            add(0)
            text.forEachIndexed { index, c -> if (c == '\n') add(index + 1) }
        }.toIntArray()

        fun read(): SymfonyXliffTree? {
            val units = linkedMapOf<String, SymfonyXliffTree>()
            var unit: Unit? = null
            var segments = 0
            var textStart = -1
            val buffer = StringBuilder()
            var inText: String? = null
            var rootSeen = false
            while (xml.hasNext()) {
                when (xml.next()) {
                    XMLStreamConstants.START_ELEMENT -> if (!rootSeen) {
                        if (xml.localName == ROOT) rootSeen = true else return null
                    } else when (val name = xml.localName) {
                        TRANS_UNIT, UNIT -> {
                            unit = Unit(
                                xml.getAttributeValue(null, if (name == UNIT) "name" else "resname"),
                                xml.getAttributeValue(null, "id"),
                                offset(),
                            )
                            segments = 0
                        }
                        SEGMENT -> segments++
                        SOURCE, TARGET -> if (unit != null && inText == null && segments <= 1) {
                            inText = name
                            textStart = offset()
                            buffer.setLength(0)
                        }
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE ->
                        if (inText != null) buffer.append(xml.text)
                    XMLStreamConstants.END_ELEMENT -> when (xml.localName) {
                        inText -> {
                            val span = TextSpan(textStart, offset())
                            if (inText == SOURCE && unit?.source == null) unit?.apply { source = buffer.toString(); sourceSpan = span }
                            if (inText == TARGET && unit?.target == null) unit?.apply { target = buffer.toString(); targetSpan = span }
                            inText = null
                        }
                        TRANS_UNIT, UNIT -> {
                            unit?.let { leafOf(it) }?.let { units.putIfAbsent(it.value().key!!, it) }
                            unit = null
                        }
                    }
                }
            }
            if (!rootSeen) return null
            return SymfonyXliffTree(ParsedNode(null, null, null, TextSpan(0, text.length)), units.values.toList())
        }

        private fun leafOf(unit: Unit): SymfonyXliffTree? {
            val key = when (keying) {
                XliffKeying.SYMFONY -> unit.name?.trim()?.takeIf { it.isNotEmpty() } ?: unit.source?.trim()?.takeIf { it.isNotEmpty() }
                else -> unit.id?.trim()?.takeIf { it.isNotEmpty() }
            } ?: return null
            val fromTarget = when (keying) {
                XliffKeying.SYMFONY -> unit.target != null
                XliffKeying.ANGULAR_SOURCE -> false
                XliffKeying.ANGULAR_TARGET -> true
            }
            val value = (if (fromTarget) unit.target else unit.source) ?: return null
            val span = (if (fromTarget) unit.targetSpan else unit.sourceSpan) ?: return null
            return SymfonyXliffTree(ParsedNode(key, value, TextSpan(unit.start, unit.start), span), null)
        }

        /** Where the current event ends, as an offset in [text]. */
        private fun offset(): Int {
            val location = xml.location
            val line = (location.lineNumber - 1).coerceIn(0, lineStarts.size - 1)
            return (lineStarts[line] + location.columnNumber - 1).coerceIn(0, text.length)
        }
    }
}

/** How [SymfonyXliffTree] keys and values the units of a catalogue. */
enum class XliffKeying {
    /** By `resname` (`name` in 2.0), else the source text; valued by the `<target>`, else the `<source>`. */
    SYMFONY,

    /** Angular's source-locale file, `messages.xlf`: by `id`, valued by the `<source>`. */
    ANGULAR_SOURCE,

    /** An Angular locale's file, `messages.fr.xlf`: by `id`, valued by the `<target>`; a unit with none is a missing translation. */
    ANGULAR_TARGET,
}
