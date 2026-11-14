package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.GlossaryTermInspection
import com.ibrahimdans.i18n.plugin.translate.GlossaryTerm
import com.ibrahimdans.i18n.plugin.translate.MachineTranslationSettings
import com.intellij.lang.annotation.HighlightSeverity
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A translation that drops a kept term or translates a term otherwise than the glossary says is reported. */
class GlossaryTermInspectionTest : PlatformBaseTest() {

    private fun glossaryWarnings(path: String, content: String, vararg terms: GlossaryTerm): List<String> {
        val settings = MachineTranslationSettings.getInstance(project)
        settings.loadState(MachineTranslationSettings.Settings(glossary = terms.toMutableList()))
        try {
            myFixture.enableInspections(GlossaryTermInspection())
            val file = myFixture.addFileToProject(path, content)
            myFixture.configureFromExistingVirtualFile(file.virtualFile)
            return myFixture.doHighlighting()
                .filter { it.severity == HighlightSeverity.WEAK_WARNING }
                .mapNotNull { it.description }
                .filter { it.startsWith("Glossary: ") }
        } finally {
            settings.loadState(MachineTranslationSettings.Settings())
        }
    }

    private val acme = GlossaryTerm("Acme")
    private val cart = GlossaryTerm("cart", linkedMapOf("fr" to "panier"))

    @Test
    fun aKeptTermDroppedIsReported() {
        myFixture.addFileToProject("locales/en/common.json", """{"hi": "Welcome to Acme"}""")
        val warnings = glossaryWarnings("locales/fr/common.json", """{"hi": "Bienvenue chez Akmé"}""", acme)
        Assertions.assertEquals(listOf("Glossary: 'Acme' is kept as written, and this translation drops it"), warnings)
    }

    @Test
    fun anotherTranslationOfATermIsReported() {
        myFixture.addFileToProject("locales/en/common.json", """{"empty": "Your cart is empty"}""")
        val warnings = glossaryWarnings("locales/fr/common.json", """{"empty": "Votre chariot est vide"}""", cart)
        Assertions.assertEquals(listOf("Glossary: 'cart' is translated 'panier' in fr, not found in this translation"), warnings)
    }

    @Test
    fun aTranslationFollowingTheGlossaryIsNotReported() {
        myFixture.addFileToProject("locales/en/common.json", """{"empty": "Your Acme cart is empty"}""")
        assertTrue(glossaryWarnings("locales/fr/common.json", """{"empty": "Votre panier Acme est vide"}""", acme, cart).isEmpty())
    }

    @Test
    fun withoutAGlossaryNothingIsReported() {
        myFixture.addFileToProject("locales/en/common.json", """{"hi": "Welcome to Acme"}""")
        assertTrue(glossaryWarnings("locales/fr/common.json", """{"hi": "Bienvenue chez Akmé"}""").isEmpty())
    }

    @Test
    fun theReferenceFileIsNeverReported() {
        myFixture.addFileToProject("locales/fr/common.json", """{"hi": "Bienvenue"}""")
        assertTrue(glossaryWarnings("locales/en/common.json", """{"hi": "Welcome"}""", GlossaryTerm("Welcome")).isEmpty())
    }

    @Test
    fun yamlFilesAreChecked() {
        myFixture.addFileToProject("locales/en/common.yml", "empty: Your cart is empty\n")
        val warnings = glossaryWarnings("locales/fr/common.yml", "empty: Votre chariot est vide\n", cart)
        Assertions.assertEquals(1, warnings.size, "$warnings")
    }
}
