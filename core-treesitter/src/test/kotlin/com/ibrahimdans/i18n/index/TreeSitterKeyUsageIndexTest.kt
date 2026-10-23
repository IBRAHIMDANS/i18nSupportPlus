package com.ibrahimdans.i18n.index

import com.ibrahimdans.i18n.plugin.KeyUsage
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.TextSpan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.system.measureTimeMillis

class TreeSitterKeyUsageIndexTest {

    private val root = File("..").canonicalFile
    private val example = root.resolve("examples/react-multi-namespace/src")

    private val exampleIndex = TreeSitterKeyUsageIndex().apply {
        sourcesUnder(example).forEach { update(it.path, it.readText()) }
    }

    private fun sourcesUnder(dir: File): List<File> =
        dir.walkTopDown().onEnter { it.name != "node_modules" }.filter { it.isFile && it.extension in SOURCES }.toList()

    /** The usage of [literal], quotes included, written at [line] and [column] (1-based) of [path] under the example. */
    private fun at(path: String, line: Int, column: Int, literal: String): KeyUsage {
        val file = example.resolve(path)
        val start = file.readText().lineSequence().take(line - 1).sumOf { it.length + 1 } + column - 1
        return KeyUsage(file.path, TextSpan(start, start + literal.length))
    }

    private fun linesOf(usages: List<KeyUsage>): List<String> = usages.map { usage ->
        val file = File(usage.path)
        "${file.name}:${file.readText().substring(0, usage.span.start).count { it == '\n' } + 1}"
    }.sorted()

    // ── examples/react-multi-namespace ────────────────────────────────────────

    @Test
    fun `a key written with its namespace is found at its literal`() {
        assertEquals(
            listOf(at("components/Navigation.tsx", 17, 58, "'navigation:menu.home'")),
            exampleIndex.usages("navigation:menu.home"),
        )
    }

    @Test
    fun `a key written without its namespace is found under its hook's namespace only`() {
        assertEquals(listOf(at("components/LoginForm.tsx", 30, 14, "'login.title'")), exampleIndex.usages("auth:login.title"))
        assertEquals(emptyList<KeyUsage>(), exampleIndex.usages("common:login.title"))
    }

    @Test
    fun `a key spelled without namespace is found only in a default one`() {
        assertEquals(listOf(at("App.tsx", 32, 40, "'appName'")), exampleIndex.usages("common:appName"))
        assertEquals(emptyList<KeyUsage>(), exampleIndex.usages("appName"))
    }

    @Test
    fun `a Trans i18nKey attribute is a usage`() {
        assertEquals(
            listOf(at("components/DepositBox.tsx", 62, 21, "\"deposit-box:activity.entry.fileAdded\"")),
            exampleIndex.usages("deposit-box:activity.entry.fileAdded"),
        )
    }

    @Test
    fun `a plural form stands for the key the code writes`() {
        assertEquals(
            listOf("DepositBox.tsx:31", "DepositBox.tsx:38"),
            linesOf(exampleIndex.usages("deposit-box:myBoxes.myTrustees.modal.confirmPassword.addTrustee.description_one")),
        )
    }

    @Test
    fun `a key holding an object is reached by the calls under it`() {
        assertEquals(
            listOf("Navigation.tsx:17", "Navigation.tsx:18", "Navigation.tsx:19", "Navigation.tsx:20", "Navigation.tsx:25"),
            linesOf(exampleIndex.usages("navigation:menu")),
        )
        assertEquals(emptyList<KeyUsage>(), exampleIndex.usages("navigation:menu.hom"))
    }

    @Test
    fun `a typo in a written namespace hides the call from the right key`() {
        assertEquals(listOf("Navigation.tsx:14"), linesOf(exampleIndex.usages("dashboard:stats.users.count")))
        assertEquals(listOf("Dashboard.tsx:27"), linesOf(exampleIndex.usages("dashboardd:statss.users.count")))
    }

    @Test
    fun `a template literal reaches the keys of its shape`() {
        assertEquals(
            setOf("deposit-box:status.pending", "deposit-box:status.accepted"),
            exampleIndex.dynamicallyReached(
                listOf("deposit-box:status.pending", "deposit-box:status.accepted", "deposit-box:title", "auth:status.pending"),
            ),
        )
    }

    // ── incremental updates ───────────────────────────────────────────────────

    @Test
    fun `a file updated or removed changes only its own usages`() {
        val index = TreeSitterKeyUsageIndex()
        index.update("a.ts", "t('menu.home')")
        index.update("b.ts", "t('menu.home'); t(`status.\${kind}`)")
        assertEquals(listOf(KeyUsage("a.ts", TextSpan(2, 13)), KeyUsage("b.ts", TextSpan(2, 13))), index.usages("menu.home"))
        assertEquals(setOf("status.ok"), index.dynamicallyReached(listOf("status.ok")))

        index.update("a.ts", "// moved\nt('menu.about')")
        assertEquals(listOf(KeyUsage("b.ts", TextSpan(2, 13))), index.usages("menu.home"))
        assertEquals(listOf(KeyUsage("a.ts", TextSpan(11, 23))), index.usages("menu.about"))

        index.remove("b.ts")
        assertEquals(emptyList<KeyUsage>(), index.usages("menu.home"))
        assertEquals(emptySet<String>(), index.dynamicallyReached(listOf("status.ok")))
        assertEquals(listOf(KeyUsage("a.ts", TextSpan(11, 23))), index.usages("menu"))
    }

    @Test
    fun `the settings decide the separators and the default namespace`() {
        val index = TreeSitterKeyUsageIndex(Config(keySeparator = "/", defaultNs = "common"))
        index.update("a.tsx", "function A() { const { t } = useTranslation('common'); return t('menu/home') }")
        assertEquals(listOf(KeyUsage("a.tsx", TextSpan(64, 75))), index.usages("menu/home"))
        assertEquals(emptyList<KeyUsage>(), index.usages("other:menu/home"))
    }

    @Test
    fun `a call under a hook counts in the fallback namespaces`() {
        val index = TreeSitterKeyUsageIndex(fallbackNamespaces = listOf("common"))
        index.update("a.tsx", "function A() { const { t } = useTranslation('admin'); return t('ok') }")
        assertEquals(1, index.usages("common:ok").size)
        assertEquals(0, index.usages("other:ok").size)
    }

    // ── indexing time ─────────────────────────────────────────────────────────

    @Test
    fun `indexing time of the repository's sources`() {
        val files = (sourcesUnder(root.resolve("examples")) + sourcesUnder(root.resolve("src/test/resources")))
            .map { it.path to it.readText() }
        val index = TreeSitterKeyUsageIndex()
        val millis = measureTimeMillis { files.forEach { (path, text) -> index.update(path, text) } }
        println("Indexed ${files.size} files, ${files.sumOf { it.second.length }} chars, in $millis ms")
    }

    private companion object {
        val SOURCES = setOf("js", "jsx", "ts", "tsx", "mjs", "cjs")
    }
}
