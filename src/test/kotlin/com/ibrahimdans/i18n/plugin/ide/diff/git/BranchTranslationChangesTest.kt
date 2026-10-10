package com.ibrahimdans.i18n.plugin.ide.diff.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The branch *Compare Translations with Branch* offers first. */
class BranchTranslationChangesTest {

    @Test
    fun `the main line comes before the current branch's own upstream`() {
        // origin/feature only holds what was not pushed yet: a pull request is reviewed against main.
        assertEquals(
            "origin/main",
            BranchTranslationChanges.defaultBase(listOf("feature", "origin/feature", "origin/main"), tracked = "origin/feature")
        )
    }

    @Test
    fun `master and develop are main lines too`() {
        assertEquals("origin/master", BranchTranslationChanges.defaultBase(listOf("origin/master", "origin/x"), null))
        assertEquals("origin/develop", BranchTranslationChanges.defaultBase(listOf("origin/develop", "origin/x"), null))
    }

    @Test
    fun `without a main line, the upstream, then the first branch`() {
        assertEquals("origin/x", BranchTranslationChanges.defaultBase(listOf("a", "origin/x"), tracked = "origin/x"))
        assertEquals("a", BranchTranslationChanges.defaultBase(listOf("a", "b"), tracked = "origin/gone"))
    }

    @Test
    fun `no branch, no default`() {
        assertNull(BranchTranslationChanges.defaultBase(emptyList(), null))
    }
}
