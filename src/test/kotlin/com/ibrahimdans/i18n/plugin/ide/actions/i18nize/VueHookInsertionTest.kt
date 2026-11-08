package com.ibrahimdans.i18n.plugin.ide.actions.i18nize

import com.ibrahimdans.i18n.plugin.rules.FrameworkPresets
import com.ibrahimdans.i18n.plugin.rules.HookGives
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/** The composable *Internationalize…* inserts in a `<script setup>` is the one the vue-i18n preset names. */
class VueHookInsertionTest {

    @Test
    fun theInsertedHookIsTheVueI18nPresetHook() {
        Assertions.assertEquals("useI18n", VueHookInsertion.HOOK)
        Assertions.assertEquals(FrameworkPresets.VUE_I18N.hooks.single().name, VueHookInsertion.HOOK)
        Assertions.assertTrue(VueHookInsertion.HOOK in FrameworkPresets.hookNames(HookGives.NAMESPACES))
    }
}
