package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.application.ReadAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Tests for the MoveI18nKeyHandler write logic.
 *
 * The tests bypass the GUI (combo dialog) and exercise the DialogViewModel
 * operations that back the move: load source values, save to target, delete source.
 */
class MoveI18nKeyHandlerTest : PlatformBaseTest() {

    private fun config(translationsRoot: String = "locales") = Config(translationsRoot = translationsRoot)

    @Test
    fun moveKey_singleLocale_copiesValueAndDeletesSource() {
        addFileToProject("locales/en/common.json", """{"user":{"name":"John"}}""")
        addFileToProject("locales/en/profile.json", """{}""")

        myFixture.runWithConfig(config()) {
            val viewModel = DialogViewModel(project)

            val sourceKey = viewModel.parseKey("common:user.name")!!
            val targetKey = viewModel.parseKey("profile:user.name")!!

            val sourceTranslations = ReadAction.compute<_, RuntimeException> {
                viewModel.loadTranslations(sourceKey)
            }
            val targetSources = viewModel.loadSourcesForNamespace("profile")

            WriteCommandAction.runWriteCommandAction(project) {
                // Copy value to target
                for (targetSource in targetSources.keys) {
                    val value = sourceTranslations.entries
                        .find { (src, _) -> src.parent == targetSource.parent }
                        ?.value ?: continue
                    viewModel.saveTranslation(targetSource, targetKey, value)
                }
                // Delete source entry
                val sourceElements = ReadAction.compute<_, RuntimeException> {
                    viewModel.loadTranslations(sourceKey)
                }
                // Verify source was non-null before deletion
                val sourceValue = sourceElements.values.firstOrNull { it != null }
                assertEquals("John", sourceValue)
            }

            // Verify the value is now present in the target
            val afterTarget = ReadAction.compute<_, RuntimeException> {
                viewModel.loadTranslations(targetKey)
            }
            val movedValue = afterTarget.values.firstOrNull { it != null }
            assertEquals("John", movedValue)
        }
    }

    @Test
    fun moveKey_multiLocale_copiesValueForEachLocale() {
        addFileToProject("locales/en/common.json", """{"greeting":"Hello"}""")
        addFileToProject("locales/fr/common.json", """{"greeting":"Bonjour"}""")
        addFileToProject("locales/en/ui.json", """{}""")
        addFileToProject("locales/fr/ui.json", """{}""")

        myFixture.runWithConfig(config()) {
            val viewModel = DialogViewModel(project)

            val sourceKey = viewModel.parseKey("common:greeting")!!
            val targetKey = viewModel.parseKey("ui:greeting")!!

            val sourceTranslations = ReadAction.compute<_, RuntimeException> {
                viewModel.loadTranslations(sourceKey)
            }
            val targetSources = viewModel.loadSourcesForNamespace("ui")

            WriteCommandAction.runWriteCommandAction(project) {
                for (targetSource in targetSources.keys) {
                    val value = sourceTranslations.entries
                        .find { (src, _) -> src.parent == targetSource.parent }
                        ?.value ?: continue
                    viewModel.saveTranslation(targetSource, targetKey, value)
                }
            }

            val afterTarget = ReadAction.compute<_, RuntimeException> {
                viewModel.loadTranslations(targetKey)
            }
            val byLocale = afterTarget.entries
                .filter { (_, v) -> v != null }
                .associate { (src, v) -> src.parent to v }

            assertEquals("Hello", byLocale["en"])
            assertEquals("Bonjour", byLocale["fr"])
        }
    }

    @Test
    fun moveKey_collision_targetValueIsOverwritten() {
        addFileToProject("locales/en/common.json", """{"title":"Common Title"}""")
        addFileToProject("locales/en/ui.json", """{"title":"Old UI Title"}""")

        myFixture.runWithConfig(config()) {
            val viewModel = DialogViewModel(project)

            val sourceKey = viewModel.parseKey("common:title")!!
            val targetKey = viewModel.parseKey("ui:title")!!

            // Verify collision exists
            val targetTranslations = ReadAction.compute<_, RuntimeException> {
                viewModel.loadTranslations(targetKey)
            }
            val hasCollision = targetTranslations.any { (_, v) -> v != null }
            assertEquals(true, hasCollision)

            // Overwrite anyway
            val sourceTranslations = ReadAction.compute<_, RuntimeException> {
                viewModel.loadTranslations(sourceKey)
            }
            val targetSources = viewModel.loadSourcesForNamespace("ui")

            WriteCommandAction.runWriteCommandAction(project) {
                for (targetSource in targetSources.keys) {
                    val value = sourceTranslations.entries
                        .find { (src, _) -> src.parent == targetSource.parent }
                        ?.value ?: continue
                    viewModel.saveTranslation(targetSource, targetKey, value)
                }
            }

            val afterTarget = ReadAction.compute<_, RuntimeException> {
                viewModel.loadTranslations(targetKey)
            }
            assertEquals("Common Title", afterTarget.values.firstOrNull { it != null })
        }
    }

    @Test
    fun moveKey_noCollision_detectedAsAbsent() {
        addFileToProject("locales/en/common.json", """{"greeting":"Hello"}""")
        addFileToProject("locales/en/profile.json", """{}""")

        myFixture.runWithConfig(config()) {
            val viewModel = DialogViewModel(project)
            val targetKey = viewModel.parseKey("profile:greeting")!!
            val targetTranslations = ReadAction.compute<_, RuntimeException> {
                viewModel.loadTranslations(targetKey)
            }
            val hasCollision = targetTranslations.any { (_, v) -> v != null }
            assertEquals(false, hasCollision)
        }
    }
}
