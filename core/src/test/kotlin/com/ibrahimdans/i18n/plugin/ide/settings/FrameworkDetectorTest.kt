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
        assertTrue(FrameworkDetector.detect(composerJson("php", "symfony/console")).isEmpty())
        assertTrue(FrameworkDetector.detect(composerJson("laravel/tinker", "illuminate/translation")).isEmpty())
    }

    /** `composer require translation` adds the component: a Symfony application without it translates nothing. */
    @Test
    fun `a composer json requiring symfony translation is a Symfony project`() {
        assertEquals(setOf("symfony"), FrameworkDetector.detect(composerJson("php", "symfony/framework-bundle", "symfony/translation")))
        assertTrue(FrameworkDetector.detect(composerJson("php", "symfony/framework-bundle")).isEmpty())
    }

    @Test
    fun `the Symfony preset fills the documented layout`() {
        val layout = LayoutPresets.of("symfony")!!
        assertEquals("translations/{ns}.{lang}.yaml", layout.pathTemplate)
        assertEquals("{key}", layout.keyTemplate)
        assertTrue("symfony" in FrameworkDetector.LABELS, "offered by the wizard and by the module presets")
    }

    @Test
    fun `the Laravel preset fills the documented layout`() {
        val layout = LayoutPresets.of("laravel")!!
        assertEquals("lang/{lang}/{ns}.php", layout.pathTemplate)
        assertEquals("{ns}.{key}", layout.keyTemplate)
        assertTrue("laravel" in FrameworkDetector.LABELS, "offered by the wizard and by the module presets")
    }

    /** Spring is given away by its group in a Maven or Gradle build, whatever the artifact or the DSL. */
    @Test
    fun `a Maven or Gradle build naming a Spring artifact is a Spring project`() {
        val pom = """
            <project><dependencies><dependency>
              <groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId>
            </dependency></dependencies></project>
        """.trimIndent()
        assertEquals(setOf(FrameworkDetector.SPRING), FrameworkDetector.detect(pom))
        assertEquals(setOf(FrameworkDetector.SPRING), FrameworkDetector.detect("""plugins { id("org.springframework.boot") version "3.3.0" }"""))
        assertEquals(setOf(FrameworkDetector.SPRING), FrameworkDetector.detect("dependencies { implementation 'org.springframework:spring-context:6.1.0' }"))
    }

    @Test
    fun `a build naming no Spring artifact detects nothing`() {
        assertTrue(FrameworkDetector.detect("<project><groupId>com.example</groupId><artifactId>springfield</artifactId></project>").isEmpty())
        assertTrue(FrameworkDetector.detect("dependencies { implementation 'com.google.guava:guava:33.0.0-jre' }").isEmpty())
    }

    /** A bundle is placed by its name (`messages_fr.properties`): the Spring preset names the framework, no layout. */
    @Test
    fun `the Spring preset is offered and documents no layout`() {
        assertTrue(FrameworkDetector.SPRING in FrameworkDetector.LABELS, "offered by the wizard and by the module presets")
        assertEquals(null, LayoutPresets.of(FrameworkDetector.SPRING))
    }

    /** Angular's own i18n: the projects of an `angular.json` listing their translations, `sourceLocale` an object or not. */
    @Test
    fun `an angular json listing translations is Angular's own i18n`() {
        val angularJson = """
            { "projects": { "shop": {
              "i18n": { "sourceLocale": { "code": "en-US", "baseHref": "" }, "locales": { "fr": "src/locale/messages.fr.xlf" } },
              "architect": { "build": { "options": { "localize": true } } } } } }
        """.trimIndent()
        assertEquals(setOf(FrameworkDetector.ANGULAR_I18N), FrameworkDetector.detect(angularJson))
        assertTrue(FrameworkDetector.ANGULAR_I18N in FrameworkDetector.LABELS, "offered by the wizard and by the module presets")
    }

    /** `sourceLocale` alone sets the locale data of an app another library translates, ngx-translate's here. */
    @Test
    fun `an angular json setting only its source locale is not Angular's own i18n`() {
        assertTrue(FrameworkDetector.detect("""{ "projects": { "shop": { "i18n": { "sourceLocale": "fr" } } } }""").isEmpty())
        assertTrue(FrameworkDetector.detect(packageJson("@angular/core", "@angular/common")).isEmpty())
    }
}
