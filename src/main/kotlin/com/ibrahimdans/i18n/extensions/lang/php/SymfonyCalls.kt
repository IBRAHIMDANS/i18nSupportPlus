package com.ibrahimdans.i18n.extensions.lang.php

import com.ibrahimdans.i18n.extensions.technology.symfony.SymfonyCatalogue
import com.ibrahimdans.i18n.extensions.technology.symfony.SymfonyProject
import com.intellij.psi.PsiElement
import com.jetbrains.php.lang.psi.elements.ConstantReference
import com.jetbrains.php.lang.psi.elements.FunctionReference
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.NewExpression
import com.jetbrains.php.lang.psi.elements.ParameterList
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression

/**
 * Symfony's translation calls, read in a Symfony project only ([SymfonyProject]):
 * `$translator->trans('key', [], 'admin')`, the `t('key', [], 'admin')` function and
 * `new TranslatableMessage('key', [], 'admin')`. Each takes the key first and the domain third — the
 * namespace the key is looked up in, `messages` when the call names none.
 *
 * A call whose domain is computed at runtime (`trans('key', [], $domain)`) is no key: which catalogue
 * holds it is unknown, and reporting it missing from all of them would flag a working call.
 */
internal object SymfonyCalls {

    private const val TRANS = "trans"
    private const val T = "t"
    private const val TRANSLATABLE_MESSAGE = "TranslatableMessage"
    private const val DOMAIN_INDEX = 2

    /** Whether [element] — a string token or the expression holding it — is the key of one of Symfony's calls. */
    fun isCall(element: PsiElement): Boolean = domainOf(element) != null

    /**
     * The domain the call holding [element] as its key reads it from: its third argument, `messages`
     * when it passes none or `null`. Null when [element] is no such key or the domain is only known at runtime.
     */
    fun domainOf(element: PsiElement): String? {
        val arguments = argumentsOf(element) ?: return null
        if (!SymfonyProject.isSymfony(element.project)) return null
        return when (val domain = arguments.getOrNull(DOMAIN_INDEX)) {
            null -> SymfonyCatalogue.DEFAULT_DOMAIN
            is ConstantReference -> SymfonyCatalogue.DEFAULT_DOMAIN.takeIf { domain.name.equals("null", ignoreCase = true) }
            is StringLiteralExpression -> domain.contents.takeIf { domain.firstPsiChild == null && it.isNotEmpty() }
            else -> null
        }
    }

    /** The arguments of the Symfony call [element] is the first argument of, or null. */
    private fun argumentsOf(element: PsiElement): Array<PsiElement>? {
        val argument = generateSequence(element) { it.parent }.firstOrNull { it.parent is ParameterList } ?: return null
        val list = argument.parent as ParameterList
        if (list.parameters.firstOrNull() !== argument) return null
        val isSymfonyCall = when (val call = list.parent) {
            // Before FunctionReference: a method reference is one too.
            is MethodReference -> call.name == TRANS
            is FunctionReference -> call.name == T
            is NewExpression -> call.classReference?.name == TRANSLATABLE_MESSAGE
            else -> false
        }
        return if (isSymfonyCall) list.parameters else null
    }
}
