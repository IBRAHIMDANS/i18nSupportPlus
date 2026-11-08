package com.ibrahimdans.i18n.plugin.rules

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.Lang
import com.ibrahimdans.i18n.extensions.lang.js.JsLang
import com.ibrahimdans.i18n.extensions.lang.js.JsxLang
import com.ibrahimdans.i18n.extract.CodeExtractors
import com.ibrahimdans.i18n.extract.ConstantKeys
import com.ibrahimdans.i18n.extract.treesitter.TreeSitterCodeNode
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runVue
import com.ibrahimdans.i18n.plugin.parser.ExpressionNormalizer
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.extract.svelte.SvelteSfc
import com.ibrahimdans.i18n.extract.vuei18n.VueSfc
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Plugin = `i18n-check --code`: the keys the PSI extractors find in an example file are the keys the
 * tree-sitter extractors find there, line for line, with the same namespaces and prefixes. Both
 * sides read the frameworks' `FrameworkPresets`; this is what says they read them alike.
 *
 * Every code file of every example the CLI reads is compared, found by walking the example rather than listed: a file
 * added to an example is compared from then on. An example the CLI does not read through `CodeExtractors` is
 * [EXCLUDED] with its reason, and a line the two sides read differently is a [KNOWN_GAPS] entry, never a file left out.
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
    private fun psiKeys(path: String): List<String> = psiKeysOf(path, root.resolve(path).readText())

    /** [psiKeys] of [text], read as a file named like [path]. */
    private fun psiKeysOf(path: String, text: String): List<String> {
        val lang: Lang = if (path.endsWith(".tsx") || path.endsWith(".jsx")) JsxLang() else JsLang()
        val names = Extensions.TECHNOLOGY.extensionList.flatMap { it.translationFunctionNames() }
        myFixture.configureByText(File(path).name, text)
        val keys = linkedSetOf<String>()
        val injections = InjectedLanguageManager.getInstance(project)
        fun read(file: PsiElement) = PsiTreeUtil.processElements(file) { element ->
            if (lang.canExtractKey(element, names) && !isLeafOfClaimedParent(element, lang, names)) {
                val offset = injections.injectedToHost(element, element.textRange.startOffset)
                lang.extractRawKey(element)?.let { keys += describe(lineOf(text, offset), it) }
            }
            true
        }
        read(myFixture.file)
        // A Vue template's `{{ … }}` and bound attributes are injected JavaScript, outside the host's PSI.
        PsiTreeUtil.processElements(myFixture.file) { element ->
            if (element is PsiLanguageInjectionHost) injections.enumerate(element) { injected, _ -> read(injected) }
            true
        }
        return if (isComponent(path)) keys.sortedBy(::lineOfKey) else keys.toList()
    }

    private fun isComponent(path: String) = path.endsWith(".vue") || path.endsWith(".svelte")

    private fun lineOfKey(described: String) = described.substringBefore(':').toInt()

    private fun isLeafOfClaimedParent(element: PsiElement, lang: Lang, names: List<String>): Boolean {
        if (element.firstChild != null) return false
        val parent = element.parent ?: return false
        return parent.firstChild === element && lang.canExtractKey(parent, names)
    }

    /** What `i18n-check --code` reads in [path], outside any module: every framework's extractors. */
    private fun treeSitterKeys(path: String): List<String> = treeSitterKeysOf(path, root.resolve(path).readText())

    /** [treeSitterKeys] of [text], read as a file named like [path], the keys passed through a constant included. */
    private fun treeSitterKeysOf(path: String, text: String): List<String> {
        // As `CodeUsages` reads a component: its code keeps the component's length, so offsets and lines are the file's.
        val code = when {
            path.endsWith(".vue") -> VueSfc.codeOf(text)
            path.endsWith(".svelte") -> SvelteSfc.codeOf(text)
            else -> text
        }
        val tree = TreeSitterCodeNode.parseTsx(code, path)
        val keys = ConstantKeys.findKeys(code, tree, CodeExtractors.forPreset(null)).map { (node, key) -> describe(lineOf(text, node.span.start), key) }.distinct()
        return if (isComponent(path)) keys.sortedBy(::lineOfKey) else keys
    }

    /**
     * The code files of [example] that `i18n-check --code` reads (`CodeUsages.EXTENSIONS`), project-relative, sorted.
     * Left out: the `.html` files — the CLI reads them as Angular templates through `AngularTemplate` only in an Angular
     * project, where the plugin's side is the Angular plugin's injected expressions, not a `Lang` this test can walk.
     */
    private fun codeFiles(example: String): List<String> =
        root.resolve("examples/$example").walkTopDown()
            .onEnter { it.name !in SKIPPED_DIRECTORIES }
            .filter { it.isFile && it.extension in SCRIPTS }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .sorted()
            .toList()

    /**
     * Every code file of [example] read alike by both sides, line for line. A line of [KNOWN_GAPS] is left out on both
     * sides, and must still differ: a gap that closes fails here until its entry goes. The example must hold a key.
     */
    private fun assertExample(example: String) {
        val files = codeFiles(example)
        assertTrue(files.isNotEmpty(), "examples/$example: no code file")
        val mismatches = mutableListOf<String>()
        var keys = 0
        for (path in files) {
            val gaps = KNOWN_GAPS.filterKeys { it.substringBeforeLast(':') == path }.keys.map { it.substringAfterLast(':').toInt() }.toSet()
            val psi = psiKeys(path)
            if (isComponent(path)) assertTrue(myFixture.file.fileType.name != "PLAIN_TEXT", "$path: read as plain text, its plugin is not loaded")
            val treeSitter = treeSitterKeys(path)
            keys += psi.size
            for (line in gaps) {
                if (psi.filter { lineOfKey(it) == line } == treeSitter.filter { lineOfKey(it) == line }) {
                    mismatches += "$path:$line is listed in KNOWN_GAPS but both sides now read it alike: remove the entry"
                }
            }
            val psiKept = psi.filterNot { lineOfKey(it) in gaps }
            val treeSitterKept = treeSitter.filterNot { lineOfKey(it) in gaps }
            if (psiKept != treeSitterKept) {
                mismatches += "$path\n  plugin only: ${psiKept - treeSitterKept.toSet()}\n  i18n-check only: ${treeSitterKept - psiKept.toSet()}\n" +
                    "  plugin: $psiKept\n  i18n-check: $treeSitterKept"
            }
        }
        assertTrue(keys > 0, "examples/$example: the plugin reads no key there")
        assertEquals(emptyList<String>(), mismatches, "examples/$example")
    }

    /** A new example is either compared here or excluded with its reason: none slips past both lists. */
    @Test
    fun testEveryExampleIsComparedOrExcluded() {
        val examples = root.resolve("examples").listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }.map { it.name }.toSet()
        assertEquals(emptySet<String>(), COMPARED intersect EXCLUDED.keys, "an example is both compared and excluded")
        assertEquals(examples.sorted(), (COMPARED + EXCLUDED.keys).sorted())
    }

    @Test
    fun testAngularTranslocoExample() = assertExample("angular-transloco")

    @Test
    fun testLinguiExample() = assertExample("lingui")

    @Test
    fun testMonorepoReactExample() = assertExample("monorepo-react")

    @Test
    fun testNextIntlExample() = assertExample("next-intl")

    @Test
    fun testReactI18nUsingHooksExample() = assertExample("react-i18n-using-hooks")

    @Test
    fun testReactIntlExample() = assertExample("react-intl")

    @Test
    fun testI18nextExample() = assertExample("react-multi-namespace")

    @Test
    fun testSimpleExample() = assertExample("simple")

    @Test
    fun testSvelteI18nExample() = assertExample("svelte-i18n")

    @Test
    fun testVueI18nExample() = runVue { assertExample("vue-i18n") }

    @Test
    fun testNothingReadWhereNothingIsAKey() {
        for (path in listOf("examples/react-intl/src/NotIntl.tsx", "examples/lingui/src/notLingui.ts")) {
            assertEquals(emptyList<String>(), psiKeys(path), path)
            assertEquals(emptyList<String>(), treeSitterKeys(path), path)
        }
    }

    @Test
    fun testKeysKeptInConstants() {
        val text = """
            const SAVE = 'actions.save';
            const KEYS = { page: { home: 'page.home' } } as const;
            enum Titles { Cart = 'page.cart' }
            export function Page() {
              const { t } = useTranslation('common');
              return [t(SAVE), t(KEYS.page.home), t(Titles.Cart)];
            }
        """.trimIndent()
        val psi = psiKeysOf("Page.tsx", text)
        assertEquals(3, psi.size, psi.toString())
        assertEquals(psi, treeSitterKeysOf("Page.tsx", text))
    }

    private companion object {
        /** `CodeUsages.EXTENSIONS`: what `i18n-check --code` reads outside an Angular or Laravel project. */
        val SCRIPTS = setOf("js", "jsx", "ts", "tsx", "mjs", "cjs", "vue", "svelte")

        /** Build output and dependencies: `SourceDiscovery.DEFAULT_EXCLUDED_DIRECTORIES` leaves them out too. */
        val SKIPPED_DIRECTORIES = setOf("node_modules", "build", "dist", ".svelte-kit", ".next")

        /** The examples whose code `i18n-check --code` reads with `CodeExtractors`, each compared file by file. */
        val COMPARED = setOf(
            "angular-transloco", "lingui", "monorepo-react", "next-intl", "react-i18n-using-hooks", "react-intl",
            "react-multi-namespace", "simple", "svelte-i18n", "vue-i18n",
        )

        /** The examples left out, and why: none is read through `CodeExtractors`. */
        val EXCLUDED = mapOf(
            "angular-native-i18n" to "Angular's own i18n marks are read by `AngularMessageIds`, not by `CodeExtractors`",
            "paraglide-js" to "Paraglide's `m.key()` calls are read by the plugin only, `i18n-check --code` has no extractor for them",
            "typesafe-i18n" to "typesafe-i18n's `LL.key()` calls are read by the plugin only, `i18n-check --code` has no extractor for them",
            "laravel" to "PHP and Blade: the CLI reads them through `LaravelSource`, the plugin through `PhpLang`; not a TSX comparison",
            "php" to "PHP gettext: `i18n-check --code` does not read PHP outside a Laravel project",
            "symfony" to "Twig and PHP: `i18n-check --code` does not read them",
            "flutter-arb" to "Dart: `i18n-check --code` does not read it",
            "spring-messages" to "Java: `i18n-check --code` does not read it",
        )

        /**
         * `path:line` read differently by the two sides, and why. Each is a difference to look into, never a file taken
         * out of the comparison: the rest of the file is still compared.
         */
        val KNOWN_GAPS = mapOf(
            "examples/vue-i18n/src/components/OrderSummary.vue:11" to
                "a template literal key in a `<script lang=\"ts\">` block: the plugin reads it as an empty key, " +
                "i18n-check as `order.status.\${props.status}` (in a `.ts` or `.tsx` file both read the latter)",
        )
    }
}
