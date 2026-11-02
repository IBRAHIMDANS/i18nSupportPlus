package com.ibrahimdans.i18n.extensions.localization.properties

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.CleanupUnusedKeysAction
import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.inspection.PlaceholderConsistencyInspection
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TableViewModel
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.TranslationPsi
import com.intellij.lang.properties.PropertiesImplUtil
import com.intellij.lang.xml.XMLLanguage
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.application.ReadAction
import com.intellij.patterns.XmlPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.reference.PsiReferenceContributorEP
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.util.ProcessingContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Java resource bundles: `messages.properties` holds the reference locale, `messages_fr.properties`
 * and `messages_pt_BR.properties` the translations; `application.properties` is configuration.
 */
class PropertiesLocalizationTest : PlatformBaseTest() {

    private companion object {
        const val DIR = "src/main/resources"
        const val ENGLISH = "# Greetings\nuser.name=Name\nuser.greeting=Hello {0}\ncafe=Caf\\u00e9\nlong=first \\\n    second\n"
        const val FRENCH = "user.name=Nom\nuser.greeting=Bonjour {1}\nother=Autre\n"
        const val BRAZILIAN = "user.name=Nome\nuser.greeting=Ol\\u00e1 {0}\ncafe=Caf\\u00e9\nlong=longo\n"
    }

    private fun addBundle() {
        myFixture.addFileToProject("$DIR/messages.properties", ENGLISH)
        myFixture.addFileToProject("$DIR/messages_fr.properties", FRENCH)
        myFixture.addFileToProject("$DIR/messages_pt_BR.properties", BRAZILIAN)
        myFixture.addFileToProject("$DIR/application.properties", "server.port=8080\n")
    }

    private fun bundleSources(): List<LocalizationSource> = ReadAction.compute<List<LocalizationSource>, RuntimeException> {
        project.service<LocalizationSourceService>().findAllSources(project).filter { it.name.endsWith(".properties") }
    }

    private fun text(path: String): String =
        FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir(path))!!.text

    @Test
    fun eachBundleFileIsASourceOfTheLocaleItsNameEndsWith() {
        addBundle()
        myFixture.addFileToProject("target/classes/messages_de.properties", "user.name=Name\n")

        val sources = bundleSources()

        Assertions.assertEquals(
            mapOf("messages.properties" to "en", "messages_fr.properties" to "fr", "messages_pt_BR.properties" to "pt_BR"),
            sources.associate { it.name to it.locale },
        )
        Assertions.assertTrue(sources.all { it.namespace == null }, "one bundle, the default namespace: $sources")
    }

    @Test
    fun theFileWithoutASuffixTakesTheReferenceLocaleOfItsModule() {
        for (app in listOf("web", "admin")) {
            myFixture.addFileToProject("apps/$app/$DIR/messages.properties", "user.name=Name\n")
            myFixture.addFileToProject("apps/$app/$DIR/messages_de.properties", "user.name=Name\n")
        }
        val config = Config(
            modules = listOf(
                ModuleConfig(name = "web", rootDirectory = "apps/web", referenceLocale = "fr"),
                ModuleConfig(name = "admin", rootDirectory = "apps/admin", referenceLocale = "en"),
            ),
        )

        var locales = emptyMap<String, String?>()
        myFixture.runWithConfig(config) {
            locales = bundleSources().filter { it.name == "messages.properties" }
                .associate { source -> listOf("web", "admin").single { "apps/$it/" in source.displayPath } to source.locale }
        }

        Assertions.assertEquals(mapOf("web" to "fr", "admin" to "en"), locales)
    }

    @Test
    fun aDottedKeyResolvesLevelByLevelToItsDecodedValue() {
        addBundle()
        val english = bundleSources().single { it.locale == "en" }

        val (greeting, cafe, long) = ReadAction.compute<List<String?>, RuntimeException> {
            val tree = english.tree!!
            val leaf = tree.findChild("user")!!.findChild("greeting")!!
            Assertions.assertTrue(leaf.isLeaf())
            Assertions.assertEquals(listOf("user", "cafe", "long"), tree.entries()!!.map { it.first })
            listOf(leaf, tree.findChild("cafe")!!, tree.findChild("long")!!).map { TranslationPsi.readValue(it.value()) }
        }
        Assertions.assertEquals("Hello {0}", greeting)
        Assertions.assertEquals("Café", cafe)
        Assertions.assertEquals("first second", long)
    }

    @Test
    fun theTableShowsEveryKeyPerLocale() {
        addBundle()

        val rows = TableViewModel().loadRows(project).associateBy { it.key }

        Assertions.assertEquals(setOf("cafe", "long", "other", "user.greeting", "user.name"), rows.keys)
        Assertions.assertEquals(mapOf("en" to "Name", "fr" to "Nom", "pt_BR" to "Nome"), rows.getValue("user.name").values)
        Assertions.assertEquals("Café", rows.getValue("cafe").values["en"])
    }

    /** Sync Keys proposes what French lacks and writes it next to its siblings, escaped. */
    @Test
    fun syncKeysWritesTheMissingKeyAtItsPlace() {
        addBundle()

        val missing = KeysSynchronizer().missingEntries(project, Config())
            .filter { it.locale == "fr" }
        Assertions.assertEquals(listOf("cafe", "long"), missing.map { it.key }.sorted())

        val french = bundleSources().single { it.locale == "fr" }
        DialogViewModel(project).saveTranslation(french, KeysSynchronizer().buildFullKey("user.title"), "Titre\nsur deux lignes")
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val lines = text("$DIR/messages_fr.properties").lines()
        Assertions.assertEquals(listOf("user.name=Nom", "user.greeting=Bonjour {1}", "user.title=Titre\\nsur deux lignes", "other=Autre"), lines.filter { it.isNotBlank() })
    }

    @Test
    fun anEditedValueIsEscapedBack() {
        addBundle()
        val french = bundleSources().single { it.locale == "fr" }

        WriteCommandAction.runWriteCommandAction(project) {
            TranslationPsi.replaceValue(french.tree!!.findChild("other")!!.value(), "a=b\nc", project)
        }

        Assertions.assertTrue(text("$DIR/messages_fr.properties").contains("other=a=b\\nc"), text("$DIR/messages_fr.properties"))
        val value = ReadAction.compute<String?, RuntimeException> { TranslationPsi.readValue(french.tree!!.findChild("other")!!.value()) }
        Assertions.assertEquals("a=b\nc", value)
    }

    /**
     * A bundle key is counted by the platform's references to its properties — what `getString`,
     * `getMessage` or Thymeleaf's `#{key}` leave in IDEA. Here a reference of the test's own, from
     * `<msg key="…"/>`, resolving to the key's property in every locale as they do.
     */
    @Test
    fun aBundleKeyReadByTheCodeIsCountedAndOnlyAnUnreadOneIsOfferedByCleanup() {
        addBundle()
        registerMessageKeyReferences()
        myFixture.addFileToProject("src/main/resources/templates/page.xml", """<page><msg key="user.name"/><msg key="user.name"/></page>""")
        val viewModel = TableViewModel()

        val counted = viewModel.countUsages(project, viewModel.loadRows(project)).associate { it.key to it.usageCount }

        Assertions.assertEquals(2, counted["user.name"], "two references, each resolving to three locales: $counted")
        Assertions.assertEquals(0, counted["user.greeting"], "$counted")
        Assertions.assertEquals(
            listOf("cafe", "long", "other", "user.greeting"),
            CleanupUnusedKeysAction().orphans(project, viewModel).map { it.second.key }.sorted(),
        )
    }

    /** Registers [MessageKeyReferences] for the test only, as `plugin.xml` would declare it. */
    private fun registerMessageKeyReferences() {
        val bean = PsiReferenceContributorEP().apply {
            language = XMLLanguage.INSTANCE.id
            implementationClass = MessageKeyReferences::class.java.name
            pluginDescriptor = DefaultPluginDescriptor("properties-native-usages-test")
        }
        PsiReferenceContributor.EP_NAME.point.registerExtension(bean, testRootDisposable)
    }

    @Test
    fun aMessageFormatPlaceholderOfTheReferenceMissingIsReported() {
        addBundle()
        myFixture.enableInspections(PlaceholderConsistencyInspection())

        myFixture.configureFromTempProjectFile("$DIR/messages_fr.properties")
        val messages = myFixture.doHighlighting().mapNotNull { it.description }

        Assertions.assertTrue(messages.any { it.contains("{0}") }, "$messages")
        Assertions.assertTrue(messages.any { it.contains("{1}") }, "$messages")
    }

    /** Two bundles in one folder: `errors_fr` is compared to `errors`, not to nothing nor to `messages`. */
    @Test
    fun eachBundleOfAFolderIsComparedToItsOwnReference() {
        addBundle()
        myFixture.addFileToProject("$DIR/errors.properties", "error.missing=Missing {0}\n")
        myFixture.addFileToProject("$DIR/errors_fr.properties", "error.missing=Manquant {1}\n")
        myFixture.enableInspections(PlaceholderConsistencyInspection())

        myFixture.configureFromTempProjectFile("$DIR/errors_fr.properties")
        val errors = myFixture.doHighlighting().mapNotNull { it.description }
        myFixture.configureFromTempProjectFile("$DIR/messages_fr.properties")
        val messages = myFixture.doHighlighting().mapNotNull { it.description }

        Assertions.assertTrue(errors.any { it.contains("{0}") }, "$errors")
        Assertions.assertTrue(messages.any { it.contains("{0}") } && messages.any { it.contains("{1}") }, "$messages")
    }
}

/** `key="…"` of an XML attribute refers to the bundle properties of that key, in every locale, as `getString` does in IDEA. */
internal class MessageKeyReferences : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(XmlPatterns.xmlAttributeValue("key"), object : PsiReferenceProvider() {
            override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> =
                arrayOf(object : PsiPolyVariantReferenceBase<PsiElement>(element) {
                    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
                        PropertiesImplUtil.findPropertiesByKey(element.project, (element as XmlAttributeValue).value)
                            .map { PsiElementResolveResult(it.psiElement) }.toTypedArray()
                })
        })
    }
}
