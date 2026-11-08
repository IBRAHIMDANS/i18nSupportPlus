package com.ibrahimdans.i18n.plugin.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PresetNamesCoverageTest {

    @Test
    fun theHookNamesAreEveryPresetsHooks() {
        assertEquals(FrameworkPresets.ALL.flatMap { it.hooks }.map { it.name }.toSet(), FrameworkPresets.hookNames())
        assertEquals(setOf("useTranslation", "useTranslations", "getTranslations", "useI18n"), FrameworkPresets.hookNames())
    }

    @Test
    fun theHooksGivingNamespacesAreSplitFromThoseGivingAPrefix() {
        assertEquals(setOf("useTranslation", "useI18n"), FrameworkPresets.hookNames(HookGives.NAMESPACES))
        assertEquals(setOf("useTranslations", "getTranslations"), FrameworkPresets.hookNames(HookGives.KEY_PREFIX))
    }

    @Test
    fun theI18nextHookIsTheOneWithAKeyPrefixOption() {
        val withPrefix = FrameworkPresets.ALL.flatMap { it.hooks }.filter { it.keyPrefixOption != null }
        assertEquals(listOf(FrameworkPresets.I18NEXT_HOOK), withPrefix.map { it.name })
        assertEquals("useTranslation", FrameworkPresets.I18NEXT_HOOK)
    }

    @Test
    fun theNamespaceCallsAreNoHook() {
        assertTrue(FrameworkPresets.I18NEXT_NAMESPACE_CALLS.none { it in FrameworkPresets.hookNames() })
    }
}
