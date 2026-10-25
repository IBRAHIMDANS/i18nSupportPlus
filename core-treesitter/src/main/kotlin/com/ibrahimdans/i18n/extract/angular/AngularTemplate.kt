package com.ibrahimdans.i18n.extract.angular

/**
 * An Angular template as code the TSX grammar reads, the way [com.ibrahimdans.i18n.extract.vuei18n.VueSfc]
 * reads a Vue component: the `{{ … }}` interpolations and the values of `[prop]`, `(event)` and
 * `[(model)]` bindings, each opened by a `;` written over the character before it. Every other character
 * becomes a space, line breaks excepted, so a key keeps the offset and line it has in the `.html` file.
 *
 * A pipe reads as JavaScript's `|`: `'home.title' | transloco` is a binary expression whose right operand
 * names the pipe. Its arguments (`| translate: { name }`) are blanked, being no JavaScript.
 *
 * The markup holding keys outside any expression is rewritten into calls, over the same characters:
 *  - Transloco's `transloco="key"` and `[transloco]="'key'"` → a call of [TRANSLOCO_DIRECTIVE];
 *  - ngx-translate's `translate="key"`, `[translate]="'key'"` and `<p translate>key</p>` → [TRANSLATE_DIRECTIVE];
 *  - an element publishing Transloco's function — `*transloco="let t; read: 'admin'"`, or
 *    `<ng-template transloco let-t [translocoRead]="'admin'">` — opens a block `{` closed where the
 *    element closes, whose first statement is `[TRANSLOCO_READ]('admin', 't')`: the calls of `t` inside
 *    it take that prefix. `prefix:` and `[translocoPrefix]` (Transloco 8) are read the same.
 *
 * Comments and the template's text are left out; the control flow blocks (`@if (…) {`) too.
 */
object AngularTemplate {

    /** The function `transloco="key"` is rewritten into. */
    const val TRANSLOCO_DIRECTIVE = "\$tlk"

    /** The function `translate="key"` is rewritten into. */
    const val TRANSLATE_DIRECTIVE = "\$ngk"

    /** The function the first statement of a Transloco block calls with its prefix and its function's name. */
    const val TRANSLOCO_READ = "\$tlr"

    private val INTERPOLATION = Regex("""\{\{(.*?)}}""", RegexOption.DOT_MATCHES_ALL)
    private val COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val LET = Regex("""\blet\s+([\w$]+)""")
    private val READ = Regex("""\b(?:read|prefix)\s*:\s*(['"])(.*?)\1""")
    private val LITERAL = Regex("""\s*(['"])([^'"]*)\1\s*""")
    private val READ_INPUTS = setOf("[translocoRead]", "[translocoPrefix]")

    /** Elements that hold no content and have no closing tag. */
    private val VOID = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr",
    )

    /** The code of the template [text], as long as [text]. */
    fun codeOf(text: String): String = Writer(text).run()

    private class Attribute(val name: String, val nameStart: Int, val open: Int, val close: Int) {
        val hasValue get() = open >= 0
        val valueRange get() = open + 1 until close
    }

    /** An element left open: its name, and whether it opened a block. */
    private class Open(val name: String, val block: Boolean)

    private class Writer(private val text: String) {
        private val code = CharArray(text.length) { if (text[it] == '\n' || text[it] == '\r') text[it] else ' ' }
        private val comments = COMMENT.findAll(text).map { it.range }.toList()
        private val open = ArrayDeque<Open>()

        fun run(): String {
            for (interpolation in INTERPOLATION.findAll(text)) {
                if (commented(interpolation.range.first) || insideTag(interpolation.range.first)) continue
                code[interpolation.range.first] = ';'
                expression(interpolation.groups[1]!!.range)
            }
            var offset = 0
            while (offset < text.length) {
                val comment = comments.firstOrNull { offset in it }
                offset = when {
                    comment != null -> comment.last + 1
                    text.startsWith("</", offset) -> closeTag(offset)
                    text[offset] == '<' && text.getOrNull(offset + 1)?.isLetter() == true -> openTag(offset)
                    else -> offset + 1
                }
            }
            return String(code)
        }

        private fun commented(offset: Int) = comments.any { offset in it }

        /** Whether [offset] sits between a tag's `<` and its `>`: an attribute's value holding `{{`. */
        private fun insideTag(offset: Int): Boolean {
            val lt = text.lastIndexOf('<', offset)
            return lt >= 0 && text.indexOf('>', lt).let { it < 0 || it > offset }
        }

        private fun keep(range: IntRange) = range.forEach { code[it] = text[it] }

        private fun write(at: Int, value: String) = value.forEachIndexed { index, c -> code[at + index] = c }

        /** Keeps the expression in [range], the arguments of its pipes blanked. */
        private fun expression(range: IntRange) {
            keep(range)
            var i = range.first
            var depth = 0
            var quote: Char? = null
            var blanking = false
            while (i <= range.last) {
                val c = text[i]
                when {
                    quote != null -> if (c == quote) quote = null
                    c == '\'' || c == '"' || c == '`' -> quote = c
                    c in "([{" -> depth++
                    c in ")]}" -> depth--
                    c == '|' && depth == 0 && text.getOrNull(i + 1) != '|' && text.getOrNull(i - 1) != '|' -> {
                        blanking = false
                        // The pipe's name, then its arguments: `| translate: { name }`.
                        var j = i + 1
                        while (j <= range.last && text[j].isWhitespace()) j++
                        while (j <= range.last && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '$')) j++
                        while (j <= range.last && text[j].isWhitespace()) j++
                        if (j <= range.last && text[j] == ':') {
                            blanking = true
                            i = j
                        }
                    }
                }
                if (blanking && text[i] != '\n' && text[i] != '\r') code[i] = ' '
                i++
            }
        }

        /** Writes the tag opened at [lt]; the offset past it. */
        private fun openTag(lt: Int): Int {
            var i = lt + 1
            while (i < text.length && (text[i].isLetterOrDigit() || text[i] in "-_.:")) i++
            val name = text.substring(lt + 1, i).lowercase()
            val attributes = mutableListOf<Attribute>()
            var selfClosing = false
            while (i < text.length) {
                while (i < text.length && text[i].isWhitespace()) i++
                if (i >= text.length) return i
                if (text[i] == '>') break
                if (text[i] == '/') { selfClosing = true; i++; continue }
                val nameStart = i
                while (i < text.length && !text[i].isWhitespace() && text[i] !in "=>/") i++
                val attributeName = text.substring(nameStart, i)
                var j = i
                while (j < text.length && text[j].isWhitespace()) j++
                if (j >= text.length || text[j] != '=') {
                    attributes += Attribute(attributeName, nameStart, -1, -1)
                    continue
                }
                j++
                while (j < text.length && text[j].isWhitespace()) j++
                val quote = text.getOrNull(j)?.takeIf { it == '"' || it == '\'' }
                if (quote == null) { i = j; continue }
                val close = text.indexOf(quote, j + 1).takeIf { it >= 0 } ?: return text.length
                attributes += Attribute(attributeName, nameStart, j, close)
                i = close + 1
            }
            val gt = i
            attributes.forEach(::attribute)
            val block = translocoBlock(attributes)
            when {
                name in VOID -> {}
                selfClosing -> if (block) code[gt - 1] = '}'
                else -> {
                    open.addLast(Open(name, block))
                    attributes.firstOrNull { it.name == TRANSLATE && !it.hasValue }?.let { translateText(it, name, gt) }
                }
            }
            return gt + 1
        }

        /** Pops the elements up to the one `</name>` at [lt] closes, closing their blocks there. */
        private fun closeTag(lt: Int): Int {
            var i = lt + 2
            while (i < text.length && (text[i].isLetterOrDigit() || text[i] in "-_.:")) i++
            val name = text.substring(lt + 2, i).lowercase()
            if (open.any { it.name == name }) {
                while (true) {
                    val element = open.removeLast()
                    if (element.block && code[lt] == ' ') code[lt] = '}'
                    if (element.name == name) break
                }
            }
            val gt = text.indexOf('>', i)
            return if (gt < 0) text.length else gt + 1
        }

        /** Writes [attribute] into the code when it holds code or a key. */
        private fun attribute(attribute: Attribute) {
            if (!attribute.hasValue) return
            val name = attribute.name
            when {
                name == "[$TRANSLOCO]" -> boundDirective(attribute, TRANSLOCO_DIRECTIVE)
                name == "[$TRANSLATE]" -> boundDirective(attribute, TRANSLATE_DIRECTIVE)
                name == TRANSLOCO -> staticDirective(attribute, TRANSLOCO_DIRECTIVE)
                name == TRANSLATE -> staticDirective(attribute, TRANSLATE_DIRECTIVE)
                name in READ_INPUTS -> {}
                name.startsWith("[") || name.startsWith("(") || name.startsWith("bind-") || name.startsWith("on-") -> {
                    code[attribute.open] = ';'
                    expression(attribute.valueRange)
                }
            }
        }

        /** `[transloco]="'key'"`: the call opens over the name, the closing quote closes it. */
        private fun boundDirective(attribute: Attribute, function: String) {
            write(attribute.nameStart, ";$function(")
            expression(attribute.valueRange)
            code[attribute.close] = ')'
        }

        /** `transloco="key"`: the value is the key itself, kept quoted, the call closed just past the quote. */
        private fun staticDirective(attribute: Attribute, function: String) {
            if (text.getOrNull(attribute.close + 1)?.let { it.isWhitespace() || it == '>' || it == '/' } != true) return
            write(attribute.nameStart, ";$function(")
            keep(attribute.open..attribute.close)
            code[attribute.close + 1] = ')'
        }

        /**
         * `<p translate>key</p>`: the element's text as the key, when it is all the element holds on one
         * line and nothing but whitespace follows the attribute in the tag — the call opens over the
         * attribute, the tag's `>` and the closing tag's `<` quote the text, its `/` closes the call.
         */
        private fun translateText(attribute: Attribute, name: String, gt: Int) {
            val end = attribute.nameStart + TRANSLATE.length
            if (text.substring(end, gt).isNotBlank()) return
            val closing = text.indexOf("</$name", gt + 1, ignoreCase = true).takeIf { it > gt } ?: return
            val content = text.substring(gt + 1, closing)
            if (content.isBlank() || content.any { it in "<>{}\"\n\r" }) return
            write(attribute.nameStart, ";$TRANSLATE_DIRECTIVE(")
            code[gt] = '"'
            keep(gt + 1 until closing)
            code[closing] = '"'
            code[closing + 1] = ')'
        }

        /**
         * Opens a block when the element publishes Transloco's function — `*transloco="let t; read: 'x'"`,
         * or `transloco` with `let-t` — writing its first statement over an attribute long enough to hold it.
         * Returns whether a block was opened.
         */
        private fun translocoBlock(attributes: List<Attribute>): Boolean {
            val structural = attributes.firstOrNull { it.name == "*$TRANSLOCO" && it.hasValue }
            val function: String
            val read: String
            val host: Attribute
            if (structural != null) {
                val value = text.substring(structural.valueRange.first, structural.close)
                function = LET.find(value)?.groupValues?.get(1) ?: return false
                read = READ.find(value)?.groupValues?.get(2).orEmpty()
                host = structural
            } else {
                if (attributes.none { it.name == TRANSLOCO && !it.hasValue }) return false
                function = attributes.firstOrNull { it.name.startsWith("let-") }?.name?.removePrefix("let-") ?: return false
                val input = attributes.firstOrNull { it.name in READ_INPUTS && it.hasValue }
                read = input?.let { LITERAL.matchEntire(text.substring(it.valueRange.first, it.close))?.groupValues?.get(2) }.orEmpty()
                host = input ?: attributes.first { it.name == TRANSLOCO }
            }
            // Opened by `;`: the expression before it must not run on into the block.
            val statement = ";{$TRANSLOCO_READ('$read','$function');"
            val room = (if (host.hasValue) host.close + 1 else host.nameStart + host.name.length) - host.nameStart
            // Too long for its attribute: the block still opens, without a prefix of its own.
            write(host.nameStart, if (statement.length <= room && '\'' !in read) statement else ";{")
            return true
        }
    }

    private const val TRANSLOCO = "transloco"
    private const val TRANSLATE = "translate"
}
