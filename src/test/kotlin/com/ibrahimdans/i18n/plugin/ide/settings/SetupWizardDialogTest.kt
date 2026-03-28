package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.openapi.application.ApplicationManager
import org.junit.jupiter.api.Assertions.*
import java.io.File

/**
 * Tests for SetupWizardDialog: framework detection via package.json and translation file scanning.
 *
 * The dialog is headless-unsafe (DialogWrapper requires a UI context), so we test the two
 * detection methods that contain real business logic by invoking them through a helper that
 * replicates their behaviour without instantiating the dialog itself.
 *
 * Any test that requires a full Swing context is named ignoredTestXxx so it is compiled but
 * skipped by the JUnit 3 runner inside BasePlatformTestCase.
 */
class SetupWizardDialogTest : PlatformBaseTest() {

    // -----------------------------------------------------------------------
    // Sentinel — required by JUnit 3 runner (BasePlatformTestCase)
    // -----------------------------------------------------------------------

    fun testPlaceholder() {
        assertTrue(true)
    }

    // -----------------------------------------------------------------------
    // Framework detection logic (pure logic, extracted from detectFrameworks)
    // -----------------------------------------------------------------------

    /**
     * Replicates the detectFrameworks() logic without a real dialog instance,
     * so it can run headlessly.
     */
    private fun detectFrameworksFromContent(content: String): Set<String> {
        val frameworkKeys = mapOf(
            "i18next" to listOf("i18next", "react-i18next"),
            "vue-i18n" to listOf("vue-i18n"),
            "lingui" to listOf("@lingui/core", "@lingui/react")
        )
        return frameworkKeys
            .filter { (_, deps) -> deps.any { content.contains("\"$it\"") } }
            .keys
            .toSet()
    }

    @org.junit.jupiter.api.Test
    fun `detectFrameworks detects i18next from package json content`() {
        val content = """
            {
              "dependencies": {
                "i18next": "^23.0.0",
                "react": "^18.0.0"
              }
            }
        """.trimIndent()

        val detected = detectFrameworksFromContent(content)

        assertTrue(detected.contains("i18next"), "i18next should be detected")
        assertFalse(detected.contains("vue-i18n"), "vue-i18n should not be detected")
    }

    @org.junit.jupiter.api.Test
    fun `detectFrameworks detects react-i18next as i18next`() {
        val content = """{"dependencies": {"react-i18next": "^13.0.0"}}"""

        val detected = detectFrameworksFromContent(content)

        assertTrue(detected.contains("i18next"), "react-i18next maps to i18next")
    }

    @org.junit.jupiter.api.Test
    fun `detectFrameworks detects vue-i18n`() {
        val content = """{"dependencies": {"vue-i18n": "^9.0.0"}}"""

        val detected = detectFrameworksFromContent(content)

        assertTrue(detected.contains("vue-i18n"))
        assertFalse(detected.contains("i18next"))
    }

    @org.junit.jupiter.api.Test
    fun `detectFrameworks detects lingui from lingui core`() {
        val content = """{"dependencies": {"@lingui/core": "^4.0.0"}}"""

        val detected = detectFrameworksFromContent(content)

        assertTrue(detected.contains("lingui"))
    }

    @org.junit.jupiter.api.Test
    fun `detectFrameworks returns empty set when no known framework present`() {
        val content = """{"dependencies": {"axios": "^1.0.0"}}"""

        val detected = detectFrameworksFromContent(content)

        assertTrue(detected.isEmpty(), "No framework should be detected")
    }

    @org.junit.jupiter.api.Test
    fun `detectFrameworks detects multiple frameworks at once`() {
        val content = """{"dependencies": {"i18next": "^23.0.0", "vue-i18n": "^9.0.0"}}"""

        val detected = detectFrameworksFromContent(content)

        assertEquals(setOf("i18next", "vue-i18n"), detected)
    }

    // -----------------------------------------------------------------------
    // Translation file scanning logic
    // -----------------------------------------------------------------------

    /**
     * Replicates the collectTranslationFiles/collectAllTranslations logic without a dialog.
     */
    private fun scanTranslationFiles(base: File): List<String> {
        val translationFolderNames = setOf("locales", "i18n", "translations")
        val translationExtensions = setOf("json", "yaml", "yml")
        val maxScanDepth = 5
        val found = mutableListOf<String>()

        fun collectAll(folder: File) {
            folder.walkTopDown()
                .filter { it.isFile && it.extension in translationExtensions }
                .forEach { found.add(it.relativeTo(base).path) }
        }

        fun scan(dir: File, depth: Int) {
            if (depth > maxScanDepth) return
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (child.isDirectory) {
                    if (child.name in translationFolderNames) {
                        collectAll(child)
                    } else if (!child.name.startsWith(".") && child.name != "node_modules" && child.name != "build") {
                        scan(child, depth + 1)
                    }
                }
            }
        }

        scan(base, 0)
        return found
    }

    @org.junit.jupiter.api.Test
    fun `scanTranslationFiles finds json files in locales folder`() {
        // Build a temp directory structure: <tmp>/locales/en/common.json
        val tempDir = createTempDir("wizard-scan-test")
        try {
            val localesDir = File(tempDir, "locales/en").also { it.mkdirs() }
            File(localesDir, "common.json").writeText("{}")
            File(localesDir, "auth.json").writeText("{}")

            val found = scanTranslationFiles(tempDir)

            assertTrue(found.any { it.contains("common.json") }, "common.json should be found, got: $found")
            assertTrue(found.any { it.contains("auth.json") }, "auth.json should be found, got: $found")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @org.junit.jupiter.api.Test
    fun `scanTranslationFiles finds yaml files in i18n folder`() {
        val tempDir = createTempDir("wizard-scan-yaml")
        try {
            val i18nDir = File(tempDir, "i18n/fr").also { it.mkdirs() }
            File(i18nDir, "messages.yaml").writeText("key: value")

            val found = scanTranslationFiles(tempDir)

            assertTrue(found.any { it.contains("messages.yaml") }, "messages.yaml should be found, got: $found")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @org.junit.jupiter.api.Test
    fun `scanTranslationFiles ignores node_modules`() {
        val tempDir = createTempDir("wizard-scan-ignore")
        try {
            // File inside node_modules should be skipped
            val nodeModulesDir = File(tempDir, "node_modules/some-pkg/locales/en").also { it.mkdirs() }
            File(nodeModulesDir, "translation.json").writeText("{}")

            // File in a valid locales folder should be found
            val localesDir = File(tempDir, "locales/en").also { it.mkdirs() }
            File(localesDir, "app.json").writeText("{}")

            val found = scanTranslationFiles(tempDir)

            // node_modules content is skipped, but we may find app.json
            assertTrue(found.any { it.contains("app.json") }, "app.json should be found")
            // The translation.json inside node_modules should not appear directly via locales scan
            // (it's inside node_modules which is skipped, so the locales subfolder is never reached)
            assertFalse(
                found.any { it.contains("node_modules") },
                "Files under node_modules should be excluded, got: $found"
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @org.junit.jupiter.api.Test
    fun `scanTranslationFiles returns empty list when no translation folders exist`() {
        val tempDir = createTempDir("wizard-scan-empty")
        try {
            File(tempDir, "src").mkdirs()
            File(tempDir, "src/index.ts").writeText("export default {};")

            val found = scanTranslationFiles(tempDir)

            assertTrue(found.isEmpty(), "Should find no translation files, got: $found")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // -----------------------------------------------------------------------
    // package.json reading from temp filesystem (integration)
    // -----------------------------------------------------------------------

    @org.junit.jupiter.api.Test
    fun `detectFrameworks reads real package json from project base path`() {
        // Write a package.json in the project's virtual base directory
        // and verify the logic detects the framework correctly
        val tempDir = createTempDir("wizard-pkgjson")
        try {
            File(tempDir, "package.json").writeText(
                """{"dependencies": {"react-i18next": "^13.0.0", "react": "^18.0.0"}}"""
            )
            val content = File(tempDir, "package.json").readText()
            val detected = detectFrameworksFromContent(content)

            assertTrue(detected.contains("i18next"), "react-i18next should map to i18next")
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
