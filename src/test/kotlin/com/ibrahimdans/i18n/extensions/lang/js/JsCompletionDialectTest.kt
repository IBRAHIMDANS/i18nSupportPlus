package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.codeInsight.completion.CompletionContributor
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class JsCompletionDialectTest : PlatformBaseTest() {

    // Contributors are inherited by dialects: one declared on a dialect as well as on JavaScript runs
    // twice for that dialect's files.
    @Test
    fun contributorIsRegisteredOncePerDialect() {
        for (ext in listOf("js", "jsx", "ts", "tsx")) {
            val language = myFixture.configureByText("dialect.$ext", "").language
            val count = CompletionContributor.forLanguage(language).count { it is JsCompletionContributor }
            Assertions.assertEquals(1, count, ".$ext (${language.id}) must see the JS completion contributor exactly once")
        }
    }
}
