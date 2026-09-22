package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.elementAt
import com.ibrahimdans.i18n.plugin.ide.runVue
import com.ibrahimdans.i18n.plugin.utils.generator.code.JsCodeGenerator
import com.ibrahimdans.i18n.plugin.utils.generator.code.VueCodeGenerator
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * [hostFile] and [hostVirtualFile], the single crossing from an injected fragment back to the file
 * the key is written in.
 *
 * Module ownership, the `Key template` and the import gate all ask which file a key sits in, and
 * each one used to spell the crossing out. The ones that read `containingFile` directly saw the
 * injected fragment of a `{{ }}` interpolation — a file under no module root, importing nothing —
 * and fell back to project-wide behaviour without reporting anything. Asserting the crossing once,
 * here, is what keeps a sixth caller from spelling it out a sixth time and getting it wrong.
 */
class PsiHostFileTest : PlatformBaseTest() {

    private val js = JsCodeGenerator()
    private val vue = VueCodeGenerator()

    private val key = "'common.title'"

    @Test
    fun `an element outside any injection keeps its own file`() {
        val file = addFileToProject("src/plain.js", js.generate(key))
        read {
            val element = myFixture.elementAt(file, key)
            Assertions.assertNotNull(element, "no PSI element carries $key")
            Assertions.assertSame(file, element!!.hostFile(), "the host of a plain JS element is its own file")
            Assertions.assertEquals(file.virtualFile, element.hostVirtualFile(), "the host file on disk")
        }
    }

    @Test
    fun `an element in a template interpolation crosses back to the component`() = runVue {
        val file = addFileToProject("src/Template.vue", vue.generateTemplate(key))
        read {
            val element = myFixture.elementAt(file, key)
            Assertions.assertNotNull(element, "no PSI element carries $key")
            // Without this the test would pass on the host PSI and prove nothing about injection.
            Assertions.assertNotSame(
                file, element!!.containingFile,
                "$key was read from the host PSI, not from the injected fragment"
            )
            Assertions.assertSame(file, element.hostFile(), "the host of an injected element is the .vue component")
            Assertions.assertEquals(file.virtualFile, element.hostVirtualFile(), "the component on disk, not the fragment")
        }
    }
}
