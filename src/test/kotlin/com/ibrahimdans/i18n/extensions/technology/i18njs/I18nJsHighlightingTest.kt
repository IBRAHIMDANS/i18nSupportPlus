package com.ibrahimdans.i18n.extensions.technology.i18njs

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.generator.code.TsCodeGenerator
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * i18n-js, the React Native / Expo default.
 *
 * Nothing declared it before: `t` worked only because i18next happens to publish that name,
 * so an i18n-js project silently depended on a framework it does not use. Its catalogue is
 * read by `TsCatalogTechnology`; this covers the call shapes and the nested plural object.
 */
class I18nJsHighlightingTest : PlatformBaseTest() {

    private val cg = TsCodeGenerator()

    private val catalog = """
        export const translations = {
          fr: {
            dashboard: { title: 'Ma pharmacie' },
            common: { box: { one: '1 boîte', other: '%{count} boîtes' } },
          },
        } as const;
    """.trimIndent()

    @Test
    fun bareCallResolves() = myFixture.runWithConfig(Config()) {
        addFileToProject("src/i18n/translations.ts", catalog)
        myFixture.configureByText("test.${cg.ext()}", cg.generate("\"dashboard.title\""))
        myFixture.checkHighlighting(true, true, true, true)
    }

    /**
     * `i18n.t('…')` is the idiomatic call. A qualified call is rejected unless its full text
     * is a declared name, which is what stops `toast.t('…')` from being claimed.
     */
    @Test
    fun qualifiedCallResolves() = myFixture.runWithConfig(Config()) {
        addFileToProject("src/i18n/translations.ts", catalog)
        myFixture.configureByText(
            "test.${cg.ext()}",
            "function test() { return i18n.t(\"dashboard.title\"); }"
        )
        myFixture.checkHighlighting(true, true, true, true)
    }

    /**
     * The false positive this task exists for: i18n-js pluralizes into a nested object, so the
     * key resolves onto `{ one, other }` and used to be reported as "Reference to object".
     */
    @Test
    fun nestedPluralIsNotReportedAsAnObjectReference() = myFixture.runWithConfig(Config()) {
        addFileToProject("src/i18n/translations.ts", catalog)
        myFixture.configureByText("test.${cg.ext()}", cg.generate("\"common.box\""))
        myFixture.checkHighlighting(true, true, true, true)
    }

    /** An ordinary object must still be reported: only full plural groups are exempt. */
    @Test
    fun anOrdinaryObjectIsStillReported() = myFixture.runWithConfig(Config()) {
        addFileToProject("src/i18n/translations.ts", catalog)
        myFixture.configureByText(
            "test.${cg.ext()}",
            cg.generate("\"<error descr=\"Reference to object\">dashboard</error>\"")
        )
        myFixture.checkHighlighting(true, true, true, true)
    }

    /** An unqualified call on an unrelated object must not be claimed. */
    @Test
    fun anUnrelatedQualifiedCallIsIgnored() = myFixture.runWithConfig(Config()) {
        addFileToProject("src/i18n/translations.ts", catalog)
        myFixture.configureByText(
            "test.${cg.ext()}",
            "function test() { return toast.t(\"not.a.translation.key\"); }"
        )
        myFixture.checkHighlighting(true, true, true, true)
    }

    /** The technology must publish the names the extraction relies on. */
    @Test
    fun theTechnologyIsRegistered() {
        val names = Extensions.TECHNOLOGY.extensionList
            .filterIsInstance<I18nJsTechnology>()
            .flatMap { it.translationFunctionNames() }

        Assertions.assertEquals(listOf("t", "i18n.t"), names, "i18n-js must be declared in plugin.xml")
    }
}
