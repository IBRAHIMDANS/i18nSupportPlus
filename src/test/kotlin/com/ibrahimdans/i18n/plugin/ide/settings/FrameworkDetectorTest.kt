package com.ibrahimdans.i18n.plugin.ide.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The dependencies of a `package.json` that give away each framework the setup wizard offers. */
class FrameworkDetectorTest {

    private fun packageJson(vararg dependencies: String): String =
        dependencies.joinToString(",\n", "{\n  \"dependencies\": {\n", "\n  }\n}") { "    \"$it\": \"^1.0.0\"" }

    @Test
    fun `every dependency listed gives away its framework, and only it`() {
        for ((framework, dependencies) in FrameworkDetector.FRAMEWORK_KEYS) {
            for (dependency in dependencies) {
                assertEquals(setOf(framework), FrameworkDetector.detect(packageJson(dependency)), dependency)
            }
        }
    }

    /** `@nuxtjs/i18n` bundles `vue-i18n`, which a Nuxt project usually does not declare. */
    @Test
    fun `a Nuxt project is a vue-i18n project`() {
        assertEquals(setOf("vue-i18n"), FrameworkDetector.detect(packageJson("nuxt", "@nuxtjs/i18n")))
    }

    @Test
    fun `a package sharing a framework's prefix is not that framework`() {
        assertTrue(FrameworkDetector.detect(packageJson("react-intl-universal")).isEmpty())
        assertTrue(FrameworkDetector.detect(packageJson("i18next-browser-languagedetector")).isEmpty())
    }

    @Test
    fun `several frameworks are all detected`() {
        assertEquals(
            setOf("i18next", "vue-i18n"),
            FrameworkDetector.detect(packageJson("react-i18next", "@nuxtjs/i18n", "react"))
        )
    }

    @Test
    fun `a project with no i18n dependency detects nothing`() {
        assertTrue(FrameworkDetector.detect(packageJson("react", "vue")).isEmpty())
    }
}
