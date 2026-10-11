package com.ibrahimdans.i18n.plugin.ide.annotator

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.annotator.KeyDiagnosis.NamespaceOrigin
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * The lines [KeyDiagnosis] writes under an unresolved key: one reason per case, in the bundle's
 * English text. A platform test only for the bundle — the diagnosis itself touches no PSI.
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class KeyDiagnosisTest : PlatformBaseTest() {

    @Test
    fun `a key partially resolved names the segment that exists and the one that does not`() {
        val diagnosis = KeyDiagnosis(
            namespaces = listOf("common"),
            origin = NamespaceOrigin.WRITTEN,
            searched = listOf("locales/en/common.json", "locales/fr/common.json"),
            found = "user",
            missing = "name",
        )
        Assertions.assertEquals(
            listOf(
                "Namespace 'common', written in the key",
                "Looked up in locales/en/common.json, locales/fr/common.json",
                "'user' exists, 'name' does not",
            ),
            diagnosis.explain(),
        )
    }

    @Test
    fun `a first segment missing is said to be missing at the top`() {
        val lines = KeyDiagnosis(listOf("common"), NamespaceOrigin.WRITTEN, searched = listOf("en/common.json"), missing = "nope").explain()
        Assertions.assertEquals("'nope' does not exist at the top of these files", lines.last())
    }

    @Test
    fun `a namespace without a file in its module`() {
        val lines = KeyDiagnosis(listOf("ghost"), NamespaceOrigin.WRITTEN, module = "web").explain()
        Assertions.assertEquals(
            listOf("Namespace 'ghost', written in the key, in module 'web'", "No file for namespace 'ghost' in module 'web'"),
            lines,
        )
    }

    @Test
    fun `a namespace without a file in the project`() {
        val lines = KeyDiagnosis(listOf("ghost"), NamespaceOrigin.WRITTEN).explain()
        Assertions.assertEquals("No file for namespace 'ghost' in the project", lines.last())
    }

    @Test
    fun `a namespace whose files lie outside the module`() {
        val lines = KeyDiagnosis(
            listOf("settings"), NamespaceOrigin.WRITTEN, module = "web",
            outsideModule = listOf("apps/admin/locales/en/settings.json"),
        ).explain()
        Assertions.assertEquals(
            "No file for namespace 'settings' in module 'web', only outside it: apps/admin/locales/en/settings.json",
            lines.last(),
        )
    }

    @Test
    fun `a namespace read by the module's key template`() {
        val lines = KeyDiagnosis(listOf("guide"), NamespaceOrigin.KEY_TEMPLATE, keyTemplate = "{ns}.{key}", module = "docs").explain()
        Assertions.assertEquals("Namespace 'guide', read by the key template {ns}.{key}, in module 'docs'", lines.first())
    }

    @Test
    fun `a key template reading no namespace falls back to the default`() {
        val lines = KeyDiagnosis(listOf("common"), NamespaceOrigin.KEY_TEMPLATE_DEFAULT, keyTemplate = "{key}").explain()
        Assertions.assertEquals("The key template {key} reads no namespace: default 'common'", lines.first())
    }

    @Test
    fun `the namespaces of the call and the default one`() {
        Assertions.assertEquals(
            "Namespace 'dashboard, common', from the call (useTranslation hook or ns option)",
            KeyDiagnosis(listOf("dashboard", "common"), NamespaceOrigin.CALL).explain().first(),
        )
        Assertions.assertEquals(
            "No namespace in the key: default 'common'",
            KeyDiagnosis(listOf("common"), NamespaceOrigin.DEFAULT).explain().first(),
        )
    }

    @Test
    fun `a long list of files is cut`() {
        val files = (1..5).map { "l$it/common.json" }
        val line = KeyDiagnosis(listOf("common"), NamespaceOrigin.WRITTEN, searched = files).explain()[1]
        Assertions.assertEquals("Looked up in l1/common.json, l2/common.json, l3/common.json (+2)", line)
    }
}
