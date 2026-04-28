package com.ibrahimdans.i18n.extensions.localization.json

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test

class JsonContentGeneratorTest : PlatformBaseTest() {

    private val generator = JsonLocalization().contentGenerator()

    @Test
    fun testGenerateWithEmptyUnresolved_doesNotThrow() {
        myFixture.configureByText("test.json", "{}")
        val element = myFixture.file
        val fullKey = FullKey("key", null, listOf(Literal("key")))
        assertDoesNotThrow {
            generator.generate(element, fullKey, emptyList(), null)
        }
    }
}
