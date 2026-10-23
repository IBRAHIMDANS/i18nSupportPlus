package com.ibrahimdans.i18n.plugin.ide

import com.ibrahimdans.i18n.plugin.KeyUsage
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.utils.generator.code.TsxCodeGenerator
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
}
