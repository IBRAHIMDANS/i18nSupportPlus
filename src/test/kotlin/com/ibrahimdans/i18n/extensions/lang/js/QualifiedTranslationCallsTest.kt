package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * `props.t` / `this.props.t` (react-i18next `withTranslation`) and `i18next.t` were rejected as
 * qualified calls whose text no technology published: no annotation, so a missing key went
 * unreported and a present one got no navigation.
 */
class QualifiedTranslationCallsTest : PlatformBaseTest() {

    private fun check(callee: String, key: String) = myFixture.runWithConfig(Config()) {
        myFixture.addFileToProject("assets/en/test.json", """{"ref": {"title": "Hello"}}""")
        myFixture.configureByText(
            "Qualified.tsx",
            """
            export class Header {
                props: any;
                render() {
                    return $callee($key);
                }
            }
            """.trimIndent()
        )
        myFixture.checkHighlighting(true, true, true, true)
    }

    @ParameterizedTest
    @ValueSource(strings = ["props.t", "this.props.t", "i18next.t"])
    fun `a resolved key through a qualified call is not flagged`(callee: String) =
        check(callee, "'test:ref.title'")

    /** The negative proves the call is analysed at all: before, nothing was reported either way. */
    @ParameterizedTest
    @ValueSource(strings = ["props.t", "this.props.t", "i18next.t"])
    fun `a missing key through a qualified call is reported`(callee: String) =
        check(callee, "'test:ref.<error descr=\"Unresolved key\">absent</error>'")
}
