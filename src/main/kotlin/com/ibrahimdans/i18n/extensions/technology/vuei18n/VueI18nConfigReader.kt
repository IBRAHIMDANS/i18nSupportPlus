package com.ibrahimdans.i18n.extensions.technology.vuei18n

import com.ibrahimdans.i18n.plugin.ide.settings.VueI18nConfig
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

/**
 * Reads a Nuxt configuration (`nuxt.config.ts`) or a vue-i18n `createI18n({ … })` call through the
 * IDE's JavaScript PSI, into a [VueI18nConfig]. Nothing here runs Node, as in [I18nextCliConfigReader].
 *
 * Nuxt: the `i18n` options of the configuration object, or the options of an
 * `['@nuxtjs/i18n', { … }]` entry of `modules`; `langDir`, `restructureDir` and the top-level
 * `srcDir` give the root ([VueI18nConfig.nuxtTranslationsRoot]), `defaultLocale` the reference
 * locale, and the `file` / `files` of the locale objects the format.
 *
 * vue-i18n: the first `createI18n` call given an object; `fallbackLocale`, else `locale`, gives the
 * reference locale, and the catalogues imported for `messages` give the root and the format.
 *
 * A value computed at runtime — options built by a function, `langDir: resolve(…)` — is left out:
 * an `i18n` option that is not an object literal yields a configuration stating nothing.
 */
object VueI18nConfigReader {

    /**
     * The configuration [file] states, [relativePath] being its project-relative path; null when it
     * holds neither Nuxt i18n options nor a `createI18n` call. [isDirectory] tells whether a
     * project-relative folder exists, which the Nuxt root depends on.
     */
    fun read(file: PsiFile, relativePath: String, isDirectory: (String) -> Boolean): VueI18nConfig? =
        if (relativePath.substringAfterLast('/') in VueI18nConfig.NUXT_FILE_NAMES) readNuxt(file, relativePath, isDirectory)
        else readCreateI18n(file, relativePath)

    private fun readNuxt(file: PsiFile, relativePath: String, isDirectory: (String) -> Boolean): VueI18nConfig? {
        val config = PsiTreeUtil.findChildrenOfType(file, JSObjectLiteralExpression::class.java)
            .firstOrNull { it.findProperty(I18N) != null || it.findProperty(MODULES) != null }
            ?: return null
        val modules = (config.findProperty(MODULES)?.value as? JSArrayLiteralExpression)?.expressions.orEmpty()
        val entry = modules.firstOrNull { module ->
            module.quoted() == NUXT_MODULE ||
                (module as? JSArrayLiteralExpression)?.expressions?.firstOrNull()?.quoted() == NUXT_MODULE
        }
        val i18n = config.findProperty(I18N)
        if (i18n == null && entry == null) return null
        // Listed with no options at all, the module runs on its defaults: `i18n/locales`.
        val options = i18n?.value ?: (entry as? JSArrayLiteralExpression)?.expressions?.getOrNull(1)
        if (options != null && options !is JSObjectLiteralExpression) return VueI18nConfig(relativePath)
        val stated = options as? JSObjectLiteralExpression
        val computed = config.isComputed(SRC_DIR) ||
            stated != null && (stated.isComputed(LANG_DIR) || stated.isComputed(RESTRUCTURE_DIR, allowFalse = true))
        val root = if (computed) null
            else VueI18nConfig.nuxtTranslationsRoot(
                langDir = stated.string(LANG_DIR),
                restructureDir = if (stated?.isFalse(RESTRUCTURE_DIR) == true) "" else stated.string(RESTRUCTURE_DIR),
                srcDir = config.string(SRC_DIR),
                isDirectory = isDirectory,
            )
        return VueI18nConfig(
            file = relativePath,
            translationsRoot = root,
            referenceLocale = stated.string(DEFAULT_LOCALE),
            localeFiles = stated?.let(::localeFiles).orEmpty(),
        )
    }

    /** The catalogue names the locale objects declare: `file: 'en.json'`, `file: { path }`, `files: [ … ]`. */
    private fun localeFiles(options: JSObjectLiteralExpression): List<String> {
        val locales = options.findProperty(LOCALES)?.value as? JSArrayLiteralExpression ?: return emptyList()
        return locales.expressions.filterIsInstance<JSObjectLiteralExpression>().flatMap { locale ->
            val file = locale.findProperty(FILE)?.value
            val files = (locale.findProperty(FILES)?.value as? JSArrayLiteralExpression)?.expressions.orEmpty()
            (listOfNotNull(file) + files).mapNotNull { it.quoted() ?: (it as? JSObjectLiteralExpression).string(PATH) }
        }
    }

    private fun readCreateI18n(file: PsiFile, relativePath: String): VueI18nConfig? {
        val options = PsiTreeUtil.findChildrenOfType(file, JSCallExpression::class.java)
            .firstOrNull { (it.methodExpression as? JSReferenceExpression)?.referenceName == CREATE_I18N }
            ?.arguments?.firstOrNull() as? JSObjectLiteralExpression
            ?: return null
        val imports = importsOf(file)
        val catalogues = (options.findProperty(MESSAGES)?.value as? JSObjectLiteralExpression)?.properties.orEmpty()
            .map { (it.value as? JSReferenceExpression)?.referenceName?.let(imports::get) }
        // One message object that is not an imported file leaves the root unknown rather than partial.
        val paths = catalogues.takeIf { it.isNotEmpty() && null !in it }?.filterNotNull().orEmpty()
        return VueI18nConfig(
            file = relativePath,
            translationsRoot = VueI18nConfig.commonFolder(relativePath.substringBeforeLast('/', ""), paths),
            referenceLocale = options.string(FALLBACK_LOCALE) ?: options.string(LOCALE),
            localeFiles = paths.map { it.substringAfterLast('/') },
        )
    }

    /** Default-imported binding name to the module path it comes from: `import en from './locales/en.json'`. */
    private fun importsOf(file: PsiFile): Map<String, String> =
        PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java).flatMap { declaration ->
            val module = declaration.fromClause?.referenceText?.trim('\'', '"', '`') ?: return@flatMap emptyList()
            declaration.importedBindings.mapNotNull { binding -> binding.name?.let { it to module } }
        }.toMap()

    /** Property [name] when it is a non-empty quoted string, null otherwise. */
    private fun JSObjectLiteralExpression?.string(name: String): String? = this?.findProperty(name)?.value?.quoted()

    private fun JSExpression.quoted(): String? {
        val literal = this as? JSLiteralExpression ?: return null
        return literal.stringValue?.takeIf { literal.isQuotedLiteral && it.isNotEmpty() }
    }

    private fun JSObjectLiteralExpression.isFalse(name: String): Boolean = findProperty(name)?.value?.text == "false"

    /** True when [name] is set to something other than a quoted string (or `false`, when [allowFalse]). */
    private fun JSObjectLiteralExpression.isComputed(name: String, allowFalse: Boolean = false): Boolean {
        val value = findProperty(name)?.value ?: return false
        return value.quoted() == null && !(allowFalse && value.text == "false")
    }

    private const val I18N = "i18n"
    private const val NUXT_MODULE = "@nuxtjs/i18n"
    private const val MODULES = "modules"
    private const val SRC_DIR = "srcDir"
    private const val LANG_DIR = "langDir"
    private const val RESTRUCTURE_DIR = "restructureDir"
    private const val DEFAULT_LOCALE = "defaultLocale"
    private const val LOCALES = "locales"
    private const val FILE = "file"
    private const val FILES = "files"
    private const val PATH = "path"
    private const val CREATE_I18N = "createI18n"
    private const val MESSAGES = "messages"
    private const val FALLBACK_LOCALE = "fallbackLocale"
    private const val LOCALE = "locale"
}
