package com.ibrahimdans.i18n.extensions.technology.angulari18n

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * What one project of an `angular.json` says about its native i18n:
 *
 * ```json
 * "i18n": {
 *   "sourceLocale": "en-US",
 *   "locales": { "fr": "src/locale/messages.fr.xlf", "de": { "translation": "src/locale/messages.de.xlf" } }
 * },
 * "architect": { "extract-i18n": { "options": { "outputPath": "src/locale" } } }
 * ```
 *
 * [translations] are each locale's files, a string, an array of them, or an object's `translation`;
 * [referenceFiles] the places `ng extract-i18n` writes the source file to, most likely first: its
 * `outputPath` and `outFile` when set, else `messages.xlf` beside the first translation, else at the
 * workspace root. Every path is relative to the folder holding `angular.json`.
 *
 * Pure: no IntelliJ class, so it is tested without a platform fixture.
 */
data class AngularI18nProject(
    val name: String,
    val sourceLocale: String,
    val translations: List<Pair<String, String>>,
    val referenceFiles: List<String>,
)

object AngularI18nConfig {

    /** Angular's own default when `sourceLocale` is not set. */
    const val DEFAULT_SOURCE_LOCALE = "en-US"

    private const val DEFAULT_OUT_FILE = "messages.xlf"

    /** The projects of the `angular.json` [text] that configure i18n; empty when it is no JSON or none does. */
    fun parse(text: String): List<AngularI18nProject> {
        val root = runCatching { JsonParser.parseString(text) }.getOrNull() as? JsonObject ?: return emptyList()
        val projects = root.get("projects") as? JsonObject ?: return emptyList()
        return projects.entrySet().mapNotNull { (name, value) -> (value as? JsonObject)?.let { projectOf(name, it) } }
    }

    private fun projectOf(name: String, project: JsonObject): AngularI18nProject? {
        val i18n = project.get("i18n") as? JsonObject ?: return null
        val sourceLocale = i18n.get("sourceLocale").let { it.stringOrNull() ?: (it as? JsonObject)?.get("code").stringOrNull() }
            ?: DEFAULT_SOURCE_LOCALE
        val locales = i18n.get("locales") as? JsonObject
        val translations = locales?.entrySet().orEmpty().flatMap { (locale, value) -> pathsOf(value).map { locale to normalise(it) } }
        return AngularI18nProject(name, sourceLocale, translations, referenceFiles(project, translations))
    }

    /** A locale's files: a path, an array of paths, or an object whose `translation` is either. */
    private fun pathsOf(value: JsonElement?): List<String> = when {
        value == null -> emptyList()
        value.isJsonObject -> pathsOf(value.asJsonObject.get("translation"))
        value.isJsonArray -> value.asJsonArray.mapNotNull { it.stringOrNull() }
        else -> listOfNotNull(value.stringOrNull())
    }.filter { it.isNotBlank() }

    private fun referenceFiles(project: JsonObject, translations: List<Pair<String, String>>): List<String> {
        val options = ((project.get("architect") as? JsonObject)?.get("extract-i18n") as? JsonObject)?.get("options") as? JsonObject
        val outputPath = options?.get("outputPath").stringOrNull()
        val outFile = options?.get("outFile").stringOrNull() ?: DEFAULT_OUT_FILE
        val besideTranslation = translations.firstOrNull()?.second?.substringBeforeLast('/', "")
        return listOfNotNull(
            outputPath?.let { join(it, outFile) },
            besideTranslation?.let { join(it, outFile) },
            normalise(outFile),
        ).distinct()
    }

    private fun join(folder: String, file: String): String = normalise(if (folder.isBlank()) file else "${folder.trimEnd('/')}/$file")

    private fun normalise(path: String): String = path.removePrefix("./").trimStart('/')

    private fun JsonElement?.stringOrNull(): String? =
        if (this != null && isJsonPrimitive && asJsonPrimitive.isString) asString.takeIf { it.isNotBlank() } else null
}
