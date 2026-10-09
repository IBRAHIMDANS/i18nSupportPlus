package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementWalkingVisitor

/**
 * The keys the code around an extraction already uses, from which the dialog guesses where the
 * new key belongs: in `MyTrusteesTab.tsx`, whose keys read `account:myAccount.myTrustees.…`, a
 * new one most likely goes to `account`, under `myAccount.myTrustees`.
 *
 * Read the way the annotator reads keys — [com.ibrahimdans.i18n.Lang.canExtractKey], then
 * [RawKeyParser] — so a key counts here exactly when it is highlighted as one. Walks PSI: in a
 * read action, never on the EDT.
 */
internal object ContextKeys {

    /** A key as placed in the translations: its namespace and its levels. */
    data class Placed(val namespace: String, val path: List<String>)

    /** Neighbour files read when the file holds no key: enough to tell, cheap to walk. */
    private const val MAX_NEIGHBOURS = 20

    /**
     * The distinct keys of [file], or of the other files of its folder when it has none — a
     * component being written usually has none yet. A key without a namespace is placed in the
     * namespace its hook declares, else in [defaultNamespace].
     */
    fun around(file: PsiFile, defaultNamespace: String): List<Placed> {
        val own = keysOf(file, defaultNamespace)
        if (own.isNotEmpty()) return own
        val neighbours = file.containingDirectory?.files.orEmpty().filter { it != file }.take(MAX_NEIGHBOURS)
        return neighbours.flatMap { keysOf(it, defaultNamespace) }.distinct()
    }

    private fun keysOf(file: PsiFile, defaultNamespace: String): List<Placed> {
        val functionNames = Extensions.TECHNOLOGY.extensionList.flatMap { it.translationFunctionNames() }
        val langs = Extensions.LANG.extensionList
        val parser = RawKeyParser(file.project)
        val found = linkedSetOf<Placed>()
        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                langs.firstOrNull { it.canExtractKey(element, functionNames) }
                    ?.extractRawKey(element)
                    ?.let { parser.parse(it, element) }
                    ?.takeUnless { it.isDynamic || it.compositeKey.isEmpty() }
                    ?.let { key ->
                        found += Placed(key.allNamespaces().firstOrNull() ?: defaultNamespace, key.compositeKey.map { it.text })
                    }
                super.visitElement(element)
            }
        })
        return found.toList()
    }

    /**
     * Where [keys] suggest a new key goes: the namespace most of them use among [namespaces],
     * and under it the deepest group still holding a clear majority of them — [MAJORITY] — so
     * one stray key does not cut the guess short the way a common prefix would. Null when no
     * key falls in an offered namespace.
     */
    fun placement(keys: List<Placed>, namespaces: Collection<String>): Pair<String, List<String>>? {
        val offered = keys.filter { it.namespace in namespaces }
        val namespace = offered.groupingBy { it.namespace }.eachCount().maxByOrNull { it.value }?.key ?: return null
        val groups = offered.filter { it.namespace == namespace }.map { it.path.dropLast(1) }
        var parent = emptyList<String>()
        while (true) {
            val depth = parent.size + 1
            val next = groups.filter { it.size >= depth && it.take(parent.size) == parent }
                .groupingBy { it.take(depth) }.eachCount()
                .maxByOrNull { it.value }
                ?.takeIf { it.value >= groups.size * MAJORITY }
                ?: break
            parent = next.key
        }
        return namespace to parent
    }

    private const val MAJORITY = 0.6
}
