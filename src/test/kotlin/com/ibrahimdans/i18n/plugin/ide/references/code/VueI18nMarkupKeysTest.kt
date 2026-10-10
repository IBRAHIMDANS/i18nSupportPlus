package com.ibrahimdans.i18n.plugin.ide.references.code

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.elementAt
import com.ibrahimdans.i18n.plugin.ide.runVue
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.generator.translation.JsonTranslationGenerator
import com.ibrahimdans.i18n.plugin.utils.unQuote
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * vue-i18n's template keys outside `$t`: `<i18n-t keypath>` and the `v-t` directive, as a string
 * or as the `path` of an object (issue #363). They had no navigation and no unknown-key report.
 *
 * Every assertion is qualified as `Assertions.…`, as in [VueReferenceTest].
 */
class VueI18nMarkupKeysTest : PlatformBaseTest() {

    private val json = JsonTranslationGenerator()

    private companion object {
        const val KEY = "test:ref.section.key"
        const val MISSING = "test:nope.missing"

        /** Each form, `%s` standing for the quoted-less key. */
        const val KEYPATH = """<i18n-t keypath="%s" tag="p"><b>x</b></i18n-t>"""
        const val KEYPATH_PASCAL = """<I18nT keypath="%s" tag="p"></I18nT>"""
        const val DIRECTIVE = """<p v-t="'%s'"></p>"""
        const val DIRECTIVE_OBJECT = """<p v-t="{ path: '%s', args: { name } }"></p>"""
    }

    private fun seed() = addFileToProject("assets/test.json", json.generateContent("ref", "section", "key", "Reference in json"))

    private fun component(markup: String) = "<template>\n  $markup\n</template>\n"

    @ParameterizedTest
    @ValueSource(strings = [KEYPATH, KEYPATH_PASCAL, DIRECTIVE, DIRECTIVE_OBJECT])
    fun `a template key resolves to its translation`(form: String) = runVue {
        seed()
        myFixture.configureByText("Markup.vue", component(form.format(KEY)))

        read {
            val element = myFixture.elementAt(myFixture.file, KEY)
            Assertions.assertNotNull(element, "no PSI element carries the key")
            Assertions.assertEquals(
                "Reference in json", element!!.references.firstOrNull()?.resolve()?.text?.unQuote(),
                "$form did not resolve"
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [KEYPATH, KEYPATH_PASCAL, DIRECTIVE, DIRECTIVE_OBJECT])
    fun `an unknown template key is reported`(form: String) = runVue {
        seed()
        myFixture.configureByText("Markup.vue", component(form.format(MISSING)))

        val unresolved = PluginBundle.getMessage("annotator.unresolved.key")
        val reported = myFixture.doHighlighting().mapNotNull { it.description }

        Assertions.assertEquals(1, reported.count { it == unresolved }, "$form: expected one '$unresolved', got $reported")
    }

    @Test
    fun `strings that are not the key stay out`() = runVue {
        seed()
        myFixture.configureByText(
            "Others.vue",
            component(
                """<p v-t="{ path: 'test:ref.section.key', args: { label: 'not.a.key' } }"></p>
                  |  <my-comp keypath="other.attr"></my-comp>
                  |  <i18n-t tag="not.a.key" keypath="test:ref.section.key"></i18n-t>""".trimMargin()
            )
        )

        val unresolved = PluginBundle.getMessage("annotator.unresolved.key")
        val reported = myFixture.doHighlighting().mapNotNull { it.description }

        Assertions.assertFalse(reported.contains(unresolved), "an args value, another tag's keypath or tag were read as keys: $reported")
    }
}
