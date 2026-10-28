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

    // The example is documentation and gets edited (#698 added a comment line): the lines are read
    // off its text, so a moved call still passes and only a call reported on the wrong line fails.

    /** `line: description` for every place a fragment is written in [path] outside a `//` comment, in file order. */
    private fun written(path: String, vararg fragments: Pair<String, String>): List<String> {
        val text = root.resolve(path).readText()
        return fragments.flatMap { (fragment, description) ->
            generateSequence(text.indexOf(fragment).takeIf { it >= 0 }) { previous ->
                text.indexOf(fragment, previous + 1).takeIf { it >= 0 }
            }.filterNot { offset -> "//" in text.substring(text.lastIndexOf('\n', offset) + 1, offset) } // a commented call
                .toList().also { check(it.isNotEmpty()) { "'$fragment' is not in $path" } }
                .map { offset -> text.substring(0, offset).count { it == '\n' } + 1 to description }
        }.sortedBy { it.first }.map { (line, description) -> "$line: $description" }
    }

    /** Calls `t('key'` of [keys] (the call, not the literal: `'title'` is also a type field), described with [suffix]. */
    private fun calls(keys: List<String>, suffix: String = ""): Array<Pair<String, String>> =
        keys.map { "t('$it'" to "$it$suffix" }.toTypedArray()

    @Test
    fun `Dashboard - two hook namespaces on every literal`() {
        val path = "$example/components/Dashboard.tsx"
        assertEquals(
            written(
                path,
                *calls(
                    listOf(
                        "title", "welcome", "stats.users.label", "dashboardd:statss.users.count", "stats.files.count",
                        "dashboard:notifications.title", "dashboard:notifications.unread", "emptyState.title",
                        "emptyState.description", "common:actions.edit", "common:actions.delete", "common:actions.cancel",
                        "common:pagination.page", "common:pagination.previous", "common:pagination.next",
                    ),
                    suffix = " [dashboard, common]",
                ),
            ),
            keysOf(path),
        )
    }

    @Test
    fun `DepositBox - template, nested key, Trans and i18n t`() {
        val path = "$example/components/DepositBox.tsx"
        assertEquals(
            written(
                path,
                "t(`deposit-box:status." to "deposit-box:status.\${status}",
                "\"deposit-box:activity.entry.fileAdded\"" to "deposit-box:activity.entry.fileAdded",
                *calls(
                    listOf(
                        "deposit-box:title",
                        "deposit-box:myBoxes.myTrustees.modal.confirmPassword.addTrustee.description",
                        "deposit-box:myBoxes.myTrustees.modal.confirmPassword.removeTrustee.description",
                        "deposit-box:myBoxes.myTrustees.toasts.trusteeAdded",
                        "deposit-box:activity.entry.boxShared",
                        "deposit-box:activity.tooltipInfo",
                        "deposit-box:retention.unit.day",
                    ),
                ),
            ),
            keysOf(path),
        )
    }

    @Test
    fun `LoginForm - one hook namespace`() {
        val path = "$example/components/LoginForm.tsx"
        assertEquals(
            written(
                path,
                *calls(
                    listOf(
                        "errors.invalidCredentials", "login.title", "login.form.email.label", "login.form.email.placeholder",
                        "login.form.password.label", "login.form.password.placeholder", "login.button.submit", "login.button.forgotPassword",
                    ),
                    suffix = " [auth]",
                ),
            ),
            keysOf(path),
        )
    }

    @Test
    fun `Navigation and App`() {
        val navigation = "$example/components/Navigation.tsx"
        assertEquals(
            written(
                navigation,
                *calls(
                    listOf(
                        "dashboard:stats.users.count", "navigation:breadrumb.home", "navigation:menu.home",
                        "navigation:menu.dashboard", "navigation:menu.profile", "navigation:menu.settings", "navigation:menu.logout",
                    ),
                ),
            ),
            keysOf(navigation),
        )
        assertEquals(written("$example/App.tsx", *calls(listOf("appName"), suffix = " [common]")), keysOf("$example/App.tsx"))
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
