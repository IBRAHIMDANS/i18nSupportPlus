package com.ibrahimdans.i18n.plugin.ide.actions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeysSynchronizerTest {

    private val synchronizer = KeysSynchronizer()

    @Test
    fun buildFullKey_normalKey_parsesNamespaceAndPath() {
        val result = synchronizer.buildFullKey("common:menu.home")
        assertEquals("common", result.ns?.text)
        assertEquals(listOf("menu", "home"), result.compositeKey.map { it.text })
    }

    @Test
    fun buildFullKey_noNamespace_parsesPathOnly() {
        val result = synchronizer.buildFullKey("menu.home")
        assertEquals(null, result.ns)
        assertEquals(listOf("menu", "home"), result.compositeKey.map { it.text })
    }

    @Test
    fun buildFullKey_namespaceOnly_producesEmptyCompositeKey() {
        val result = synchronizer.buildFullKey("common:")
        assertEquals("common", result.ns?.text)
        assertTrue(result.compositeKey.isEmpty())
    }

    @Test
    fun buildFullKey_emptyKey_producesEmptyCompositeKey() {
        val result = synchronizer.buildFullKey("")
        assertEquals(null, result.ns)
        assertTrue(result.compositeKey.isEmpty())
    }
}
