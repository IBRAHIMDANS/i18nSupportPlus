package com.ibrahimdans.i18n.plugin.ide.actions.i18nize

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * *Internationalize…* on the `<script>` of a Vue component: `t('…')` in `<script setup>`, which
 * gets its `useI18n()` once, `this.$t('…')` in a method of the Options API — and never a string
 * that is Vue code.
 */
class I18nizeVueScriptTest : PlatformBaseTest() {

    private val config = Config(preferredLocalization = "json", defaultNs = "common")

    private fun planOf(file: PsiFile): I18nizePlan =
        ReadAction.compute<I18nizePlan, RuntimeException> { I18nizePlan.of(listOf(file)) }

    private fun write(plan: I18nizePlan): I18nizeApply.Outcome =
        ReadAction.compute<I18nizeApply, RuntimeException> { I18nizeApply.prepare(plan) }.write("Internationalize")

    private fun textOf(file: PsiFile): String = PsiDocumentManager.getInstance(project).getDocument(file)!!.text

    private fun I18nizePlan.texts(): List<String> = files.flatMap { file -> file.entries.map { it.candidate.originalText } }

    private fun component(script: String) = myFixture.addFileToProject("src/Profile.vue", "<template><div/></template>\n$script")

    @Test
    fun aScriptSetupGetsItsHookOnceInOneUndo() = myFixture.runWithConfig(config) {
        val translations = myFixture.addFileToProject("locales/en/common.json", "{}")
        val profile = component("""
            <script setup>
            import { ref } from 'vue';
            const label = ref('Save changes');
            function done() { alert('Saved!'); }
            </script>
        """.trimIndent())
        val before = textOf(profile)
        myFixture.configureFromExistingVirtualFile(profile.virtualFile)

        write(planOf(profile))

        Assertions.assertEquals("""
            <template><div/></template>
            <script setup>
            import { ref } from 'vue';
            import { useI18n } from 'vue-i18n';
            const { t } = useI18n();
            const label = ref(t('profile.save_changes'));
            function done() { alert(t('profile.saved')); }
            </script>
        """.trimIndent(), textOf(profile))
        Assertions.assertTrue("\"saved\": \"Saved!\"" in textOf(translations), textOf(translations))

        UndoManager.getInstance(project).undo(FileEditorManager.getInstance(project).selectedEditor!!)

        Assertions.assertEquals(before, textOf(profile))
        Assertions.assertEquals("{}", textOf(translations))
    }

    @Test
    fun aScriptSetupWithItsTAlreadyGetsNoSecondOne() = myFixture.runWithConfig(config) {
        myFixture.addFileToProject("locales/en/common.json", "{}")
        val profile = component("""
            <script setup>
            import { useI18n } from 'vue-i18n'
            const { t } = useI18n()
            const title = 'Your profile'
            </script>
        """.trimIndent())

        write(planOf(profile))

        Assertions.assertEquals("""
            <template><div/></template>
            <script setup>
            import { useI18n } from 'vue-i18n'
            const { t } = useI18n()
            const title = t('profile.your_profile')
            </script>
        """.trimIndent(), textOf(profile))
    }

    @Test
    fun aMethodOfTheOptionsApiCallsThisT() = myFixture.runWithConfig(config) {
        myFixture.addFileToProject("locales/en/common.json", "{}")
        val profile = component("""
            <script>
            export default {
              name: 'Profile',
              methods: {
                save() { this.notify('Profile saved') }
              }
            }
            </script>
        """.trimIndent())

        write(planOf(profile))

        Assertions.assertTrue("save() { this.notify(this.\$t('profile.profile_saved')) }" in textOf(profile), textOf(profile))
        Assertions.assertTrue("name: 'Profile'" in textOf(profile), textOf(profile))
    }

    @Test
    fun vueCodeIsNeverProposed() = myFixture.runWithConfig(config) {
        myFixture.addFileToProject("locales/en/common.json", "{}")
        val setup = component("""
            <script setup lang="ts">
            type Kind = 'primary' | 'secondary'
            const props = defineProps({ label: { type: String, default: 'Default label' } })
            const emit = defineEmits(['save'])
            function go() { emit('saved') }
            const message = 'Keep this one'
            </script>
        """.trimIndent())
        Assertions.assertEquals(listOf("Keep this one"), planOf(setup).texts())

        val options = myFixture.addFileToProject("src/Plain.vue", """
            <template><div/></template>
            <script>
            const greeting = 'Top level text'
            export default {
              setup() { return { label: 'In setup' } },
              data: () => ({ title: 'In an arrow' })
            }
            </script>
        """.trimIndent())
        Assertions.assertEquals(emptyList<String>(), planOf(options).texts())
    }
}
