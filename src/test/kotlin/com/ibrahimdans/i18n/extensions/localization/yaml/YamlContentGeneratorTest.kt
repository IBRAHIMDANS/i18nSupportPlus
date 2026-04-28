package com.ibrahimdans.i18n.extensions.localization.yaml

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test

class YamlContentGeneratorTest : PlatformBaseTest() {

    private val generator = YamlLocalization().contentGenerator()

    @Test
    fun testGenerateWithEmptyUnresolved_doesNotThrow() {
        myFixture.configureByText("test.yml", "key: value\n")
        val element = myFixture.file
        val fullKey = FullKey("key", null, listOf(Literal("key")))
        assertDoesNotThrow {
            generator.generate(element, fullKey, emptyList(), null)
        }
    }
}
