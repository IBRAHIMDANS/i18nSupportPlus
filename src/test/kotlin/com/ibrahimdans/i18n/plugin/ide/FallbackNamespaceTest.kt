package com.ibrahimdans.i18n.plugin.ide

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.UnusedTranslationKeyInspection
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TableViewModel
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.util.TextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * i18next's `fallbackNS`: under `useTranslation('admin')`, `t('shared.ok')` is read from `common`
 * once `admin` lacks it — for resolving and usage counts, never for writing.
 */
class FallbackNamespaceTest : PlatformBaseTest() {

    private val unresolved = PluginBundle.getMessage("annotator.unresolved.key")
    private val withFallback = Config(fallbackNs = "common")

    private fun addTranslations() {
        addFileToProject("locales/en/admin.json", """{"dashboard": {"title": "Dashboard"}, "both": "From admin"}""")
        addFileToProject("locales/en/common.json", """{"shared": {"ok": "OK"}, "both": "From common", "dead": "x"}""")
        addFileToProject("locales/en/extra.json", """{"only": {"extra": "Extra"}}""")
    }

    private fun openComponent(calls: String) {
        val file = addFileToProject(
            "src/App.tsx",
            "import { useTranslation } from 'react-i18next';\n" +
                "export const A = () => { const { t } = useTranslation('admin'); return [$calls]; };"
        )
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
    }

    private fun unresolvedKeys(): List<String> =
        myFixture.doHighlighting().filter { it.description == unresolved }
            .map { myFixture.editor.document.getText(TextRange(it.startOffset, it.endOffset)) }

    /** The value the reference on [key] lands on, in the open component. */
    private fun resolvedValue(key: String): String? {
        val element = myFixture.file.findElementAt(myFixture.file.text.indexOf(key) + 1)
        val target = listOfNotNull(element, element?.parent).firstNotNullOfOrNull { it.references.firstOrNull()?.resolve() }
        return target?.text?.trim('"')
    }

    @Test
    fun aKeyMissingFromItsNamespaceResolvesInTheFallback() = myFixture.runWithConfig(withFallback) {
        addTranslations()
        openComponent("t('shared.ok')")
        assertTrue(unresolvedKeys().isEmpty(), "${unresolvedKeys()}")
        assertEquals("OK", resolvedValue("shared.ok"))
    }

    @Test
    fun withoutFallbackTheKeyStaysUnresolved() = myFixture.runWithConfig(Config()) {
        addTranslations()
        openComponent("t('shared.ok')")
        assertEquals(listOf("shared.ok"), unresolvedKeys())
    }

    @Test
    fun theOwnNamespaceComesFirst() = myFixture.runWithConfig(withFallback) {
        addTranslations()
        openComponent("t('both')")
        assertEquals("From admin", resolvedValue("both"))
    }

    @Test
    fun severalFallbacksAreTriedInOrder() = myFixture.runWithConfig(Config(fallbackNs = "common, extra")) {
        addTranslations()
        openComponent("t('shared.ok'), t('only.extra')")
        assertTrue(unresolvedKeys().isEmpty(), "${unresolvedKeys()}")
    }

    /** A key writing its namespace is looked up there alone: a typo in it stays visible. */
    @Test
    fun aKeyWritingItsNamespaceGetsNoFallback() = myFixture.runWithConfig(withFallback) {
        addTranslations()
        openComponent("t('admin:shared.ok')")
        assertEquals(listOf("shared.ok"), unresolvedKeys().map { it.substringAfter(':') })
    }

    @Test
    fun aKeyReachedThroughTheFallbackIsNotAnOrphan() = myFixture.runWithConfig(withFallback) {
        addTranslations()
        openComponent("t('shared.ok')")
        val viewModel = TableViewModel()
        val orphans = viewModel.countUsages(project, viewModel.loadRows(project)).filter { it.usageCount == 0 }.map { it.key }
        assertFalse("common:shared.ok" in orphans, "$orphans")
        assertTrue("common:dead" in orphans, "$orphans")
    }

    @Test
    fun theInspectionDoesNotReportAKeyReachedThroughTheFallback() = myFixture.runWithConfig(withFallback) {
        addTranslations()
        openComponent("t('shared.ok')")
        myFixture.enableInspections(UnusedTranslationKeyInspection::class.java)
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("locales/en/common.json"))
        val reported = myFixture.doHighlighting().filter { it.description == PluginBundle.getMessage("inspection.unused.message") }
            .map { myFixture.editor.document.getText(TextRange(it.startOffset, it.endOffset)).trim('"') }
        assertFalse("ok" in reported, "$reported")
        assertTrue("dead" in reported, "$reported")
    }

    /** Writing — creating a missing key, extracting — goes to the key's own namespace only. */
    @Test
    fun theFallbackIsForReadingOnly() {
        val key = FullKey("shared.ok", null, listOf(Literal("shared"), Literal("ok")), namespaces = listOf("admin"), fallbackNamespaces = listOf("common"))
        assertEquals(listOf("admin"), key.allNamespaces())
        assertEquals(listOf("admin", "common"), key.lookupNamespaces())
    }

    @Test
    fun aKeyWithoutNamespacesKeepsTheLookupOverEveryFile() {
        val key = FullKey("shared.ok", null, listOf(Literal("shared"), Literal("ok")), fallbackNamespaces = listOf("common"))
        assertEquals(emptyList<String>(), key.lookupNamespaces())
    }
}
