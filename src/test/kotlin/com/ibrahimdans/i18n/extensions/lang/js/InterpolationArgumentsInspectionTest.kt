package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.lang.annotation.HighlightSeverity
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A translation call must pass the variables its value uses in the reference locale — and the
 * inspection must keep quiet whenever it cannot tell what the call passes.
 */
class InterpolationArgumentsInspectionTest : PlatformBaseTest() {

    private var fileIndex = 0

    /**
     * The warnings this inspection reports on [call], written in a component of its own. The
     * translation files are created on the first call of a test only, so a test passes the same
     * [translations] to each of its calls.
     */
    private fun warningsFor(translations: String, call: String, vararg extraFiles: Pair<String, String>): List<String> {
        var warnings = emptyList<String>()
        myFixture.runWithConfig(Config(defaultNs = "translation")) {
            if (fileIndex == 0) {
                myFixture.enableInspections(InterpolationArgumentsInspection::class.java)
                myFixture.addFileToProject("en/translation.json", translations)
                extraFiles.forEach { (path, content) -> myFixture.addFileToProject(path, content) }
            }
            myFixture.configureByText(
                "App${fileIndex++}.tsx",
                """
                import { useTranslation } from 'react-i18next';
                export default function App({ user, opts, n }: any) {
                    const { t } = useTranslation();
                    return $call;
                }
                """.trimIndent()
            )
            warnings = myFixture.doHighlighting()
                .filter { it.severity == HighlightSeverity.WARNING }
                .mapNotNull { it.description }
                .filter { it.contains("not passed to the call") }
        }
        return warnings
    }

    @Test
    fun aMissingVariableIsReported() {
        val warnings = warningsFor("""{"greeting": "Hello {{name}}"}""", "t('greeting')")
        Assertions.assertEquals(1, warnings.size, "$warnings")
        assertTrue(warnings.single().endsWith(": name"), "$warnings")
    }

    @Test
    fun aVariablePassedIsNotReported() {
        assertTrue(warningsFor("""{"greeting": "Hello {{name}}"}""", "t('greeting', { name: user.name })").isEmpty())
        assertTrue(warningsFor("""{"greeting": "Hello {{ name }}"}""", "t('greeting', { name })").isEmpty())
    }

    @Test
    fun onlyTheVariablesNotPassedAreReported() {
        val warnings = warningsFor("""{"greeting": "Hello {{name}}, {{count}} new"}""", "t('greeting', { count: n })")
        assertTrue(warnings.single().endsWith(": name"), "$warnings")
    }

    @Test
    fun optionsThatAreNotAnObjectLiteralAreIgnored() {
        val translations = """{"greeting": "Hello {{name}}"}"""
        assertTrue(warningsFor(translations, "t('greeting', opts)").isEmpty(), "variable")
        assertTrue(warningsFor(translations, "t('greeting', { ...opts })").isEmpty(), "spread")
        assertTrue(warningsFor(translations, "t('greeting', { [n]: user })").isEmpty(), "computed name")
        assertTrue(warningsFor(translations, "t('greeting', 'Hi ' + n)").isEmpty(), "concatenated default")
    }

    @Test
    fun aPluralNeedsCount() {
        val translations = """{"item_one": "One item", "item_other": "{{count}} items"}"""
        assertTrue(warningsFor(translations, "t('item', { count: n })").isEmpty())
        assertTrue(warningsFor(translations, "t('item')").single().endsWith(": count"))
    }

    @Test
    fun anUnresolvedKeyIsIgnored() {
        assertTrue(warningsFor("""{"greeting": "Hello {{name}}"}""", "t('farewell')").isEmpty())
    }

    @Test
    fun theReferenceLocaleValueIsRead() {
        // Only the French value names a variable: `en` is the reference, and it needs none.
        val warnings = warningsFor(
            """{"greeting": "Hello"}""", "t('greeting')",
            "fr/translation.json" to """{"greeting": "Bonjour {{name}}"}"""
        )
        assertTrue(warnings.isEmpty(), "$warnings")
    }

    @Test
    fun defaultValuesAreUnderstood() {
        val translations = """{"greeting": "Hello {{name}}"}"""
        assertTrue(warningsFor(translations, "t('greeting', 'Hello {{name}}', { name: n })").isEmpty())
        assertTrue(warningsFor(translations, "t('greeting', { defaultValue: 'Hi', name: n })").isEmpty())
        assertTrue(warningsFor(translations, "t('greeting', 'Hello')").single().endsWith(": name"))
    }

    @Test
    fun theReplaceObjectCounts() {
        assertTrue(warningsFor("""{"greeting": "Hello {{name}}"}""", "t('greeting', { replace: { name: n } })").isEmpty())
    }

    @Test
    fun aNestedVariableNeedsItsRootObject() {
        val translations = """{"greeting": "Hello {{user.name}}"}"""
        assertTrue(warningsFor(translations, "t('greeting', { user })").isEmpty())
        assertTrue(warningsFor(translations, "t('greeting')").single().endsWith(": user"))
    }

    @Test
    fun positionalPlaceholdersAreNotNamedOptions() {
        assertTrue(warningsFor("""{"greeting": "Hello %s"}""", "t('greeting')").isEmpty())
    }

    @Test
    fun icuBranchesAreNotVariables() {
        val icu = """{"gender": "{g, select, male {He} female {She} other {They}} left"}"""
        assertTrue(warningsFor(icu, "t('gender', { g: n })").isEmpty())
    }

    @Test
    fun namedVariablesFollowTheDialogRules() {
        Assertions.assertEquals(
            setOf("name", "amount", "user", "raw"),
            InterpolationArgumentsInspection.namedVariables("{{name}} {amount, number} {{user.name}} {{- raw}} %s %1\$s {0}")
        )
    }
}
