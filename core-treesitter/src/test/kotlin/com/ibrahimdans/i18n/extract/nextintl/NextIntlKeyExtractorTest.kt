package com.ibrahimdans.i18n.extract.nextintl

import com.ibrahimdans.i18n.extract.CodeExtractors
import com.ibrahimdans.i18n.extract.i18next.findKeys
import com.ibrahimdans.i18n.extract.treesitter.TreeSitterCodeNode
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NextIntlKeyExtractorTest {

    private fun keys(source: String, preset: String? = CodeExtractors.NEXT_INTL): List<RawKey> =
        findKeys(TreeSitterCodeNode.parseTsx(source), CodeExtractors.forPreset(preset)).map { it.key }

    private fun key(key: String, prefix: String? = null) =
        RawKey(listOf(KeyElement.literal(key)), emptyList(), listOfNotNull(prefix?.let(KeyElement::literal)))

    @Test
    fun `the hook's argument prefixes the keys, not a namespace`() {
        assertEquals(
            listOf(key("title", "Cart"), key("title", "Settings.Privacy")),
            keys(
                """
                function Cart() { const t = useTranslations('Cart'); return t('title'); }
                function Privacy() { const t = useTranslations('Settings.Privacy'); return t('title'); }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `getTranslations is awaited, its namespace option read, and no argument gives no prefix`() {
        assertEquals(
            listOf(key("title", "HomePage"), key("title", "Metadata"), key("Common.appName")),
            keys(
                """
                async function Page() { const t = await getTranslations('HomePage'); return t('title'); }
                async function meta() { const t = await getTranslations({ locale, namespace: 'Metadata' }); return t('title'); }
                async function Layout() { const t = await getTranslations(); return t('Common.appName'); }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `any name the hook binds is read, and t's methods too`() {
        assertEquals(
            listOf(key("a", "Nav"), key("b", "Nav"), key("c", "Nav"), key("d", "Nav"), key("e", "Nav")),
            keys("function A() { const tNav = useTranslations('Nav'); tNav('a'); tNav.rich('b', {}); tNav.markup('c', {}); tNav.raw('d'); tNav.has('e'); }"),
        )
    }

    @Test
    fun `another object's method and a t bound by no hook are no next-intl key`() {
        // The plain `t` is still read, as next-intl's technology publishes it: without a prefix.
        assertEquals(
            listOf(key("b")),
            keys("function A() { const t = useTranslations('Nav'); toast.rich('a'); } function B() { t('b'); }"),
        )
    }

    @Test
    fun `the nearest scope's hook wins`() {
        assertEquals(
            listOf(key("a", "Inner"), key("b", "Outer")),
            keys(
                """
                function Outer() {
                  const t = useTranslations('Outer');
                  const Inner = () => { const t = useTranslations('Inner'); return t('a'); };
                  return t('b');
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `without a preset, react-i18next's hook keeps its namespaces`() {
        assertEquals(
            listOf(RawKey(listOf(KeyElement.literal("a")), listOf("common")), key("b", "Nav")),
            keys(
                "function A() { const { t } = useTranslation('common'); return t('a'); } function B() { const t = useTranslations('Nav'); return t('b'); }",
                preset = null,
            ),
        )
    }
}
