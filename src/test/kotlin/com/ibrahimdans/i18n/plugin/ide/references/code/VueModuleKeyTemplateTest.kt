package com.ibrahimdans.i18n.plugin.ide.references.code

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.elementAt
import com.ibrahimdans.i18n.plugin.ide.runVue
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.utils.generator.code.VueCodeGenerator
import com.ibrahimdans.i18n.plugin.utils.generator.translation.JsonTranslationGenerator
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.psi.PsiFile
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * A module's `Key template` for keys written in a Vue single-file component.
 *
 * The template is the one of the module holding the **host** file. A `{{ }}` interpolation is an
 * injected JS file, whose `containingFile.originalFile` is not the `.vue` file: no module was found
 * for it, so its keys were read with the project's separators instead of the module's template —
 * with the default `:` namespace separator, `ref.section` came out as the namespace-less segment
 * list `ref`, `section` and resolved against no file at all. The key was reported unresolved,
 * carried no reference, and neither Ctrl+click nor the hover table worked, while the same key
 * written in `<script>` resolved. `ModulePresets` and `RuleCalls` already reached the host file
 * through `InjectedLanguageManager.getTopLevelFile`; the parser and the source lookup did not.
 *
 * The files are resolved from the PSI without opening an editor: the key's references do not
 * depend on one, and opening a `.vue` file starts the Vue LSP service, which a test sandbox
 * cannot always initialise.
 */
class VueModuleKeyTemplateTest : PlatformBaseTest() {

    private val cg = VueCodeGenerator()
    private val json = JsonTranslationGenerator()

    /** `{ns}.{key}`: `ref.section` reads namespace `ref`, key `section` — the file is `ref.json`. */
    private val module = Config(
        modules = listOf(
            ModuleConfig(
                name = "app",
                rootDirectory = "src",
                pathTemplate = "locales/{lang}/{ns}.json",
                keyTemplate = "{ns}.{key}",
                referenceLocale = "en",
            )
        )
    )

    private fun seedJson() = myFixture.addFileToProject(
        "src/locales/en/ref.json",
        json.generateContent("section", "key", "Reference")
    )

    private fun component(name: String, text: String): PsiFile =
        myFixture.addFileToProject("src/$name", text)

    private fun assertResolves(file: PsiFile, key: String) = myFixture.runWithConfig(module) {
        read {
            val element = myFixture.elementAt(file, key)
            Assertions.assertNotNull(element, "no PSI element carries $key")
            Assertions.assertEquals(
                "Reference",
                element!!.references.firstOrNull()?.resolve()?.text?.unQuote(),
                "$key was not read with the module's key template"
            )
        }
    }

    @Test
    fun `a key in the script block is read with the module's key template`() = runVue {
        seedJson()
        assertResolves(component("Script.vue", cg.generate("'ref.section.key'")), "ref.section.key")
    }

    @Test
    fun `a key in a template interpolation is read with the module's key template`() = runVue {
        seedJson()
        assertResolves(component("Template.vue", cg.generateTemplate("'ref.section.key'")), "ref.section.key")
    }
}
