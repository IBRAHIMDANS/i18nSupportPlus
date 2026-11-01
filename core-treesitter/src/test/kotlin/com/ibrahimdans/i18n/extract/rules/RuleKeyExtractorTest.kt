package com.ibrahimdans.i18n.extract.rules

import com.ibrahimdans.i18n.extract.i18next.findKeys
import com.ibrahimdans.i18n.extract.treesitter.TreeSitterCodeNode
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.rules.FrameworkPresets
import com.ibrahimdans.i18n.plugin.rules.FrameworkRules
import com.ibrahimdans.i18n.plugin.rules.RuleKeyExtractor
import com.ibrahimdans.i18n.plugin.tree.ContextOption
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [RuleKeyExtractor] on tree-sitter, reading the presets as the plugin's extractors read the same code. */
class RuleKeyExtractorTest {

    private fun keys(rules: FrameworkRules, source: String): List<RawKey> =
        findKeys(TreeSitterCodeNode.parseTsx(source), listOf(ruleExtractor(rules))).map { it.key }

    private fun literal(key: String, namespaces: List<String> = emptyList(), context: ContextOption? = null) =
        RawKey(listOf(KeyElement.literal(key)), namespaces, context = context)

    // ── react-intl ────────────────────────────────────────────────────────────

    @Test
    fun `react-intl - the id of a descriptor passed to formatMessage, whatever qualifies the call`() {
        assertEquals(
            listOf(literal("a"), literal("b"), literal("c")),
            keys(
                FrameworkPresets.REACT_INTL,
                """
                intl.formatMessage({ id: "a", defaultMessage: "Hello" }, { name });
                this.props.intl.formatMessage({ id: "b" });
                formatMessage({ id: 'c', description: "d" });
                other({ id: "e" }); intl.formatMessage("f"); intl.formatMessage({ key: "g" });
                """,
            ),
        )
    }

    @Test
    fun `react-intl - the ids of a catalogue, the descriptor itself or one level below`() {
        assertEquals(
            listOf(literal("orders.paid"), literal("orders.pending"), literal("orders.refunded")),
            keys(
                FrameworkPresets.REACT_INTL,
                """
                const messages = defineMessages({
                  paid: { id: "orders.paid", defaultMessage: "Paid", description: "Order status" },
                  pending: { id: "orders.pending" },
                });
                const refunded = defineMessage({ id: "orders.refunded", defaultMessage: "Refunded" });
                const placeholder = { email: { id: "not.a.message" } };
                """,
            ),
        )
    }

    @Test
    fun `react-intl - the id attribute of FormattedMessage, of no other tag`() {
        assertEquals(
            listOf(literal("app.title")),
            keys(FrameworkPresets.REACT_INTL, """const C = () => <><FormattedMessage id="app.title" values={{ n }} /><input id="not.a.key" /></>;"""),
        )
    }

    @Test
    fun `react-intl - the other strings of a descriptor are inside it, the key and strings elsewhere are not`() {
        val root = TreeSitterCodeNode.parseTsx("""intl.formatMessage({ id: "a", defaultMessage: "Hello" }); f({ defaultMessage: "x" }); t("y")""")
        val extractor = RuleKeyExtractor(FrameworkPresets.REACT_INTL, TsxGrammar())
        val strings = generateSequence(listOf(root as com.ibrahimdans.i18n.plugin.parser.CodeNode)) { nodes -> nodes.flatMap { it.children }.takeIf { it.isNotEmpty() } }
            .flatten().filter { it.type == "string" }.associateBy { it.text }
        assertTrue(extractor.isInsideDescriptor(strings.getValue("\"Hello\"")))
        assertTrue(extractor.isInsideDescriptor(strings.getValue("\"a\"")))
        assertFalse(extractor.isInsideDescriptor(strings.getValue("\"x\"")))
        assertFalse(extractor.isInsideDescriptor(strings.getValue("\"y\"")))
    }

    // ── Lingui ────────────────────────────────────────────────────────────────

    @Test
    fun `lingui - msg and its descriptor once the file imports Lingui, i18n _ always`() {
        assertEquals(
            listOf(literal("nav.home"), literal("nav.settings"), literal("about.beta")),
            keys(
                FrameworkPresets.LINGUI,
                """
                import { msg } from "@lingui/core/macro";
                export const navItems = [msg({ id: "nav.home" }), msg("nav.settings")];
                export const betaNotice = () => i18n._("about.beta", { name });
                """,
            ),
        )
    }

    @Test
    fun `lingui - a msg of the file's own is no key`() {
        assertEquals(
            emptyList<RawKey>(),
            keys(FrameworkPresets.LINGUI, """const msg = (text: string) => console.info(text); msg("app.started"); msg({ id: "x" });"""),
        )
    }

    // ── i18next, the generic rule only ────────────────────────────────────────

    @Test
    fun `i18next - the first argument, its ns and context options, a ternary's branches but not its condition`() {
        assertEquals(
            listOf(
                literal("a", listOf("ns")),
                literal("b", listOf("x", "y"), ContextOption.Fixed("male")),
                literal("c", context = ContextOption.Dynamic),
                literal("d"), literal("e"),
                literal("g"),
            ),
            keys(
                FrameworkPresets.I18NEXT,
                """
                t('a', { ns: 'ns' }); i18n.t("b", { ns: ['x', 'y'], context: 'male' }); t('c', { context });
                t(mode === 'f' ? 'd' : ('e')); toast.t('x'); t(`tpl`); this.props.t('g'); t(x as string);
                """,
            ),
        )
    }

    @Test
    fun `i18next - the i18nKey attribute on any tag, with the tag's context`() {
        assertEquals(
            listOf(literal("k1", context = ContextOption.Fixed("male")), literal("k2", context = ContextOption.Dynamic), literal("k3")),
            keys(
                FrameworkPresets.I18NEXT,
                """const C = () => <><Trans i18nKey="k1" context={'male'} /><Trans i18nKey="k2" context /><Other i18nKey="k3" /></>;""",
            ),
        )
    }

    // ── svelte-i18n ───────────────────────────────────────────────────────────

    @Test
    fun `svelte-i18n - the stores, the id of a descriptor, and the gated names only once imported`() {
        assertEquals(
            listOf(literal("a"), literal("b"), literal("c")),
            keys(FrameworkPresets.SVELTE_I18N, """${'$'}_('a'); ${'$'}format({ id: 'b', values: { n } }); ${'$'}json('c'); _('d'); ${'$'}t('e');"""),
        )
        assertEquals(
            listOf(literal("d"), literal("e")),
            keys(FrameworkPresets.SVELTE_I18N, """import { _ } from 'svelte-i18n'; _('d'); ${'$'}t('e');"""),
        )
    }

    @Test
    fun `the presets publish the names the technologies publish`() {
        assertEquals(listOf("t", "i18n.t", "i18next.t", "props.t", "this.props.t"), FrameworkPresets.I18NEXT.callNames)
        assertEquals(listOf("formatMessage", "defineMessages", "defineMessage"), FrameworkPresets.REACT_INTL.callNames)
        assertEquals(listOf("msg", "i18n._"), FrameworkPresets.LINGUI.callNames)
        assertEquals(
            mapOf(
                "msg" to listOf("@lingui/"), "_" to listOf("svelte-i18n"), "\$t" to listOf("svelte-i18n"),
                "translate" to listOf("@jsverse/transloco", "@ngneat/transloco"), "translateSignal" to listOf("@jsverse/transloco", "@ngneat/transloco"),
            ),
            FrameworkPresets.IMPORT_GATED_NAMES,
        )
    }
}
