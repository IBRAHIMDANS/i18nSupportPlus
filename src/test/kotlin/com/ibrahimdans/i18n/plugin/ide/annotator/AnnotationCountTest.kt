package com.ibrahimdans.i18n.plugin.ide.annotator

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.UnresolvedKeyInspection
import com.ibrahimdans.i18n.plugin.ide.runVue
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.extensions.PluginId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * One problem, one annotation. A key was annotated twice in every JS dialect — its literal
 * expression and its leaf token were both claimed — and three times in a `.tsx`, which also ran
 * the JSX annotator next to the JavaScript one it inherits.
 *
 * The cases added for PHP, Blade, Twig and Angular qualify `Assertions.…`: with three strings,
 * `BasePlatformTestCase`'s inherited JUnit 3 `assertEquals(message, expected, actual)` wins over the
 * static import.
 */
class AnnotationCountTest : PlatformBaseTest() {

    @ParameterizedTest
    @ValueSource(strings = ["js", "jsx", "ts", "tsx"])
    fun `an unresolved key is annotated once`(ext: String) {
        myFixture.addFileToProject("locales/en/test.json", """{"ref": {"title": "x"}}""")
        myFixture.configureByText("Once.$ext", "export const a = (t) => t('test:ref.missing');")
        val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
        assertEquals(listOf("Unresolved key"), errors.map { it.description }, ".$ext")
    }

    /**
     * A Vue component is read by two annotators — the one declared on JavaScript for its script and
     * template expressions, the one declared on Vue for its markup — and by one inspection: each
     * unresolved key, in either part, is still reported once.
     */
    @Test
    fun `an unresolved key of a Vue component is reported once`() = runVue {
        myFixture.addFileToProject("locales/en/test.json", """{"ref": {"title": "x"}}""")
        myFixture.configureByText(
            "Once.vue",
            """
            <template>
              <p>{{ ${'$'}t('test:ref.inTemplate') }}</p>
              <i18n-t keypath="test:ref.inMarkup" tag="p"></i18n-t>
            </template>
            <script setup>
            import { useI18n } from 'vue-i18n'
            const { t } = useI18n()
            const label = t('test:ref.inScript')
            </script>
            """.trimIndent(),
        )
        val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
        assertEquals(listOf("inTemplate", "inMarkup", "inScript"), errors.map { it.text }, errors.map { it.description }.toString())
    }

    /** The errors of the open file, each with the text it covers and the tool reporting it. */
    private fun errors(): List<HighlightInfo> = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }

    private fun open(path: String, text: String) =
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject(path, text).virtualFile)

    @Test
    fun `an unresolved key of PHP is reported once, by the inspection`() {
        myFixture.addFileToProject("assets/test.json", """{"ref": {"title": "x"}}""")
        open("src/page.php", "<?php\necho t('test:ref.missing');\n")
        val errors = errors()
        Assertions.assertEquals(listOf("missing"), errors.map { it.text }, errors.map { it.description }.toString())
        Assertions.assertEquals(listOf(UnresolvedKeyInspection.SHORT_NAME), errors.map { it.inspectionToolId })
    }

    @Test
    fun `an unresolved key of a Blade view is reported once, by the inspection`() {
        myFixture.addFileToProject("composer.json", """{"require": {"laravel/framework": "^11.0"}}""")
        myFixture.addFileToProject("assets/test.json", """{"ref": {"title": "x"}}""")
        open("resources/views/a.blade.php", "<p>{{ __('test:ref.missing') }}</p>")
        Assertions.assertEquals("Blade", myFixture.file.language.id, "the view must be parsed as Blade")
        val errors = errors()
        Assertions.assertEquals(listOf("missing"), errors.map { it.text }, errors.map { it.description }.toString())
        Assertions.assertEquals(listOf(UnresolvedKeyInspection.SHORT_NAME), errors.map { it.inspectionToolId })
    }

    @Test
    fun `an unresolved key of a Twig template is reported once, by the inspection`() {
        val twig = PluginManagerCore.getPlugin(PluginId.getId("com.jetbrains.twig"))
        Assumptions.assumeTrue(twig != null && twig.isEnabled, "the Twig plugin is not loaded in this sandbox")
        myFixture.addFileToProject("composer.json", """{"require": {"symfony/translation": "7.1.*"}}""")
        myFixture.addFileToProject("translations/messages.en.yaml", "user:\n  name: Name\n")
        open("templates/page.html.twig", "{{ 'user.name'|trans }} {{ 'user.missing'|trans }}")
        Assertions.assertEquals("Twig", myFixture.file.fileType.name, "the template must be parsed as Twig")
        val errors = errors()
        Assertions.assertEquals(listOf("missing"), errors.map { it.text }, errors.map { it.description }.toString())
        Assertions.assertEquals(listOf(UnresolvedKeyInspection.SHORT_NAME), errors.map { it.inspectionToolId })
    }

    /** Read by two annotators (Angular, and JavaScript for the expression) and one inspection: one report. */
    @Test
    fun `an unresolved key of an Angular template is reported once, by the inspection`() =
        myFixture.runWithConfig(Config(defaultNs = "translation")) {
            myFixture.addFileToProject("assets/translation.json", """{"menu":{"home":"Home"}}""")
            myFixture.addFileToProject("package.json", """{"dependencies":{"@angular/core":"17.0.0","@ngx-translate/core":"15.0.0"}}""")
            myFixture.addFileToProject("node_modules/@angular/core/package.json", """{"name":"@angular/core","version":"17.0.0"}""")
            myFixture.addFileToProject(
                "app.component.ts",
                "import { Component } from '@angular/core';\n" +
                    "@Component({ selector: 'app-root', templateUrl: './tpl.html' })\nexport class AppComponent {}\n",
            )
            myFixture.configureByText("tpl.html", """<p>{{ 'menu.missing' | translate }}</p><p translate>menu.gone</p>""")
            Assertions.assertEquals("Angular17Html", myFixture.file.fileType.name, "the template must be parsed as an Angular template")
            val errors = errors()
            Assertions.assertEquals(listOf("missing", "gone"), errors.map { it.text }, errors.map { it.description }.toString())
            Assertions.assertEquals(listOf(UnresolvedKeyInspection.SHORT_NAME, UnresolvedKeyInspection.SHORT_NAME), errors.map { it.inspectionToolId })
        }
}
