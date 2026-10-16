package com.ibrahimdans.i18n.plugin.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** A value of the inlang message format, as folding shows it: a string as is, variants as one branch. */
class InlangMessageRendererTest {

    private fun variants(match: String) = """[{"declarations": ["input count", "local countPlural = count: plural"],
        "selectors": ["countPlural"], "match": {$match}}]"""

    @Test
    fun aStringIsUnchanged() {
        assertEquals("\"Hello {name}!\"", InlangMessageRenderer.preview("\"Hello {name}!\""))
    }

    @Test
    fun aPluralShowsItsOtherBranch() {
        assertEquals(
            "{count} items…",
            InlangMessageRenderer.preview(variants(""""countPlural=one": "One item", "countPlural=other": "{count} items""""))
        )
    }

    /** Several selectors: the branch where every one of them is `*`. */
    @Test
    fun aMatchShowsItsCatchAllBranch() {
        val value = """[{"match": {"platform=android, userGender=male": "Android", "platform=*, userGender=*": "Any"}}]"""
        assertEquals("Any…", InlangMessageRenderer.preview(value))
    }

    @Test
    fun withoutCatchAllTheFirstBranchIsShown() {
        assertEquals("One…", InlangMessageRenderer.preview(variants(""""countPlural=one": "One", "countPlural=two": "Two"""")))
    }

    @Test
    fun aSingleBranchHasNoEllipsis() {
        assertEquals("Only", InlangMessageRenderer.preview(variants(""""countPlural=*": "Only"""")))
    }

    /** Not the inlang shape: left as is for the other renderers. */
    @Test
    fun anotherArrayOrBrokenJsonIsUnchanged() {
        assertEquals("[1, 2]", InlangMessageRenderer.preview("[1, 2]"))
        assertEquals("[{ broken", InlangMessageRenderer.preview("[{ broken"))
    }
}
