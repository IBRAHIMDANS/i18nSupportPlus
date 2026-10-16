package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.utils.hostFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

/**
 * The names a file binds Paraglide's message object to: `m` in `import { m } from './paraglide/messages.js'`.
 *
 * Paraglide compiles each message into a function of that object, so `m.hello()` names the key
 * `hello` without any string. Only an import whose specifier ends with `paraglide/messages` (`.js`
 * optional — `$lib/paraglide/messages`, `@/paraglide/messages.js`, a relative path) counts, in the
 * three shapes Paraglide documents: `{ m }`, `{ m as msg }`, and v1's `* as m`. A `m` of any other
 * module, or a local one, is no message object.
 *
 * Read from the text of the host file — the component, for a fragment injected into Svelte or Vue —
 * once per change of that file: the extractor asks for every reference expression of every file.
 */
internal object ParaglideImports {

    private const val SPECIFIER = """['"][^'"]*paraglide/messages(?:\.js)?['"]"""
    private val NAMED = Regex("""import\s+(?:type\s+)?\{([^}]*)\}\s*from\s*$SPECIFIER""")
    private val NAMESPACE = Regex("""import\s+\*\s+as\s+([\w$]+)\s+from\s*$SPECIFIER""")
    private val SPECIFIER_ITEM = Regex("""^m(?:\s+as\s+([\w$]+))?$""")

    /** The names [element]'s host file binds the message object to; empty without a Paraglide import. */
    fun messageObjectNames(element: PsiElement): Set<String> {
        val file = element.hostFile() ?: return emptySet()
        return CachedValuesManager.getCachedValue(file) { CachedValueProvider.Result.create(namesIn(file), file) }
    }

    private fun namesIn(file: PsiFile): Set<String> {
        val text = file.text
        if (!text.contains("paraglide/messages")) return emptySet()
        val named = NAMED.findAll(text).flatMap { match ->
            match.groupValues[1].split(',').mapNotNull { item ->
                SPECIFIER_ITEM.matchEntire(item.trim())?.let { it.groupValues[1].ifEmpty { "m" } }
            }
        }
        val namespaces = NAMESPACE.findAll(text).map { it.groupValues[1] }
        return (named + namespaces).toSet()
    }
}
