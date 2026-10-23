package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.StaticI18nRegistry
import com.ibrahimdans.i18n.Workspace
import com.ibrahimdans.i18n.extensions.technology.SimpleTechnology
import com.ibrahimdans.i18n.extensions.technology.lingui.LinguiTechnology
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** i18next's `fallbackNS`, gathered from the settings and the technologies of a registry held in memory. */
class FallbackNamespacesTest {

    private val workspace = object : Workspace {
        override val basePath = "/project"
    }

    /** A technology whose configuration falls back to [namespaces], read on the workspace it is given. */
    private class FallingBack(private vararg val namespaces: String) : SimpleTechnology() {
        var askedOn: Workspace? = null
        override fun translationFunctionNames() = listOf("t")
        override fun fallbackNamespaces(workspace: Workspace): List<String> {
            askedOn = workspace
            return namespaces.toList()
        }
    }

    @Test
    fun `none without settings nor technology saying any`() {
        assertEquals(emptyList<String>(), FallbackNamespaces.of(workspace, Config(), StaticI18nRegistry<Nothing, Nothing>()))
        assertEquals(
            emptyList<String>(),
            FallbackNamespaces.of(workspace, Config(), StaticI18nRegistry(technologies = listOf(LinguiTechnology())))
        )
    }

    @Test
    fun `the settings come first, then each technology in the registry's order, without repeats`() {
        val registry = StaticI18nRegistry(technologies = listOf(FallingBack("common", "shared"), FallingBack("shared", "base")))

        assertEquals(
            listOf("admin", "common", "shared", "base"),
            FallbackNamespaces.of(workspace, Config(fallbackNs = "admin, common"), registry)
        )
    }

    @Test
    fun `technologies read the workspace they are given`() {
        val technology = FallingBack("common")

        FallbackNamespaces.of(workspace, Config(), StaticI18nRegistry(technologies = listOf(technology)))

        assertSame(workspace, technology.askedOn)
    }
}
