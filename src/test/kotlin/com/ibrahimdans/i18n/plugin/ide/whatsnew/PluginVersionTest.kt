package com.ibrahimdans.i18n.plugin.ide.whatsnew

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluginVersionTest {

    @Test
    fun `the build writes the plugin version into the resource`() {
        val version = PluginVersion.current
        assertNotNull(version, "plugin-version.properties is missing or was not expanded")
        assertTrue(Regex("""\d+\.\d+\.\d+.*""").matches(version!!), "unexpected version: $version")
    }
}
