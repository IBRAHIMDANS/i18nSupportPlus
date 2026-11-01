package com.ibrahimdans.i18n.plugin.ide.settings

/**
 * What a Nuxt or vue-i18n project states about its translations, as far as the setup wizard can
 * use it: the `i18n` options of `nuxt.config.ts`, or the options passed to `createI18n`.
 *
 * Only literal values are kept, for the reason [I18nextCliConfig] gives: the file is read without
 * running Node, so a value computed at runtime is left null rather than guessed.
 *
 * Parser-free: the IDE reads the file into this shape, and [WizardPlan] turns it into proposals.
 *
 * @param file the configuration file, project-relative: the origin shown for every value
 * @param translationsRoot the folder holding the catalogues, project-relative
 * @param referenceLocale `defaultLocale` (Nuxt), else `fallbackLocale` then `locale` (vue-i18n)
 * @param localeFiles the catalogue file names the locales declare: their extension gives the format
 */
data class VueI18nConfig(
    val file: String,
    val translationsRoot: String? = null,
    val referenceLocale: String? = null,
    val localeFiles: List<String> = emptyList(),
) {

    companion object {
        /** The names Nuxt looks for, in its own order. */
        val NUXT_FILE_NAMES = listOf("nuxt.config.ts", "nuxt.config.js", "nuxt.config.mjs")

        /** Where a plain Vue project usually calls `createI18n`, project-relative. */
        val VUE_I18N_FILE_NAMES = listOf("ts", "js").flatMap { ext ->
            listOf("src/i18n.$ext", "src/i18n/index.$ext", "src/plugins/i18n.$ext", "src/main.$ext")
        }

        private const val DEFAULT_LANG_DIR = "locales"
        private const val DEFAULT_RESTRUCTURE_DIR = "i18n"

        /**
         * The folder `langDir` designates, project-relative, for a `nuxt.config` at the project root.
         *
         * @nuxtjs/i18n v9 and later resolve it from `restructureDir` (`i18n` by default, or the
         * source directory when set to `false`, passed here as an empty string); v8 resolved it from
         * the source directory (`srcDir`, the project root by default). The version is not read: the
         * v9 folder is kept when it exists, else the v8 one when it does, else the v9 one, since a
         * project starting today gets v9 or later.
         *
         * Null when the path leaves the project or is absolute: nothing then points at a folder of it.
         *
         * @param restructureDir null for the default, empty for `restructureDir: false`
         * @param isDirectory whether a project-relative folder exists
         */
        fun nuxtTranslationsRoot(
            langDir: String?,
            restructureDir: String?,
            srcDir: String?,
            isDirectory: (String) -> Boolean,
        ): String? {
            val dir = langDir ?: DEFAULT_LANG_DIR
            val source = srcDir.orEmpty()
            val current = join(restructureDir?.let { it.ifEmpty { source } } ?: DEFAULT_RESTRUCTURE_DIR, dir)
            val legacy = join(source, dir)
            val candidates = listOfNotNull(current, legacy).distinct()
            return candidates.firstOrNull(isDirectory) ?: current
        }

        /**
         * The folder holding the catalogues [importPaths] point at, project-relative, resolved from
         * [fileFolder], the folder of the file importing them. Their deepest common folder; null when
         * there is none, or when one leaves the project or is not a relative path.
         */
        fun commonFolder(fileFolder: String, importPaths: List<String>): String? {
            if (importPaths.isEmpty() || importPaths.any { !it.startsWith("./") && !it.startsWith("../") }) return null
            val folders = importPaths.map { join(fileFolder, it.substringBeforeLast('/', ""))?.split('/') ?: return null }
            val common = folders.reduce { acc, segments -> acc.zip(segments).takeWhile { (a, b) -> a == b }.map { it.first } }
            return common.joinToString("/").ifEmpty { null }
        }

        /** [base] then [path], normalized; null for an absolute [path] or one climbing above the project. */
        private fun join(base: String, path: String): String? {
            val normalized = path.replace('\\', '/')
            if (normalized.startsWith("/") || normalized.matches(Regex("^[A-Za-z]:.*"))) return null
            val out = ArrayDeque<String>()
            for (segment in (base.replace('\\', '/') + "/" + normalized).split('/')) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> out.removeLastOrNull() ?: return null
                    else -> out.addLast(segment)
                }
            }
            return out.joinToString("/").ifEmpty { null }
        }
    }
}
