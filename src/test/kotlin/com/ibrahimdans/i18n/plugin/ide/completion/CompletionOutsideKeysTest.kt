package com.ibrahimdans.i18n.plugin.ide.completion

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.codeInsight.completion.CompletionType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A string that is no key keeps the IDE's own suggestions (issue #1056). */
class CompletionOutsideKeysTest : PlatformBaseTest() {

    private val key = "zzChannelTitle"

    /** The text of the keys offered at the caret, a sole variant having been inserted. */
    private fun offered(name: String, code: String): String {
        addFileToProject("locales/en/translation.json", """{"$key": "Zz methods", "zzOther": "Zz methods too"}""")
        myFixture.configureByText(name, code)
        myFixture.complete(CompletionType.BASIC, 1)
        return myFixture.lookupElementStrings?.joinToString() ?: myFixture.editor.document.text
    }

    private fun assertNotOffered(name: String, code: String) = myFixture.runWithConfig(Config()) {
        val offered = offered(name, code)
        assertFalse(key in offered, offered)
    }

    private fun assertOffered(name: String, code: String) = myFixture.runWithConfig(Config()) {
        val offered = offered(name, code)
        assertTrue(key in offered, offered)
    }

    @Test
    fun testImportPathIsCompletedWithFiles() = myFixture.runWithConfig(Config()) {
        addFileToProject("methods/alphaMethod.ts", "export {}")
        addFileToProject("methods/betaMethod.ts", "export {}")
        val offered = offered("index.ts", "import './methods/<caret>';")
        assertTrue("alphaMethod" in offered && "betaMethod" in offered, offered)
        assertFalse(key in offered, offered)
    }

    @Test
    fun testImportFrom() = assertNotOffered("index.ts", "import { a } from '<caret>';")

    @Test
    fun testExportFrom() = assertNotOffered("index.ts", "export * from '<caret>';")

    @Test
    fun testDynamicImport() = assertNotOffered("index.js", "const a = import('<caret>');")

    @Test
    fun testRequire() = assertNotOffered("index.js", "const a = require('<caret>');")

    @Test
    fun testStringOutsideATranslationCall() = assertNotOffered("index.tsx", "const a = <div className={'<caret>'}/>;")

    @Test
    fun testSecondArgumentOfATranslationCall() = assertNotOffered("index.ts", "i18n.t('a', '<caret>');")

    @Test
    fun testPhpInclude() = assertNotOffered("index.php", "<?php include '<caret>';")

    @Test
    fun testTranslationCall() = assertOffered("index.ts", "i18n.t('zz<caret>');")

    @Test
    fun testTernaryBranch() = assertOffered("index.ts", "i18n.t(a ? 'zz<caret>' : 'b');")

    @Test
    fun testFallbackKeys() = assertOffered("index.ts", "i18n.t(['a', 'zz<caret>']);")

    @Test
    fun testTransAttribute() = assertOffered("a.tsx", "const a = <Trans i18nKey=\"zz<caret>\"/>;")

    @Test
    fun testTransAttributeInBraces() = assertOffered("a.tsx", "const a = <Trans i18nKey={'zz<caret>'}/>;")

    @Test
    fun testNextIntlHas() = assertOffered(
        "a.tsx", "function A() { const t = useTranslations(); return t.has('zz<caret>') }"
    )
}
