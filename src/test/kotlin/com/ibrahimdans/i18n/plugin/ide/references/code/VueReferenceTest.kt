package com.ibrahimdans.i18n.plugin.ide.references.code

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.elementAt
import com.ibrahimdans.i18n.plugin.ide.runVue
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.generator.code.VueCodeGenerator
import com.ibrahimdans.i18n.plugin.utils.generator.translation.JsonTranslationGenerator
import com.ibrahimdans.i18n.plugin.utils.generator.translation.TranslationGenerator
import com.ibrahimdans.i18n.plugin.utils.generator.translation.YamlTranslationGenerator
import com.ibrahimdans.i18n.plugin.utils.unQuote
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * Vue single-file components, which the plugin has supported and declared since 1.1 without a
 * single test: `VueI18nTechnology`, `vueConfig.xml`, five entries in `plugin.xml` and a bundled
 * plugin in `build.gradle.kts`, all unexercised.
 *
 * Every assertion is qualified as `Assertions.…`: `BasePlatformTestCase` inherits JUnit 3's
 * `assertEquals(message, expected, actual)`, whose argument order is the reverse of the Jupiter
 * one and which silently wins over a static import — reporting the message as the actual value.
 */
class VueReferenceTest : PlatformBaseTest() {

    private val cg = VueCodeGenerator()
    private val json = JsonTranslationGenerator()

    private companion object {
        const val KEY = "test:ref.section.key"

        @JvmStatic
        fun translationGenerators(): List<TranslationGenerator> =
            listOf(JsonTranslationGenerator(), YamlTranslationGenerator())
    }

    private fun seed(tg: TranslationGenerator) = addFileToProject(
        "assets/test.${tg.ext()}",
        tg.generateContent("ref", "section", "key", "Reference in ${tg.ext()}")
    )

    @ParameterizedTest
    @MethodSource("translationGenerators")
    fun `a key used in the script block resolves`(tg: TranslationGenerator) = runVue {
        seed(tg)
        myFixture.configureByText("Script.${cg.ext()}", cg.generate("'$KEY'"))

        read {
            val element = myFixture.elementAt(myFixture.file, KEY)
            Assertions.assertNotNull(element, "no PSI element carries the key")
            Assertions.assertEquals(
                "Reference in ${tg.ext()}", element!!.references.firstOrNull()?.resolve()?.text?.unQuote(),
                "the key in <script> did not resolve"
            )
        }
    }

    /**
     * A `{{ }}` interpolation is an `XmlText` holding an injected JS file, so the reference lives
     * in the injected PSI and not in the host — a different path from the script block, and the
     * one a user actually clicks through in a template.
     */
    @Test
    fun `a key used in a template interpolation resolves`() = runVue {
        seed(json)
        myFixture.configureByText("Template.${cg.ext()}", cg.generateTemplate("'$KEY'"))

        read {
            val element = myFixture.elementAt(myFixture.file, KEY)
            Assertions.assertNotNull(element, "no PSI element carries the key")
            Assertions.assertEquals(
                "Reference in json", element!!.references.firstOrNull()?.resolve()?.text?.unQuote(),
                "the key in a {{ }} interpolation did not resolve"
            )
        }
    }

    /**
     * The definition of done. Annotation goes through `Lang.canExtractKey`, which is handed the
     * names every `Technology` publishes — and `$t`, `$tc`, `$te` are published by
     * `VueI18nTechnology` alone. Unplug it and an unknown key stops being reported here.
     *
     * `$tc` and `$te` matter more than `$t` for that: `$t` is also hardcoded in
     * `JsReferenceAssistant`'s pattern, so it would keep resolving without the technology.
     */
    @ParameterizedTest
    @ValueSource(strings = ["\$t", "\$tc", "\$te"])
    fun `an unknown key is reported for every vue-i18n function name`(function: String) = runVue {
        seed(json)
        myFixture.configureByText("Names.${cg.ext()}", cg.generateWith(function, "'test:nope.missing'"))

        val unresolved = PluginBundle.getMessage("annotator.unresolved.key")
        val reported = myFixture.doHighlighting().mapNotNull { it.description }

        Assertions.assertTrue(
            reported.contains(unresolved),
            "$function raised no '$unresolved' — VueI18nTechnology is the only technology declaring it, got $reported"
        )
    }

    /**
     * `$tc` and `$te` used to be annotated but carry no reference, because
     * `JsReferenceAssistant.pattern()` hardcoded `t` and `$t` rather than reading the names the
     * technologies publish. The README announces all three; now all three navigate.
     */
    @ParameterizedTest
    @ValueSource(strings = ["\$t", "\$tc", "\$te"])
    fun `every vue-i18n function name resolves a key`(function: String) = runVue {
        seed(json)
        myFixture.configureByText("Names.${cg.ext()}", cg.generateWith(function, "'$KEY'"))

        read {
            val element = myFixture.elementAt(myFixture.file, KEY)
            Assertions.assertEquals(
                "Reference in json", element?.references?.firstOrNull()?.resolve()?.text?.unQuote(),
                "$function did not resolve"
            )
        }
    }
}
