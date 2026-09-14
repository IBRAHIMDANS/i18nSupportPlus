package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.key.parser.KeyParserBuilder
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Keys whose hook prepends a prefix the literal does not write: react-i18next's `keyPrefix`
 * option and next-intl's `useTranslations('Ns')`. Both used to be looked up without the prefix,
 * so every such key was annotated as unresolved.
 */
class HookKeyPrefixHighlightingTest : PlatformBaseTest() {

    private fun reactComponent(hookArguments: String, call: String) = """
        import { useTranslation } from 'react-i18next';
        export default function Header() {
            const { t } = useTranslation($hookArguments);
            return t($call);
        }
    """.trimIndent()

    private fun nextIntlComponent(namespace: String, call: String) = """
        import { useTranslations } from 'next-intl';
        export default function Home() {
            const t = useTranslations('$namespace');
            return t($call);
        }
    """.trimIndent()

    private fun addDashboard() =
        myFixture.addFileToProject("en/dashboard.json", """{"header": {"title": "Hi", "sub": {"label": "L"}}}""")

    @Test
    fun keyPrefixResolvesTheKey() {
        addDashboard()
        myFixture.configureByText("Header.tsx", reactComponent("'dashboard', { keyPrefix: 'header' }", "'title'"))
        myFixture.checkHighlighting(true, true, true, true)
    }

    @Test
    fun nestedKeyPrefixResolvesTheKey() {
        addDashboard()
        myFixture.configureByText("Header.tsx", reactComponent("'dashboard', { keyPrefix: 'header.sub' }", "'label'"))
        myFixture.checkHighlighting(true, true, true, true)
    }

    /** The prefix resolves but the written key does not: only the written part is underlined. */
    @Test
    fun missingKeyUnderKeyPrefixUnderlinesTheWrittenPart() {
        addDashboard()
        myFixture.configureByText(
            "Header.tsx",
            reactComponent("'dashboard', { keyPrefix: 'header' }", "'sub.<error descr=\"Unresolved key\">absent</error>'")
        )
        myFixture.checkHighlighting(true, true, true, true)
    }

    /** Resolution stops inside the prefix itself: nothing written resolved, so all of it is flagged. */
    @Test
    fun unresolvedKeyPrefixUnderlinesTheWholeKey() {
        addDashboard()
        myFixture.configureByText(
            "Header.tsx",
            reactComponent("'dashboard', { keyPrefix: 'nope' }", "'<error descr=\"Unresolved key\">title</error>'")
        )
        myFixture.checkHighlighting(true, true, true, true)
    }

    /** next-intl: one file per locale, the hook argument is the path of the object holding the keys. */
    @Test
    fun nextIntlNamespaceResolvesTheKey() {
        myFixture.addFileToProject("messages/en.json", """{"Home": {"title": "Welcome"}}""")
        myFixture.configureByText("Home.tsx", nextIntlComponent("Home", "'title'"))
        myFixture.checkHighlighting(true, true, true, true)
    }

    @Test
    fun nextIntlMissingKeyIsStillReported() {
        myFixture.addFileToProject("messages/en.json", """{"Home": {"title": "Welcome"}}""")
        myFixture.configureByText("Home.tsx", nextIntlComponent("Home", "'<error descr=\"Unresolved key\">absent</error>'"))
        myFixture.checkHighlighting(true, true, true, true)
    }

    /** The prefix leads the composite key, while `source` stays the written text. */
    @Test
    fun parserPrependsThePrefixAndKeepsTheWrittenSource() {
        val key = KeyParserBuilder.withSeparators(":", ".").build().parse(
            RawKey(listOf(KeyElement.literal("common:title")), keyPrefix = listOf(KeyElement.literal("a.b")))
        )!!
        assertEquals("common:title", key.source)
        assertEquals("common", key.ns?.text)
        assertEquals(listOf("a", "b", "title"), key.compositeKey.map { it.text })
        assertEquals(listOf("a", "b"), key.keyPrefix.map { it.text })
    }
}
