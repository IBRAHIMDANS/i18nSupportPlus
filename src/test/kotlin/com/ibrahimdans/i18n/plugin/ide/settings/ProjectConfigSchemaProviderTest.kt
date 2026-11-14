package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.guessProjectDir
import com.jetbrains.jsonSchema.impl.inspections.JsonSchemaComplianceInspection
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** `i18n-check.json` at the project's root gets the schema of [ProjectConfigSchema]; no other JSON file does. */
class ProjectConfigSchemaProviderTest : PlatformBaseTest() {

    @BeforeEach
    fun enableSchemaCheck() {
        myFixture.enableInspections(JsonSchemaComplianceInspection::class.java)
    }

    @AfterEach
    fun deleteFiles() {
        val root = project.guessProjectDir() ?: return
        WriteAction.runAndWait<Exception> {
            listOf(ProjectConfigFile.PATH, "other.json").forEach { root.findChild(it)?.delete(this) }
        }
    }

    private fun schemaWarnings(path: String, text: String): List<String> {
        val file = addFileToProject(path, text)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return myFixture.doHighlighting()
            .filter { it.severity >= HighlightSeverity.WEAK_WARNING }
            .mapNotNull(HighlightInfo::getDescription)
    }

    @Test
    fun `an unknown setting and a value of the wrong type are reported`() {
        val warnings = schemaWarnings(ProjectConfigFile.PATH, """{ "keySeprator": "/", "flatKeys": "yes" }""")

        assertTrue(warnings.any { "keySeprator" in it }, warnings.toString())
        assertTrue(warnings.any { it.contains("boolean", ignoreCase = true) }, warnings.toString())
    }

    @Test
    fun `a valid file has no warning from the schema`() {
        val warnings = schemaWarnings(
            ProjectConfigFile.PATH,
            """{ "keySeparator": "/", "flatKeys": true, "keyOrder": "${KeyOrder.entries.first().name}", "modules": [{ "name": "web", "codeRoots": ["src"] }] }""",
        )

        assertEquals(emptyList<String>(), warnings)
    }

    @Test
    fun `setting names are completed`() {
        myFixture.configureByText(ProjectConfigFile.PATH, """{ "key<caret>" }""")

        val offered = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()

        assertTrue("keySeparator" in offered && "keyOrder" in offered, offered.toString())
    }

    @Test
    fun `another JSON file is left alone`() {
        val warnings = schemaWarnings("other.json", """{ "keySeprator": "/", "flatKeys": "yes" }""")

        assertEquals(emptyList<String>(), warnings)
    }
}
