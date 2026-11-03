package com.ibrahimdans.i18n.plugin.ide

import com.ibrahimdans.i18n.plugin.KeyUsage
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.generator.code.TsxCodeGenerator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The usage searches asked through [IdeKeyUsageIndex]: the same answers as the table's *Usage*
 * column ([com.ibrahimdans.i18n.plugin.ide.toolwindow.UsageScanTest],
 * [com.ibrahimdans.i18n.plugin.ide.toolwindow.DynamicKeyUsageTest]), now with where each one is.
 */
class IdeKeyUsageIndexTest : PlatformBaseTest() {

    private val tsx = TsxCodeGenerator()
    private val index by lazy { IdeKeyUsageIndex(project) }

    @Test
    fun `a call naming the key is one usage, located on its literal`() {
        val file = myFixture.configureByText("Menu.tsx", tsx.generate("'common:menu.home'"))

        val usages = index.usages("common:menu.home")

        assertEquals(1, usages.size)
        val usage = usages.single()
        assertEquals(file.virtualFile.path, usage.path)
        assertTrue(file.text.substring(usage.span.start, usage.span.end).contains("common:menu.home"))
    }

    @Test
    fun `a plural form is found from the key the code writes`() {
        myFixture.configureByText("Cart.tsx", tsx.generate("'common:cart.item'"))

        assertEquals(1, index.usages("common:cart.item_other").size)
    }

    @Test
    fun `a call under a hook key prefix is found`() {
        myFixture.addFileToProject("locales/en/dashboard.json", """{"header": {"title": "Hi"}, "unused": "x"}""")
        myFixture.configureByText(
            "Header.tsx",
            """
            import { useTranslation } from 'react-i18next';
            export default function Header() {
                const { t } = useTranslation('dashboard', { keyPrefix: 'header' });
                return t('title');
            }
            """.trimIndent()
        )

        assertEquals(1, index.usages("dashboard:header.title").size)
        assertEquals(0, index.usages("dashboard:unused").size)
    }

    @Test
    fun `a key built at runtime reaches the keys of its shape only`() {
        myFixture.addFileToProject(
            "locales/en/common.json",
            """{"status":{"pending":"Pending","accepted":"Accepted"},"menu":{"home":"Home"}}"""
        )
        myFixture.addFileToProject(
            "src/Status.tsx",
            "export const label = (kind: string) => i18n.t(`common:status.\${kind}`);"
        )

        val reached = index.dynamicallyReached(listOf("common:status.pending", "common:status.accepted", "common:menu.home"))

        assertEquals(setOf("common:status.pending", "common:status.accepted"), reached)
        assertTrue(index.usages("common:status.pending").isEmpty(), "a dynamic key names no key outright")
    }

    @Test
    fun `a batch gathers direct and key-prefixed usages of the same key`() {
        myFixture.addFileToProject("locales/en/common.json", """{"menu":{"home":"Home"},"profile":{"name":"Name"}}""")
        myFixture.configureByText(
            "Menu.tsx",
            """
            import { useTranslation } from 'react-i18next';
            export const menu = () => i18n.t('common:menu.home');
            export const named = () => i18n.t('common:profile.name');
            export const Profile = () => {
                const { t } = useTranslation('common', { keyPrefix: 'profile' });
                return t('name');
            }
            """.trimIndent()
        )

        val usages = index.usages(listOf("common:menu.home", "common:profile.name", "common:missing"))

        assertEquals(1, usages["common:menu.home"]?.size)
        assertEquals(2, usages["common:profile.name"]?.size)
        assertEquals(emptyList<KeyUsage>(), usages["common:missing"])
        assertEquals(2, index.usageCounts(listOf("common:profile.name"))["common:profile.name"])
    }

    @Test
    fun `a count over several batches, off the EDT and interrupted by a write, counts every key`() {
        val keys = (0 until IdeKeyUsageIndex.BATCH_SIZE * 2 + 1).map { "common:section$it.title$it" }
        myFixture.addFileToProject("src/Keys.ts", keys.joinToString("\n") { "i18n.t('$it');" })
        val edited = myFixture.addFileToProject("src/Other.ts", "export const a = 1;")

        val counting = ApplicationManager.getApplication().executeOnPooledThread<Map<String, Int>> { index.usageCounts(keys) }
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.getDocument(edited).insertString(0, "// edited\n")
        }
        val counts = counting.get(60, TimeUnit.SECONDS)

        assertEquals(keys.associateWith { 1 }, counts)
    }

    @Test
    fun `the keys searching the same word stay in one batch`() {
        val titles = (0 until IdeKeyUsageIndex.BATCH_SIZE + 5).map { "common:page$it.title" }
        val others = (0 until 3).map { "common:menu.item$it" }

        val batches = IdeKeyUsageIndex.batchesOf(others + titles, Config())

        assertEquals(listOf(others, titles), batches)
        assertEquals(others + titles, batches.flatten())
    }

    /**
     * `processElementsWithWord` hands the files holding a word to several threads at once, and
     * every hit of the key-prefix scan went into a plain map and set: with enough files, two hits
     * written at the same moment lost one of them, and a key in use read one usage short — or none.
     * The race is one of timing, so the count is taken several times over many files.
     */
    @Test
    fun `the hits of a parallel word search are all collected`() {
        val files = 150
        repeat(files) { i ->
            myFixture.addFileToProject(
                "src/Section$i.tsx",
                """
                import { useTranslation } from 'react-i18next';
                const name = $i; const title = 'x';
                export const Section$i = () => {
                    const { t } = useTranslation('common', { keyPrefix: 'section' });
                    return <p title={title} aria-label={name}>{t('name')}{t('title')}</p>;
                };
                """.trimIndent()
            )
        }
        myFixture.addFileToProject("locales/en/common.json", """{"section": {"name": "Name", "title": "Title"}}""")
        val keys = listOf("common:section.name", "common:section.title")

        repeat(3) { round ->
            assertEquals(keys.associateWith { files }, index.usageCounts(keys), "round $round")
        }
    }
}
