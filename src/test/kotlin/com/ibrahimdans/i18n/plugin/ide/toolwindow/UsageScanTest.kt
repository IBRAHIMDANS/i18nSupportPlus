package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.utils.generator.code.TsxCodeGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What the *Usage* column, and therefore *Cleanup unused keys*, actually finds in the sources.
 *
 * [TableViewModelTest] covers the terms searched; this covers the count they produce against
 * real PSI, which is where both defects showed: a plural form found nothing at all, and a
 * namespaced key found each of its call sites twice.
 */
class UsageScanTest : PlatformBaseTest() {

    private val viewModel = TableViewModel()
    private val tsx = TsxCodeGenerator()

    private fun usagesOf(key: String): Int =
        viewModel.countUsages(project, listOf(TranslationRow(key, mapOf("en" to "value"))))
            .first().usageCount

    /**
     * The reported bug: the file holds `…description_other`, the source writes
     * `t('…description', { count })` — i18next appends the suffix itself. Searching the stored
     * form matched nothing, so a key in use was shown as an orphan.
     */
    @Test
    fun `a CLDR plural form is counted from the key the source writes`() {
        myFixture.configureByText("Cart.tsx", tsx.generate("'common:cart.item'"))

        assertEquals(1, usagesOf("common:cart.item_other"))
        assertEquals(1, usagesOf("common:cart.item_one"))
    }

    /**
     * A namespaced key is searched twice — prefixed and bare — because the namespace may be
     * implicit at the call site. Both searches reach the same call, which used to be counted
     * once per search: three usages were reported as six.
     */
    @Test
    fun `a call site is counted once, not once per search term`() {
        myFixture.configureByText("Menu.tsx", tsx.generate("'common:menu.home'"))

        assertEquals(1, usagesOf("common:menu.home"))
    }

    @Test
    fun `every call site is counted`() {
        myFixture.configureByText(
            "Menu.tsx",
            tsx.multiGenerate("'common:menu.home'", "'common:menu.home'")
        )

        assertEquals(2, usagesOf("common:menu.home"))
    }

    @Test
    fun `a key no source mentions stays an orphan`() {
        myFixture.configureByText("Menu.tsx", tsx.generate("'common:menu.home'"))

        assertEquals(0, usagesOf("common:menu.missing"))
    }
}
