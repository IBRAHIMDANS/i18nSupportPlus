package com.ibrahimdans.i18n.plugin.ide.settings

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * `i18n-check.json`, the project settings meant for version control: read by the IDE and by the
 * command line check, written by the IDE's *Export to i18n-check.json*.
 *
 * It takes the names of the IDE's settings — `{ "keySeparator": "/", "modules": [{ "name": "web",
 * "rootDirectory": "apps/web" }] }` — and only the ones a team shares: where the translations are,
 * how keys are written and ordered, which locales are checked. What belongs to one machine (folding, the gutter,
 * the preview locale shown in the status bar) stays in `.idea/i18nSettings.xml`. A name it does not
 * know is refused rather than ignored: it is most likely a typo, and a setting silently left at its
 * default is worse than an error.
 */
object ProjectConfigFile {

    /** Where the file sits, relative to the project directory. */
    const val PATH = "i18n-check.json"

    /** The name of the module list in the file. */
    const val MODULES = "modules"

    /**
     * Read and accepted, but personal: the IDE keeps its own preview locale, which the status bar
     * switches, and never exports it. The command line check accepts it for files written before.
     */
    const val PREVIEW_LOCALE = "previewLocale"

    private class Field(
        val get: (Config) -> String,
        val set: (Config, String) -> Config,
        val flag: Boolean = false,
        /** The values the setting takes, null when any string does: another one is refused by [parse]. */
        val choices: Set<String>? = null,
    )

    /**
     * The [SettingScope.TEAM] settings of [Config.SCOPES], `modules` aside, plus [PREVIEW_LOCALE]:
     * `ProjectConfigFileTest` fails when the two lists part.
     */
    private val FIELDS: Map<String, Field> = linkedMapOf(
        "translationsRoot" to Field({ it.translationsRoot }, { c, v -> c.copy(translationsRoot = v) }),
        "excludedDirectories" to Field({ it.excludedDirectories }, { c, v -> c.copy(excludedDirectories = v) }),
        "excludedFileExtensions" to Field({ it.excludedFileExtensions }, { c, v -> c.copy(excludedFileExtensions = v) }),
        "jsConfiguration" to Field({ it.jsConfiguration }, { c, v -> c.copy(jsConfiguration = v) }),
        "defaultNs" to Field({ it.defaultNs }, { c, v -> c.copy(defaultNs = v) }),
        "fallbackNs" to Field({ it.fallbackNs }, { c, v -> c.copy(fallbackNs = v) }),
        "nsSeparator" to Field({ it.nsSeparator }, { c, v -> c.copy(nsSeparator = v) }),
        "keySeparator" to Field({ it.keySeparator }, { c, v -> c.copy(keySeparator = v) }),
        "pluralSeparator" to Field({ it.pluralSeparator }, { c, v -> c.copy(pluralSeparator = v) }),
        "firstComponentNs" to Field({ it.firstComponentNs.toString() }, { c, v -> c.copy(firstComponentNs = v.toBoolean()) }, flag = true),
        "flatKeys" to Field({ it.flatKeys.toString() }, { c, v -> c.copy(flatKeys = v.toBoolean()) }, flag = true),
        "gettext" to Field({ it.gettext.toString() }, { c, v -> c.copy(gettext = v.toBoolean()) }, flag = true),
        "gettextAliases" to Field({ it.gettextAliases }, { c, v -> c.copy(gettextAliases = v) }),
        "foldingPreferredLanguage" to Field({ it.foldingPreferredLanguage }, { c, v -> c.copy(foldingPreferredLanguage = v) }),
        "ignoredLocales" to Field({ it.ignoredLocales }, { c, v -> c.copy(ignoredLocales = v) }),
        "regionalFallback" to Field({ it.regionalFallback.toString() }, { c, v -> c.copy(regionalFallback = v.toBoolean()) }, flag = true),
        "localeAliases" to Field({ it.localeAliases }, { c, v -> c.copy(localeAliases = v) }),
        "keptKeys" to Field({ it.keptKeys }, { c, v -> c.copy(keptKeys = v) }),
        // How the shared catalogues are ordered: two values here, and every extraction reorders them.
        "extractSorted" to Field({ it.extractSorted.toString() }, { c, v -> c.copy(extractSorted = v.toBoolean()) }, flag = true),
        "sortKeysAlphabetically" to Field({ it.sortKeysAlphabetically.toString() }, { c, v -> c.copy(sortKeysAlphabetically = v.toBoolean()) }, flag = true),
        "keyOrder" to Field(
            { it.keyOrder.name },
            { c, v -> c.copy(keyOrder = KeyOrder.valueOf(v)) },
            choices = KeyOrder.entries.map { it.name }.toSet(),
        ),
        PREVIEW_LOCALE to Field({ it.previewLocale }, { c, v -> c.copy(previewLocale = v) }),
    )

    private class ModuleField(
        val get: (ModuleConfig) -> String,
        val set: ModuleConfig.(String) -> Unit,
        val flag: Boolean = false,
    )

    private val MODULE_FIELDS: Map<String, ModuleField> = linkedMapOf(
        "name" to ModuleField({ it.name }, { name = it }),
        "rootDirectory" to ModuleField({ it.rootDirectory }, { rootDirectory = it }),
        "pathTemplate" to ModuleField({ it.pathTemplate }, { pathTemplate = it }),
        "fileTemplate" to ModuleField({ it.fileTemplate }, { fileTemplate = it }),
        "keyTemplate" to ModuleField({ it.keyTemplate }, { keyTemplate = it }),
        "preset" to ModuleField({ it.preset }, { preset = it }),
        "referenceLocale" to ModuleField({ it.referenceLocale }, { referenceLocale = it }),
        "callTemplate" to ModuleField({ it.callTemplate }, { callTemplate = it }),
        "writeDefaultValue" to ModuleField({ it.writeDefaultValue.toString() }, { writeDefaultValue = it.toBoolean() }, flag = true),
        "enabled" to ModuleField({ it.enabled.toString() }, { enabled = it.toBoolean() }, flag = true),
        CODE_ROOTS to ModuleField({ it.codeRoots }, { codeRoots = it }),
    )

    /** A module's code folders: a comma-separated string, or an array of folders. */
    private const val CODE_ROOTS = "codeRoots"

    /** The setting names the file takes, [MODULES] aside. */
    val names: Set<String> get() = FIELDS.keys

    /**
     * What a file sets: the [options] it names, as strings, and its [modules], null when it names
     * none — an empty list is a file that says the project has no module.
     */
    class Content(val options: Map<String, String>, val modules: List<ModuleConfig>?) {

        /** Every name the file sets, [MODULES] included when it lists modules. */
        val names: Set<String> get() = if (modules == null) options.keys else options.keys + MODULES

        /**
         * [base] with what the file sets, [ignored] names aside.
         *
         * A module of the file is *active* only when its counterpart of the same name in [base] is
         * active too: putting an application aside (*Focus on This Module*) is one developer's
         * choice, kept with their own settings, while a module the file sets aside stays aside.
         */
        fun applyTo(base: Config, ignored: Set<String> = emptySet()): Config {
            val withOptions = options.entries.fold(base) { config, (name, value) ->
                if (name in ignored) config else FIELDS.getValue(name).set(config, value)
            }
            val listed = modules ?: return withOptions
            if (MODULES in ignored) return withOptions
            val activeHere = base.modules.associate { it.name to it.enabled }
            return withOptions.copy(modules = listed.map { module ->
                module.copy(enabled = module.enabled && activeHere[module.name] != false)
            })
        }

        override fun equals(other: Any?): Boolean =
            other is Content && other.options == options && other.modules == modules

        override fun hashCode(): Int = 31 * options.hashCode() + (modules?.hashCode() ?: 0)
    }

    /**
     * Reads [text], the content of the file.
     *
     * @throws IllegalArgumentException text that is no JSON object, a name the file does not take,
     *   a value that is neither a string, a boolean nor a number
     */
    fun parse(text: String): Content {
        val root = try {
            JsonParser.parseString(text)
        } catch (e: Exception) {
            throw IllegalArgumentException(e.message ?: "not JSON", e)
        } as? JsonObject ?: throw IllegalArgumentException("expected a JSON object")
        val modules = root.remove(MODULES)?.let { list ->
            if (!list.isJsonArray) throw IllegalArgumentException("'$MODULES' must be an array")
            list.asJsonArray.map { module ->
                val bean = module as? JsonObject ?: throw IllegalArgumentException("each module must be an object")
                bean.get(CODE_ROOTS)?.takeIf { it.isJsonArray }?.let { roots ->
                    bean.addProperty(CODE_ROOTS, roots.asJsonArray.joinToString(", ") {
                        (it as? JsonPrimitive)?.asString ?: throw IllegalArgumentException("'$CODE_ROOTS' must list folders")
                    })
                }
                moduleOf(stringsOf(bean, MODULE_FIELDS.keys))
            }
        }
        val options = stringsOf(root, FIELDS.keys)
        options.forEach { (name, value) ->
            val choices = FIELDS.getValue(name).choices ?: return@forEach
            if (value !in choices) throw IllegalArgumentException("'$name' must be one of ${choices.sorted()}, not '$value'")
        }
        return Content(options, modules)
    }

    /**
     * What [options] set — the settings of another store, the IDE's XML read without the IDE —
     * the names the file does not take left out rather than refused.
     */
    fun contentOf(options: Map<String, String>, modules: List<ModuleConfig>?): Content =
        Content(options.filterKeys { it in FIELDS }, modules)

    /** The module [options] describe, each field missing at its default. */
    fun moduleOf(options: Map<String, String>): ModuleConfig =
        ModuleConfig().apply {
            options.forEach { (name, value) -> MODULE_FIELDS[name]?.set?.invoke(this, value) }
        }

    /** The value [config] holds for the setting [name], as written in the file. */
    fun valueOf(config: Config, name: String): String? = FIELDS[name]?.get?.invoke(config)

    /**
     * [edited] with the settings [names] put back as [stored] holds them: the IDE never writes the
     * values the file sets into its own settings, so that removing the file brings back what was
     * there before. [MODULES] is not one of them: a module's *Active* box is kept per developer.
     */
    fun restore(edited: Config, stored: Config, names: Set<String>): Config =
        names.fold(edited) { config, name ->
            FIELDS[name]?.let { it.set(config, it.get(stored)) } ?: config
        }

    /**
     * The file for [config]: the settings that differ from their default, [PREVIEW_LOCALE] aside,
     * and every module with the fields it sets — its *Active* box aside, one developer's choice.
     */
    fun export(config: Config): String {
        val default = Config()
        val root = JsonObject()
        FIELDS.forEach { (name, field) ->
            if (name == PREVIEW_LOCALE) return@forEach
            val value = field.get(config)
            if (value != field.get(default)) root.add(name, primitive(value, field.flag))
        }
        if (config.modules.isNotEmpty()) {
            val defaultModule = ModuleConfig()
            root.add(MODULES, JsonArray().apply {
                config.modules.forEach { module ->
                    add(JsonObject().apply {
                        MODULE_FIELDS.forEach { (name, field) ->
                            if (name == "enabled") return@forEach
                            val value = field.get(module)
                            if (name == "name" || value != field.get(defaultModule)) add(name, primitive(value, field.flag))
                        }
                    })
                }
            })
        }
        return GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n"
    }

    private fun primitive(value: String, flag: Boolean): JsonPrimitive =
        if (flag) JsonPrimitive(value.toBoolean()) else JsonPrimitive(value)

    /** The `name` -> value of [json], each a string, a boolean or a number; an unknown name is likely a typo. */
    private fun stringsOf(json: JsonObject, known: Set<String>): Map<String, String> =
        json.entrySet().associate { (name, value) ->
            if (name !in known) throw IllegalArgumentException("unknown setting '$name', expected one of ${known.sorted()}")
            val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException("'$name' must be a string or a boolean")
            name to primitive.asString
        }
}
