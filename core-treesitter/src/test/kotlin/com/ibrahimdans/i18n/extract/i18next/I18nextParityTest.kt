package com.ibrahimdans.i18n.extract.i18next

import com.ibrahimdans.i18n.plugin.parser.ExpressionNormalizer
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.extract.treesitter.TreeSitterCodeNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The keys the tree-sitter extractors find in the repository's i18next sources, against those the
 * plugin's PSI extractors give there — read off `ReactUseTranslationHookExtractor`,
 * `StringLiteralKeyExtractor`, `TemplateKeyExtractor` and `XmlAttributeKeyExtractor`, and expected by
 * the plugin tests over the same fixtures (`UnusedKeysFixtureTest`, the `keyResolution` tests).
 *
 * One line per key: `line: key [namespaces] prefix=…`, the key as the key parser sees it once its
 * `ExpressionNormalizer` has dropped a template's tokens.
 */
class I18nextParityTest {

    private val root = File("..").canonicalFile

    private fun keysOf(path: String, functionNames: Set<String> = I18NEXT_FUNCTIONS): List<String> {
        val file = root.resolve(path)
        val text = file.readText()
        val tree = TreeSitterCodeNode.parseTsx(text, file.path)
        assertFalse(tree.hasError, "$path does not parse")
        return findKeys(tree, i18nextExtractors(functionNames)).map { (node, key) ->
            "${text.substring(0, node.span.start).count { it == '\n' } + 1}: ${describe(key)}"
        }
    }

    private fun describe(key: RawKey): String {
        val normalizer = ExpressionNormalizer()
        val text = key.keyElements.mapNotNull(normalizer::normalize).joinToString("") { it.text }
        val namespaces = key.arguments.takeIf { it.isNotEmpty() }?.joinToString(", ", " [", "]").orEmpty()
        val prefix = key.keyPrefix.takeIf { it.isNotEmpty() }?.joinToString("") { it.text }?.let { " prefix=$it" }.orEmpty()
        return text + namespaces + prefix
    }

    // ── examples/react-multi-namespace ────────────────────────────────────────

    private val example = "examples/react-multi-namespace/src"

    @Test
    fun `Dashboard - two hook namespaces on every literal`() {
        val ns = " [dashboard, common]"
        assertEquals(
            listOf(
                "19: title$ns", "22: welcome$ns", "25: stats.users.label$ns", "27: dashboardd:statss.users.count$ns",
                "28: stats.files.count$ns", "32: dashboard:notifications.title$ns", "39: dashboard:notifications.unread$ns",
                "43: emptyState.title$ns", "44: emptyState.description$ns", "52: common:actions.edit$ns",
                "53: common:actions.delete$ns", "54: common:actions.cancel$ns", "61: common:pagination.page$ns",
                "63: common:pagination.previous$ns", "64: common:pagination.next$ns",
            ),
            keysOf("$example/components/Dashboard.tsx"),
        )
    }

    @Test
    fun `DepositBox - template, nested key, Trans and i18n t`() {
        assertEquals(
            listOf(
                "24: deposit-box:status.\${status}",
                "28: deposit-box:title",
                "31: deposit-box:myBoxes.myTrustees.modal.confirmPassword.addTrustee.description",
                "38: deposit-box:myBoxes.myTrustees.modal.confirmPassword.addTrustee.description",
                "42: deposit-box:myBoxes.myTrustees.modal.confirmPassword.removeTrustee.description",
                "43: deposit-box:myBoxes.myTrustees.toasts.trusteeAdded",
                "51: deposit-box:activity.entry.boxShared",
                "52: deposit-box:title",
                "62: deposit-box:activity.entry.fileAdded",
                "68: deposit-box:activity.tooltipInfo",
                "74: deposit-box:retention.unit.day",
            ),
            keysOf("$example/components/DepositBox.tsx"),
        )
    }

    @Test
    fun `LoginForm - one hook namespace`() {
        assertEquals(
            listOf(20, 30, 35, 40, 49, 54, 66, 68).zip(
                listOf(
                    "errors.invalidCredentials", "login.title", "login.form.email.label", "login.form.email.placeholder",
                    "login.form.password.label", "login.form.password.placeholder", "login.button.submit", "login.button.forgotPassword",
                ),
            ).map { (line, key) -> "$line: $key [auth]" },
            keysOf("$example/components/LoginForm.tsx"),
        )
    }

    @Test
    fun `Navigation and App`() {
        assertEquals(
            listOf(
                "14: dashboard:stats.users.count", "15: navigation:breadrumb.home", "17: navigation:menu.home",
                "18: navigation:menu.dashboard", "19: navigation:menu.profile", "20: navigation:menu.settings",
                "25: navigation:menu.logout",
            ),
            keysOf("$example/components/Navigation.tsx"),
        )
        assertEquals(listOf("32: appName [common]"), keysOf("$example/App.tsx"))
    }

    @Test
    fun `the setup files hold no key`() {
        assertEquals(emptyList<String>(), keysOf("$example/i18n.ts") + keysOf("$example/main.tsx"))
    }

    // ── src/test/resources/unusedKeys ─────────────────────────────────────────

    private val unused = "src/test/resources/unusedKeys"

    @Test
    fun `unusedKeys - hooks, key prefix and ns option`() {
        assertEquals(
            listOf(
                "5: hook.title [common]", "10: dashboard.title [admin, common]", "15: name [common] prefix=profile",
                "20: label [other]", "26: shared.ok [admin]",
            ),
            keysOf("$unused/single/src/Hooks.tsx"),
        )
    }

    @Test
    fun `unusedKeys - templates with a static prefix, a dynamic namespace, nothing static`() {
        assertEquals(
            listOf("4: common:errors.\${code}", "5: \${ns}:status.ok", "6: \${name}"),
            keysOf("$unused/single/src/Errors.tsx"),
        )
    }

    @Test
    fun `unusedKeys - wrappers declared by key assistance rules`() {
        val names = setOf("translate", "i18n.translate")
        assertEquals(listOf("3: common:wrapped.plain", "4: common:wrapped.member"), keysOf("$unused/single/src/Wrappers.ts", names))
        // The plugin's exclusion rule for legacy/ is no concern of the extractor: the key is counted there too.
        assertEquals(listOf("4: common:wrapped.excluded"), keysOf("$unused/single/legacy/Old.ts", names))
    }

    @Test
    fun `unusedKeys - monorepo apps call i18n t`() {
        assertEquals(
            listOf("3: common:title", "3: common:adminOnly", "3: common:orphan"),
            keysOf("$unused/monorepo/apps/admin/src/App.tsx"),
        )
        assertEquals(listOf("3: common:title", "3: common:webOnly"), keysOf("$unused/monorepo/apps/web/src/App.tsx"))
    }

    // ── src/test/resources/keyResolution (i18next files) ──────────────────────

    @Test
    fun `keyResolution - one key per react-i18next file`() {
        val single = "src/test/resources/keyResolution/single/src"
        val monorepo = "src/test/resources/keyResolution/monorepo/apps"
        val expected = mapOf(
            "$single/Default.tsx" to "2: defaultOnly",
            "$single/DefaultMissing.tsx" to "2: missing",
            "$single/Dynamic.tsx" to "2: common:\${k}",
            "$single/Explicit.tsx" to "2: common:title",
            "$single/Hook.tsx" to "2: dashboard.title [admin]",
            "$single/MissingKey.tsx" to "2: common:nope.missing",
            "$single/MissingNs.tsx" to "2: ghost:title",
            "$single/Nested.tsx" to "2: common:user.name",
            "$single/NestedMissing.tsx" to "2: common:user.age",
            "$single/NsOption.tsx" to "2: label [other]",
            "$single/Plural.tsx" to "2: common:item",
            "$single/Prefix.tsx" to "2: name [common] prefix=profile",
            "$single/Trans.tsx" to "2: common:title",
            "$single/TransMissing.tsx" to "2: common:ghost.key",
            "$monorepo/admin/src/Title.tsx" to "2: common:title",
            "$monorepo/docs/src/Doc.tsx" to "2: guide.intro",
            "$monorepo/docs/src/DocMissing.tsx" to "2: guide.outro",
            "$monorepo/web/src/Foreign.tsx" to "2: common:adminOnly",
            "$monorepo/web/src/OtherModuleNs.tsx" to "2: settings:theme",
            "$monorepo/web/src/Title.tsx" to "2: common:title",
        )
        assertEquals(expected, expected.mapValues { (path, _) -> keysOf(path).single() })
    }
}
