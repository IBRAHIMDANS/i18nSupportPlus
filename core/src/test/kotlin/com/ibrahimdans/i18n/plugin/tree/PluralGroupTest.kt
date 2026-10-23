package com.ibrahimdans.i18n.plugin.tree

import com.ibrahimdans.i18n.plugin.format.JsonTranslationTree
import com.ibrahimdans.i18n.plugin.format.ParsedNode
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * The rule that keeps a nested plural object from being reported as "Reference to object".
 *
 * i18n-js, ruby-i18n and vue-i18n pluralize through `{ one: …, other: … }` where i18next uses
 * the flat `key_one` suffixes the resolver already expands.
 */
class PluralGroupTest {

    private fun nodeOf(json: String, key: String): Tree<ParsedNode>? = JsonTranslationTree.parse(json)?.findChild(key)

    @Test
    fun anObjectOfPluralCategoriesIsAPluralGroup() {
        val node = nodeOf("""{"box": {"one": "1 boîte", "other": "%{count} boîtes"}}""", "box")

        Assertions.assertTrue(PluralGroup.isPluralGroup(node))
    }

    @Test
    fun everySixCategoriesAreAccepted() {
        val node = nodeOf(
            """{"box": {"zero": "0", "one": "1", "two": "2", "few": "3", "many": "5", "other": "n"}}""",
            "box"
        )

        Assertions.assertTrue(PluralGroup.isPluralGroup(node))
    }

    /** A namespace that merely holds a key named `other` stays a namespace. */
    @Test
    fun anObjectMixingCategoriesAndKeysIsNotAPluralGroup() {
        val node = nodeOf("""{"menu": {"other": "Other", "home": "Home"}}""", "menu")

        Assertions.assertFalse(PluralGroup.isPluralGroup(node))
    }

    @Test
    fun anOrdinaryObjectIsNotAPluralGroup() {
        val node = nodeOf("""{"dashboard": {"title": "T", "subtitle": "S"}}""", "dashboard")

        Assertions.assertFalse(PluralGroup.isPluralGroup(node))
    }

    @Test
    fun aLeafIsNotAPluralGroup() {
        val node = nodeOf("""{"title": "T"}""", "title")

        Assertions.assertFalse(PluralGroup.isPluralGroup(node))
    }

    @Test
    fun anEmptyObjectIsNotAPluralGroup() {
        val node = nodeOf("""{"empty": {}}""", "empty")

        Assertions.assertFalse(PluralGroup.isPluralGroup(node))
    }

    // -----------------------------------------------------------------------
    // displayableValue — the single entry point folding, hints and inlays use
    // -----------------------------------------------------------------------

    @Test
    fun displayableValueOfAPluralGroupIsItsOtherBranch() {
        val node = nodeOf("""{"box": {"one": "1 boîte", "other": "%{count} boîtes"}}""", "box")

        val displayed = PluralGroup.displayableValue(node)?.value()?.value
        Assertions.assertEquals("%{count} boîtes", displayed)
    }

    /** Without `other`, the first category declared stands for the group. */
    @Test
    fun displayableValueFallsBackToTheFirstCategory() {
        val node = nodeOf("""{"box": {"few": "quelques", "many": "beaucoup"}}""", "box")

        val displayed = PluralGroup.displayableValue(node)?.value()?.value
        Assertions.assertEquals("quelques", displayed)
    }

    @Test
    fun displayableValueOfALeafIsTheLeafItself() {
        val node = nodeOf("""{"title": "T"}""", "title")

        Assertions.assertNotNull(PluralGroup.displayableValue(node))
    }

    @Test
    fun anOrdinaryObjectHasNothingToDisplay() {
        val node = nodeOf("""{"dashboard": {"title": "T", "subtitle": "S"}}""", "dashboard")

        Assertions.assertNull(PluralGroup.displayableValue(node))
    }

    /** A value is displayed, never a sub-tree: a branch holding an object shows nothing. */
    @Test
    fun aBranchThatIsItselfAnObjectIsNotDisplayed() {
        val node = nodeOf("""{"box": {"other": {"nested": "value"}}}""", "box")

        Assertions.assertNull(PluralGroup.displayableValue(node))
    }
}
