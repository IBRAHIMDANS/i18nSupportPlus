package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test

class KeyCreatorTest : PlatformBaseTest() {

    @Test
    fun testCreateKey_noNamespaceInKey_doesNotThrow() {
        // FullKey with ns=null exercises the defaultNamespaces().firstOrNull() ?: "common" path.
        // In a test project with no translation files, files will be empty → fileName assignment runs.
        myFixture.configureByText("test.ts", "const t = (k: string) => k")
        val fullKey = FullKey("key", null, listOf(Literal("key")))
        assertDoesNotThrow {
            KeyCreator().createKey(myFixture.project, fullKey, "key", myFixture.editor) {}
        }
    }
}
