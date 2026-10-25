package com.ibrahimdans.i18n.extensions.technology

import com.ibrahimdans.i18n.TranslationTechnology
import com.ibrahimdans.i18n.Workspace
import com.ibrahimdans.i18n.WorkspaceFile
import com.ibrahimdans.i18n.extensions.technology.i18njs.I18nJsTechnology
import com.ibrahimdans.i18n.extensions.technology.lingui.LinguiTechnology
import com.ibrahimdans.i18n.extensions.technology.nextintl.NextIntlTechnology
import com.ibrahimdans.i18n.extensions.technology.ngxtranslate.NgxTranslateTechnology
import com.ibrahimdans.i18n.extensions.technology.reactintl.ReactIntlTechnology
import com.ibrahimdans.i18n.extensions.technology.sveltei18n.SvelteI18nTechnology
import com.ibrahimdans.i18n.extensions.technology.vuei18n.VueI18nTechnology
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The simple technologies run outside the IDE: on a workspace held in memory, they declare names and nothing else. */
class SimpleTechnologyTest {

    private val workspace = object : Workspace {
        override val basePath = "/project"
    }

    private val file = object : WorkspaceFile {
        override val path = "/project/src/assets/i18n/admin/en.json"
    }

    private val technologies: List<TranslationTechnology<*, *>> = listOf(
        I18nJsTechnology(), LinguiTechnology(), NextIntlTechnology(), NgxTranslateTechnology(),
        ReactIntlTechnology(), SvelteI18nTechnology(), VueI18nTechnology(),
    )

    @Test
    fun theyReadNothingFromTheWorkspace() {
        technologies.forEach { technology ->
            technology.initialize(workspace)
            val name = technology.frameworkId()
            assertTrue(technology.findSourcesByConfiguration(workspace).isEmpty(), "$name declares no source")
            assertTrue(technology.cfgNamespaces().isEmpty(), "$name has no configured namespace")
            assertTrue(technology.keyScopes(workspace).isEmpty(), "$name declares no scope")
            assertNull(technology.keyScopeOf(workspace, file), "$name names no file's scope")
            assertTrue(technology.fallbackNamespaces(workspace).isEmpty(), "$name has no fallback namespace")
        }
    }

    @Test
    fun eachNamesItsFrameworkAndItsCalls() {
        assertEquals(
            mapOf(
                "i18n-js" to listOf("t", "i18n.t"),
                "lingui" to listOf("msg", "i18n._"),
                "next-intl" to listOf("t"),
                "ngx-translate" to emptyList(),
                "react-intl" to listOf("formatMessage"),
                "svelte-i18n" to listOf("_", "\$_", "\$t", "\$format", "\$json"),
                "vue-i18n" to listOf("\$t", "\$tc", "\$te"),
            ),
            technologies.associate { it.frameworkId() to it.translationFunctionNames() }
        )
    }
}
