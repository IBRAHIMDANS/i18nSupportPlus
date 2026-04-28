package com.ibrahimdans.i18n.extensions.localization.js

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test

class TsContentGeneratorTest : PlatformBaseTest() {

    private val generator = TsContentGenerator()

    @Test
    fun testGenerateWithEmptyUnresolved_doesNotThrow() {
        myFixture.configureByText("test.ts", "export const x = {}")
        val element = myFixture.file
        val fullKey = FullKey("key", null, listOf(Literal("key")))
        assertDoesNotThrow {
            generator.generate(element, fullKey, emptyList(), null)
        }
    }
}
