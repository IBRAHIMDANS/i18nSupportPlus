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

    @Test
    fun `a trailing slash is a folder, and Lingui's single braces are placeholders`() {
        assertEquals("src/assets/i18n", rootOf("src/assets/i18n/"))
        assertEquals("src/locales", rootOf("src/locales/{locale}/messages"))
    }

    @Test
    fun `a project-relative output ignores the file's folder`() {
        val config = I18nextCliConfig("src/i18n.ts", output = "public/locales/{{lng}}.json", fromProjectRoot = true)

        assertEquals("public/locales", config.translationsRoot)
    }

    @Test
    fun `placeholders become the plugin's`() {
        fun template(output: String) = I18nextCliConfig("f.ts", output = output).pathTemplate

        assertEquals("{lang}/{ns}.json", template("locales/{{lng}}/{{ns}}.json"))
        assertEquals("{ns}/{lang}.json", template("public/locales/{{namespace}}/{{language}}.json"))
        assertEquals("{lang}/messages", template("src/locales/{locale}/messages"))
        assertNull(template("locales/all.json"))
        assertNull(template("locales/{{lng}}/{{unknown}}.json"))
    }
}
