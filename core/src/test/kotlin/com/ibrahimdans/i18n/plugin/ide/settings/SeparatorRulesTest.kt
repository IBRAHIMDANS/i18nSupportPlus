package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.ide.settings.SeparatorRules.Fault
import com.ibrahimdans.i18n.plugin.ide.settings.SeparatorRules.Problem
import com.ibrahimdans.i18n.plugin.ide.settings.SeparatorRules.Separator
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * The separator rule, on the values the settings used to accept and read wrongly.
 *
 * The tokenizer splits on each character of a separator and the annotator counts one character
 * per separator, so `"::"` or `"__"` broke keys and highlights silently. The rule is what stands
 * between the settings and the pipeline: a value it lets through is one the pipeline reads as meant.
 */
class SeparatorRulesTest {

    @Test
    fun `the defaults pass`() {
        Assertions.assertEquals(emptyList<Problem>(), SeparatorRules.check(":", ".", "-"))
    }

    @Test
    fun `a two-character namespace separator is refused as several characters`() {
        Assertions.assertEquals(
            listOf(Problem(Separator.NAMESPACE, Fault.SEVERAL_CHARACTERS, "::")),
            SeparatorRules.check("::", ".", "-")
        )
    }

    @Test
    fun `a two-character key separator is refused as several characters`() {
        Assertions.assertEquals(
            listOf(Problem(Separator.KEY, Fault.SEVERAL_CHARACTERS, "__")),
            SeparatorRules.check(":", "__", "-")
        )
    }

    @Test
    fun `a two-character plural separator is refused as several characters`() {
        Assertions.assertEquals(
            listOf(Problem(Separator.PLURAL, Fault.SEVERAL_CHARACTERS, "--")),
            SeparatorRules.check(":", ".", "--")
        )
    }

    @Test
    fun `the namespace and key separators cannot be the same character, reported once on the key`() {
        Assertions.assertEquals(
            listOf(Problem(Separator.KEY, Fault.SAME_AS_ANOTHER, ".", other = Separator.NAMESPACE)),
            SeparatorRules.check(".", ".", "-")
        )
    }

    @Test
    fun `only the namespace separator may be empty`() {
        Assertions.assertEquals(emptyList<Problem>(), SeparatorRules.check("", ".", "-"))
        Assertions.assertEquals(
            listOf(Problem(Separator.KEY, Fault.EMPTY, "")),
            SeparatorRules.check(":", "", "-")
        )
        Assertions.assertEquals(
            listOf(Problem(Separator.PLURAL, Fault.EMPTY, "")),
            SeparatorRules.check(":", ".", "")
        )
    }

    @Test
    fun `an empty key separator is not the same as an empty namespace separator`() {
        Assertions.assertEquals(
            listOf(Problem(Separator.KEY, Fault.EMPTY, "")),
            SeparatorRules.check("", "", "-")
        )
    }

    @Test
    fun `a space or a template character is refused`() {
        Assertions.assertEquals(
            listOf(Problem(Separator.NAMESPACE, Fault.REFUSED_CHARACTER, " ")),
            SeparatorRules.check(" ", ".", "-")
        )
        Assertions.assertEquals(
            listOf(Problem(Separator.KEY, Fault.REFUSED_CHARACTER, "{")),
            SeparatorRules.check(":", "{", "-")
        )
        Assertions.assertEquals(
            listOf(Problem(Separator.PLURAL, Fault.REFUSED_CHARACTER, "`")),
            SeparatorRules.check(":", ".", "`")
        )
    }

    @Test
    fun `every problem is listed, namespace first`() {
        Assertions.assertEquals(
            listOf(
                Problem(Separator.NAMESPACE, Fault.SEVERAL_CHARACTERS, "::"),
                Problem(Separator.KEY, Fault.REFUSED_CHARACTER, "$"),
                Problem(Separator.PLURAL, Fault.EMPTY, "")
            ),
            SeparatorRules.check("::", "$", "")
        )
    }
}
