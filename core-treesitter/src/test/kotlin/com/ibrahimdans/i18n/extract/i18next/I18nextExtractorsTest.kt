package com.ibrahimdans.i18n.extract.i18next

import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.extract.treesitter.TreeSitterCodeNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class I18nextExtractorsTest {

    private fun keys(source: String, functionNames: Set<String> = I18NEXT_FUNCTIONS): List<RawKey> =
        findKeys(TreeSitterCodeNode.parseTsx(source), i18nextExtractors(functionNames)).map { it.key }

    private fun literal(key: String, namespaces: List<String> = emptyList(), prefix: String? = null) =
        RawKey(listOf(KeyElement.literal(key)), namespaces, listOfNotNull(prefix?.let(KeyElement::literal)))

    @Test
    fun `a literal passed to t or i18n t is a key, its namespace written in it`() {
        assertEquals(
            listOf(literal("ns:a.b"), literal("c.d"), literal("e")),
            keys("t('ns:a.b'); i18n.t(\"c.d\"); i18next.t('e')"),
        )
    }

    @Test
    fun `the literal is kept as written, escapes included`() {
        assertEquals(listOf(literal("it\\'s")), keys("t('it\\'s')"))
    }

    @Test
    fun `only the first argument of a configured function holds a key`() {
        assertEquals(
            listOf(literal("a")),
            keys("t('a', 'b'); toast.t('c'); translate('d'); foo('e'); new t('f'); t(x as string)"),
        )
    }

    @Test
    fun `a configured name is read instead of the defaults`() {
        assertEquals(listOf(literal("a"), literal("b")), keys("translate('a'); i18n.translate('b'); t('c')", setOf("translate", "i18n.translate")))
    }

    @Test
    fun `the branches of a ternary are keys, its condition is not`() {
        assertEquals(listOf(literal("a"), literal("b")), keys("t(x === 'remove' ? 'a' : 'b')"))
    }

    @Test
    fun `a key passed in another key's options is a key of its own`() {
        assertEquals(listOf(literal("a"), literal("b")), keys("t('a', { name: t('b') })"))
    }

    @Test
    fun `the ns option names the namespaces`() {
        assertEquals(
            listOf(literal("a", listOf("x")), literal("b", listOf("y", "z")), literal("c")),
            keys("t('a', { ns: 'x' }); t('b', { 'ns': ['y', 'z'] }); t('c', { count: 1 })"),
        )
    }

    @Test
    fun `the hook binding t gives its namespaces and key prefix`() {
        val source = """
            export function A() { const { t } = useTranslation('common', { keyPrefix: 'profile' }); return t('name'); }
            export const B = () => { const { t, i18n } = useTranslation(['admin', 'common']); return t('dashboard.title'); };
            export const C = () => { const { t } = useTranslation('common'); return t('label', { ns: 'other' }); };
            export const D = () => { const { t } = useTranslation('common', { keyPrefix: '' }); return t('k'); };
        """.trimIndent()

        assertEquals(
            listOf(
                literal("name", listOf("common"), "profile"),
                literal("dashboard.title", listOf("admin", "common")),
                literal("label", listOf("other")),
                literal("k", listOf("common")),
            ),
            keys(source),
        )
    }

    @Test
    fun `the hook is looked up in the enclosing scopes, not in a sibling function`() {
        val source = """
            function Outer() {
              const { t } = useTranslation('outer');
              const inner = () => t('a');
              return inner;
            }
            function Sibling() { const { t } = useTranslation('sibling'); }
            function Other() { return t('b'); }
        """.trimIndent()

        assertEquals(listOf(literal("a", listOf("outer")), literal("b")), keys(source))
    }

    @Test
    fun `a nested scope's own hook hides the enclosing one`() {
        val source = """
            function Outer() {
              const { t } = useTranslation('outer');
              const Inner = () => { const { t } = useTranslation('inner'); return t('a'); };
              return t('b');
            }
        """.trimIndent()

        assertEquals(listOf(literal("a", listOf("inner")), literal("b", listOf("outer"))), keys(source))
    }

    /** The extractors keep the hooks of the file they read: the next file must not see them. */
    @Test
    fun `the same extractors read each file with its own hooks`() {
        val extractors = i18nextExtractors()
        fun read(source: String) = findKeys(TreeSitterCodeNode.parseTsx(source), extractors).map { it.key }
        val component = "export function A() { const { t } = useTranslation('%s'); return t('k'); }"

        assertEquals(listOf(literal("k", listOf("first"))), read(component.format("first")))
        assertEquals(listOf(literal("k", listOf("second"))), read(component.format("second")))
        assertEquals(listOf(literal("k")), read("export function A() { return t('k'); }"))
    }

    @Test
    fun `i18n t takes no hook namespace`() {
        assertEquals(listOf(literal("a")), keys("const A = () => { const { t, i18n } = useTranslation('common'); return i18n.t('a'); };"))
    }

    @Test
    fun `a template literal is split into its parts as the PSI tokens are`() {
        val key = keys("const { t } = useTranslation('common'); t(`ns:status.\${s}.x\\n`, { ns: 'other' })").single()

        assertEquals(
            RawKey(
                listOf(
                    KeyElement.literal("`"), KeyElement.literal("ns:status."),
                    KeyElement.literal("$"), KeyElement.literal("{"), KeyElement.template("s"), KeyElement.literal("}"),
                    KeyElement.literal(".x\\n"), KeyElement.literal("`"),
                ),
                listOf("other"),
            ),
            key,
        )
    }

    @Test
    fun `a literal inside a template key is no key`() {
        assertEquals(1, keys("t(`a.\${b ? 'x' : 'y'}`)").size)
    }

    @Test
    fun `the i18nKey attribute is a key without namespace`() {
        assertEquals(
            listOf(literal("common:title"), literal("b")),
            keys("const A = () => <><Trans i18nKey=\"common:title\" values={{ a: 'x' }} /><Other i18nKey='b'>c</Other><p title=\"d\" /></>;"),
        )
    }
}
