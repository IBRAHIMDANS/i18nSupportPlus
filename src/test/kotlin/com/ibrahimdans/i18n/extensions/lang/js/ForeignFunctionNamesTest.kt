package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.lang.annotation.HighlightSeverity
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Every technology's function names apply to every project, so a bare call sharing one of them —
 * a form library's `get`, lodash's `_`, a local `msg` — was annotated as an unresolved key.
 */
class ForeignFunctionNamesTest : PlatformBaseTest() {

    private fun errors(code: String): List<String?> {
        myFixture.addFileToProject("locales/en/translation.json", """{"other": "x"}""")
        myFixture.configureByText("Form.tsx", code)
        return myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }.map { it.description }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "const { get } = useForm(); get('user.email');",
        "import _ from 'lodash'; _('user.email');",
        "const msg = (s: string) => s; msg('user.email');",
        "const stream = (s: string) => s; stream('user.email');",
        "const instant = (s: string) => s; instant('user.email');",
    ])
    fun `a bare call to a foreign function is not an i18n key`(code: String) {
        val found = errors(code)
        assertTrue(found.isEmpty(), "$code -> $found")
    }

    /** The gate opens with the framework's import: the key is analysed again. */
    @ParameterizedTest
    @ValueSource(strings = [
        "import { msg } from '@lingui/macro'; msg('user.email');",
        "import { _ } from 'svelte-i18n'; _('user.email');",
    ])
    fun `the same call is analysed once the framework is imported`(code: String) {
        val found = errors(code)
        assertTrue(found.contains("Unresolved key"), "$code -> $found")
    }
}
