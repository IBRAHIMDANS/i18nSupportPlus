package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.ide.inspection.KeepList
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeepListTest {

    private fun keepList(vararg rules: String, ns: String = ":", key: String = ".", defaults: List<String> = listOf("translation")) =
        KeepList(rules.toList(), ns, key, defaults)

    @Test
    fun `an exact rule keeps that key only`() {
        val list = keepList("errors.timeout")

        assertTrue(list.matches("errors.timeout"))
        assertFalse(list.matches("errors.timeoutLong"))
        assertFalse(list.matches("errors"))
    }

    @Test
    fun `a rule ending with the key separator keeps everything under it`() {
        val list = keepList("errors.")

        assertTrue(list.matches("errors.timeout"))
        assertTrue(list.matches("errors.network.offline"))
        assertFalse(list.matches("errors"))
        assertFalse(list.matches("errorsOld.timeout"))
    }

    @Test
    fun `a trailing star keeps everything under the prefix`() {
        val list = keepList("errors.*")

        assertTrue(list.matches("errors.timeout"))
        assertTrue(list.matches("errors.network.offline"))
        assertFalse(list.matches("form.errors.timeout"))
    }

    @Test
    fun `a leading star keeps every key ending the same way`() {
        val list = keepList("*.label")

        assertTrue(list.matches("form.name.label"))
        assertFalse(list.matches("form.name.labels"))
    }

    @Test
    fun `a rule without a namespace applies to every namespace`() {
        val list = keepList("errors.*")

        assertTrue(list.matches("common:errors.timeout"))
        assertTrue(list.matches("admin:errors.timeout"))
    }

    @Test
    fun `a rule with a namespace applies to that namespace only`() {
        val list = keepList("common:errors.*")

        assertTrue(list.matches("common:errors.timeout"))
        assertFalse(list.matches("admin:errors.timeout"))
    }

    @Test
    fun `a key without a namespace lives in a default one`() {
        assertTrue(keepList("translation:errors.*").matches("errors.timeout"))
        assertFalse(keepList("common:errors.*").matches("errors.timeout"))
    }

    @Test
    fun `custom separators are honoured`() {
        val list = keepList("common|errors/", ns = "|", key = "/")

        assertTrue(list.matches("common|errors/timeout"))
        assertFalse(list.matches("common|errors.timeout"))
    }

    @Test
    fun `regex characters in a rule are matched literally`() {
        val list = keepList("price.(eur)+", "[a-z")

        assertTrue(list.matches("price.(eur)+"))
        assertFalse(list.matches("price.eureur"))
        assertTrue(list.matches("[a-z"))
    }

    @Test
    fun `blank rules are dropped and an empty list keeps nothing`() {
        val list = keepList("", "   ")

        assertTrue(list.isEmpty())
        assertFalse(list.matches("errors.timeout"))
    }

    @Test
    fun `the settings field is split on commas and semicolons`() {
        assertEquals(listOf("errors.*", "*.label", "common:ok"), KeepList.parse(" errors.* , *.label;common:ok, "))
    }

    @Test
    fun `the list is read from the configuration`() {
        val list = KeepList.of(Config(keptKeys = "errors.*", nsSeparator = ":", keySeparator = "."))

        assertTrue(list.matches("common:errors.timeout"))
        assertFalse(list.matches("common:menu.home"))
    }
}
