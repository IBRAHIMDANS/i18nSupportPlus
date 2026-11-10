package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.extensions.lang.js.JsUnresolvedKeyInspection
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.UnresolvedKeyInspectionBase
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * An unresolved key of the code is an inspection's problem, not an annotation: its severity is the
 * profile's, a comment suppresses it, and a batch run lists it. The annotator only colours the keys
 * that resolve.
 *
 * Every assertion is qualified as `Assertions.…`: BasePlatformTestCase inherits JUnit 3's
 * `assertEquals(message, expected, actual)`, which would win over a static import.
 */
class UnresolvedKeyInspectionTest : PlatformBaseTest() {

    private val unresolved = PluginBundle.getMessage("annotator.unresolved.key")

    private fun seed() = addFileToProject("locales/en/test.json", """{"ref": {"title": "x"}}""")

    private fun reported() = myFixture.doHighlighting().filter { it.description == unresolved }

    @Test
    fun `an unresolved key is reported by the inspection`() {
        seed()
        myFixture.configureByText("App.ts", "export const a = (t) => t('test:ref.missing');")

        val reported = reported()
        Assertions.assertEquals(listOf(UnresolvedKeyInspectionBase.SHORT_NAME), reported.map { it.inspectionToolId })
        Assertions.assertEquals(HighlightSeverity.ERROR, reported.single().severity)
    }

    @Test
    fun `a resolved key is coloured by the annotator and reported by nothing`() {
        seed()
        myFixture.configureByText("App.ts", "export const a = (t) => t('test:ref.title');")

        val infos = myFixture.doHighlighting()
        Assertions.assertTrue(
            infos.any { it.forcedTextAttributesKey == DefaultLanguageHighlighterColors.LINE_COMMENT && it.text == "ref.title" },
            "the resolved key lost its colour: ${infos.map { it.text to it.forcedTextAttributesKey }}",
        )
        Assertions.assertEquals(emptyList<String>(), infos.filter { it.severity >= HighlightSeverity.WEAK_WARNING }.map { it.description })
    }

    @Test
    fun `the severity set in the profile is the one shown`() {
        seed()
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
        profile.setErrorLevel(HighlightDisplayKey.find(UnresolvedKeyInspectionBase.SHORT_NAME)!!, HighlightDisplayLevel.WARNING, project)
        myFixture.configureByText("App.ts", "export const a = (t) => t('test:ref.missing');")

        Assertions.assertEquals(listOf(HighlightSeverity.WARNING), reported().map { it.severity })
    }

    @Test
    fun `a noinspection comment silences the call`() {
        seed()
        myFixture.configureByText(
            "App.ts",
            """
            // noinspection ${UnresolvedKeyInspectionBase.SHORT_NAME}
            export const a = (t) => t('test:ref.missing');
            export const b = (t) => t('test:ref.other');
            """.trimIndent(),
        )

        Assertions.assertEquals(listOf("other"), reported().map { it.text })
    }

    @Test
    fun `a batch run lists the unresolved keys of a file`() {
        seed()
        val file = addFileToProject(
            "src/App.tsx",
            """
            export const A = ({ t }) => <p>{t('test:ref.missing')}{t('test:ref.title')}{t('nope:any')}</p>;
            """.trimIndent(),
        )
        val context = InspectionManager.getInstance(project).createNewGlobalContext()

        val problems = InspectionEngine.runInspectionOnFile(file, LocalInspectionToolWrapper(JsUnresolvedKeyInspection()), context)

        Assertions.assertEquals(
            listOf(unresolved, PluginBundle.getMessage("annotator.unresolved.ns")),
            problems.map { it.descriptionTemplate },
        )
        // The fixes open dialogs on the editor of the file: a batch result has no editor to show them on.
        Assertions.assertEquals(listOf(0, 0), problems.map { it.fixes?.size ?: 0 })
    }
}
