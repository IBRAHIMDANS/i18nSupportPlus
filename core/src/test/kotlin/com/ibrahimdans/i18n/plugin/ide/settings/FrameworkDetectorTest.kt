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

    /** `@inlang/sdk` is a dependency of every inlang tool, not only of Paraglide. */
    @Test
    fun `the inlang sdk alone is not a Paraglide project`() {
        assertTrue(FrameworkDetector.detect(packageJson("@inlang/sdk")).isEmpty())
        assertEquals(setOf("paraglide"), FrameworkDetector.detect(packageJson("@inlang/sdk", "@inlang/paraglide-js")))
    }

    @Test
    fun `the Paraglide preset reads messages per locale at the project root`() {
        val layout = LayoutPresets.of("paraglide")!!
        assertEquals("messages/{lang}.json", layout.pathTemplate)
        assertEquals("{key}", layout.keyTemplate)
        assertEquals("Paraglide JS (inlang)", FrameworkDetector.LABELS["paraglide"])
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

    private fun composerJson(vararg packages: String): String =
        packages.joinToString(",\n", "{\n  \"require\": {\n", "\n  }\n}") { "    \"$it\": \"^11.0\"" }

    @Test
    fun `a composer json requiring laravel framework is a Laravel project`() {
        assertEquals(setOf("laravel"), FrameworkDetector.detect(composerJson("php", "laravel/framework", "laravel/tinker")))
    }

    /** Laravel's satellites, or its translation component alone, do not bring the framework's layout. */
    @Test
    fun `a composer json without laravel framework detects nothing`() {
        assertTrue(FrameworkDetector.detect(composerJson("php", "symfony/translation")).isEmpty())
        assertTrue(FrameworkDetector.detect(composerJson("laravel/tinker", "illuminate/translation")).isEmpty())
    }

    @Test
    fun `the Laravel preset fills the documented layout`() {
        val layout = LayoutPresets.of("laravel")!!
        assertEquals("lang/{lang}/{ns}.php", layout.pathTemplate)
        assertEquals("{ns}.{key}", layout.keyTemplate)
        assertTrue("laravel" in FrameworkDetector.LABELS, "offered by the wizard and by the module presets")
    }
}
