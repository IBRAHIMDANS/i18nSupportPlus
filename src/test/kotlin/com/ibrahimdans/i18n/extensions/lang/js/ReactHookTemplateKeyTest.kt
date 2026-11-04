package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.KeyElementType
import com.intellij.lang.javascript.psi.ecma6.JSStringTemplateExpression
import com.intellij.psi.util.PsiTreeUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Regression tests for hook keys with a mid-key interpolation, such as
 * `` t(`common:role.${role}.label`) ``: the template structure and hook namespace must be kept.
 */
class ReactHookTemplateKeyTest : PlatformBaseTest() {

    private companion object {
        /** The `${role}` the fixtures write inside their template keys. */
        const val ROLE = "\${role}"
    }

    /** A label under each role for mid-key interpolation tests. */
    private fun translations() = addFileToProject(
        "en/common.json",
        """{"role":{"owner":{"label":"Owner"},"member":{"label":"Member"},"auditor":{"label":"Auditor"}}}"""
    )

    /** The text of each range the annotator reports as an unresolved key in the open file. */
    private fun unresolvedKeys(): List<String> {
        val text = myFixture.file.text
        return myFixture.doHighlighting()
            .filter { it.description == "Unresolved key" }
            .map { text.substring(it.startOffset, it.endOffset) }
    }

    @Test
    fun `a dynamic segment in the middle of a hook key is not reported`() {
        myFixture.runWithConfig(Config(defaultNs = "common")) {
            translations()
            myFixture.configureByText(
                "RoleLabels.tsx",
                """
                import { useTranslation } from 'react-i18next';

                const ROLES = ['owner', 'member', 'auditor'] as const;

                export function RoleLabels() {
                    const { t } = useTranslation();
                    return (
                        <ul>
                            {ROLES.map((role) => (
                                <li key={role}>{t(`common:role.$ROLE.label`)}</li>
                            ))}
                        </ul>
                    );
                }
                """.trimIndent()
            )
            assertEquals(emptyList<String>(), unresolvedKeys())
        }
    }

    @Test
    fun `the hook namespace is kept for a template key`() {
        myFixture.runWithConfig(Config(defaultNs = "translation")) {
            translations()
            myFixture.configureByText(
                "RoleLabel.tsx",
                """
                import { useTranslation } from 'react-i18next';

                export function RoleLabel({ role }: { role: string }) {
                    const { t } = useTranslation('common');
                    return <span>{t(`role.$ROLE.label`)}</span>;
                }
                """.trimIndent()
            )
            val template = PsiTreeUtil.findChildrenOfType(myFixture.file, JSStringTemplateExpression::class.java)
                .single()
            val rawKey = JsxLang().extractRawKey(template)
            assertNotNull(rawKey, "The template must be extracted as a translation key")
            assertEquals(
                listOf("common"),
                rawKey!!.arguments,
                "The hook namespace must override the default namespace",
            )
            assertEquals(
                listOf(KeyElement.template("role")),
                rawKey.keyElements.filter { it.type == KeyElementType.TEMPLATE },
                "The interpolation must remain dynamic rather than being flattened into a literal",
            )
            assertEquals(emptyList<String>(), unresolvedKeys())
        }
    }

    @Test
    fun `the hook key prefix is prepended without changing the written template`() {
        myFixture.runWithConfig(Config(defaultNs = "translation")) {
            translations()
            myFixture.configureByText(
                "PrefixedRoleLabel.tsx",
                """
                import { useTranslation } from 'react-i18next';

                export function RoleLabel({ role }: { role: string }) {
                    const { t } = useTranslation('common', { keyPrefix: 'role' });
                    return <span>{t(`$ROLE.label`)}</span>;
                }
                """.trimIndent()
            )
            val template = PsiTreeUtil.findChildrenOfType(myFixture.file, JSStringTemplateExpression::class.java)
                .single()
            val rawKey = JsxLang().extractRawKey(template)
            assertNotNull(rawKey, "The prefixed template must be extracted as a translation key")
            assertEquals(listOf("common"), rawKey!!.arguments)
            assertEquals(listOf(KeyElement.literal("role")), rawKey.keyPrefix)
            val fullKey = RawKeyParser(project).parse(rawKey, template)
            assertNotNull(fullKey, "The prefixed template must parse as a translation key")
            assertEquals(listOf("role", "*", "label"), fullKey!!.compositeKey.map { it.text })
            assertEquals("$ROLE.label", fullKey.source)
            assertEquals(emptyList<String>(), unresolvedKeys())
        }
    }

    @Test
    fun `a template call namespace overrides the hook namespace`() {
        myFixture.runWithConfig(Config(defaultNs = "translation")) {
            translations()
            addFileToProject("en/auth.json", """{"login":"Log in"}""")
            myFixture.configureByText(
                "OverriddenRoleLabel.tsx",
                """
                import { useTranslation } from 'react-i18next';

                export function RoleLabel({ role }: { role: string }) {
                    const { t } = useTranslation('auth');
                    return <span>{t(`role.$ROLE.label`, { ns: 'common' })}</span>;
                }
                """.trimIndent()
            )
            val template = PsiTreeUtil.findChildrenOfType(myFixture.file, JSStringTemplateExpression::class.java)
                .single()
            val rawKey = JsxLang().extractRawKey(template)
            assertNotNull(rawKey, "The template with namespace options must be extracted as a translation key")
            assertEquals(
                listOf("common"),
                rawKey!!.arguments,
                "The call namespace must replace, not append to, the hook namespace",
            )
            assertEquals(emptyList<String>(), unresolvedKeys())
        }
    }

    @Test
    fun `a dynamic segment at the end of a hook key is still not reported`() {
        myFixture.runWithConfig(Config(defaultNs = "common")) {
            translations()
            myFixture.configureByText(
                "RoleName.tsx",
                """
                import { useTranslation } from 'react-i18next';

                export function RoleName({ role }: { role: string }) {
                    const { t } = useTranslation('common');
                    return <span>{t(`role.$ROLE`)}</span>;
                }
                """.trimIndent()
            )
            assertEquals(emptyList<String>(), unresolvedKeys())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["absent", "role.absent"])
    fun `a missing static prefix before an interpolation is still reported`(prefix: String) {
        myFixture.runWithConfig(Config(defaultNs = "common")) {
            translations()
            myFixture.configureByText(
                "MissingRolePrefix.tsx",
                """
                import { useTranslation } from 'react-i18next';

                export function RoleLabel({ role }: { role: string }) {
                    const { t } = useTranslation('common');
                    return <span>{t(`$prefix.$ROLE.label`)}</span>;
                }
                """.trimIndent()
            )
            // The dynamic suffix cannot hide a typo before it; an existing prefix is not underlined.
            assertEquals(listOf("absent.$ROLE.label"), unresolvedKeys())
        }
    }

    @Test
    fun `a missing static key is still reported`() {
        myFixture.runWithConfig(Config(defaultNs = "common")) {
            translations()
            myFixture.configureByText(
                "MissingRole.tsx",
                """
                import { useTranslation } from 'react-i18next';

                export function MissingRole() {
                    const { t } = useTranslation('common');
                    return <span>{t('role.absent.label')}</span>;
                }
                """.trimIndent()
            )
            assertEquals(listOf("absent.label"), unresolvedKeys())
        }
    }
}
