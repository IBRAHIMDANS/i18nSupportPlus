package com.ibrahimdans.i18n.plugin.ide.completion

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runVue
import com.ibrahimdans.i18n.plugin.utils.generator.translation.JsonTranslationGenerator
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Key completion in vue-i18n's template keys outside `$t`: `<i18n-t keypath>` / `<I18nT>` and the
 * `v-t` directive, as a string or an object's `path`. They resolved since #422 but offered no key,
 * the keypath attribute only the root keys whatever was typed.
 */
internal class VueI18nMarkupCompletionTest : PlatformBaseTest() {

    override fun getTestDataPath(): String = "src/test/resources/completion/vue"

    private val json = JsonTranslationGenerator()

    @ParameterizedTest
    @ValueSource(strings = ["keypath", "keypathPascal", "directive", "directiveObject"])
    fun `a namespaced template key is completed`(form: String) = runVue {
        myFixture.addFileToProject("assets/test.json", json.generateContent("ref", "section", "key", "Reference"))
        myFixture.testCompletion("$form.vue", "$form.after.vue")
    }

    @ParameterizedTest
    @ValueSource(strings = ["keypathDefaultNs", "directiveDefaultNs"])
    fun `a template key of the default namespace is completed`(form: String) = runVue {
        myFixture.addFileToProject("assets/translation.json", json.generateContent("ref", "section", "key", "Reference"))
        myFixture.testCompletion("$form.vue", "$form.after.vue")
    }
}
