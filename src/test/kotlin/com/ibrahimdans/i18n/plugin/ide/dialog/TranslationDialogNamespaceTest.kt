package com.ibrahimdans.i18n.plugin.ide.dialog

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The namespace rule behind the "+" button's [com.intellij.openapi.ui.InputValidator].
 *
 * The validator itself is a widget listener and cannot be exercised headlessly, but the rule
 * it enforces can — and it is the part that used to live in a second, after-the-fact error
 * dialog with nothing pinning it. The validator delegates here, so the two cannot disagree.
 */
class TranslationDialogNamespaceTest {

    @Test
    fun `accepts letters digits and hyphens`() {
        listOf("common", "auth", "user-profile", "ns2", "A-1").forEach {
            assertTrue(TranslationDialog.isValidNamespace(it), "expected '$it' to be accepted")
        }
    }

    @Test
    fun `trims before validating`() {
        // The caller trims the accepted value too, so the validator must not refuse what it keeps.
        assertTrue(TranslationDialog.isValidNamespace("  common  "))
    }

    @Test
    fun `rejects an empty or blank name`() {
        listOf(null, "", "   ").forEach {
            assertFalse(TranslationDialog.isValidNamespace(it), "expected '$it' to be rejected")
        }
    }

    @Test
    fun `rejects anything that would not make a file name`() {
        // A namespace names a translation file: a separator or a path segment cannot be one.
        listOf("common.json", "ns/sub", "ns:key", "a b", "üser").forEach {
            assertFalse(TranslationDialog.isValidNamespace(it), "expected '$it' to be rejected")
        }
    }
}
