package com.ibrahimdans.i18n.plugin.review

import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewLocationTest {

    @Test
    fun `a module's root is resolved under the project, as ModuleSources reads it`() {
        assertEquals("/repo/apps/web", ReviewLocation.rootPathOf(ModuleConfig(rootDirectory = "apps/web"), "/repo"))
        assertEquals("/repo/apps/web", ReviewLocation.rootPathOf(ModuleConfig(rootDirectory = " /apps/web/ "), "/repo/"))
    }

    @Test
    fun `a module without root, or a project without directory, resolves to nothing`() {
        assertNull(ReviewLocation.rootPathOf(ModuleConfig(rootDirectory = " "), "/repo"))
        assertNull(ReviewLocation.rootPathOf(ModuleConfig(rootDirectory = "apps/web"), null))
    }

    @Test
    fun `one file per locale, inside the reviews folder`() {
        assertEquals("fr.json", ReviewLocation.fileName("fr"))
        assertEquals("pt_BR.json", ReviewLocation.fileName("pt_BR"))
        assertTrue(ReviewLocation.isReviewPath("/repo/apps/web/.i18n-reviews/fr.json"))
        assertTrue(ReviewLocation.isReviewPath("C:\\repo\\.i18n-reviews"))
        assertFalse(ReviewLocation.isReviewPath("/repo/locales/fr.json"))
        assertFalse(ReviewLocation.isReviewPath("/repo/.i18n-reviews-old/fr.json"))
    }

    @Test
    fun `a lone module and no module share the folder already there, the module's first`() {
        val enabled = setOf("/repo/app", "/repo")
        val on = { root: String -> root in enabled }
        assertEquals("/repo/app", ReviewLocation.shared(listOf("/repo/app", "/repo"), "/repo", on))
        assertEquals("/repo", ReviewLocation.shared(listOf("/repo/web", "/repo"), "/repo/web") { it == "/repo" })
        assertEquals("/repo", ReviewLocation.shared(listOf(null, "/repo"), null) { it == "/repo" })
    }

    @Test
    fun `without any folder, each scope keeps its own root, where a folder would be created`() {
        assertEquals("/repo/app", ReviewLocation.shared(listOf("/repo/app", "/repo"), "/repo/app") { false })
        assertEquals("/repo", ReviewLocation.shared(listOf("/repo/app", "/repo"), "/repo") { false })
        assertNull(ReviewLocation.shared(listOf<String?>(null, null), null) { true })
    }
}
