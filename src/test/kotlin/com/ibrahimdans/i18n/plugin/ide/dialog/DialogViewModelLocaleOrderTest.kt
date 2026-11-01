package com.ibrahimdans.i18n.plugin.ide.dialog

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * *Create Translation* and *Edit Translations* list the reference locale first, then the others
 * alphabetically: the rows followed the index, so `fr` sat above `en` in one IDE and below it in another.
 */
class DialogViewModelLocaleOrderTest : PlatformBaseTest() {

    private val title = FullKey(source = "auth:title", ns = Literal("auth"), compositeKey = listOf(Literal("title")))

    private fun addLocales(root: String = "") {
        listOf("fr", "de", "en").forEach { myFixture.addFileToProject("${root}locales/$it/auth.json", """{"title": "$it"}""") }
    }

    @Test
    fun theProjectReferenceLocaleLeadsInCreationAndInEdition() = myFixture.runWithConfig(Config()) {
        addLocales()
        val viewModel = DialogViewModel(project)

        assertEquals(listOf("en", "de", "fr"), viewModel.loadSourcesForNamespace("auth").keys.map { it.localeLabel() })
        assertEquals(listOf("en", "de", "fr"), viewModel.loadTranslations(title).keys.map { it.localeLabel() })
    }

    @Test
    fun theModuleReferenceLocaleLeads() = myFixture.runWithConfig(
        Config(modules = listOf(ModuleConfig(name = "app", rootDirectory = "apps/app", referenceLocale = "fr")))
    ) {
        addLocales(root = "apps/app/")
        val viewModel = DialogViewModel(project)

        assertEquals(listOf("fr", "de", "en"), viewModel.loadSourcesForNamespace("auth").keys.map { it.localeLabel() })
        assertEquals(listOf("fr", "de", "en"), viewModel.loadTranslations(title).keys.map { it.localeLabel() })
    }
}
