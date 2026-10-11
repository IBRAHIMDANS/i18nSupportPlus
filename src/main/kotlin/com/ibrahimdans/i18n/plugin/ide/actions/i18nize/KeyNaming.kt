package com.ibrahimdans.i18n.plugin.ide.actions.i18nize

import com.ibrahimdans.i18n.plugin.ide.actions.ExtractKeyModel
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.lang.javascript.psi.ecmal4.JSClass
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import java.text.Normalizer

/**
 * The key *Internationalize…* gives a text: `<namespace>:<component>.<text>` — the namespace of the
 * file's hook or the default one, the component in camelCase, the text's slug (`save_changes`).
 *
 * One instance names every text of one module, so that its keys never clash:
 * - the same text in the same component and namespace gets the same key;
 * - two texts giving the same slug get `_2`, `_3`…;
 * - a name an [existing] key already holds with another value is never reused: the text moves on to
 *   the next suffix. Holding the same value, it is the key the text would reuse anyway.
 *
 * [spell] writes a namespace and a path the way the code writes keys (`ExistingKeyFinder.spell`).
 */
internal class KeyNaming(
    private val spell: (namespace: String, path: List<String>) -> String,
    private val existing: Map<String, String> = emptyMap()
) {

    /** Key -> the text it was given to, in this plan. */
    private val assigned = mutableMapOf<String, String>()

    private val named = mutableMapOf<Triple<String, String, String>, String>()

    fun keyFor(namespace: String, component: String, text: String): String {
        val trimmed = text.trim()
        return named.getOrPut(Triple(namespace, component, trimmed)) {
            val slug = ExtractKeyModel.proposeKey(trimmed).ifEmpty { EMPTY_SLUG }
            generateSequence(1) { it + 1 }
                .map { n -> spell(namespace, listOf(component, if (n == 1) slug else "${slug}_$n")) }
                .first { key -> isFreeFor(key, trimmed) }
                .also { assigned[it] = trimmed }
        }
    }

    private fun isFreeFor(key: String, text: String): Boolean {
        assigned[key]?.let { return it == text }
        existing[key]?.let { return it.trim() == text }
        return true
    }

    companion object {
        /** The slug of a text with no letter or digit to name it by (`!!!`, `日本語`). */
        const val EMPTY_SLUG = "text"

        /**
         * The component [element] belongs to: the innermost function or class named with a capital
         * — `function UserProfile()`, `const UserProfile = () =>`, `class UserProfile` — else the
         * file's name, both in camelCase (`userProfile`).
         */
        fun componentOf(element: PsiElement): String {
            var current: PsiElement? = element
            while (current != null) {
                val name = when (current) {
                    is JSFunction -> current.name ?: PsiTreeUtil.getParentOfType(current, JSVariable::class.java, true, JSFunction::class.java)?.name
                    is JSClass -> current.name
                    else -> null
                }
                if (name != null && name.first().isUpperCase()) return camelCase(name)
                current = current.parent
            }
            return camelCase(element.containingFile.virtualFile?.nameWithoutExtension ?: element.containingFile.name.substringBefore('.'))
        }

        /** `UserProfile`, `user-profile`, `user_profile` → `userProfile`; accents dropped; `component` when nothing is left. */
        fun camelCase(name: String): String {
            val words = Normalizer.normalize(name, Normalizer.Form.NFD).replace(DIACRITICS, "")
                .split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return "component"
            return words.mapIndexed { index, word ->
                if (index == 0) word.replaceFirstChar { it.lowercase() } else word.replaceFirstChar { it.uppercase() }
            }.joinToString("")
        }

        private val DIACRITICS = Regex("\\p{Mn}+")
    }
}
