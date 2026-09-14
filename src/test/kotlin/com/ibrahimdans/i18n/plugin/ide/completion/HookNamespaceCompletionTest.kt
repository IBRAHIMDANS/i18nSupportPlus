package com.ibrahimdans.i18n.plugin.ide.completion

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.codeInsight.completion.CompletionType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Completion offers the keys of the namespaces a key actually works in. A key written without a
 * namespace used to be completed against the default namespace (or every file), whatever its
 * `useTranslation` hook declared.
 */
class HookNamespaceCompletionTest : PlatformBaseTest() {

    private fun lookups(code: String): List<String> {
        myFixture.configureByText("Login.tsx", code)
        myFixture.complete(CompletionType.BASIC, 1)
        return myFixture.lookupElementStrings.orEmpty().sorted()
    }

    /**
     * The other namespace's keys are chosen to be words no platform contributor offers on its own:
     * the lookup also carries JS/DOM string completions, so absence is only meaningful for them.
     */
    private fun addNamespaces() {
        addFileToProject("locales/en/auth.json", """{"login": "Log in", "logout": "Log out"}""")
        addFileToProject("locales/en/common.json", """{"zzCommonOnly": "Cancel", "zzConfirmOnly": "OK"}""")
    }

    @Test
    fun hookNamespaceRestrictsTheKeysOffered() {
        addNamespaces()
        val offered = lookups(
            """
            import { useTranslation } from 'react-i18next';
            export default function Login() {
                const { t } = useTranslation('auth');
                return t('<caret>');
            }
            """.trimIndent()
        )
        assertTrue(offered.containsAll(listOf("login", "logout")), "the hook namespace's keys must be offered: $offered")
        assertFalse(offered.any { it.startsWith("zz") }, "another namespace's keys must not be offered: $offered")
    }

    /** The key prefix a hook declares is the level whose children are offered. */
    @Test
    fun keyPrefixIsTheLevelCompleted() {
        addFileToProject("locales/en/auth.json", """{"form": {"zzEmail": "E-mail", "zzPassword": "Password"}, "zzLogout": "Log out"}""")
        val offered = lookups(
            """
            import { useTranslation } from 'react-i18next';
            export default function Login() {
                const { t } = useTranslation('auth', { keyPrefix: 'form' });
                return t('<caret>');
            }
            """.trimIndent()
        )
        assertTrue(offered.containsAll(listOf("zzEmail", "zzPassword")), "the prefix level must be offered: $offered")
        assertFalse("zzLogout" in offered, "a key outside the prefix must not be offered: $offered")
    }
}
