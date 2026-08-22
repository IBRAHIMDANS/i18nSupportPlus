package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlTag

/**
 * Extracts msgid from Lingui <Trans>source text</Trans> — the source text IS the key.
 *
 * Unlike key-based approaches (i18n._('key')), Lingui's source-based JSX component uses
 * the visible text directly as the lookup key in .po files.
 *
 * Simple: <Trans>Hello world!</Trans> → msgid "Hello world!"
 *
 * The extractor targets the XmlTag element (<Trans>…</Trans>) rather than its XmlText
 * children. The JSX parser may split text nodes at word boundaries (e.g. "Hello world!"
 * → ["Hello", " ", "world!"]), so attaching to a single XmlText token produces an
 * element whose text does not contain the full msgid; CompositeKeyAnnotatorBase's
 * indexOf(fullKey.source) then returns -1, causing an invalid TextRange.
 *
 * By targeting the XmlTag, element.text = "<Trans>Hello world!</Trans>" and
 * indexOf("Hello world!") correctly locates the msgid within the element text,
 * so the annotation range stays inside the element bounds.
 *
 * Interpolated <Trans> (e.g. <Trans>Hello {name}!</Trans>) yields "Hello {0}!": Lingui numbers
 * the expressions it extracts, so the msgid in the catalogue carries positional placeholders and
 * not the expression source. Reproducing the literal "{name}" would never match a catalogue.
 */
class LinguiTransKeyExtractor : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean {
        if (element !is XmlTag) return false
        return element.name == "Trans" && buildMsgid(element).isNotEmpty()
    }

    override fun extract(element: PsiElement): RawKey {
        if (element !is XmlTag) return RawKey(emptyList())
        return RawKey(listOf(KeyElement.literal(buildMsgid(element))))
    }

    /**
     * Extracts the msgid from the raw element text by slicing between the first `>`
     * (end of opening tag) and the last `</Trans>`. This avoids relying on
     * XmlTagValue children, which may omit whitespace-only tokens in JSX, causing
     * multi-word text like "Hello world!" to be joined without spaces.
     */
    private fun buildMsgid(tag: XmlTag): String {
        val raw = tag.text
        val contentStart = raw.indexOf('>') + 1
        val contentEnd = raw.lastIndexOf("</${tag.name}>")
        if (contentStart <= 0 || contentEnd < contentStart) return ""
        return numberExpressions(raw.substring(contentStart, contentEnd).trim())
    }

    /**
     * Replaces each JSX expression with its positional placeholder: `Hello {name}!` becomes
     * `Hello {0}!`, which is the msgid Lingui writes into the catalogue.
     *
     * Braces are matched by nesting depth rather than by the first `}`, so an expression holding
     * one — `{ {a: 1}.a }`, `{cond ? "{" : ""}` — is consumed whole and counts as a single
     * placeholder instead of being cut in the middle.
     *
     * Text carrying no `{` comes back untouched, so a plain `<Trans>` keeps the msgid it had.
     * An unbalanced `{` is left as written: the file is mid-edit, and inventing a placeholder
     * there would produce a key that matches nothing.
     */
    private fun numberExpressions(text: String): String {
        if (!text.contains('{')) return text
        val out = StringBuilder()
        var index = 0
        var i = 0
        while (i < text.length) {
            if (text[i] != '{') {
                out.append(text[i]); i++; continue
            }
            var depth = 0
            var j = i
            while (j < text.length) {
                if (text[j] == '{') depth++
                if (text[j] == '}' && --depth == 0) break
                j++
            }
            if (j >= text.length) {   // jamais refermee
                out.append(text, i, text.length); break
            }
            out.append('{').append(index++).append('}')
            i = j + 1
        }
        return out.toString()
    }
}
