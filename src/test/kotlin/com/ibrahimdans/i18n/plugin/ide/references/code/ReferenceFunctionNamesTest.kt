package com.ibrahimdans.i18n.plugin.ide.references.code

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.utils.generator.translation.JsonTranslationGenerator
import com.ibrahimdans.i18n.plugin.utils.unQuote
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * A key reaches the same navigation whichever `Technology` publishes the function name.
 *
 * `JsReferenceAssistant.pattern()` used to hardcode `t` and `$t`, so every other published name
 * was annotated — annotation reads the full list — but carried no reference. Two frameworks are
 * covered here on purpose: one name is not evidence that the list is read, only that one more
 * literal was added.
 */
class ReferenceFunctionNamesTest : PlatformBaseTest() {

    private val tg = JsonTranslationGenerator()

    private fun resolve(call: String): String? {
        addFileToProject("assets/test.json", tg.generateContent("ref", "section", "key", "Reference in json"))
        myFixture.configureByText("Names.js", "export const label = () => $call;")

        var resolved: String? = null
        read {
            val offset = myFixture.file.text.indexOf("test:ref.section.key")
            val element = myFixture.file.findElementAt(offset)?.parent
            resolved = element?.references?.firstOrNull()?.resolve()?.text?.unQuote()
        }
        return resolved
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "t('test:ref.section.key')",          // i18next — worked before, guards the regression
        "msg('test:ref.section.key')",        // lingui
        "i18n._('test:ref.section.key')",     // lingui, qualified: the qualifier is a declared name
        "_('test:ref.section.key')",          // svelte-i18n
        "\$_('test:ref.section.key')",        // svelte-i18n
    ])
    fun `a key resolves through any published function name`(call: String) {
        Assertions.assertEquals(
            "Reference in json", resolve(call),
            "$call carries no reference — the pattern is not reading the technologies' names"
        )
    }

    /**
     * The qualifier check is what keeps the widened pattern honest: `instant` is now a matched
     * method name, but `toast.instant('…')` is not a translation call and must stay unclaimed.
     */
    @ParameterizedTest
    @ValueSource(strings = [
        "toast.t('test:ref.section.key')",
        "toast.instant('test:ref.section.key')",
    ])
    fun `a call qualified by something undeclared carries no reference`(call: String) {
        Assertions.assertEquals(
            null, resolve(call),
            "$call must not be taken for a translation call"
        )
    }
}
