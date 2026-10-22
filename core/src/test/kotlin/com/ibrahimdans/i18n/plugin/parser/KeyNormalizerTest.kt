package com.ibrahimdans.i18n.plugin.parser

import com.ibrahimdans.i18n.plugin.utils.KeyElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class KeyNormalizerTest {

    // The values of CompletionInitializationContext.DUMMY_IDENTIFIER and DUMMY_IDENTIFIER_TRIMMED.
    private val dummy = "IntellijIdeaRulezzz "
    private val trimmed = "IntellijIdeaRulezzz"

    @Test
    fun normalizeDummy() {
        val element = KeyElement.literal("valid:ROOT.Key1.$dummy")
        val normalized = DummyTextNormalizer(dummy, trimmed).normalize(element)
        assertEquals("valid:ROOT.Key1.$trimmed", normalized?.text)
    }
}
