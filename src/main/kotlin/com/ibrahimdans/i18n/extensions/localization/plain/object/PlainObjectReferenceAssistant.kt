package com.ibrahimdans.i18n.extensions.localization.plain.`object`

import com.ibrahimdans.i18n.*
import com.ibrahimdans.i18n.plugin.ide.references.translation.TranslationToCodeReferenceProvider
import com.ibrahimdans.i18n.plugin.utils.isQuoted
import com.ibrahimdans.i18n.plugin.utils.type
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PatternCondition
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.util.ProcessingContext

/** `msgid` line of a PO/POT section, as named by org.jetbrains.plugins.localization. */
private const val ID_LINE = "ID_LINE"

/** Quoted string carried by an [ID_LINE] — the msgid itself. */
private const val STRING_LITERAL_EXPRESSION = "STRING_LITERAL_EXPRESSION"

/**
 * Navigates from a msgid to the code that calls it.
 *
 * Element types are matched by name rather than by class: org.jetbrains.plugins.localization is an
 * optional dependency, and [PlainObjectTextTree] already reads the same tree that way.
 *
 * A msgid *is* the key — GetText has no nesting — so the whole string is handed over as a single
 * path component. A msgid spread over several literals (`msgid ""` followed by continuation lines)
 * is not joined: only the literal under the caret is read.
 */
class PlainObjectReferenceAssistant : TranslationReferenceAssistant<PsiElement> {

    private val provider = TranslationToCodeReferenceProvider()

    override fun pattern(): ElementPattern<out PsiElement> =
        PlatformPatterns.psiElement().with(object : PatternCondition<PsiElement>("poMsgidLiteral") {
            override fun accepts(element: PsiElement, context: ProcessingContext?): Boolean =
                element.type() == STRING_LITERAL_EXPRESSION && element.parent?.type() == ID_LINE
        })

    override fun references(element: PsiElement): List<PsiReference> {
        val msgid = element.text.unQuote()
        // An empty msgid is the PO header, not a key.
        if (msgid.isBlank()) return emptyList()
        return provider.getReferences(element, textRange(element), listOf(msgid))
    }

    private fun textRange(element: PsiElement): TextRange =
        if (element.text.isQuoted()) TextRange(1, element.textLength - 1)
        else TextRange(0, element.textLength)
}
