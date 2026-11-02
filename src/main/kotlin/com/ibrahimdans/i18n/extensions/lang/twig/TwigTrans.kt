package com.ibrahimdans.i18n.extensions.lang.twig

import com.ibrahimdans.i18n.extensions.technology.symfony.SymfonyCatalogue
import com.ibrahimdans.i18n.extensions.technology.symfony.SymfonyProject
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.jetbrains.twig.TwigTokenTypes
import com.jetbrains.twig.elements.TwigElementTypes

/** A key a Twig template writes, with the domain it is looked up in. */
internal data class TwigKey(val text: String, val domain: String)

/**
 * Symfony's translations in a Twig template, read from the Twig PSI:
 *  - the filter: `{{ 'key'|trans }}`, `'key'|trans({}, 'admin')`, `'key'|trans(domain='admin')`;
 *  - the tag: `{% trans %}key{% endtrans %}`, `{% trans from 'admin' %}key{% endtrans %}`.
 *
 * A key naming no domain falls back to the template's `{% trans_default_domain 'admin' %}`, then to
 * `messages`. A domain only known at runtime (`trans({}, domain)`) makes no key: which catalogue holds
 * it is unknown. Read in a Symfony project only ([SymfonyProject]): Drupal's `|trans` holds a text.
 *
 * The Twig parser leaves a quoted string bare in an expression — quote, `STRING_TEXT`, quote — so the
 * key is that `STRING_TEXT` token; a tag's body is a `TEMPLATE_HTML_TEXT` token of the Twig tree.
 */
internal object TwigTrans {

    private const val TRANS = "trans"
    private const val END_TRANS = "endtrans"
    private const val DEFAULT_DOMAIN_TAG = "trans_default_domain"
    private const val FROM = "from"
    private const val DOMAIN = "domain"
    private const val DOMAIN_POSITION = 1

    private val QUOTES = setOf(TwigTokenTypes.SINGLE_QUOTE, TwigTokenTypes.DOUBLE_QUOTE)

    /** The key [element] holds, or null when it is no key of a `trans` filter or tag. */
    fun keyOf(element: PsiElement): TwigKey? {
        val read = when (element.elementType) {
            TwigTokenTypes.STRING_TEXT -> filterKeyOf(element)
            TwigTokenTypes.TEMPLATE_HTML_TEXT -> tagKeyOf(element)
            else -> null
        } ?: return null
        if (!SymfonyProject.isSymfony(element.project)) return null
        return read
    }

    /** `'key'|trans` or `'key'|trans(…)`: the string's closing quote is followed by the filter. */
    private fun filterKeyOf(text: PsiElement): TwigKey? {
        val open = text.prevSibling?.takeIf { it.elementType in QUOTES } ?: return null
        val close = text.nextSibling?.takeIf { it.elementType == open.elementType } ?: return null
        val filter = nextToken(close)?.takeIf { it.elementType == TwigTokenTypes.FILTER } ?: return null
        val name = nextToken(filter) ?: return null
        val domain = when {
            name.elementType == TwigTokenTypes.IDENTIFIER && name.text == TRANS -> Domain.Absent
            name.elementType == TwigElementTypes.FUNCTION_CALL && name.firstChild?.text == TRANS -> filterDomainOf(name)
            else -> return null
        }
        return domainOf(domain, text)?.let { TwigKey(text.text, it) }
    }

    /** The domain `trans(params, 'admin')` or `trans(domain='admin')` names. */
    private fun filterDomainOf(call: PsiElement): Domain {
        val arguments = argumentsOf(call)
        val named = arguments.firstOrNull { it.size > 2 && it[0].text == DOMAIN && it[1].elementType == TwigTokenTypes.EQ }
        val positional = arguments.filterNot { it.size > 1 && it[1].elementType == TwigTokenTypes.EQ }
        val value = named?.drop(2) ?: positional.getOrNull(DOMAIN_POSITION) ?: return Domain.Absent
        return valueOf(value)
    }

    /** `{% trans %}key{% endtrans %}`: [body] is all there is between the tag and its end. */
    private fun tagKeyOf(body: PsiElement): TwigKey? {
        val open = previousToken(body)?.takeIf { tagName(it) == TRANS } ?: return null
        nextToken(body)?.takeIf { tagName(it) == END_TRANS } ?: return null
        val key = body.text.trim().takeIf { it.isNotEmpty() } ?: return null
        val from = generateSequence(open.firstChild) { it.nextSibling }
            .firstOrNull { it.elementType == TwigTokenTypes.IDENTIFIER && it.text == FROM }
        val domain = from?.let { valueOf(tokensAfter(it)) } ?: Domain.Absent
        return domainOf(domain, body)?.let { TwigKey(key, it) }
    }

    /** The domain [domain] stands for in [element]'s template: its `trans_default_domain`, else `messages`. */
    private fun domainOf(domain: Domain, element: PsiElement): String? = when (domain) {
        is Domain.Named -> domain.name
        Domain.Absent -> defaultDomain(element.containingFile) ?: SymfonyCatalogue.DEFAULT_DOMAIN
        Domain.Runtime -> null
    }

    /** The domain of `{% trans_default_domain 'admin' %}` in [file], or null when it sets none. */
    fun defaultDomain(file: PsiFile?): String? {
        file ?: return null
        return CachedValuesManager.getCachedValue(file) {
            val name = PsiTreeUtil.collectElements(file) { tagName(it) == DEFAULT_DOMAIN_TAG }.firstOrNull()?.let(::nameToken)
            val domain = name?.let { (valueOf(tokensAfter(it)) as? Domain.Named)?.name }
            CachedValueProvider.Result.create(domain, file)
        }
    }

    /** A domain as a template writes it. */
    private sealed interface Domain {
        data class Named(val name: String) : Domain
        /** None written, or `null`: the default domain. */
        data object Absent : Domain
        /** An expression only known when the template renders. */
        data object Runtime : Domain
    }

    /** The domain an argument's [tokens] write: a plain quoted string, `null`, or anything else known at runtime. */
    private fun valueOf(tokens: List<PsiElement>): Domain {
        val significant = tokens.filterNot { it is PsiWhiteSpace }
        if (significant.size == 1 && significant[0].text == "null") return Domain.Absent
        if (significant.size != 3 || significant[0].elementType !in QUOTES || significant[2].elementType != significant[0].elementType) return Domain.Runtime
        val text = significant[1].takeIf { it.elementType == TwigTokenTypes.STRING_TEXT }?.text ?: return Domain.Runtime
        return Domain.Named(text)
    }

    /** The arguments of a `trans(…)` call, each as its tokens, cut at the commas between its parentheses. */
    private fun argumentsOf(call: PsiElement): List<List<PsiElement>> {
        val children = generateSequence(call.firstChild) { it.nextSibling }.filterNot { it is PsiWhiteSpace }.toList()
        val inner = children.dropWhile { it.elementType != TwigTokenTypes.LBRACE }.drop(1).dropLastWhile { it.elementType != TwigTokenTypes.RBRACE }.dropLast(1)
        if (inner.isEmpty()) return emptyList()
        val arguments = mutableListOf(mutableListOf<PsiElement>())
        inner.forEach { if (it.elementType == TwigTokenTypes.COMMA) arguments.add(mutableListOf()) else arguments.last().add(it) }
        return arguments
    }

    /** The tokens following [token] up to the end of its tag, whitespace left out: what a tag writes after a word. */
    private fun tokensAfter(token: PsiElement): List<PsiElement> =
        generateSequence(token.nextSibling) { it.nextSibling }
            .filterNot { it is PsiWhiteSpace }
            .takeWhile { it.elementType != TwigTokenTypes.STATEMENT_BLOCK_END && it.elementType != TwigTokenTypes.IDENTIFIER }
            .toList()

    /** The name of the tag [element] is — `trans` for `{% trans %}` — or null when it is no tag. */
    private fun tagName(element: PsiElement): String? =
        element.takeIf { it.elementType == TwigElementTypes.TAG }?.let(::nameToken)?.text

    private fun nameToken(tag: PsiElement): PsiElement? =
        generateSequence(tag.firstChild) { it.nextSibling }.firstOrNull { it.elementType == TwigTokenTypes.TAG_NAME }

    private fun nextToken(element: PsiElement): PsiElement? =
        generateSequence(element.nextSibling) { it.nextSibling }.firstOrNull { it !is PsiWhiteSpace }

    private fun previousToken(element: PsiElement): PsiElement? =
        generateSequence(element.prevSibling) { it.prevSibling }.firstOrNull { it !is PsiWhiteSpace }
}
