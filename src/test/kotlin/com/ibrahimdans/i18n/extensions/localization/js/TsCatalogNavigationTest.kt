package com.ibrahimdans.i18n.extensions.localization.js

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.quickfix.AllSourcesSelector
import com.ibrahimdans.i18n.plugin.ide.quickfix.CreateKeyQuickFix
import com.ibrahimdans.i18n.plugin.ide.references.translation.TranslationToCodeReference
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.waitForAsyncWork
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.psi.PsiPolyVariantReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ctrl+click and key creation in the two TS/JS formats: a catalogue keyed by locale (i18n-js,
 * React Native) and the `resources` an i18next configuration file declares inline.
 */
class TsCatalogNavigationTest : PlatformBaseTest() {

    private val catalog = """
        export const translations = {
          fr: {
            dashboard: {
              title: 'Ma pharmacie',
            },
          },
          en: {
            dashboard: {
              title: 'My pharmacy',
            },
          },
        };
    """.trimIndent()

    private val i18nextConfig = """
        i18n.init({
          resources: {
            en: {
              translation: {
                home: {
                  title: 'Home',
                },
              },
            },
            fr: {
              translation: {
                home: {
                  title: 'Accueil',
                },
              },
            },
          },
        });
    """.trimIndent()

    private val withResources = Config(jsConfiguration = "src/i18n.ts")

    // Each call writes a file of its own: a test may ask several times.
    private var calls = 0

    /** The values Ctrl+click on the key under `<caret>` offers, one per locale. */
    private fun targetsOf(code: String): Set<String> {
        myFixture.configureFromExistingVirtualFile(addFileToProject("src/screens/Home${calls++}.ts", code).virtualFile)
        return ReadAction.compute<Set<String>, RuntimeException> {
            val element = myFixture.file.findElementAt(myFixture.caretOffset)!!.parent
            element.references.filterIsInstance<PsiPolyVariantReference>()
                .flatMap { ref -> ref.multiResolve(false).mapNotNull { it.element?.text?.unQuote() } }
                .toSet()
        }
    }

    /** The code the key at [marker] of the translation file at [path] leads to. */
    private fun usagesOf(path: String, marker: String): Set<String> {
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir(path))
        return ReadAction.compute<Set<String>, RuntimeException> {
            val offset = myFixture.file.text.indexOf(marker) + 1
            val element = myFixture.file.findElementAt(offset)!!.parent
            element.references.filterIsInstance<TranslationToCodeReference>()
                .flatMap { ref -> ref.multiResolve(false).mapNotNull { it.element?.text?.unQuote() } }
                .toSet()
        }
    }

    private fun documentText(path: String): String =
        FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir(path))!!.text

    private fun createKey(text: String, value: String) {
        val key = RawKeyParser(project).parse(RawKey(listOf(KeyElement.literal(text))))!!
        val previous = TestDialogManager.setTestInputDialog(answering(value))
        try {
            CreateKeyQuickFix(key, AllSourcesSelector(), "Create key", value).invoke(project, myFixture.editor)
            waitForAsyncWork()
        } finally {
            TestDialogManager.setTestInputDialog(previous)
        }
    }

    private fun answering(value: String) = object : TestInputDialog {
        override fun show(message: String): String = value
        override fun show(message: String, validator: InputValidator?): String = value
    }

    private fun notificationsDuring(action: () -> Unit): List<Notification> {
        val received = mutableListOf<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                received.add(notification)
            }
        })
        action()
        return received
    }

    // ── Catalogue keyed by locale ─────────────────────────────────────────────

    @Test
    fun aKeyLeadsToItsValueInEveryLocaleOfTheCatalogue() = myFixture.runWithConfig(Config()) {
        addFileToProject("src/i18n/translations.ts", catalog)
        assertEquals(setOf("Ma pharmacie", "My pharmacy"), targetsOf("t('dashboard.ti<caret>tle')"))
    }

    @Test
    fun aKeyOfTheCatalogueLeadsToTheCodeUsingIt() = myFixture.runWithConfig(Config()) {
        addFileToProject("src/i18n/translations.ts", catalog)
        addFileToProject("src/screens/Dashboard.ts", "t('dashboard.title')")
        // The sources are discovered on first use, as the editor does when it highlights the code.
        targetsOf("t('dashboard.ti<caret>tle')")
        assertEquals(setOf("dashboard.title"), usagesOf("src/i18n/translations.ts", "title: 'Ma"))
    }

    @Test
    fun aMissingKeyIsWrittenIntoANestedObjectOfEveryLocale() = myFixture.runWithConfig(Config()) {
        addFileToProject("src/i18n/translations.ts", catalog)
        myFixture.configureFromExistingVirtualFile(addFileToProject("src/screens/A.ts", "t('x')").virtualFile)
        createKey("dashboard.subtitle", "It's \"open\"")
        createKey("settings.theme.dark", "Dark")

        val text = documentText("src/i18n/translations.ts")
        val expectedLocale = { title: String ->
            """
              |    dashboard: {
              |      title: '$title',
              |      subtitle: 'It\'s "open"',
              |    },
              |    settings: {
              |      theme: {
              |        dark: 'Dark',
              |      },
              |    },
            """.trimMargin()
        }
        assertTrue(expectedLocale("Ma pharmacie") in text, text)
        assertTrue(expectedLocale("My pharmacy") in text, text)
        // The new key resolves: Ctrl+click reaches it, spelled as written.
        assertEquals(setOf("It\\'s \"open\""), targetsOf("t('dashboard.sub<caret>title')"))
    }

    @Test
    fun aKeyWrittenIntoAOneLineObjectStaysOnItsLine() = myFixture.runWithConfig(Config()) {
        addFileToProject(
            "src/i18n/translations.ts",
            "export default {\n  en: { dashboard: { title: \"My pharmacy\" } },\n};\n"
        )
        myFixture.configureFromExistingVirtualFile(addFileToProject("src/screens/A.ts", "t('x')").virtualFile)
        createKey("dashboard.subtitle", "Today")
        createKey("menu.home", "Home")

        assertEquals(
            "export default {\n  en: { dashboard: { title: \"My pharmacy\", subtitle: \"Today\" }, menu: { home: \"Home\" } },\n};\n",
            documentText("src/i18n/translations.ts")
        )
    }

    @Test
    fun aKeyIsNotWrittenIntoAnObjectBuiltWithASpread() = myFixture.runWithConfig(Config()) {
        val built = """
            const common = { ok: 'OK' };
            export const translations = {
              en: {
                ...common,
                title: 'Home',
              },
            };
        """.trimIndent()
        addFileToProject("src/i18n/translations.ts", built)
        myFixture.configureFromExistingVirtualFile(addFileToProject("src/screens/A.ts", "t('x')").virtualFile)

        val notifications = notificationsDuring { createKey("greeting", "Hello") }

        assertEquals(built, documentText("src/i18n/translations.ts"))
        assertTrue(notifications.any { "greeting" in it.content && "translations.ts" in it.content }, notifications.map { it.content }.toString())
    }

    @Test
    fun syncKeysWritesTheMissingKeyIntoTheCatalogue() = myFixture.runWithConfig(Config()) {
        addFileToProject(
            "src/i18n/translations.ts",
            catalog.replace("title: 'My pharmacy',", "title: 'My pharmacy',\n      empty: 'Nothing yet',")
        )

        val missing = KeysSynchronizer().missingEntries(project, Config())

        // The tool window lists a catalogue's keys under the file's name (`translations:`), as before.
        assertEquals(listOf("dashboard.empty" to "fr"), missing.map { it.key.substringAfter(':') to it.locale })
        val entry = missing.single()
        DialogViewModel(project).saveTranslation(entry.source, KeysSynchronizer().buildFullKey(entry.key), "")
        assertTrue("      title: 'Ma pharmacie',\n      empty: '',\n" in documentText("src/i18n/translations.ts"))
    }

    // ── i18next `resources` ───────────────────────────────────────────────────

    @Test
    fun aKeyLeadsToItsValueInTheI18nextResources() = myFixture.runWithConfig(withResources) {
        addFileToProject("src/i18n.ts", i18nextConfig)
        assertEquals(setOf("Home", "Accueil"), targetsOf("t('home.ti<caret>tle')"))
    }

    @Test
    fun aKeyOfTheI18nextResourcesLeadsToTheCodeUsingIt() = myFixture.runWithConfig(withResources) {
        addFileToProject("src/i18n.ts", i18nextConfig)
        addFileToProject("src/screens/Welcome.ts", "t('home.title')")
        targetsOf("t('home.ti<caret>tle')")
        assertEquals(setOf("home.title"), usagesOf("src/i18n.ts", "title: 'Acc"))
    }

    @Test
    fun aMissingKeyIsWrittenIntoTheI18nextResources() = myFixture.runWithConfig(withResources) {
        addFileToProject("src/i18n.ts", i18nextConfig)
        myFixture.configureFromExistingVirtualFile(addFileToProject("src/screens/A.ts", "t('x')").virtualFile)
        createKey("home.subtitle", "Welcome")

        val text = documentText("src/i18n.ts")
        assertEquals(2, Regex("""title: '[^']+',\n {10}subtitle: 'Welcome',\n {8}},""").findAll(text).count(), text)
    }
}
