package com.ibrahimdans.i18n.plugin.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The display layer used to show the raw ICU source of a react-intl translation, so folding
 * replaced `t('cart.items')` with `{count, plural, one {# arti…` — less readable than the key it
 * replaced. These tests pin the minimal rendering that fixes it, and the guarantee that non-ICU
 * messages (every i18next project) come back byte-for-byte unchanged.
 */
class IcuMessageRendererTest {

    @Test
    fun rendersThePluralOtherBranch() {
        assertEquals(
            "{count} articles",
            IcuMessageRenderer.render("{count, plural, one {# article} other {# articles}}")
        )
    }

    @Test
    fun substitutesTheHashWithTheArgument() {
        assertEquals(
            "You have {itemCount} items left",
            IcuMessageRenderer.render("{itemCount, plural, other {You have # items left}}")
        )
    }

    @Test
    fun fallsBackToTheFirstBranchWhenOtherIsMissing() {
        assertEquals(
            "{count} article",
            IcuMessageRenderer.render("{count, plural, one {# article} few {# articles}}")
        )
    }

    @Test
    fun rendersASelectBranch() {
        assertEquals(
            "They liked it",
            IcuMessageRenderer.render("{gender, select, male {He liked it} female {She liked it} other {They liked it}}")
        )
    }

    @Test
    fun keepsTheHashLiteralInsideASelectBranch() {
        assertEquals(
            "tag #",
            IcuMessageRenderer.render("{kind, select, other {tag #}}")
        )
    }

    @Test
    fun rendersASelectordinalBranch() {
        assertEquals(
            "{place}th place",
            IcuMessageRenderer.render("{place, selectordinal, one {#st place} two {#nd place} other {#th place}}")
        )
    }

    @Test
    fun skipsThePluralOffsetToken() {
        assertEquals(
            "{count} others liked this",
            IcuMessageRenderer.render("{count, plural, offset:1 one {# other liked this} other {# others liked this}}")
        )
    }

    @Test
    fun rendersTextSurroundingTheBlock() {
        assertEquals(
            "Cart: {count} articles.",
            IcuMessageRenderer.render("Cart: {count, plural, one {# article} other {# articles}}.")
        )
    }

    @Test
    fun rendersNestedBlocks() {
        assertEquals(
            "They have {count} files",
            IcuMessageRenderer.render(
                "{gender, select, male {He has {count, plural, other {# files}}} " +
                    "other {They have {count, plural, other {# files}}}}"
            )
        )
    }

    @Test
    fun leavesAMessageWithoutIcuUntouched() {
        listOf(
            "Hello world",
            "Bonjour, ça va ?",
            "50% off — today only!",
            ""
        ).forEach { assertEquals(it, IcuMessageRenderer.render(it)) }
    }

    @Test
    fun leavesInterpolationPlaceholdersUntouched() {
        listOf(
            "Hello {{name}}",
            "Hello {name}",
            "{count} items",
            // `{date, date, short}` used to sit here: a formatted argument is now rendered
            // as `{date}`, which `date and time formats show the argument alone` pins.
            "%{count} boxes"
        ).forEach { assertEquals(it, IcuMessageRenderer.render(it)) }
    }

    @Test
    fun fallsBackToTheRawValueOnAnUnclosedBrace() {
        val malformed = "{count, plural, one {# article} other {# articles}"
        assertEquals(malformed, IcuMessageRenderer.render(malformed))
    }

    @Test
    fun fallsBackToTheRawValueOnAnUnopenedBrace() {
        val malformed = "{count} articles}"
        assertEquals(malformed, IcuMessageRenderer.render(malformed))
    }

    @Test
    fun fallsBackToTheRawValueOnAnEmptyBlock() {
        val malformed = "{count, plural, }"
        assertEquals(malformed, IcuMessageRenderer.render(malformed))
    }

    @Test
    fun truncationAppliesAfterRenderingNotBefore() {
        val icu = "{count, plural, one {# article in your cart} other {# articles in your cart}}"
        // Before the fix the raw ICU source was truncated mid-brace, e.g. "{count, plural, one {#".
        assertEquals("{count} articles in ...", IcuMessageRenderer.render(icu).ellipsis(20))
    }

    @Test
    fun theFluentAliasMatchesTheRenderer() {
        val icu = "{count, plural, other {# articles}}"
        assertEquals(IcuMessageRenderer.render(icu), icu.renderIcu())
    }

    // --- ICU quoting -------------------------------------------------------------------

    @Test
    fun `a quoted brace is literal, not an argument`() {
        assertEquals("{count}", IcuMessageRenderer.render("'{'count'}'"))
    }

    @Test
    fun `a doubled apostrophe is a single apostrophe`() {
        assertEquals("l'article {n}", IcuMessageRenderer.render("l''article {n}"))
    }

    @Test
    fun `a lone apostrophe before an ordinary letter stays literal`() {
        assertEquals("L'equipe a {count} membres",
            IcuMessageRenderer.render("L'equipe a {count, plural, other {# membres}}"))
    }

    @Test
    fun `a quoted brace no longer unbalances a pluralised message`() {
        assertEquals("{ {count} articles",
            IcuMessageRenderer.render("'{' {count, plural, other {# articles}}"))
    }

    @Test
    fun `a quoted hash stays literal inside a plural branch`() {
        assertEquals("# {count} articles",
            IcuMessageRenderer.render("{count, plural, other {'#' # articles}}"))
    }

    @Test
    fun `an unterminated quote consumes the rest as literal text`() {
        assertEquals("a {b", IcuMessageRenderer.render("a '{b"))
    }

    // --- formatted arguments -----------------------------------------------------------

    @Test
    fun `a number format shows the argument alone`() {
        assertEquals("{amount}", IcuMessageRenderer.render("{amount, number, currency}"))
    }

    @Test
    fun `a number argument without style shows the argument alone`() {
        assertEquals("{amount}", IcuMessageRenderer.render("{amount, number}"))
    }

    @Test
    fun `date and time formats show the argument alone`() {
        assertEquals("Le {day} a {hour}",
            IcuMessageRenderer.render("Le {day, date, short} a {hour, time, short}"))
    }

    @Test
    fun `a formatted argument nested in a plural branch is rendered too`() {
        assertEquals("{count} articles pour {amount}",
            IcuMessageRenderer.render("{count, plural, other {# articles pour {amount, number, currency}}}"))
    }
}
