package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.psi.PsiFile
import com.intellij.psi.util.parents
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [ancestors] stands in for the platform's `PsiElement.parents(withSelf)`, which the plugin cannot call
 * (absent overload in 2025.2 and 2025.3 once compiled against 2026.2): it must walk the same elements.
 * The tests run on one platform only, so calling `parents` here ships nothing.
 */
class PsiAncestorsTest : PlatformBaseTest() {

    @Test
    fun theWalkIsThePlatformsFromALeaf() {
        val file = myFixture.configureByText("en.json", """{"menu": {"home": "Home"}}""")
        val leaf = file.findElementAt(file.text.indexOf("\"Home\"") + 1)!!
        for (withSelf in listOf(true, false)) {
            assertEquals(leaf.parents(withSelf).toList(), leaf.ancestors(withSelf).toList(), "withSelf = $withSelf")
        }
        assertTrue(leaf.ancestors(true).last() is PsiFile, "the walk ends on the file, never its directory")
    }

    @Test
    fun theFileHasNoAncestor() {
        val file = myFixture.configureByText("en.json", "{}")
        assertEquals(listOf(file), file.ancestors(true).toList())
        assertEquals(emptyList<Any>(), file.ancestors(false).toList())
    }
}
