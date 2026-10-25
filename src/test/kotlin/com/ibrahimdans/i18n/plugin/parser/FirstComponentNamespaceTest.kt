package com.ibrahimdans.i18n.plugin.parser

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * *First component as namespace*: `t('common.title')` is the key `title` of the `common` file, as
 * extraction and *Copy Key* write it. It used to be read as a key of the default namespace.
 */
class FirstComponentNamespaceTest : PlatformBaseTest() {

    @Test
    fun theFirstSegmentNamesTheFile() = myFixture.runWithConfig(Config(firstComponentNs = true)) {
        val unresolved = PluginBundle.getMessage("annotator.unresolved.key")
        addFileToProject("locales/en/common.json", """{"title": "Title"}""")
        fun descriptions(path: String, code: String): List<String> {
            myFixture.configureFromExistingVirtualFile(addFileToProject(path, code).virtualFile)
            return myFixture.doHighlighting().mapNotNull { it.description }
        }

        assertFalse(descriptions("src/Found.js", "t('common.title')").contains(unresolved), "common.title is common.json's title")
        assertTrue(descriptions("src/Missing.js", "t('common.missing')").contains(unresolved), "a key common.json lacks is reported")
    }
}
