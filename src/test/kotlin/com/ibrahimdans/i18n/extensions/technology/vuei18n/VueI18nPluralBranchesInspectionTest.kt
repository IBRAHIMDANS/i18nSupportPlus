package com.ibrahimdans.i18n.extensions.technology.vuei18n

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.VueI18nPluralBranchesInspection
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A vue-i18n plural `a | b` needs a branch for each form its locale's language uses. */
class VueI18nPluralBranchesInspectionTest : PlatformBaseTest() {

    private val vueModule = Config(modules = listOf(ModuleConfig(name = "vue", rootDirectory = "src", preset = "vue-i18n")))

    private fun warnings(path: String, content: String): List<String> {
        myFixture.enableInspections(VueI18nPluralBranchesInspection::class.java)
        val file = myFixture.addFileToProject(path, content)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return myFixture.doHighlighting().mapNotNull { it.description }.filter { it.startsWith("Plural ") }
    }

    @Test
    fun twoBranchesInRussianAreReported() = myFixture.runWithConfig(vueModule) {
        assertEquals(
            listOf("Plural 'cart.items' has 2 branch(es), 'ru' needs 3 (one, few, many)"),
            warnings("src/locales/ru.json", """{"cart": {"items": "{n} предмет | {n} предметов"}}""")
        )
    }

    @Test
    fun threeBranchesInRussianAreEnough() = myFixture.runWithConfig(vueModule) {
        assertTrue(warnings("src/locales/ru.json", """{"items": "{n} предмет | {n} предмета | {n} предметов"}""").isEmpty())
    }

    @Test
    fun englishAndAZeroFormAreFine() = myFixture.runWithConfig(vueModule) {
        assertTrue(warnings("src/locales/en.json", """{"a": "one item | {n} items", "b": "no items | one item | {n} items"}""").isEmpty())
    }

    /** Elsewhere a pipe is text: `Home | Docs` in an i18next project is no plural. */
    @Test
    fun outsideVueI18nAPipeIsText() = myFixture.runWithConfig(Config()) {
        assertTrue(warnings("locales/ru.json", """{"title": "Главная | Документы"}""").isEmpty())
    }

    @Test
    fun aProjectWithoutModulesDependingOnVueI18nIsChecked() = myFixture.runWithConfig(Config()) {
        myFixture.addFileToProject("package.json", """{"dependencies": {"vue-i18n": "^11.0.0"}}""")
        assertEquals(1, warnings("locales/ru.json", """{"items": "a | b"}""").size)
    }
}
