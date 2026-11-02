package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.extensions.technology.i18next.I18nextInitOptions
import com.ibrahimdans.i18n.extensions.technology.i18next.I18nextInitReader
import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
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
 *
 * [readForWizard] reads the other files stating the same things, under the same rule:
 * `next-i18next.config.js`, `lingui.config.*`, `transloco.config.*` and the `backend.loadPath` of an
 * i18next init.
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

    /**
     * What the file [relativePath] of [I18nextCliConfig.WIZARD_FILE_NAMES] states; null when it holds
     * no options. [isDirectory] tells whether a project-relative folder exists, which is how the
     * URL of a `loadPath` is told from the folder serving it.
     */
    fun readForWizard(file: PsiFile, relativePath: String, isDirectory: (String) -> Boolean): I18nextCliConfig? =
        when (relativePath) {
            in I18nextCliConfig.FILE_NAMES -> read(file, relativePath)
            in I18nextCliConfig.NEXT_I18NEXT_FILE_NAMES -> readNextI18next(file, relativePath)
            in I18nextCliConfig.LINGUI_FILE_NAMES -> readLingui(file, relativePath)
            in I18nextCliConfig.TRANSLOCO_FILE_NAMES -> readTransloco(file, relativePath)
            else -> readBackend(file, relativePath, isDirectory)
        }

    /** `localePath` (`./public/locales` when left out), `defaultNS` or `ns`, `fallbackLng` or `i18n.defaultLocale`. */
    private fun readNextI18next(file: PsiFile, relativePath: String): I18nextCliConfig? {
        val options = optionsHolding(file, I18N, LOCALE_PATH, DEFAULT_NS, NS) ?: return null
        // A computed `localePath` is no reason to assume the default.
        val localePath = if (options.findProperty(LOCALE_PATH) == null) DEFAULT_LOCALE_PATH else options.string(LOCALE_PATH)
        val structure = options.string("localeStructure") ?: DEFAULT_LOCALE_STRUCTURE
        return I18nextInitReader.readOptions(options).toConfig(
            relativePath,
            output = localePath?.let { "${it.trimEnd('/')}/$structure.json" },
            fallbackLocale = (options.findProperty(I18N)?.value as? JSObjectLiteralExpression).string("defaultLocale"),
        )
    }

    /** The `path` of the first of `catalogs`, `<rootDir>` being the configuration's folder, and `sourceLocale`. */
    private fun readLingui(file: PsiFile, relativePath: String): I18nextCliConfig? {
        val options = optionsHolding(file, CATALOGS, SOURCE_LOCALE) ?: return null
        val catalog = (options.findProperty(CATALOGS)?.value as? JSArrayLiteralExpression)
            ?.expressions?.firstOrNull() as? JSObjectLiteralExpression
        return I18nextCliConfig(
            file = relativePath,
            output = catalog.string("path")?.removePrefix("<rootDir>/"),
            referenceLocale = options.string(SOURCE_LOCALE),
        )
    }

    /** `rootTranslationsPath` and `defaultLang`. */
    private fun readTransloco(file: PsiFile, relativePath: String): I18nextCliConfig? {
        val options = optionsHolding(file, ROOT_TRANSLATIONS_PATH, DEFAULT_LANG) ?: return null
        return I18nextCliConfig(
            file = relativePath,
            output = options.string(ROOT_TRANSLATIONS_PATH)?.let { "${it.trimEnd('/')}/" },
            referenceLocale = options.string(DEFAULT_LANG),
        )
    }

    /**
     * The `backend.loadPath` of an i18next init, with the options around it. A `loadPath` is a URL:
     * its catalogues are on disk under `public/`, or at that path for a project serving its root.
     * Neither folder existing, or a full URL, leaves the root to the scan.
     */
    private fun readBackend(file: PsiFile, relativePath: String, isDirectory: (String) -> Boolean): I18nextCliConfig? {
        val options = I18nextInitReader.optionsObject(file) ?: return null
        val loadPath = (options.findProperty(BACKEND)?.value as? JSObjectLiteralExpression).string(LOAD_PATH) ?: return null
        val path = loadPath.takeUnless { it.contains("://") }?.trimStart('/')
        val served = path?.let { I18nextCliConfig(relativePath, output = it, fromProjectRoot = true).translationsRoot }
        val output = when {
            served == null -> null
            isDirectory("$PUBLIC/$served") -> "$PUBLIC/$path"
            isDirectory(served) -> path
            else -> null
        }
        return I18nextInitReader.readOptions(options).toConfig(relativePath, output).copy(fromProjectRoot = true)
    }

    /** What i18next itself takes for the default namespace: `defaultNS`, else the first of `ns` without `translation`. */
    private fun I18nextInitOptions.toConfig(file: String, output: String?, fallbackLocale: String? = null) = I18nextCliConfig(
        file = file,
        output = output,
        defaultNs = defaultNs.firstOrNull() ?: ns.firstOrNull().takeUnless { TRANSLATION in ns },
        keySeparator = keySeparator,
        nsSeparator = nsSeparator,
        referenceLocale = fallbackLng ?: fallbackLocale,
    )

    /** The first object literal of [file] holding one of [names], however it is exported. */
    private fun optionsHolding(file: PsiFile, vararg names: String): JSObjectLiteralExpression? =
        PsiTreeUtil.findChildrenOfType(file, JSObjectLiteralExpression::class.java)
            .firstOrNull { options -> names.any { options.findProperty(it) != null } }

    /** Property [name] when it is a non-empty quoted string, null otherwise. */
    private fun JSObjectLiteralExpression?.string(name: String): String? {
        val literal = this?.findProperty(name)?.value as? JSLiteralExpression ?: return null
        return literal.stringValue?.takeIf { literal.isQuotedLiteral && it.isNotEmpty() }
    }

    private const val EXTRACT = "extract"
    private const val LOCALES = "locales"
    private const val I18N = "i18n"
    private const val LOCALE_PATH = "localePath"
    private const val DEFAULT_LOCALE_PATH = "./public/locales"
    private const val DEFAULT_LOCALE_STRUCTURE = "{{lng}}/{{ns}}"
    private const val DEFAULT_NS = "defaultNS"
    private const val NS = "ns"
    private const val TRANSLATION = "translation"
    private const val CATALOGS = "catalogs"
    private const val SOURCE_LOCALE = "sourceLocale"
    private const val ROOT_TRANSLATIONS_PATH = "rootTranslationsPath"
    private const val DEFAULT_LANG = "defaultLang"
    private const val BACKEND = "backend"
    private const val LOAD_PATH = "loadPath"
    private const val PUBLIC = "public"
}
