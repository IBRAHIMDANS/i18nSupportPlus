package com.ibrahimdans.i18n.plugin.parser

import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * How a key is read from its context alone: settings, technology scopes, fallback namespaces and
 * the template of the module it is written in. Each case reads as `ns|key.segments|fallbacks`.
 */
class KeyResolverTest {

    private class Case(
        val name: String,
        val key: String,
        val context: KeyContext,
        val expected: String,
        val namespaces: List<String> = emptyList(),
        val keyPrefix: String? = null
    )

    private val cases = listOf(
        Case("namespace and key with the default separators", "common:title.main", KeyContext(Config()), "common|title.main|"),
        Case("no namespace written", "title.main", KeyContext(Config()), "|title.main|"),
        Case("custom separators", "common#title/main", KeyContext(Config(nsSeparator = "#", keySeparator = "/")), "common|title.main|"),
        Case("flat keys are never split", "title.main", KeyContext(Config(flatKeys = true)), "|title.main|"),
        Case("gettext keys are never split", "Hello. World", KeyContext(Config(gettext = true)), "|Hello. World|"),
        Case(
            "a flat key joins its hook's prefix", "help.caption", KeyContext(Config(flatKeys = true)),
            "|OverviewActions.help.caption|", keyPrefix = "OverviewActions"
        ),
        Case(
            "firstComponentNs leaves key scopes unread", "admin.title",
            KeyContext(Config(firstComponentNs = true), keyScopes = { setOf("admin") }), "|admin.title|"
        ),
        Case(
            "module template {ns}:{key} keeps the colon", "common:title",
            KeyContext(Config(nsSeparator = "|"), callerSyntax = KeySyntax.Namespaced(":")), "common|title|"
        ),
        Case(
            "module template {ns}.{key} reads the first segment as namespace", "common.title.main",
            KeyContext(Config(), callerSyntax = KeySyntax.Namespaced(".")), "common|title.main|"
        ),
        Case(
            "module template {key} reads no namespace", "title.main",
            KeyContext(Config(), callerSyntax = KeySyntax.NoNamespace), "|title.main|"
        ),
        Case(
            "a declared key scope leads the key", "admin.title",
            KeyContext(Config(), keyScopes = { setOf("admin") }), "admin|title|"
        ),
        Case(
            "an undeclared first segment stays a segment", "users.title",
            KeyContext(Config(), keyScopes = { setOf("admin") }), "|users.title|"
        ),
        Case(
            "a module template wins over a key scope", "admin.title",
            KeyContext(Config(), keyScopes = { setOf("admin") }, callerSyntax = KeySyntax.NoNamespace), "|admin.title|"
        ),
        Case(
            "a package scope is cut off before the key", "courier::messages.welcome",
            KeyContext(Config(), keyScopes = { setOf("courier::messages") }), "courier::messages|welcome|"
        ),
        Case(
            "fallback namespaces follow a key whose namespaces come from its hook", "shared.ok",
            KeyContext(Config(), fallbackNamespaces = { listOf("common") }), "|shared.ok|common", namespaces = listOf("admin")
        ),
        Case(
            "a key writing its namespace gets no fallback", "admin:shared.ok",
            KeyContext(Config(), fallbackNamespaces = { listOf("common") }), "admin|shared.ok|", namespaces = listOf("admin")
        ),
        Case(
            "a key without namespaces gets no fallback", "shared.ok",
            KeyContext(Config(), fallbackNamespaces = { listOf("common") }), "|shared.ok|"
        )
    )

    @TestFactory
    fun `keys read from their context`() = cases.map { case ->
        DynamicTest.dynamicTest(case.name) {
            val rawKey = RawKey(
                listOf(KeyElement.literal(case.key)),
                case.namespaces,
                case.keyPrefix?.let { listOf(KeyElement.literal(it)) }.orEmpty()
            )
            Assertions.assertEquals(case.expected, describe(KeyResolver.parse(rawKey, case.context)))
        }
    }

    @Test
    fun `scopes and fallbacks are not asked of a key that needs neither`() {
        val context = KeyContext(
            Config(),
            keyScopes = { Assertions.fail("key scopes asked for a key holding its namespace") },
            fallbackNamespaces = { Assertions.fail("fallbacks asked for a key holding its namespace") }
        )

        Assertions.assertEquals("common|title|", describe(KeyResolver.parse(RawKey(listOf(KeyElement.literal("common:title"))), context)))
    }

    private fun describe(key: FullKey?): String =
        if (key == null) "null"
        else "${key.ns?.text.orEmpty()}|${key.compositeKey.joinToString(".") { it.text }}|${key.fallbackNamespaces.joinToString(",")}"
}
