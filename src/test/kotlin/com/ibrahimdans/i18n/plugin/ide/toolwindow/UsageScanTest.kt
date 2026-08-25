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

    /**
     * The prefix match used to cross segment boundaries: `menu.home` counted the calls to
     * `menu.homePage` too, so the column added up neighbouring keys and a dead key could read
     * as used.
     */
    @Test
    fun `a longer key of the same prefix is not counted`() {
        myFixture.configureByText("Menu.tsx", tsx.generate("'common:menu.homePage'"))

        assertEquals(0, usagesOf("common:menu.home"))
    }

    @Test
    fun `a longer key separated by a dash is not counted either`() {
        // The one the word search does let through: `-` ends a word, so `menu.home-page`
        // reaches the filter where `menu.homePage` never does.
        myFixture.configureByText("Menu.tsx", tsx.generate("'common:menu.home-page'"))

        assertEquals(0, usagesOf("common:menu.home"))
    }

    /**
     * A key naming an object is reached by every call under it — what makes navigation from a
     * parent node find its children's call sites. The boundary rule must not take that away.
     */
    @Test
    fun `a parent key is still reached by the calls under it`() {
        myFixture.configureByText("Menu.tsx", tsx.generate("'common:menu.home'"))

        assertEquals(1, usagesOf("common:menu"))
    }

    // ── The namespace the call site works under ───────────────────────────────

    private fun withHook(namespace: String, key: String) {
        myFixture.configureByText(
            "Menu.tsx",
            """
            import { useTranslation } from 'react-i18next';

            export const Menu = () => {
                const { t } = useTranslation('$namespace');
                return t('$key');
            };
            """.trimIndent()
        )
    }

    /**
     * The bare form is searched so that `useTranslation('navigation') + t('menu.profile')` is
     * found, but nothing checked *which* namespace the hook declared: a call written under
     * `common` was counted as a usage of every namespace holding a key of that name.
     */
    @Test
    fun `a bare call counts only for the namespace its hook declares`() {
        withHook("common", "menu.home")

        assertEquals(1, usagesOf("common:menu.home"))
        assertEquals(0, usagesOf("navigation:menu.home"))
    }

    @Test
    fun `a call site declaring no namespace still counts`() {
        // Nothing to compare against — and reporting a live key as an orphan is the error
        // worth avoiding, so an unresolvable hook keeps its usages.
        myFixture.configureByText("Menu.tsx", tsx.generate("'menu.home'"))

        assertEquals(1, usagesOf("navigation:menu.home"))
    }

    @Test
    fun `a namespace written at the call site wins over the hook`() {
        withHook("common", "navigation:menu.home")

        assertEquals(1, usagesOf("navigation:menu.home"))
        assertEquals(0, usagesOf("common:menu.home"))
    }

    @Test
    fun `a key no source mentions stays an orphan`() {
        myFixture.configureByText("Menu.tsx", tsx.generate("'common:menu.home'"))

        assertEquals(0, usagesOf("common:menu.missing"))
    }
}
