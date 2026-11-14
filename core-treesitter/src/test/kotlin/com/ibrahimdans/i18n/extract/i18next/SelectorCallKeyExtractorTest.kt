package com.ibrahimdans.i18n.extract.i18next

import com.ibrahimdans.i18n.extract.treesitter.TreeSitterCodeNode
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** i18next's selector API on a tree-sitter tree: `t($ => $.button.save)` names `button.save`, as the plugin reads it. */
class SelectorCallKeyExtractorTest {

    private fun keys(source: String): List<RawKey> =
        findKeys(TreeSitterCodeNode.parseTsx(source), i18nextExtractors()).map { it.key }

    private fun literal(key: String, namespaces: List<String> = emptyList(), prefix: String? = null) =
        RawKey(listOf(KeyElement.literal(key)), namespaces, listOfNotNull(prefix?.let(KeyElement::literal)))

    @Test
    fun `the path a selector reads is the key, bracket access included`() {
        assertEquals(
            listOf(literal("button.save"), literal("errors.not-found"), literal("a.b"), literal("c")),
            keys("t($ => $.button.save); t($ => $.errors['not-found']); i18n.t((\$: S) => \$.a.b); t([\$ => \$.c])"),
        )
    }

    @Test
    fun `the hook's namespaces and key prefix and the ns option apply`() {
        val source = """
            export function A() { const { t } = useTranslation('common', { keyPrefix: 'button' }); return t($ => $.save); }
            export const B = () => { const { t } = useTranslation('common'); return t($ => $.login, { ns: 'auth' }); };
        """.trimIndent()
        assertEquals(listOf(literal("save", listOf("common"), "button"), literal("login", listOf("auth"))), keys(source))
    }

    /** As i18next's `keysFromSelector`: a secondary namespace only, never the first one, and not under an explicit `ns`. */
    @Test
    fun `a first segment naming a secondary namespace of the hook routes the key`() {
        val source = """
            export const A = () => {
              const { t } = useTranslation(['common', 'auth']);
              return [t($ => $.auth.login), t($ => $.common.title), t($ => $.auth.login, { ns: 'x' })];
            };
        """.trimIndent()
        assertEquals(
            listOf(literal("login", listOf("auth")), literal("common.title", listOf("common", "auth")), literal("auth.login", listOf("x"))),
            keys(source),
        )
    }

    /** Redux's `useSelector`, a computed body, two parameters, another root, a computed index: no key. */
    @Test
    fun `an arrow that is no selector of a translation call holds no key`() {
        assertEquals(
            emptyList<RawKey>(),
            keys("useSelector(s => s.user.name); t($ => c ? $.a : $.b); t(($, x) => $.a); t($ => other.a); t($ => $[k]); t($ => $)"),
        )
    }
}
