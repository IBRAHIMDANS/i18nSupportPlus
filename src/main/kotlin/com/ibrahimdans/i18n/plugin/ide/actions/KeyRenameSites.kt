package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.psi.PsiElement

/**
 * Where a language writes a key, or part of one, outside the literal a translation call resolves —
 * react-i18next's `keyPrefix`, Paraglide's `m.key` member — and how it is rewritten when a level of
 * the key is renamed. Given by [com.ibrahimdans.i18n.Lang.renameSites]: [KeyLevelRename] asks every
 * language and keeps no PSI of its own.
 */
interface KeyRenameSites {

    /** [element]'s whole text replaced by [text]. */
    data class Rewrite(val element: PsiElement, val text: String)

    /**
     * The rewrite of a key prefix [element] writes — a `keyPrefix` of a hook on one of [namespaces]
     * (the default ones when the hook names none) — when the prefix goes through [path], whose last
     * segment becomes [newName]. Null when [element] writes no such prefix.
     */
    fun prefixRewrite(element: PsiElement, path: List<String>, newName: String, namespaces: List<String>, config: Config): Rewrite?

    /** The member at or just above [element] naming a message directly, in a module allowing it; null otherwise. */
    fun messageMemberAt(element: PsiElement): PsiElement?

    /** The key [member] names, as written: `m.button.save`'s is `button.save`. Null when it holds none. */
    fun messageKeyOf(member: PsiElement): String?

    /** The rewrite of [member] for its key renamed to [renamedKey]: the form the language spells it in. */
    fun messageMemberRewrite(member: PsiElement, renamedKey: String): Rewrite?
}
