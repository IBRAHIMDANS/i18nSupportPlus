package com.ibrahimdans.i18n.plugin.tree

import com.ibrahimdans.i18n.extensions.localization.json.JsonElementTree
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * The rule that keeps a nested plural object from being reported as "Reference to object".
 *
 * i18n-js, ruby-i18n and vue-i18n pluralize through `{ one: …, other: … }` where i18next uses
 * the flat `key_one` suffixes the resolver already expands.
 */
class PluralGroupTest : PlatformBaseTest() {

    private fun nodeOf(json: String, key: String): Tree<PsiElement>? {
        val file = addFileToProject("assets/translation.json", json)
        return ReadAction.compute<Tree<PsiElement>?, RuntimeException> {
            JsonElementTree.create(file)?.findChild(key)
        }
    }

    @Test
    fun anObjectOfPluralCategoriesIsAPluralGroup() {
        val node = nodeOf("""{"box": {"one": "1 boîte", "other": "%{count} boîtes"}}""", "box")

        Assertions.assertTrue(ReadAction.compute<Boolean, RuntimeException> { PluralGroup.isPluralGroup(node) })
    }

    @Test
    fun everySixCategoriesAreAccepted() {
        val node = nodeOf(
            """{"box": {"zero": "0", "one": "1", "two": "2", "few": "3", "many": "5", "other": "n"}}""",
            "box"
        )

        Assertions.assertTrue(ReadAction.compute<Boolean, RuntimeException> { PluralGroup.isPluralGroup(node) })
    }

    /** A namespace that merely holds a key named `other` stays a namespace. */
    @Test
    fun anObjectMixingCategoriesAndKeysIsNotAPluralGroup() {
        val node = nodeOf("""{"menu": {"other": "Other", "home": "Home"}}""", "menu")

        Assertions.assertFalse(ReadAction.compute<Boolean, RuntimeException> { PluralGroup.isPluralGroup(node) })
    }

    @Test
    fun anOrdinaryObjectIsNotAPluralGroup() {
        val node = nodeOf("""{"dashboard": {"title": "T", "subtitle": "S"}}""", "dashboard")

        Assertions.assertFalse(ReadAction.compute<Boolean, RuntimeException> { PluralGroup.isPluralGroup(node) })
    }

    @Test
    fun aLeafIsNotAPluralGroup() {
        val node = nodeOf("""{"title": "T"}""", "title")

        Assertions.assertFalse(ReadAction.compute<Boolean, RuntimeException> { PluralGroup.isPluralGroup(node) })
    }

    @Test
    fun anEmptyObjectIsNotAPluralGroup() {
        val node = nodeOf("""{"empty": {}}""", "empty")

        Assertions.assertFalse(ReadAction.compute<Boolean, RuntimeException> { PluralGroup.isPluralGroup(node) })
    }
}
