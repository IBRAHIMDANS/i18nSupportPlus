package com.ibrahimdans.i18n.plugin.ide.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The translations root read off i18next-cli's `extract.output`. */
class I18nextCliConfigTest {

    private fun rootOf(output: String?, file: String = "i18next.config.ts") = I18nextCliConfig(file, output = output).translationsRoot

    @Test
    fun `the root is the output path up to its first placeholder`() {
        assertEquals("public/locales", rootOf("public/locales/{{language}}/{{namespace}}.json"))
        assertEquals("src/i18n", rootOf("./src/i18n/{{language}}.json"))
    }

    @Test
    fun `a fixed file name is no folder`() {
        assertEquals("locales", rootOf("locales/all.json"))
    }

    @Test
    fun `the root is resolved from the configuration file's folder`() {
        assertEquals("apps/web/locales", rootOf("locales/{{language}}.json", file = "apps/web/i18next.config.ts"))
    }

    @Test
    fun `no root without a fixed folder, for an absolute path, or without output`() {
        assertNull(rootOf("{{language}}/{{namespace}}.json"))
        assertNull(rootOf("/var/locales/{{language}}.json"))
        assertNull(rootOf(null))
    }
}
