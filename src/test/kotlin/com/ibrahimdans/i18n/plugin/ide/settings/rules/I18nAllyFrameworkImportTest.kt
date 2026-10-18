package com.ibrahimdans.i18n.plugin.ide.settings.rules

import com.ibrahimdans.i18n.plugin.rules.KeyRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class I18nAllyFrameworkImportTest {

    /** The example of i18n Ally's *Custom Framework* wiki page, as published. */
    private val wikiExample = """
        # .vscode/i18n-ally-custom-framework.yml

        # An array of strings which contain Language Ids defined by VS Code
        languageIds:
          - javascript
          - typescript
          - javascriptreact
          - typescriptreact

        # An array of RegExes to find the key usage. **The key should be captured in the first match group**.
        usageMatchRegex:
          # The following example shows how to detect `t("your.i18n.keys")`
          - "[^\\w\\d]t\\(['\"`]({key})['\"`]"

        scopeRangeRegex: "useTranslation\\(\\s*\\[?\\s*['\"`](.*?)['\"`]"

        # refactorTemplates:
        #  - i18n.get("$1")

        # If set to true, only enables this custom framework (will disable all built-in frameworks)
        monopoly: true
    """.trimIndent()

    private fun plan(yaml: String, existing: List<EditorRuleState> = emptyList()): FrameworkImport.Plan {
        val result = I18nAllyFrameworkImport.plan(yaml, existing)
        assertTrue(result is FrameworkImport.Plan, "expected a plan, got $result")
        return result as FrameworkImport.Plan
    }

    @Test
    fun theWikiExampleGivesOneJsTriggerRule() {
        val plan = plan(wikiExample)

        assertEquals(listOf(EditorRuleState(id = "i18n Ally t (js)", language = "js", trigger = "t")), plan.rules)
        assertEquals(emptyList<String>(), plan.callTemplates)
    }

    @Test
    fun theWikiExampleReportsWhatHasNoEquivalent() {
        val notApplicable = plan(wikiExample).notImported.filter { it.reason == NotImportedReason.NOT_APPLICABLE }.map { it.entry }

        assertEquals(2, notApplicable.size)
        assertTrue(notApplicable[0].startsWith("scopeRangeRegex: "))
        assertEquals("monopoly: true", notApplicable[1])
    }

    @Test
    fun aMonopolyLeftOffIsNotReported() {
        val plan = plan("languageIds: [javascript]\nusageMatchRegex: ['\\bt\\(({key})']\nmonopoly: false")

        assertEquals(emptyList<NotImported>(), plan.notImported)
    }

    @Test
    fun theUsualCallsBecomeTriggers() {
        val plan = plan(
            """
            languageIds: [vue, php]
            usageMatchRegex:
              - "[^\\w\\d]\\${'$'}t\\(['\"`]({key})['\"`]"
              - '[^\w\d]i18n\.t\(\s*[''"]({key})'
              - "\\b__\\(\"({key})\""
            """.trimIndent()
        )

        assertEquals(
            listOf("\$t" to "js", "\$t" to "php", "i18n.t" to "js", "i18n.t" to "php", "__" to "js", "__" to "php"),
            plan.rules.map { it.trigger to it.language }
        )
        assertTrue(plan.rules.all { !it.exclude && it.constraintType == KeyRules.Constraint.NONE && KeyRules.isValid(it) })
    }

    @Test
    fun aRegexThatIsNotAPlainCallIsReported() {
        val untranslatable = listOf("(?:t|tc)\\(['\"]({key})['\"]", "\\\$t\\(\\s*\\w+\\.({key})", "<Trans i18nKey=\"({key})\"")
        val plan = plan("languageIds: [javascript]\nusageMatchRegex:\n" + untranslatable.joinToString("\n") { "  - '${it.replace("'", "''")}'" })

        assertEquals(emptyList<EditorRuleState>(), plan.rules)
        assertEquals(untranslatable.map { NotImported(it, NotImportedReason.NOT_A_FUNCTION_CALL) }, plan.notImported)
    }

    @Test
    fun anUnsupportedLanguageIsReportedAndTheOthersKept() {
        val plan = plan("languageIds: [dart, javascript, typescript]\nusageMatchRegex: ['[^\\w\\d]t\\(({key})']")

        assertEquals(listOf("js"), plan.rules.map { it.language })
        assertEquals(listOf(NotImported("dart", NotImportedReason.UNSUPPORTED_LANGUAGE)), plan.notImported)
    }

    @Test
    fun aCallWithNoSupportedLanguageCreatesNoRule() {
        val plan = plan("languageIds: [dart]\nusageMatchRegex: ['[^\\w\\d]t\\(({key})']")

        assertEquals(emptyList<EditorRuleState>(), plan.rules)
        assertTrue(NotImported("[^\\w\\d]t\\(({key})", NotImportedReason.NO_SUPPORTED_LANGUAGE) in plan.notImported)
    }

    @Test
    fun aRuleAlreadyDeclaredIsSkipped() {
        val existing = listOf(EditorRuleState(id = "mine", language = "", trigger = "t"))
        val plan = plan("languageIds: [javascript]\nusageMatchRegex: ['[^\\w\\d]t\\(({key})', '[^\\w\\d]tr\\(({key})', '[^\\w\\d]tr\\([''\"]({key})']", existing)

        assertEquals(listOf("tr"), plan.rules.map { it.trigger })
        assertEquals(
            listOf(NotImported("t (js)", NotImportedReason.DUPLICATE_RULE), NotImported("tr (js)", NotImportedReason.DUPLICATE_RULE)),
            plan.notImported
        )
    }

    @Test
    fun anExclusionOrAConstrainedRuleIsNotADuplicate() {
        val existing = listOf(
            EditorRuleState(id = "out", trigger = "t", exclude = true),
            EditorRuleState(id = "php", language = "php", trigger = "t"),
            EditorRuleState(id = "admin", trigger = "t", constraintType = KeyRules.Constraint.FILE_PATH, value = "admin/"),
        )

        assertEquals(listOf("t"), plan("languageIds: [javascript]\nusageMatchRegex: ['[^\\w\\d]t\\(({key})']", existing).rules.map { it.trigger })
    }

    @Test
    fun refactorTemplatesBecomeCallTemplates() {
        val plan = plan(
            """
            refactorTemplates:
              - "${'$'}t('${'$'}1')"
              - "{${'$'}t('${'$'}1')}"
              - "{{t('${'$'}1')}}"
              - i18n.get("${'$'}1")
              - "${'$'}1"
            """.trimIndent()
        )

        assertEquals(listOf("\$t({key})", "t({key})", "i18n.get({key})"), plan.callTemplates)
        assertEquals(listOf(NotImported("\$1", NotImportedReason.TEMPLATE_NOT_A_CALL)), plan.notImported)
    }

    @Test
    fun templateMapping() {
        assertEquals("\$t({key})", I18nAllyFrameworkImport.callTemplateOf("\$t('\$1')"))
        assertEquals("translate({key}, {})", I18nAllyFrameworkImport.callTemplateOf("translate(\"\$1\", {})"))
        assertNull(I18nAllyFrameworkImport.callTemplateOf("\$t(\$1)"))
        assertNull(I18nAllyFrameworkImport.callTemplateOf("t('\$1') + t('\$1')"))
    }

    @Test
    fun aMissingOrUnreadableFileCreatesNoRule() {
        assertEquals(FrameworkImport.Invalid(0), I18nAllyFrameworkImport.plan("", emptyList()))
        assertEquals(FrameworkImport.Invalid(0), I18nAllyFrameworkImport.plan("# only a comment\nname: other", emptyList()))
        assertEquals(FrameworkImport.Invalid(2), I18nAllyFrameworkImport.plan("languageIds:\n  nested: mapping", emptyList()))
        assertEquals(FrameworkImport.Invalid(1), I18nAllyFrameworkImport.plan("usageMatchRegex: \"unterminated", emptyList()))
    }
}
