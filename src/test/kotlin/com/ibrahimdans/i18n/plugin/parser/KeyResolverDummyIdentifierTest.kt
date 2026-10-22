package com.ibrahimdans.i18n.plugin.parser

import com.intellij.codeInsight.completion.CompletionInitializationContext
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * [KeyResolver] lives in `core`, out of the platform's reach, and so writes the completion
 * identifier itself: a key typed at the caret must still be normalized if the platform renames it.
 */
class KeyResolverDummyIdentifierTest {

    @Test
    fun `the completion identifiers are the platform's`() {
        Assertions.assertEquals(CompletionInitializationContext.DUMMY_IDENTIFIER, KeyResolver.DUMMY_IDENTIFIER)
        Assertions.assertEquals(CompletionInitializationContext.DUMMY_IDENTIFIER_TRIMMED, KeyResolver.DUMMY_IDENTIFIER_TRIMMED)
    }
}
