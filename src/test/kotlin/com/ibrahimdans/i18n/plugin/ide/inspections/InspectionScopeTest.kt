package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.IcuFormatInspection
import com.ibrahimdans.i18n.plugin.ide.inspection.PlaceholderConsistencyInspection
import com.intellij.lang.annotation.HighlightSeverity
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where the translation inspections apply: translation files only, with the rules of the file's
 * own language, comparing a namespace against the same namespace in the reference locale.
 */
class InspectionScopeTest : PlatformBaseTest() {

    private fun warningsIn(path: String, content: String): List<String> {
        myFixture.enableInspections(IcuFormatInspection::class.java, PlaceholderConsistencyInspection::class.java)
        val file = myFixture.addFileToProject(path, content)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        // JSON's own highlighting reports property keys as infos; only warnings are ours.
        return myFixture.doHighlighting()
            .filter { it.severity == HighlightSeverity.WARNING }
            .mapNotNull { it.description }
    }

    @Test
    fun aJsonFileThatIsNotATranslationIsNotInspected() {
        val warnings = warningsIn("package.json", """{"scripts": {"odd": "echo {oops", "plural": "{n, plural, many {x}}"}}""")
        assertTrue(warnings.isEmpty(), "package.json must not be inspected: $warnings")
    }

    /** Japanese has `other` only: an ICU plural there carries no `one` form, and that is correct. */
    @Test
    fun aPluralWithOtherOnlyIsValidInJapanese() {
        val warnings = warningsIn("locales/ja/common.json", """{"items": "{count, plural, other {# 個}}"}""")
        assertTrue(warnings.none { it.contains("one") || it.contains("zero") }, "$warnings")
    }

    @Test
    fun aPluralWithOtherOnlyIsStillReportedInEnglish() {
        val warnings = warningsIn("locales/en/common.json", """{"items": "{count, plural, other {# items}}"}""")
        assertTrue(warnings.isNotEmpty(), "English has a `one` category: $warnings")
    }

    /** `locales/fr/common.json` is compared with `locales/en/common.json`, not with a sibling `en.json`. */
    @Test
    fun placeholdersAreComparedAcrossTheLocaleDirectoryLayout() {
        myFixture.addFileToProject("locales/en/common.json", """{"hello": "Hello {name}, you have {count} messages"}""")
        val warnings = warningsIn("locales/fr/common.json", """{"hello": "Bonjour {name}"}""")
        assertTrue(warnings.any { it.contains("{count}") }, "$warnings")
    }
}
