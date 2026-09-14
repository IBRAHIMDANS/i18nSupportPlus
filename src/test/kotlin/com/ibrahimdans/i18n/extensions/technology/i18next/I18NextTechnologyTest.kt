package com.ibrahimdans.i18n.extensions.technology.i18next

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.intellij.openapi.application.ReadAction
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Regression tests for [I18NextTechnology.findInitObject].
 *
 * It used to locate the `resources` property with `.first()`, so an i18next config
 * without an inline `resources` object — the norm when translations are fetched at
 * runtime (i18next-http-backend and friends) — threw NoSuchElementException. Since
 * this runs inside `findSourcesByConfiguration`, which every key resolution goes
 * through, the plugin broke wholesale on such projects.
 */
class I18NextTechnologyTest : PlatformBaseTest() {

    private val technology = I18NextTechnology()

    @Test
    fun findInitObject_returnsNull_whenConfigHasNoInlineResources() {
        // A backend-loading config: no `resources` key anywhere.
        val file = addFileToProject(
            "src/i18n.ts",
            """
            import i18n from 'i18next';
            import HttpBackend from 'i18next-http-backend';

            i18n.use(HttpBackend).init({
              fallbackLng: 'en',
              backend: { loadPath: '/locales/{{lng}}/{{ns}}.json' },
            });
            """.trimIndent()
        )

        val init = ReadAction.compute<Any?, RuntimeException> { technology.findInitObject(file) }

        Assertions.assertNull(init, "must return null, not throw, when no inline resources object exists")
    }

    @Test
    fun findInitObject_findsTheInitObject_whenResourcesArePresent() {
        val file = addFileToProject(
            "src/i18n.ts",
            """
            import i18n from 'i18next';
            import common from './locales/en/common.json';

            i18n.init({
              fallbackLng: 'en',
              resources: { en: { common } },
            });
            """.trimIndent()
        )

        val init = ReadAction.compute<Any?, RuntimeException> { technology.findInitObject(file) }

        Assertions.assertNotNull(init, "the init object holding `resources` must be found")
    }

    /** A configured path is read even though no word search ever ran, and a JS config counts too. */
    @Test
    fun configuredConfigurationFileProvidesItsResources() = myFixture.runWithConfig(Config(jsConfiguration = "src/i18n.js")) {
        addFileToProject(
            "src/i18n.js",
            """
            i18n.init({
              resources: { en: { translation: { hello: 'Hi' } } },
            });
            """.trimIndent()
        )

        val files = ReadAction.compute<List<String>, RuntimeException> { technology.configFiles(project, Settings.getInstance(project).config()).map { it.name } }

        Assertions.assertEquals(listOf("i18n.js"), files)
    }

    @Test
    fun aMissingConfiguredPathYieldsNothingAndNeverThrows() = myFixture.runWithConfig(Config(jsConfiguration = "nope/i18n.ts, ")) {
        val sources = ReadAction.compute<Int, RuntimeException> { technology.findSourcesByConfiguration(project).size }
        Assertions.assertEquals(0, sources)
    }
}
