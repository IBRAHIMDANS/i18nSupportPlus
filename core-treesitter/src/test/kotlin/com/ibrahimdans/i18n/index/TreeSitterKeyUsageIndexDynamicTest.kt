package com.ibrahimdans.i18n.index

import com.ibrahimdans.i18n.plugin.ide.settings.Config
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * The keys the index counts as reached by a key built at runtime are the ones the IDE counts:
 * both ask `DynamicReach`, so `i18n-check` and the *Usage* column give one verdict.
 */
class TreeSitterKeyUsageIndexDynamicTest {

    private fun indexOf(source: String, config: Config = Config()): TreeSitterKeyUsageIndex =
        TreeSitterKeyUsageIndex(config).apply { update("a.tsx", source) }

    /**
     * `` t(`errors.${'$'}{code}`) `` says nothing about its namespace: the IDE lets it reach the
     * default namespace's `errors.*` only. The index used to let it reach `auth:errors.timeout`
     * too, so a key the editor called an orphan passed `i18n-check` as dynamic.
     */
    @Test
    fun `a one-segment head without a tail reaches the default namespace only`() {
        val index = indexOf("export const message = (code: string) => i18n.t(`errors.\${code}`);")

        Assertions.assertEquals(
            setOf("errors.timeout"),
            index.dynamicallyReached(listOf("errors.timeout", "auth:errors.timeout", "menu.home")),
        )
    }

    @Test
    fun `a namespace in the head or a tail keeps the plain rule`() {
        val index = indexOf(
            "const a = (code: string) => i18n.t(`common:errors.\${code}`);\n" +
                "const b = (id: string) => i18n.t(`menu.\${id}.label`);",
        )

        Assertions.assertEquals(
            setOf("common:errors.timeout", "admin:menu.home.label", "menu.home.label"),
            index.dynamicallyReached(
                listOf("common:errors.timeout", "auth:errors.timeout", "admin:menu.home.label", "menu.home.label", "admin:menu.home.icon"),
            ),
        )
    }

    @Test
    fun `the rule reads the configured separators`() {
        val index = indexOf("const a = (code: string) => i18n.t(`errors/\${code}`);", Config(nsSeparator = "::", keySeparator = "/"))

        Assertions.assertEquals(
            setOf("errors/timeout"),
            index.dynamicallyReached(listOf("errors/timeout", "auth::errors/timeout")),
        )
    }
}
