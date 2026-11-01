package com.ibrahimdans.i18n.extract.rules

import com.ibrahimdans.i18n.extract.CodeExtractors
import com.ibrahimdans.i18n.extract.i18next.findKeys
import com.ibrahimdans.i18n.extract.treesitter.TreeSitterCodeNode
import com.ibrahimdans.i18n.plugin.rules.FrameworkPresets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The keys the preset-driven extractors find in the react-intl and Lingui examples, against those
 * the plugin underlines there — read off its extractors and the examples' READMEs, which list what
 * each call site covers. One line per key: `line: key`.
 */
class ReactIntlLinguiParityTest {

    private val root = File("..").canonicalFile

    private fun keysOf(example: String, path: String, preset: String?): List<String> {
        val file = root.resolve("examples/$example/src/$path")
        val text = file.readText()
        val tree = TreeSitterCodeNode.parseTsx(text, file.path)
        assertFalse(tree.hasError, "$path does not parse")
        return findKeys(tree, CodeExtractors.forPreset(preset)).map { (node, key) ->
            "${text.substring(0, node.span.start).count { it == '\n' } + 1}: ${key.keyElements.joinToString("") { it.text }}"
        }
    }

    /** `line: key` for each place a fragment is written in the example file: the key is the fragment's first quoted string, unless given. */
    private fun lines(example: String, path: String, vararg fragments: String): List<String> =
        lines(example, path, fragments.map { it to it.trim().trim('"', '\'') })

    private fun lines(example: String, path: String, fragments: List<Pair<String, String>>): List<String> {
        val text = root.resolve("examples/$example/src/$path").readText()
        return fragments.map { (fragment, key) ->
            val offset = text.indexOf(fragment).also { check(it >= 0) { "'$fragment' is not in $path" } }
            "${text.substring(0, offset).count { it == '\n' } + 1}: $key"
        }
    }

    @Test
    fun `react-intl - FormattedMessage ids, defaultMessage left out`() {
        assertEquals(
            lines("react-intl", "App.tsx", "\"app.title\"", "\"home.greeting\"", "\"home.welcome\"", "\"home.only_in_english\"", "\"home.does_not_exist\""),
            keysOf("react-intl", "App.tsx", FrameworkPresets.REACT_INTL.id),
        )
    }

    @Test
    fun `react-intl - inline descriptors of formatMessage, qualified and bare`() {
        assertEquals(
            lines("react-intl", "Cart.tsx", listOf("\"cart.summary\" }, { count" to "cart.summary", "\"cart.checkout\"" to "cart.checkout", "\"cart.summary\" })" to "cart.summary", "\"cart.does_not_exist\"" to "cart.does_not_exist")),
            keysOf("react-intl", "Cart.tsx", FrameworkPresets.REACT_INTL.id),
        )
        assertEquals(lines("react-intl", "Profile.tsx", "\"profile.header.title\""), keysOf("react-intl", "Profile.tsx", FrameworkPresets.REACT_INTL.id))
    }

    @Test
    fun `react-intl - catalogued descriptors, the call passing them by reference holds no key`() {
        assertEquals(
            lines("react-intl", "Orders.tsx", "\"orders.status.paid\"", "\"orders.status.pending\"", "\"orders.status.does_not_exist\"", "\"orders.status.refunded\""),
            keysOf("react-intl", "Orders.tsx", FrameworkPresets.REACT_INTL.id),
        )
        assertEquals(emptyList<String>(), keysOf("react-intl", "NotIntl.tsx", FrameworkPresets.REACT_INTL.id))
    }

    @Test
    fun `lingui - source-text Trans, i18n _ and msg, and a local msg is no key`() {
        assertEquals(
            lines(
                "lingui", "App.tsx",
                listOf(
                    "<Trans>Hello world!" to "Hello world!", "<Trans>Your total is {formatPrice" to "Your total is {0}",
                    "\"app.greeting\"" to "app.greeting", "\"user.profile.title\"" to "user.profile.title", "\"cart.items\"" to "cart.items", "\"checkout.coupon\"" to "checkout.coupon",
                ),
            ),
            keysOf("lingui", "App.tsx", FrameworkPresets.LINGUI.id),
        )
        assertEquals(lines("lingui", "nav.ts", "\"nav.home\"", "\"nav.settings\""), keysOf("lingui", "nav.ts", FrameworkPresets.LINGUI.id))
        assertEquals(lines("lingui", "notices.ts", "\"about.beta\""), keysOf("lingui", "notices.ts", FrameworkPresets.LINGUI.id))
        assertEquals(emptyList<String>(), keysOf("lingui", "notLingui.ts", FrameworkPresets.LINGUI.id))
    }

    @Test
    fun `without a preset, both frameworks are read along with the others`() {
        val all = keysOf("lingui", "App.tsx", null) + keysOf("react-intl", "Orders.tsx", null)
        val own = keysOf("lingui", "App.tsx", FrameworkPresets.LINGUI.id) + keysOf("react-intl", "Orders.tsx", FrameworkPresets.REACT_INTL.id)
        assertEquals(emptyList<String>(), own - all.toSet())
    }
}
