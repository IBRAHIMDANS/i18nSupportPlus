package com.ibrahimdans.i18n.plugin.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale
import java.util.ResourceBundle

/**
 * Guards the menu entries, which no other test can see.
 *
 * An `<action>` in `plugin.xml` that declares no `text=` is labelled by the platform from
 * `<resource-bundle>`, under `action.<id>.text`. Nothing in Kotlin ever names those keys, so
 * [PluginBundleTest] — which reads the keys out of the sources — cannot check them: a typo in
 * an id, or an action renamed without its keys, ships an entry labelled `!action.….text!` in
 * the *Tools* menu.
 *
 * The check runs both ways on purpose. Missing keys break the menu; keys left behind by a
 * removed action are dead weight the parity test would then demand in every translation.
 */
class ActionDeclarationBundleTest {

    private companion object {
        val PLUGIN_XML = File("src/main/resources/META-INF/plugin.xml")

        /** An `<action>` or `<group>` element, up to the end of its attribute list. */
        val DECLARATION = Regex("""<(action|group)\s((?:[^<>"]|"[^"]*")*?)/?>""")
        val ID = Regex("""\bid="([^"]+)"""")
        val TEXT = Regex("""\btext="""")
    }

    /** Ids of the declarations that rely on the bundle for their label, by element name. */
    private fun bundledDeclarations(): List<Pair<String, String>> {
        assertTrue(PLUGIN_XML.isFile, "expected plugin.xml at ${PLUGIN_XML.absolutePath}")
        val actions = PLUGIN_XML.readText().substringAfter("<actions>").substringBefore("</actions>")

        return DECLARATION.findAll(actions)
            .mapNotNull { match ->
                val attributes = match.groupValues[2]
                val id = ID.find(attributes)?.groupValues?.get(1) ?: return@mapNotNull null
                if (TEXT.containsMatchIn(attributes)) null else match.groupValues[1] to id
            }
            .toList()
    }

    private fun baseBundle(): ResourceBundle =
        ResourceBundle.getBundle("messages.I18nBundle", Locale.ROOT, javaClass.classLoader)

    @Test
    fun `every action declared without a text attribute is labelled by the bundle`() {
        val keys = baseBundle().keySet()

        val missing = bundledDeclarations()
            .map { (tag, id) -> "$tag.$id.text" }
            .filterNot { it in keys }

        assertEquals(
            emptyList<String>(), missing,
            "these menu entries would be rendered as !key! — declare them in plugin.xml or add the key"
        )
    }

    @Test
    fun `no action key survives the declaration it labels`() {
        val declared = bundledDeclarations().map { (tag, id) -> "$tag.$id" }.toSet()

        val orphaned = baseBundle().keySet()
            .filter { it.endsWith(".text") || it.endsWith(".description") }
            .filter { it.startsWith("action.com.ibrahimdans.") || it.startsWith("group.com.ibrahimdans.") }
            .filterNot { it.substringBeforeLast('.') in declared }

        assertEquals(
            emptyList<String>(), orphaned.sorted(),
            "these keys label an action that plugin.xml no longer declares without a text attribute"
        )
    }

    /** At least one entry must go through the bundle, or both tests above pass on nothing. */
    @Test
    fun `the tools menu is declared through the bundle`() {
        assertTrue(
            bundledDeclarations().any { (_, id) -> id == "com.ibrahimdans.i18n.RunSetupWizard" },
            "expected the wizard action to take its label from the bundle"
        )
    }
}
