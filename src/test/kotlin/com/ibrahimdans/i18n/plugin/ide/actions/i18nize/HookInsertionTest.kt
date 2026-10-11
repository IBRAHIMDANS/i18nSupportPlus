package com.ibrahimdans.i18n.plugin.ide.actions.i18nize

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlText
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class HookInsertionTest : PlatformBaseTest() {

    private val config = Config(preferredLocalization = "json", defaultNs = "common")

    /** The first JSX text of [file]. */
    private fun textOf(file: PsiFile): PsiElement =
        ReadAction.compute<PsiElement, RuntimeException> { PsiTreeUtil.findChildOfType(file, XmlText::class.java)!! }

    private fun componentOf(file: PsiFile): JSFunction? =
        ReadAction.compute<JSFunction?, RuntimeException> { HookInsertion.componentNeedingHook(textOf(file)) }

    /** [file] once the hook is given to the component holding its first text, in [namespace]. */
    private fun withHook(file: PsiFile, namespace: String = "common"): String {
        val component = componentOf(file)!!
        val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) {
            HookInsertion.edits(file, mapOf(component to namespace))
                .sortedByDescending { it.range.startOffset }
                .forEach { document.replaceString(it.range.startOffset, it.range.endOffset, it.text) }
        }
        return document.text
    }

    @Test
    fun aComponentWithoutHookGetsOneAndTheImport() = myFixture.runWithConfig(config) {
        val file = myFixture.addFileToProject("src/Profile.tsx", """
            import React from 'react';

            export function Profile() {
                return <h1>My profile</h1>;
            }
        """.trimIndent())

        Assertions.assertEquals("""
            import React from 'react';
            import { useTranslation } from 'react-i18next';

            export function Profile() {
                const { t } = useTranslation('account');
                return <h1>My profile</h1>;
            }
        """.trimIndent(), withHook(file, "account"))
    }

    @Test
    fun theImportIsMergedIntoAnExistingReactI18nextImport() = myFixture.runWithConfig(config) {
        val file = myFixture.addFileToProject("src/Menu.tsx", """
            import { Trans } from 'react-i18next';
            export const Menu = () => <nav>Home</nav>;
        """.trimIndent())

        Assertions.assertEquals("""
            import { useTranslation, Trans } from 'react-i18next';
            export const Menu = () => { const { t } = useTranslation('common'); return <nav>Home</nav>; };
        """.trimIndent(), withHook(file))
    }

    @Test
    fun anImportOfTheHookIsNotRepeated() = myFixture.runWithConfig(config) {
        val file = myFixture.addFileToProject("src/Panel.tsx", """
            import { useTranslation } from 'react-i18next';
            export function Panel() { return <p>Close</p>; }
        """.trimIndent())

        Assertions.assertEquals("""
            import { useTranslation } from 'react-i18next';
            export function Panel() { const { t } = useTranslation('common'); return <p>Close</p>; }
        """.trimIndent(), withHook(file))
    }

    @Test
    fun aComponentWithAHookGetsNoOther() = myFixture.runWithConfig(config) {
        val withHook = myFixture.addFileToProject("src/Account.tsx", """
            import { useTranslation } from 'react-i18next';
            export function Account() { const { t } = useTranslation('account'); return <p>Delete</p>; }
        """.trimIndent())
        // A keyPrefix hook's `t` is not the extraction's, but a second `const { t }` would not compile.
        val withPrefix = myFixture.addFileToProject("src/Prefixed.tsx", """
            import { useTranslation } from 'react-i18next';
            export function Prefixed() { const { t } = useTranslation('account', { keyPrefix: 'page' }); return <p>Delete</p>; }
        """.trimIndent())

        Assertions.assertNull(componentOf(withHook))
        Assertions.assertNull(componentOf(withPrefix))
    }

    @Test
    fun onlyAReactComponentGetsAHook() = myFixture.runWithConfig(config) {
        val helper = myFixture.addFileToProject("src/helpers.tsx", "export function render() { return <p>Hello</p>; }")

        Assertions.assertNull(componentOf(helper))
    }
}
