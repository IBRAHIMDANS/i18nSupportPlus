package com.ibrahimdans.i18n.plugin.ide.references.code

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.utils.unQuote
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * Navigation tests for Vue SFC <template> blocks.
 *
 * These tests verify that i18n key references inside {{ $t('key') }} expressions
 * in Vue templates are resolved to the correct translation value, using the
 * JsReferenceContributor registered for the "Vue" language in vueConfig.xml.
 *
 * Known risk: JsReferenceContributor uses JSPatterns.jsArgument("$t", 0) to identify
 * key string literals. Inside a Vue <template>, string literals within {{ $t(...) }}
 * are parsed as JavaScript by the Vue plugin (language injection), so the pattern
 * should match. If it does not, the tests will be @Disabled with an explanation.
 */
class ReferencesTestVue : PlatformBaseTest() {

    @Test
    fun testReferenceInVueTemplate() {
        addFileToProject(
            "assets/test.json",
            """{"ref": {"section": {"key": "Reference in json"}}}"""
        )
        myFixture.configureByText(
            "test.vue",
            """<template><h1>{{ ${"$"}t('test:ref.section.key<caret>') }}</h1></template>"""
        )
        read {
            val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
            assertNotNull("Element at caret should not be null", element)
            // If JSPatterns.jsArgument does not match Vue template context, references will be empty.
            // The test documents whether the integration works end-to-end.
            assertTrue(
                "Expected at least one reference from Vue template to translation file",
                element!!.references.isNotEmpty()
            )
            assertEquals(
                "Reference should resolve to translation value",
                "Reference in json",
                element.references[0].resolve()?.text?.unQuote()
            )
        }
    }

    @Test
    fun testDefaultNsReferenceInVueTemplate() {
        addFileToProject(
            "assets/translation.json",
            """{"ref": {"section": {"key": "Default ns reference"}}}"""
        )
        myFixture.configureByText(
            "test.vue",
            """<template><h1>{{ ${"$"}t('ref.section.key<caret>') }}</h1></template>"""
        )
        read {
            val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
            assertNotNull("Element at caret should not be null", element)
            assertTrue(
                "Expected at least one reference from Vue template to translation file",
                element!!.references.isNotEmpty()
            )
            assertEquals(
                "Reference should resolve to translation value",
                "Default ns reference",
                element.references[0].resolve()?.text?.unQuote()
            )
        }
    }
}
