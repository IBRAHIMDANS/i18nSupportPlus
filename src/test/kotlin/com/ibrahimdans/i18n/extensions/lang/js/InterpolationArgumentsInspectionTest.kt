package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
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
    private fun warningsFor(translations: String, call: String, vararg extraFiles: Pair<String, String>): List<String> =
        warningsIn(
            translations, "tsx",
            """
            import { useTranslation } from 'react-i18next';
            export default function App({ user, opts, n }: any) {
                const { t } = useTranslation();
                return $call;
            }
            """.trimIndent(),
            *extraFiles
        )

    /** The warnings this inspection reports on a source file of [extension] holding [code]. */
    private fun warningsIn(
        translations: String,
        extension: String,
        code: String,
        vararg extraFiles: Pair<String, String>
    ): List<String> {
        var warnings = emptyList<String>()
        myFixture.runWithConfig(Config(defaultNs = "translation")) {
            if (fileIndex == 0) {
                myFixture.enableInspections(InterpolationArgumentsInspection::class.java)
                myFixture.addFileToProject("en/translation.json", translations)
                extraFiles.forEach { (path, content) -> myFixture.addFileToProject(path, content) }
            }
            myFixture.configureByText("App${fileIndex++}.$extension", code)
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

    /** A vue-i18n call: `$t` is published by vue-i18n alone, so `{name}` is a variable there. */
    private fun vueWarnings(translations: String, call: String): List<String> =
        warningsIn(translations, "js", "export default { methods: { label(n) { return this.$call; } } };")

    /** The branches of an ICU block are text; its selector is the variable that picks one. */
    @Test
    fun icuBranchesAreNotVariables() {
        val icu = """{"gender": "{g, select, male {He} female {She} other {They}} left"}"""
        assertTrue(vueWarnings(icu, "\$t('gender', { g: n })").isEmpty())
        assertTrue(vueWarnings(icu, "\$t('gender')").single().endsWith(": g"))
    }

    /** lingui writes ICU messages: the plural's argument is the variable most often forgotten. */
    @Test
    fun anIcuPluralNeedsItsArgument() {
        val translations = """{"items": "{count, plural, one {# item} other {# items}}"}"""
        fun lingui(call: String) =
            warningsIn(translations, "js", "import { i18n } from '@lingui/core';\nexport const label = (n) => $call;")
        assertTrue(lingui("i18n._('items')").single().endsWith(": count"))
        assertTrue(lingui("i18n._('items', { count: n })").isEmpty())
    }

    /** i18next prints `{count, plural, …}` as text: nothing is asked for. */
    @Test
    fun anIcuBlockIsTextForI18next() {
        val translations = """{"items": "{count, plural, one {# item} other {# items}}"}"""
        assertTrue(warningsFor(translations, "t('items')").isEmpty())
    }

    /** i18next interpolates `{{name}}` only: a single-brace `{name}` is text it prints as is. */
    @Test
    fun singleBracesAreTextForI18next() {
        val translations = """{"greeting": "Hello {name}, {{count}} new"}"""
        assertTrue(warningsFor(translations, "t('greeting', { count: n })").isEmpty())
        assertTrue(warningsFor(translations, "t('greeting')").single().endsWith(": count"))
    }

    /**
     * i18n-js writes `%{count}`. `t` is published by i18next too, so the single-brace rule stays
     * off — `%{count}` must count regardless.
     */
    @Test
    fun i18nJsPercentBracesAreVariables() {
        val translations = """{"box": "%{count} boxes", "greeting": "Hello {name}"}"""
        assertTrue(warningsFor(translations, "t('box')").single().endsWith(": count"))
        assertTrue(warningsFor(translations, "t('box', { count: n })").isEmpty())
        assertTrue(warningsFor(translations, "t('greeting')").isEmpty())
    }

    @Test
    fun singleBracesAreVariablesForVueI18n() {
        val translations = """{"greeting": "Hello {name}"}"""
        assertTrue(vueWarnings(translations, "\$t('greeting')").single().endsWith(": name"))
        assertTrue(vueWarnings(translations, "\$t('greeting', { name: n })").isEmpty())
    }

    /** Under a module preset, the preset names the technology. */
    @Test
    fun aModulePresetNamesTheTechnology() {
        var warnings = emptyList<String>()
        val config = Config(
            defaultNs = "translation",
            modules = listOf(ModuleConfig(name = "app", rootDirectory = "src", preset = "vue-i18n"))
        )
        myFixture.runWithConfig(config) {
            myFixture.enableInspections(InterpolationArgumentsInspection::class.java)
            myFixture.addFileToProject("en/translation.json", """{"greeting": "Hello {name}"}""")
            val file = myFixture.addFileToProject(
                "src/App.js",
                "export default { methods: { label() { return this.\$t('greeting'); } } };"
            )
            myFixture.configureFromExistingVirtualFile(file.virtualFile)
            warnings = myFixture.doHighlighting().mapNotNull { it.description }.filter { it.contains("not passed to the call") }
        }
        assertTrue(warnings.single().endsWith(": name"), "$warnings")
    }

    /** svelte-i18n reads its variables from `values`, and interpolates ICU `{name}`. */
    @Test
    fun svelteI18nReadsTheValuesObject() {
        val translations = """{"greeting": "Hello {name}"}"""
        fun svelte(call: String) = warningsIn(translations, "js", "import { _ } from 'svelte-i18n';\nexport const label = (n) => $call;")
        assertTrue(svelte("\$_('greeting', { values: { name: n } })").isEmpty())
        assertTrue(svelte("\$_('greeting', { name: n })").single().endsWith(": name"))
        assertTrue(svelte("\$_('greeting')").single().endsWith(": name"))
    }

    /** A react-intl or react-i18next component, written in a TSX file of its own. */
    private fun componentWarnings(translations: String, jsx: String, import: String): List<String> =
        warningsIn(translations, "tsx", "$import\nexport const App = ({ n, opts }: any) => $jsx;")

    @Test
    fun formattedMessageValuesAreChecked() {
        val translations = """{"greeting": "Hello {name}"}"""
        val import = "import { FormattedMessage } from 'react-intl';"
        assertTrue(componentWarnings(translations, """<FormattedMessage id="greeting" />""", import).single().endsWith(": name"))
        assertTrue(componentWarnings(translations, """<FormattedMessage id="greeting" values={{ name: n }} />""", import).isEmpty())
    }

    /** Values held in a variable are only known at runtime: silence. */
    @Test
    fun componentValuesInAVariableAreIgnored() {
        val translations = """{"greeting": "Hello {name}"}"""
        val import = "import { FormattedMessage } from 'react-intl';"
        assertTrue(componentWarnings(translations, """<FormattedMessage id="greeting" values={opts} />""", import).isEmpty())
    }

    @Test
    fun transValuesAreChecked() {
        val translations = """{"greeting": "Hello {{name}}"}"""
        val import = "import { Trans } from 'react-i18next';"
        assertTrue(componentWarnings(translations, """<Trans i18nKey="greeting" />""", import).single().endsWith(": name"))
        assertTrue(componentWarnings(translations, """<Trans i18nKey="greeting" values={{ name: n }} />""", import).isEmpty())
    }

    /** A `<Trans>` with children takes its message from them: left alone rather than guessed. */
    @Test
    fun transWithChildrenIsIgnored() {
        val translations = """{"greeting": "Hello {{name}}"}"""
        val import = "import { Trans } from 'react-i18next';"
        assertTrue(componentWarnings(translations, """<Trans i18nKey="greeting">Hello <b>you</b></Trans>""", import).isEmpty())
    }

    @Test
    fun namedVariablesFollowTheDialogRules() {
        Assertions.assertEquals(
            setOf("name", "amount", "user", "raw"),
            InterpolationArgumentsInspection.namedVariables("{{name}} {amount, number} {{user.name}} {{- raw}} %s %1\$s {0}", singleBraces = true)
        )
        Assertions.assertEquals(
            setOf("name", "user", "raw"),
            InterpolationArgumentsInspection.namedVariables("{{name}} {amount, number} {{user.name}} {{- raw}} %s %1\$s {0}", singleBraces = false)
        )
        Assertions.assertEquals(
            setOf("count"),
            InterpolationArgumentsInspection.namedVariables("%{count} %{ count } %s", singleBraces = false)
        )
        Assertions.assertEquals(
            setOf("rank", "gender"),
            InterpolationArgumentsInspection.namedVariables(
                "{ rank , selectordinal, one {#st} other {#th}} {gender, select, other {{n, plural, other {x}}}}",
                singleBraces = true
            ),
            "only top-level ICU blocks name a variable"
        )
    }

    // ── react-intl: formatMessage({ id }, values) ─────────────────────────────

    /** A react-intl call: the key is the descriptor's id, the values come next to it. */
    private fun formatMessageWarnings(translations: String, call: String): List<String> =
        warningsIn(translations, "js", "export const label = (intl, n, opts) => intl.$call;")

    @Test
    fun formatMessageReadsTheDescriptorIdAndTheValues() {
        val translations = """{"greeting": "Hello {name}, {count, plural, one {# item} other {# items}}"}"""
        val missing = formatMessageWarnings(translations, "formatMessage({ id: 'greeting' })")
        assertTrue(missing.single().endsWith(": count, name"), "$missing")
        assertTrue(formatMessageWarnings(translations, "formatMessage({ id: 'greeting' }, { count: n })").single().endsWith(": name"))
        assertTrue(formatMessageWarnings(translations, "formatMessage({ id: 'greeting', defaultMessage: 'Hi' }, { name: 'x', count: n })").isEmpty())
    }

    @Test
    fun formatMessageValuesKnownAtRuntimeOnlyAreNotReported() {
        val translations = """{"greeting": "Hello {name}"}"""
        assertTrue(formatMessageWarnings(translations, "formatMessage({ id: 'greeting' }, opts)").isEmpty())
        assertTrue(formatMessageWarnings(translations, "formatMessage({ id: 'greeting' }, { ...opts })").isEmpty())
    }

    // ── vue-i18n: $tc(key, choice, [locale], [values]) ────────────────────────

    /** `$tc` always passes `count` and `n`: vue-i18n fills both from the choice. */
    @Test
    fun tcPassesCountAndNByItself() {
        val translations = """{"apples": "no apples | {n} apple | {count} apples of {owner}"}"""
        assertTrue(vueWarnings(translations, "\$tc('apples', n)").single().endsWith(": owner"))
        assertTrue(vueWarnings(translations, "\$tc('apples', n, { owner: 'Ann' })").isEmpty())
        assertTrue(vueWarnings(translations, "\$tc('apples', n, 'fr', { owner: 'Ann' })").isEmpty())
    }

    @Test
    fun tcValuesKnownAtRuntimeOnlyAreNotReported() {
        val translations = """{"apples": "{count} apples of {owner}"}"""
        assertTrue(vueWarnings(translations, "\$tc('apples', n, opts)").isEmpty())
    }
}
