package com.ibrahimdans.i18n.plugin.translate

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.elementAt
import com.ibrahimdans.i18n.plugin.ide.runVue
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Where the code shows a key's text, as [KeyUiRoles] tells an engine (#702): the attribute or the
 * tag around the call, never the code itself, and nothing for a call outside the markup.
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class KeyUiRolesTest : PlatformBaseTest() {

    private companion object {
        const val KEY = "common:generation.narration"
    }

    private fun tsx(body: String) {
        myFixture.configureByText(
            "Review.tsx",
            """
            import { useTranslation } from 'react-i18next';
            export function Review() {
                const { t } = useTranslation();
                $body
            }
            """.trimIndent()
        )
    }

    private fun rolesOf(key: String = KEY): List<String> {
        var roles = emptyList<String>()
        read { roles = KeyUiRoles.of(project, key) }
        return roles
    }

    @Test
    fun `a prop of a component is its caption`() {
        tsx("return <SettingsOption caption={t('$KEY')}><Switch /></SettingsOption>;")

        Assertions.assertEquals(listOf("caption of <SettingsOption>"), rolesOf())
    }

    @Test
    fun `an attribute of an html tag is named with its tag`() {
        tsx("return <input placeholder={t('$KEY')} />;")

        Assertions.assertEquals(listOf("placeholder of <input>"), rolesOf())
    }

    @Test
    fun `the content of a tag is its text`() {
        tsx("return <h2>{t('$KEY')}</h2>;")

        Assertions.assertEquals(listOf("text of <h2>"), rolesOf())
    }

    @Test
    fun `a call outside the markup has no role`() {
        tsx("const label = t('$KEY'); return label;")

        Assertions.assertEquals(emptyList<String>(), rolesOf())
    }

    @Test
    fun `a call in an event handler has no role`() {
        tsx("return <button onClick={() => alert(t('$KEY'))}>x</button>;")

        Assertions.assertEquals(emptyList<String>(), rolesOf())
    }

    @Test
    fun `the roles of several usages are given once, the first ones only`() {
        tsx(
            """
            return <div>
                <input placeholder={t('$KEY')} />
                <input placeholder={t('$KEY')} />
                <h2>{t('$KEY')}</h2>
                <Button label={t('$KEY')} />
            </div>;
            """.trimIndent()
        )

        val roles = rolesOf()
        Assertions.assertEquals(TranslationContext.MAX_ROLES, roles.size, roles.toString())
        Assertions.assertEquals(roles.distinct(), roles)
    }

    @Test
    fun `a bound attribute of a Vue template is named without its binding`() = runVue {
        val file = myFixture.configureByText(
            "Review.vue",
            "<template>\n  <input :aria-label=\"\$t('$KEY')\" />\n  <h2>{{ \$t('$KEY') }}</h2>\n</template>\n"
        )

        read {
            val attribute = myFixture.elementAt(file, KEY)
            Assertions.assertNotNull(attribute, "no PSI element carries the key")
            Assertions.assertEquals("aria-label of <input>", KeyUiRoles.roleOf(attribute!!))
        }
    }

    @Test
    fun `an interpolation in a Vue template is the text of its tag`() = runVue {
        val file = myFixture.configureByText("Review.vue", "<template>\n  <h2>{{ \$t('$KEY') }}</h2>\n</template>\n")

        read {
            val text = myFixture.elementAt(file, KEY)
            Assertions.assertNotNull(text, "no PSI element carries the key")
            Assertions.assertEquals("text of <h2>", KeyUiRoles.roleOf(text!!))
        }
    }
}
