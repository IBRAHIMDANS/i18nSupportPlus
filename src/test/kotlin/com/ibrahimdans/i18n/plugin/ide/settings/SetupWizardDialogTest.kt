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
 * never run. The name is what keeps it out: `build.gradle.kts` disables the junit-vintage engine,
 * so nothing is discovered by the JUnit 3 `test*` convention — only Jupiter annotations count.
 */
class SetupWizardDialogTest : PlatformBaseTest() {

    // -----------------------------------------------------------------------
    // Framework detection logic (pure logic, extracted from detectFrameworks)
    // -----------------------------------------------------------------------

    /**
     * Exercises the real detection used by the wizard. It lives in [FrameworkDetector]
     * precisely so it can run headlessly, without instantiating the dialog.
     */
    private fun detectFrameworksFromContent(content: String): Set<String> =
        FrameworkDetector.detect(content)

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
    fun `detectFrameworks detects react-intl`() {
        val content = """{"dependencies": {"react-intl": "^6.0.0"}}"""

        val detected = detectFrameworksFromContent(content)

        assertTrue(detected.contains("react-intl"))
    }

    @org.junit.jupiter.api.Test
    fun `detectFrameworks detects react-intl from formatjs intl`() {
        val content = """{"dependencies": {"@formatjs/intl": "^2.0.0"}}"""

        val detected = detectFrameworksFromContent(content)

        assertTrue(detected.contains("react-intl"))
    }

    /**
     * A longer package sharing the prefix must not be mistaken for react-intl —
     * react-intl-universal is a different library with a different API.
     */
    @org.junit.jupiter.api.Test
    fun `detectFrameworks does not detect react-intl from a prefixed package`() {
        val content = """{"dependencies": {"react-intl-universal": "^2.0.0"}}"""

        val detected = detectFrameworksFromContent(content)

        assertTrue(detected.isEmpty(), "react-intl-universal must not match react-intl")
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
     * Exercises the real scan used by the wizard. It lives in [TranslationFileScanner] so it
     * can run headlessly — this helper used to hold a *copy* of the folder and extension
     * tables, which meant these tests would have kept passing had the shipped ones been
     * broken. The same trap #155 removed from framework detection.
     */
    private fun scanTranslationFiles(base: File): List<String> =
        TranslationFileScanner.scan(base)

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
    // Lingui macro package detection
    // -----------------------------------------------------------------------

    @org.junit.jupiter.api.Test
    fun `detectFrameworks detects lingui when only @lingui macro is present`() {
        val content = """{"dependencies": {"@lingui/macro": "^4.0.0"}}"""

        val detected = detectFrameworksFromContent(content)

        assertTrue(detected.contains("lingui"), "@lingui/macro alone should trigger lingui detection")
        assertFalse(detected.contains("i18next"))
        assertFalse(detected.contains("vue-i18n"))
    }

    @org.junit.jupiter.api.Test
    fun `detectFrameworks detects lingui when only @lingui react macro is present`() {
        val content = """{"dependencies": {"@lingui/react/macro": "^4.0.0"}}"""

        val detected = detectFrameworksFromContent(content)

        assertTrue(detected.contains("lingui"), "@lingui/react/macro alone should trigger lingui detection")
    }

    // -----------------------------------------------------------------------
    // PO/POT file scanning
    // -----------------------------------------------------------------------

    @org.junit.jupiter.api.Test
    fun `scanTranslationFiles finds po files in locales folder`() {
        val tempDir = createTempDir("wizard-scan-po")
        try {
            val lcMessages = File(tempDir, "locales/fr/LC_MESSAGES").also { it.mkdirs() }
            File(lcMessages, "messages.po").writeText("msgid \"\"\nmsgstr \"\"")

            val found = scanTranslationFiles(tempDir)

            assertTrue(found.any { it.contains("messages.po") }, "messages.po should be found, got: $found")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @org.junit.jupiter.api.Test
    fun `scanTranslationFiles finds pot files in locales folder`() {
        val tempDir = createTempDir("wizard-scan-pot")
        try {
            val localesDir = File(tempDir, "locales").also { it.mkdirs() }
            File(localesDir, "messages.pot").writeText("msgid \"\"\nmsgstr \"\"")

            val found = scanTranslationFiles(tempDir)

            assertTrue(found.any { it.contains("messages.pot") }, "messages.pot should be found, got: $found")
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

    // -----------------------------------------------------------------------
    // Frameworks shipped since #151 but missing from the wizard
    // -----------------------------------------------------------------------

    @org.junit.jupiter.api.Test
    fun `detectFrameworks detects ngx-translate`() {
        val detected = detectFrameworksFromContent(
            """{"dependencies": {"@ngx-translate/core": "^15.0.0", "@angular/core": "^17.0.0"}}"""
        )

        assertEquals(setOf("ngx-translate"), detected)
    }

    @org.junit.jupiter.api.Test
    fun `detectFrameworks detects svelte-i18n`() {
        val detected = detectFrameworksFromContent(
            """{"dependencies": {"svelte-i18n": "^4.0.0"}}"""
        )

        assertEquals(setOf("svelte-i18n"), detected)
    }

    @org.junit.jupiter.api.Test
    fun `detectFrameworks detects i18n-js`() {
        val detected = detectFrameworksFromContent(
            """{"dependencies": {"i18n-js": "^4.4.0", "expo": "^51.0.0"}}"""
        )

        assertEquals(setOf("i18n-js"), detected)
    }

    /** Every framework the wizard knows must offer a checkbox, or it stays invisible. */
    @org.junit.jupiter.api.Test
    fun `every detected framework carries a label`() {
        assertEquals(
            FrameworkDetector.FRAMEWORK_KEYS.keys,
            FrameworkDetector.LABELS.keys,
            "the wizard builds its checkboxes from both tables"
        )
    }

    // -----------------------------------------------------------------------
    // Folder names added for the flat / GetText layouts
    // -----------------------------------------------------------------------

    @org.junit.jupiter.api.Test
    fun `scanTranslationFiles finds files in a lang folder`() {
        val tempDir = createTempDir("wizard-lang")
        try {
            File(tempDir, "lang").mkdirs()
            File(tempDir, "lang/fr.json").writeText("{}")

            assertEquals(listOf("lang${File.separator}fr.json"), scanTranslationFiles(tempDir))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @org.junit.jupiter.api.Test
    fun `scanTranslationFiles finds files in a locale folder`() {
        val tempDir = createTempDir("wizard-locale")
        try {
            File(tempDir, "locale/fr/LC_MESSAGES").mkdirs()
            File(tempDir, "locale/fr/LC_MESSAGES/messages.po").writeText("")

            assertEquals(
                listOf(listOf("locale", "fr", "LC_MESSAGES", "messages.po").joinToString(File.separator)),
                scanTranslationFiles(tempDir)
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
