package com.ibrahimdans.i18n.plugin.rules

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.Lang
import com.ibrahimdans.i18n.extensions.lang.js.JsLang
import com.ibrahimdans.i18n.extensions.lang.js.JsxLang
import com.ibrahimdans.i18n.extract.CodeExtractors
import com.ibrahimdans.i18n.extract.i18next.findKeys
import com.ibrahimdans.i18n.extract.treesitter.TreeSitterCodeNode
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.parser.ExpressionNormalizer
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Plugin = `i18n-check --code`: the keys the PSI extractors find in an example file are the keys the
 * tree-sitter extractors find there, line for line, with the same namespaces and prefixes. Both
 * sides read the frameworks' `FrameworkPresets`; this is what says they read them alike.
 */
class ExtractorParityTest : PlatformBaseTest() {

    private val root = File(".").canonicalFile.let { dir -> generateSequence(dir) { it.parentFile }.first { File(it, "examples").isDirectory } }

    /** `line: key [namespaces] prefix=…`, a key as the key parser sees it once a template's tokens are dropped. */
    private fun describe(line: Int, key: RawKey): String {
        val normalizer = ExpressionNormalizer()
        val text = key.keyElements.mapNotNull(normalizer::normalize).joinToString("") { it.text }
        val namespaces = key.arguments.takeIf { it.isNotEmpty() }?.joinToString(", ", " [", "]").orEmpty()
        val prefix = key.keyPrefix.takeIf { it.isNotEmpty() }?.joinToString("") { it.text }?.let { " prefix=$it" }.orEmpty()
        return "$line: $text$namespaces$prefix"
    }

    private fun lineOf(text: String, offset: Int) = text.substring(0, offset).count { it == '\n' } + 1

    /** What the annotator annotates in [path]: each element the language reads a key from, its leaf token left out as the annotator leaves it. */
    private fun psiKeys(path: String): List<String> {
        val text = root.resolve(path).readText()
        val lang: Lang = if (path.endsWith(".tsx")) JsxLang() else JsLang()
        val names = Extensions.TECHNOLOGY.extensionList.flatMap { it.translationFunctionNames() }
        myFixture.configureByText(File(path).name, text)
        val keys = linkedSetOf<String>()
        PsiTreeUtil.processElements(myFixture.file) { element ->
            if (lang.canExtractKey(element, names) && !isLeafOfClaimedParent(element, lang, names)) {
                lang.extractRawKey(element)?.let { keys += describe(lineOf(text, element.textRange.startOffset), it) }
            }
            true
        }
        return keys.toList()
    }

    private fun isLeafOfClaimedParent(element: PsiElement, lang: Lang, names: List<String>): Boolean {
        if (element.firstChild != null) return false
        val parent = element.parent ?: return false
        return parent.firstChild === element && lang.canExtractKey(parent, names)
    }

    /** What `i18n-check --code` reads in [path], outside any module: every framework's extractors. */
    private fun treeSitterKeys(path: String): List<String> {
        val text = root.resolve(path).readText()
        val tree = TreeSitterCodeNode.parseTsx(text, path)
        return findKeys(tree, CodeExtractors.forPreset(null)).map { (node, key) -> describe(lineOf(text, node.span.start), key) }.distinct()
    }

    private fun assertParity(vararg paths: String) {
        for (path in paths) {
            val psi = psiKeys(path)
            assertTrue(psi.isNotEmpty(), "$path: the plugin reads no key there")
            assertEquals(psi, treeSitterKeys(path), path)
        }
    }

    @Test
    fun testReactIntlExample() = assertParity(
        "examples/react-intl/src/App.tsx",
        "examples/react-intl/src/Cart.tsx",
        "examples/react-intl/src/Profile.tsx",
        "examples/react-intl/src/Orders.tsx",
    )

    @Test
    fun testLinguiExample() = assertParity(
        "examples/lingui/src/App.tsx",
        "examples/lingui/src/nav.ts",
        "examples/lingui/src/notices.ts",
    )

    @Test
    fun testNothingReadWhereNothingIsAKey() {
        for (path in listOf("examples/react-intl/src/NotIntl.tsx", "examples/lingui/src/notLingui.ts")) {
            assertEquals(emptyList<String>(), psiKeys(path), path)
            assertEquals(emptyList<String>(), treeSitterKeys(path), path)
        }
    }

    @Test
    fun testI18nextExample() = assertParity(
        "examples/react-multi-namespace/src/components/Dashboard.tsx",
        "examples/react-multi-namespace/src/components/LoginForm.tsx",
        "examples/react-multi-namespace/src/components/DepositBox.tsx",
    )
}
