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

    // The example is documentation and gets edited (#698 added a comment line): positions are read
    // off its text rather than written here, so only a wrong position fails, not a moved one.

    /** The usage of [literal], quotes included, written once in [path] under the example. */
    private fun at(path: String, literal: String): KeyUsage {
        val file = example.resolve(path)
        val start = offsetsOf(file.readText(), literal).single()
        return KeyUsage(file.path, TextSpan(start, start + literal.length))
    }

    /** `File.tsx:line` for every place [fragment] is written in [path], as [linesOf] reports usages. */
    private fun linesWith(path: String, fragment: String): List<String> {
        val file = example.resolve(path)
        val text = file.readText()
        return offsetsOf(text, fragment).map { "${file.name}:${text.substring(0, it).count { c -> c == '\n' } + 1}" }.sorted()
    }

    private fun offsetsOf(text: String, fragment: String): List<Int> =
        generateSequence(text.indexOf(fragment).takeIf { it >= 0 }) { previous ->
            text.indexOf(fragment, previous + 1).takeIf { it >= 0 }
        }.toList().also { check(it.isNotEmpty()) { "'$fragment' is not in the example" } }

    private fun linesOf(usages: List<KeyUsage>): List<String> = usages.map { usage ->
        val file = File(usage.path)
        "${file.name}:${file.readText().substring(0, usage.span.start).count { it == '\n' } + 1}"
    }.sorted()

    // ── examples/react-multi-namespace ────────────────────────────────────────

    @Test
    fun `a key written with its namespace is found at its literal`() {
        assertEquals(
            listOf(at("components/Navigation.tsx", "'navigation:menu.home'")),
            exampleIndex.usages("navigation:menu.home"),
        )
    }

    @Test
    fun `a key written without its namespace is found under its hook's namespace only`() {
        assertEquals(listOf(at("components/LoginForm.tsx", "'login.title'")), exampleIndex.usages("auth:login.title"))
        assertEquals(emptyList<KeyUsage>(), exampleIndex.usages("common:login.title"))
    }

    @Test
    fun `a key spelled without namespace is found only in a default one`() {
        assertEquals(listOf(at("App.tsx", "'appName'")), exampleIndex.usages("common:appName"))
        assertEquals(emptyList<KeyUsage>(), exampleIndex.usages("appName"))
    }

    @Test
    fun `a Trans i18nKey attribute is a usage`() {
        assertEquals(
            listOf(at("components/DepositBox.tsx", "\"deposit-box:activity.entry.fileAdded\"")),
            exampleIndex.usages("deposit-box:activity.entry.fileAdded"),
        )
    }

    @Test
    fun `a plural form stands for the key the code writes`() {
        assertEquals(
            linesWith("components/DepositBox.tsx", "'deposit-box:myBoxes.myTrustees.modal.confirmPassword.addTrustee.description'"),
            linesOf(exampleIndex.usages("deposit-box:myBoxes.myTrustees.modal.confirmPassword.addTrustee.description_one")),
        )
    }

    @Test
    fun `a key holding an object is reached by the calls under it`() {
        assertEquals(
            linesWith("components/Navigation.tsx", "'navigation:menu."),
            linesOf(exampleIndex.usages("navigation:menu")),
        )
        assertEquals(emptyList<KeyUsage>(), exampleIndex.usages("navigation:menu.hom"))
    }

    @Test
    fun `a typo in a written namespace hides the call from the right key`() {
        assertEquals(
            linesWith("components/Navigation.tsx", "'dashboard:stats.users.count'"),
            linesOf(exampleIndex.usages("dashboard:stats.users.count")),
        )
        assertEquals(
            linesWith("components/Dashboard.tsx", "'dashboardd:statss.users.count'"),
            linesOf(exampleIndex.usages("dashboardd:statss.users.count")),
        )
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

    @Test
    fun `unknown usages - the literal calls no key is or holds`() {
        val index = TreeSitterKeyUsageIndex()
        index.update(
            "a.tsx",
            "function A() { const { t } = useTranslation('admin'); return [t('menu'), t('menu.home.icon'), t('item'), t('menu.hom'), t(`x.\${y}`)] }",
        )
        index.update("b.ts", "t('admin:menu.home'); t('other:menu.home')")
        assertEquals(
            listOf("menu.home.icon", "menu.hom", "other:menu.home"),
            index.unknownUsages(listOf("admin:menu.home", "admin:item_one")).map { it.key },
        )
        assertEquals(KeyUsage("b.ts", TextSpan(24, 41)), index.unknownUsages(listOf("admin:menu.home", "admin:item_one")).last().usage)
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
