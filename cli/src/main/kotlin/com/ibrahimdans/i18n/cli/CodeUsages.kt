package com.ibrahimdans.i18n.cli

import com.ibrahimdans.i18n.NioWorkspace
import com.ibrahimdans.i18n.ParsedFormat
import com.ibrahimdans.i18n.SourceDiscovery
import com.ibrahimdans.i18n.extract.ConstantKeys
import com.ibrahimdans.i18n.extract.CodeExtractors
import com.ibrahimdans.i18n.extensions.technology.angulari18n.AngularI18nConfig
import com.ibrahimdans.i18n.extract.angular.AngularMessageIds
import com.ibrahimdans.i18n.extract.angular.AngularTemplate
import com.ibrahimdans.i18n.extract.laravel.LaravelSource
import com.ibrahimdans.i18n.extract.laravel.laravelExtractors
import com.ibrahimdans.i18n.extract.nextintl.NextIntlKeyExtractor
import com.ibrahimdans.i18n.extract.svelte.SvelteSfc
import com.ibrahimdans.i18n.extract.vuei18n.VueSfc
import com.ibrahimdans.i18n.index.TreeSitterKeyUsageIndex
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationLeaves
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

/** A call to [key], which the reference locale does not hold, at [line] (1-based) of [file], project-relative. */
data class UnknownKey(val key: String, val file: String, val line: Int)

/**
 * Where the code of a project uses its keys: every JavaScript, TypeScript, Vue and Svelte file read into a
 * [TreeSitterKeyUsageIndex], the folders [NioWorkspace] prunes and those the settings exclude left out.
 * Keys are spelled as [ProjectSources] spells them.
 *
 * Each file is read with the extractors of its module's preset ([CodeExtractors]): i18next's, next-intl's,
 * vue-i18n's, Transloco's, ngx-translate's, react-intl's, Lingui's and svelte-i18n's calls, all of them outside a module with a
 * preset. A `.vue` file is read through [VueSfc], a `.svelte` one through [SvelteSfc]: their scripts and their markup's
 * expressions. In an Angular project — a `package.json` requiring `@angular/core` — the `.html` templates are read too,
 * through [AngularTemplate]: elsewhere a `.html` is no template of the plugin's. In a Laravel project — a `composer.json`
 * requiring `laravel/framework` — the `.php` files and Blade views are read through [LaravelSource], with Laravel's calls
 * alone: elsewhere `__('Hello', 'domain')` is WordPress, a text and not a key. A package's key, `__('courier::messages.sent')`,
 * is read under its package's namespace when the project overrides that file (`lang/vendor/courier/en/messages.php`), as
 * the plugin reads it; a package without an override keeps its keys unread and unreported.
 *
 * With Angular's own i18n — `angular.json` naming a source file `ng extract-i18n` has written — the messages the code
 * marks are read through [AngularMessageIds]: the custom id of a template's `i18n="…@@id"` / `i18n-<attr>="…@@id"`, of a
 * component's inline `template:`, and of a script's `` $localize`:…@@id:…` ``. While a message without a custom id
 * remains, the ids `ng extract-i18n` computes ([AngularMessageIds.isComputedId]) are never reported unused: the code
 * does not write them.
 *
 * A key passed through a constant, `t(KEYS.save)`, is read as the IDE reads it ([ConstantKeys]): the constant
 * declared in the file, or imported from another script of the project by a relative path or by a path alias of
 * the `tsconfig.json`, else `jsconfig.json`, nearest to the importing file, `extends` followed (`@/keys`, [pathAliasesOf]).
 */
class CodeUsages(project: Path, private val config: Config) {

    private val modules: List<ModuleConfig>

    /** The text of each file indexed, by project-relative path: where a call's line is read. */
    private val texts: Map<String, String>

    /** The code of each script, component and template, by project-relative path. */
    private val code: Map<String, String>

    /** The PHP files and Blade views of a Laravel project, by project-relative path. */
    private val php: Map<String, String>

    /** The packages' files the project overrides, `courier::messages`. */
    private val packageScopes: Set<String>

    private val imports: ConstantKeys.ProjectFiles

    /** The indexes of the whole project's code. */
    private val indexes: List<TreeSitterKeyUsageIndex>

    /** The indexes of the code that uses each module's keys, built when first asked for. */
    private val moduleIndexes = mutableMapOf<ModuleConfig, List<TreeSitterKeyUsageIndex>>()

    /** The keys of each component's `<i18n>` blocks, by project-relative path: known to its own calls. */
    private val blockKeys: Map<String, Set<String>>

    /** The messages Angular's own i18n marks, by project-relative path: none without an extracted catalogue. */
    private val angularMessages: Map<String, List<AngularMessageIds.Message>>

    init {
        val root = project.toAbsolutePath().normalize()
        val laravel = ProjectPackages.composerRequiresAny(root, ProjectPackages.LARAVEL)
        val localize = hasAngularCatalogue(root)
        val angular = localize || ProjectPackages.requiresAny(root, listOf(ProjectPackages.ANGULAR))
        val extensions = EXTENSIONS + listOfNotNull(HTML.takeIf { angular }, PHP.takeIf { laravel })
        texts = NioWorkspace(root, SourceDiscovery.DEFAULT_EXCLUDED_DIRECTORIES + config.excludedDirectorySet())
            .files(extensions)
            .associate { root.relativize(it.file).joinToString("/") to Files.readString(it.file) }
        modules = ModuleSources.active(config.modules)
        val (phpFiles, scripts) = texts.entries.partition { (path, _) -> path.endsWith(".$PHP") }
        php = phpFiles.associate { it.toPair() }
        packageScopes = php.keys.mapNotNullTo(mutableSetOf(), ::packageScopeOf)
        code = scripts.associate { (path, text) -> path to codeOf(path, text) }
        imports = ConstantKeys.ProjectFiles(code, pathAliasesByImporter(root))
        indexes = indexesOf { true }
        blockKeys = texts.filterKeys { it.endsWith(VUE) }.mapValues { (_, text) -> blockKeysOf(text, config) }.filterValues { it.isNotEmpty() }
        angularMessages = if (!localize) emptyMap() else texts.mapNotNull { (path, text) ->
            val messages = when {
                path.endsWith(".$HTML") -> AngularMessageIds.ofTemplate(text)
                SCRIPTS.any { path.endsWith(".$it") } -> AngularMessageIds.ofScript(text)
                else -> emptyList()
            }
            messages.takeIf { it.isNotEmpty() }?.let { path to it }
        }.toMap()
    }

    /**
     * The [keys] no call uses, nor any key built at runtime may produce, sorted. A [module]'s keys are only used by the
     * code it holds ([ModuleSources.holdsCode]): its folder, or its `codeRoots`; without a module, by the whole project.
     */
    fun unused(keys: Collection<String>, module: ModuleConfig? = null): List<String> {
        val indexes = indexesUsing(module)
        val marked = angularMessages.filterKeys { path -> module == null || ModuleSources.holdsCode(module, ModuleSources.FilePath(path, anchored = true)) }
            .values.flatten()
        val markedIds = marked.mapNotNullTo(mutableSetOf()) { it.id }
        val computedIds = marked.any { it.id == null }
        val unseen = keys.filter { key ->
            key !in markedIds && !(computedIds && AngularMessageIds.isComputedId(key)) && indexes.all { it.usages(key).isEmpty() }
        }
        val reached = indexes.flatMapTo(mutableSetOf()) { it.dynamicallyReached(unseen) }
        return (unseen - reached).sorted()
    }

    /**
     * The calls naming a key that none of their file's modules holds, by file and line: [keys] gives each module — null
     * for the whole project — its keys, and a file whose code no module holds is checked against all of them. A
     * component's own `<i18n>` block holds keys for its calls, and next-intl's `t.has(key)` only asks whether one exists.
     */
    fun unknown(keys: List<Pair<ModuleConfig?, Collection<String>>>): List<UnknownKey> {
        val everyKey by lazy { keys.flatMapTo(mutableSetOf()) { it.second } }
        fun keysOf(path: String): Set<String> {
            val file = ModuleSources.FilePath(path, anchored = true)
            val holders = keys.filter { (module, _) -> module != null && ModuleSources.holdsCode(module, file) }
            return if (holders.isEmpty()) everyKey else holders.flatMapTo(mutableSetOf()) { it.second }
        }
        val unmarked = angularMessages.flatMap { (path, messages) ->
            val known = keysOf(path)
            messages.mapNotNull { message -> message.id?.takeIf { it !in known }?.let { UnknownKey(it, path, lineOf(path, message.offset)) } }
        }
        return texts.keys.groupBy(::keysOf)
            .flatMap { (known, paths) ->
                val files = paths.toSet()
                indexes.flatMap { it.unknownUsages(known) }.filter { it.usage.path in files }
            }
            .filterNot { call -> call.key in blockKeys[call.usage.path].orEmpty() || isExistenceCheck(call.usage.path, call.usage.span.start) }
            .map { call ->
                UnknownKey(writtenKey(call.key, call.usage.path, call.usage.span.start, call.usage.span.end), call.usage.path, lineOf(call.usage.path, call.usage.span.start))
            }
            .plus(unmarked)
            .sortedWith(compareBy({ it.file }, { it.line }))
    }

    /** The indexes of the code that uses [module]'s keys: the whole project's without a module. */
    private fun indexesUsing(module: ModuleConfig?): List<TreeSitterKeyUsageIndex> =
        if (module == null) indexes
        else moduleIndexes.getOrPut(module) { indexesOf { path -> ModuleSources.holdsCode(module, ModuleSources.FilePath(path, anchored = true)) } }

    /**
     * The indexes of the files [holds] keeps: the Laravel ones read with Laravel's calls, the others one index per
     * preset, read with the extractors of their module's preset.
     */
    private fun indexesOf(holds: (String) -> Boolean): List<TreeSitterKeyUsageIndex> {
        val laravelIndex = php.filterKeys(holds).takeIf { it.isNotEmpty() }?.let { files ->
            TreeSitterKeyUsageIndex(config, extractors = laravelExtractors(config.nsSeparator, config.keySeparator), keyScopes = packageScopes).apply {
                files.forEach { (path, text) -> update(path, LaravelSource.codeOf(text, packageScopes)) }
            }
        }
        return listOfNotNull(laravelIndex) + code.filterKeys(holds).entries
            .groupBy { (path, _) -> ModuleSources.owner(modules, ModuleSources.FilePath(path, anchored = true))?.preset?.trim()?.ifEmpty { null } }
            .map { (preset, files) ->
                TreeSitterKeyUsageIndex(config, extractors = CodeExtractors.forPreset(preset), imports = imports).apply {
                    files.forEach { (path, text) -> update(path, text) }
                }
            }
    }

    /** The code of the file at [path]: a component or a template read as the code it holds. */
    private fun codeOf(path: String, text: String): String = when {
        path.endsWith(VUE) -> VueSfc.codeOf(text)
        path.endsWith(SVELTE) -> SvelteSfc.codeOf(text)
        path.endsWith(".$HTML") -> AngularTemplate.codeOf(text)
        else -> text
    }

    /** [key] as [path] writes it between [start] and [end]: a Laravel key is read under its file's namespace, `messages:welcome`, and reported as written. */
    private fun writtenKey(key: String, path: String, start: Int, end: Int): String =
        if (path.endsWith(".$PHP")) texts.getValue(path).substring(start, end).unQuote() else key

    /** The 1-based line of [offset] in the file at [path]. */
    private fun lineOf(path: String, offset: Int): Int = texts.getValue(path).substring(0, offset).count { it == '\n' } + 1

    /** True when the literal at [offset] of [path] is the key of a `.has(` call. */
    private fun isExistenceCheck(path: String, offset: Int): Boolean =
        texts.getValue(path).substring(0, offset).trimEnd().endsWith(".${NextIntlKeyExtractor.HAS}(")

    private companion object {
        const val VUE = ".vue"
        const val SVELTE = ".svelte"
        val EXTENSIONS = listOf("js", "jsx", "ts", "tsx", "mjs", "cjs", "vue", "svelte")
        const val HTML = "html"
        const val PHP = "php"

        /** The scripts a `$localize` template is read in. */
        val SCRIPTS = listOf("ts", "js", "mjs", "cjs")

        /** True when `angular.json` names a source file `ng extract-i18n` has written: Angular's own i18n is in use. */
        fun hasAngularCatalogue(root: Path): Boolean {
            val config = root.resolve("angular.json").takeIf { Files.isRegularFile(it) } ?: return false
            return AngularI18nConfig.parse(Files.readString(config))
                .any { project -> project.referenceFiles.any { Files.isRegularFile(root.resolve(it)) } }
        }

        /** A Laravel package's override, `lang/vendor/<package>/<locale>/<file>.php`. */
        val PACKAGE_OVERRIDE = Regex("""(?:^|/)lang/vendor/([^/]+)/[^/]+/([^/]+)\.php$""")

        /** The scope the override at [path] declares, `courier::messages` for `lang/vendor/courier/en/messages.php`; null for any other file. */
        fun packageScopeOf(path: String): String? =
            PACKAGE_OVERRIDE.find(path)?.let { "${it.groupValues[1]}::${it.groupValues[2]}" }

        /** Where TypeScript, then JavaScript, projects declare their path aliases. */
        val COMPILER_CONFIGS = listOf("tsconfig.json", "jsconfig.json")

        /**
         * The path aliases of the files in [folder], an absolute folder of the project at [root]: those of the
         * `tsconfig.json`, else `jsconfig.json`, nearest to it, from [folder] up to [root] — in a monorepo, a package's own.
         */
        fun pathAliasesOf(root: Path, folder: Path = root): ConstantKeys.PathAliases {
            val config = generateSequence(folder) { it.parent?.takeIf { parent -> parent.startsWith(root) } }
                .firstNotNullOfOrNull { dir -> COMPILER_CONFIGS.map(dir::resolve).firstOrNull(Files::isRegularFile) }
                ?: return ConstantKeys.PathAliases.NONE
            return pathAliasesOfConfig(root, config)
        }

        /**
         * The path aliases [config] declares, as TypeScript reads them: `compilerOptions.paths` and `baseUrl` from the
         * file itself, else from the files it `extends` (a relative path, or a list of them, the last one winning), each
         * read from the folder of the file that declares it; `paths` without `baseUrl` resolve from their own file's
         * folder. Comments and trailing commas are allowed, as TypeScript allows them. An `extends` naming a package
         * (`@tsconfig/node20`) is not read.
         */
        private fun pathAliasesOfConfig(root: Path, config: Path): ConstantKeys.PathAliases {
            var paths: Pair<Path, JsonObject>? = null
            var baseUrl: Path? = null
            for ((file, options) in compilerOptionsChain(config, mutableSetOf())) {
                if (paths == null) options["paths"]?.takeIf { it.isJsonObject }?.let { paths = file.parent to it.asJsonObject }
                if (baseUrl == null) options["baseUrl"]?.takeIf { it.isJsonPrimitive }?.let { baseUrl = file.parent.resolve(it.asString).normalize() }
            }
            val (declaringFolder, declared) = paths ?: return ConstantKeys.PathAliases.NONE
            val base = baseUrl ?: declaringFolder
            if (!base.startsWith(root)) return ConstantKeys.PathAliases.NONE
            val targets = declared.entrySet().associate { (pattern, value) ->
                pattern to (value.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty())
            }
            return ConstantKeys.PathAliases(root.relativize(base).joinToString("/").ifEmpty { "." }, targets)
        }

        /** The `compilerOptions` of [config], then those of the files it extends, nearest first; a file already [seen] is read once. */
        private fun compilerOptionsChain(config: Path, seen: MutableSet<Path>): List<Pair<Path, JsonObject>> {
            val file = config.toAbsolutePath().normalize()
            if (!seen.add(file)) return emptyList()
            val json = runCatching { JsonParser.parseString(jsonOf(Files.readString(file))).asJsonObject }.getOrNull() ?: return emptyList()
            val options = json["compilerOptions"]?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
            val extended = json["extends"]?.let { value ->
                if (value.isJsonArray) value.asJsonArray.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
                else listOfNotNull(value.takeIf { it.isJsonPrimitive }?.asString)
            }.orEmpty()
            return listOf(file to options) +
                extended.asReversed().mapNotNull { extendedFile(file.parent, it) }.flatMap { compilerOptionsChain(it, seen) }
        }

        /** The file [spec] names from [folder], `.json` added when left out; null for a package (`@tsconfig/node20`). */
        private fun extendedFile(folder: Path, spec: String): Path? {
            if (!spec.startsWith("./") && !spec.startsWith("../")) return null
            return listOf(spec, "$spec.json").map(folder::resolve).firstOrNull(Files::isRegularFile)
        }

        /** The path aliases of each importing file, read once per folder ([pathAliasesOf]). */
        private fun pathAliasesByImporter(root: Path): (String) -> ConstantKeys.PathAliases {
            val byFolder = mutableMapOf<String, ConstantKeys.PathAliases>()
            return { importer ->
                val folder = importer.substringBeforeLast('/', "")
                byFolder.getOrPut(folder) { pathAliasesOf(root, root.resolve(folder).normalize()) }
            }
        }

        /** [jsonc] as JSON: its line and block comments and its trailing commas left out, strings kept as written. */
        fun jsonOf(jsonc: String): String {
            val out = StringBuilder(jsonc.length)
            var i = 0
            while (i < jsonc.length) {
                val c = jsonc[i]
                when {
                    c == '"' -> {
                        var end = i + 1
                        while (end < jsonc.length && jsonc[end] != '"') end += if (jsonc[end] == '\\') 2 else 1
                        end = minOf(end + 1, jsonc.length)
                        out.append(jsonc, i, end)
                        i = end
                    }
                    jsonc.startsWith("//", i) -> i = jsonc.indexOf('\n', i).let { if (it < 0) jsonc.length else it }
                    jsonc.startsWith("/*", i) -> i = jsonc.indexOf("*/", i + 2).let { if (it < 0) jsonc.length else it + 2 }
                    else -> {
                        out.append(c)
                        i++
                    }
                }
            }
            return TRAILING_COMMA.replace(out) { match -> match.groups[1]?.value ?: match.value }
        }

        /** A string, kept as it is, or a comma closing nothing, `, }` or `, ]`, which loses its comma: a string's own `,}` stays. */
        val TRAILING_COMMA = Regex(""""(?:[^"\\]|\\.)*"|,(\s*[}\]])""")

        val I18N_BLOCK = Regex("""<i18n\b([^>]*)>(.*?)</i18n>""", RegexOption.DOT_MATCHES_ALL)
        val YAML_LANG = Regex("""\blang\s*=\s*["']ya?ml["']""")

        /** The keys of [text]'s `<i18n>` blocks, JSON or YAML, under any of their locales: `{ "en": { "a": … } }` gives `a`. */
        fun blockKeysOf(text: String, config: Config): Set<String> = I18N_BLOCK.findAll(text).flatMapTo(mutableSetOf()) { block ->
            val format = if (YAML_LANG.containsMatchIn(block.groupValues[1])) ParsedFormat.YAML else ParsedFormat.JSON
            val tree = format.parse(block.groupValues[2]) ?: return@flatMapTo emptyList()
            val leaves = mutableMapOf<String, MutableMap<String, String>>()
            TranslationLeaves.collect(config, tree, "", "", ParsedLeaves, leaves)
            // The first level names the locale.
            leaves.keys.mapNotNull { key -> key.substringAfter(config.keySeparator, "").ifEmpty { null } }
        }
    }
}
