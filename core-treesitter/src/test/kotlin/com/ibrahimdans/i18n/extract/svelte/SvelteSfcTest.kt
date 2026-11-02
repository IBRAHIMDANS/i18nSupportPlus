package com.ibrahimdans.i18n.extract.svelte

import com.ibrahimdans.i18n.extract.CodeExtractors
import com.ibrahimdans.i18n.extract.i18next.findKeys
import com.ibrahimdans.i18n.extract.treesitter.TreeSitterCodeNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.File

class SvelteSfcTest {

    private val root = File("..").canonicalFile

    /** `line: key` for each key of [text], read as `i18n-check` reads a `.svelte` file (or, with [component] false, a script). */
    private fun keysOf(text: String, preset: String? = CodeExtractors.SVELTE_I18N, component: Boolean = true): List<String> {
        val code = if (component) SvelteSfc.codeOf(text) else text
        assertEquals(text.length, code.length, "the code keeps the component's offsets")
        val tree = TreeSitterCodeNode.parseTsx(code)
        assertFalse(tree.hasError, "the code parses: $code")
        return findKeys(tree, CodeExtractors.forPreset(preset)).map { (node, key) ->
            "${text.substring(0, node.span.start).count { it == '\n' } + 1}: ${key.keyElements.joinToString("") { it.text }}"
        }
    }

    private fun example(path: String) = root.resolve("examples/svelte-i18n/src/$path").readText()

    /** `line: key` for each place a fragment is written in the example file. */
    private fun lines(path: String, vararg fragments: Pair<String, String>): List<String> {
        val text = example(path)
        return fragments.map { (fragment, key) ->
            val offset = text.indexOf(fragment).also { check(it >= 0) { "'$fragment' is not in $path" } }
            "${text.substring(0, offset).count { it == '\n' } + 1}: $key"
        }
    }

    @Test
    fun `markup expressions, attributes and block tags hold keys, their keywords and aliases do not`() {
        val component = """
            <script>
              import { _ } from 'svelte-i18n';
              const title = ${'$'}_('a');
            </script>

            <h1 title={${'$'}_('b')}>{${'$'}_('c')}</h1>
            {#if ${'$'}_('d')}
              <p>{@html ${'$'}_('e')}</p>
            {:else if ok}
              <p>text $ _('not.a.key')</p>
            {:else}
              <p>{${'$'}_({ id: 'f', values: { n: 1 } })}</p>
            {/if}
            {#each Object.values(${'$'}_('g') ?? {}) as item}
              <li>{item}</li>
            {/each}
            <!-- {${'$'}_('commented')} -->
            <style>
              p { color: red; }
            </style>
        """.trimIndent()

        assertEquals(listOf("3: a", "6: b", "6: c", "7: d", "8: e", "12: f", "14: g"), keysOf(component))
    }

    @Test
    fun `example - App svelte, script and markup, the default of format no key`() {
        assertEquals(
            lines(
                "App.svelte",
                "\"user.profile.title\"" to "user.profile.title", "\"app.title\"" to "app.title", "\"greeting\"" to "greeting",
                "\"cart.items\"" to "cart.items", "\"only_in_english\"" to "only_in_english", "\"does.not.exist\"" to "does.not.exist",
                "\"locale.switch\"" to "locale.switch",
            ),
            keysOf(example("App.svelte")),
        )
    }

    @Test
    fun `example - Nav svelte, the imported t alias and json`() {
        assertEquals(
            lines("lib/Nav.svelte", "\"nav.home\"" to "nav.home", "\"nav.settings\"" to "nav.settings", "\"nav\"" to "nav"),
            keysOf(example("lib/Nav.svelte")),
        )
    }

    @Test
    fun `example - scripts, an unwrapped store read and a local underscore not`() {
        assertEquals(
            lines("lib/labels.ts", "\"nav.settings\"" to "nav.settings", "\"greeting\"" to "greeting"),
            keysOf(example("lib/labels.ts"), component = false),
        )
        assertEquals(emptyList<String>(), keysOf(example("lib/notSvelteI18n.ts"), component = false))
    }

    @Test
    fun `without a preset, svelte-i18n is read along with the others`() {
        assertEquals(keysOf(example("App.svelte")), keysOf(example("App.svelte"), preset = null))
    }
}
