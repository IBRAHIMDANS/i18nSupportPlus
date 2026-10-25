package com.ibrahimdans.i18n.plugin.ide.settings

import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

/**
 * Reads an i18next-cli configuration file (`i18next.config.ts`, `.js`, `.mjs`, `.cjs`) through the
 * IDE's JavaScript PSI, into an [I18nextCliConfig].
 *
 * The options object is the first object literal holding `extract` or `locales`, whichever way it
 * is exported: `export default defineConfig({ … })`, `export default { … } satisfies …`,
 * `module.exports = { … }`, or a `const` exported afterwards. Only quoted strings are read: a value
 * computed at runtime, or `false`, is left out — nothing here runs Node.
 */
object I18nextCliConfigReader {

    /** The configuration [file] states, [relativePath] being its project-relative path; null when it holds no options object. */
    fun read(file: PsiFile, relativePath: String): I18nextCliConfig? {
        val options = PsiTreeUtil.findChildrenOfType(file, JSObjectLiteralExpression::class.java)
            .firstOrNull { it.findProperty(EXTRACT) != null || it.findProperty(LOCALES) != null }
            ?: return null
        val extract = options.findProperty(EXTRACT)?.value as? JSObjectLiteralExpression
        return I18nextCliConfig(
            file = relativePath,
            output = extract.string("output"),
            defaultNs = extract.string("defaultNS"),
            keySeparator = extract.string("keySeparator"),
            nsSeparator = extract.string("nsSeparator"),
        )
    }

    /** Property [name] when it is a non-empty quoted string, null otherwise. */
    private fun JSObjectLiteralExpression?.string(name: String): String? {
        val literal = this?.findProperty(name)?.value as? JSLiteralExpression ?: return null
        return literal.stringValue?.takeIf { literal.isQuotedLiteral && it.isNotEmpty() }
    }

    private const val EXTRACT = "extract"
    private const val LOCALES = "locales"
}
