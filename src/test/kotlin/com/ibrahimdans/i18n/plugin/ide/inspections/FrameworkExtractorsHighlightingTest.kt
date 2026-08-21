package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import org.junit.jupiter.api.Test

/**
 * Highlighting tests for the framework-specific key extractors wired into
 * [com.ibrahimdans.i18n.extensions.lang.js.JsLang] and
 * [com.ibrahimdans.i18n.extensions.lang.js.JsxLang].
 *
 * Each one covers a syntax the generic argument-based extraction cannot reach: a
 * react-intl descriptor object, a `<FormattedMessage>` tag, or a qualified
 * ngx-translate call. The "unresolved" cases are the ones that prove extraction
 * happens at all — without it the annotator stays silent and the check fails.
 */
class FrameworkExtractorsHighlightingTest : PlatformBaseTest() {

    private val translation = """{"tst1": {"base": {"single": "value"}}}"""

    @Test
    fun reactIntlDescriptor_resolvedKey() = myFixture.customHighlightingCheck(
        "reactIntlResolved.js",
        """const label = formatMessage({ id: "tst1.base.single" })""",
        "assets/translation.json",
        translation
    )

    @Test
    fun reactIntlDescriptor_unresolvedKey() = myFixture.customHighlightingCheck(
        "reactIntlUnresolved.js",
        """const label = formatMessage({ id: "tst1.base.<error descr="Unresolved key">missing</error>" })""",
        "assets/translation.json",
        translation
    )

    @Test
    fun formattedMessageTag_resolvedKey() = myFixture.customHighlightingCheck(
        "formattedMessageResolved.tsx",
        """const C = () => <FormattedMessage id="tst1.base.single" />""",
        "assets/translation.json",
        translation
    )

    @Test
    fun formattedMessageTag_unresolvedKey() = myFixture.customHighlightingCheck(
        "formattedMessageUnresolved.tsx",
        """const C = () => <FormattedMessage id="tst1.base.<error descr="Unresolved key">missing</error>" />""",
        "assets/translation.json",
        translation
    )

    @Test
    fun ngxTranslateQualifiedCall_resolvedKey() = myFixture.customHighlightingCheck(
        "ngxResolved.ts",
        """const label = this.translate.instant("tst1.base.single")""",
        "assets/translation.json",
        translation
    )

    @Test
    fun ngxTranslateQualifiedCall_unresolvedKey() = myFixture.customHighlightingCheck(
        "ngxUnresolved.ts",
        """const label = this.translate.instant("tst1.base.<error descr="Unresolved key">missing</error>")""",
        "assets/translation.json",
        translation
    )

    @Test
    fun svelteI18nCall_resolvedKey() = myFixture.customHighlightingCheck(
        "svelteResolved.js",
        """const label = ${'$'}_("tst1.base.single")""",
        "assets/translation.json",
        translation
    )

    @Test
    fun svelteI18nCall_unresolvedKey() = myFixture.customHighlightingCheck(
        "svelteUnresolved.js",
        """const label = ${'$'}_("tst1.base.<error descr="Unresolved key">missing</error>")""",
        "assets/translation.json",
        translation
    )

    /** The descriptor's other properties are plain text, not keys. */
    @Test
    fun reactIntlDescriptor_defaultMessageIsNotAKey() = myFixture.customHighlightingCheck(
        "reactIntlDefaultMessage.js",
        """const label = formatMessage({ id: "tst1.base.single", defaultMessage: "Hello world" })""",
        "assets/translation.json",
        translation
    )
}
