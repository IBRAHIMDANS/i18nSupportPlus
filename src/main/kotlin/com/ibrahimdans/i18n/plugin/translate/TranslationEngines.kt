package com.ibrahimdans.i18n.plugin.translate

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * The engines offered out of the box, read from `translate/engine-presets.json`: an OpenAI-compatible
 * endpoint (a local Ollama by default), DeepL, LibreTranslate and Google Cloud Translation.
 *
 * They are data, each traced to its API documentation in a `source` field, and only a starting point:
 * the settings let every field be changed, which is how a company gateway or another LLM is reached.
 */
object TranslationEngines {

    private const val RESOURCE = "/translate/engine-presets.json"

    private val loaded: List<Pair<EngineConfig, LanguageCodes?>> by lazy { load() }

    val presets: List<EngineConfig> by lazy { loaded.map { it.first } }

    fun preset(id: String): EngineConfig? = presets.firstOrNull { it.id == id }

    /** [engines] as one provider, tried in order, each with the key [apiKeyOf] gives it. */
    fun provider(engines: List<EngineConfig>, apiKeyOf: (EngineConfig) -> String, transport: HttpTransport = IdeHttpTransport): TranslationProvider =
        FallbackTranslationProvider(engines.map { engine ->
            val http = HttpTranslationProvider(engine, apiKeyOf(engine), transport)
            languageCodes(engine)?.let { LanguageCodeProvider(it, http) } ?: http
        })

    /**
     * How [engine] wants the project's locales written, when it still sends a preset's request: a changed
     * URL (DeepL Pro) keeps the preset's rule, a rewritten body keeps the locales as the project writes them.
     */
    fun languageCodes(engine: EngineConfig): LanguageCodes? =
        loaded.firstOrNull { (preset, _) -> preset.body == engine.body && preset.responsePath == engine.responsePath }?.second

    /** [delegate] asked with the locales of the request rewritten by [codes]. */
    private class LanguageCodeProvider(private val codes: LanguageCodes, private val delegate: TranslationProvider) : TranslationProvider {
        override fun translate(request: TranslationRequest, cancellation: Cancellation?): List<Translation> =
            delegate.translate(request.copy(source = codes.source(request.source), target = codes.target(request.target)), cancellation)
    }

    /** One entry as written in the file: Gson leaves an absent field null, whatever Kotlin declares. */
    private class Entry(
        val id: String?, val name: String?, val url: String?, val headers: Map<String, String>?,
        val body: String?, val responsePath: String?, val unescapeHtml: Boolean?, val local: Boolean?,
        val batch: BatchEntry?, val languageCodes: String?, val nativeGlossary: String?
    )

    private class BatchEntry(val body: String?, val listPath: String?, val itemField: String?, val jsonText: Boolean?, val maxSize: Int?)

    private fun load(): List<Pair<EngineConfig, LanguageCodes?>> {
        val text = TranslationEngines::class.java.getResourceAsStream(RESOURCE)?.bufferedReader()?.use { it.readText() }
            ?: return emptyList()
        val type = object : TypeToken<List<Entry>>() {}.type
        return Gson().fromJson<List<Entry>>(text, type).map {
            EngineConfig(
                id = it.id.orEmpty(), name = it.name.orEmpty(), url = it.url.orEmpty(), headers = it.headers.orEmpty(),
                body = it.body.orEmpty(), responsePath = it.responsePath.orEmpty(),
                unescapeHtml = it.unescapeHtml == true, local = it.local == true, nativeGlossary = it.nativeGlossary?.takeIf(String::isNotBlank),
                batch = it.batch?.takeIf { b -> !b.body.isNullOrBlank() && !b.listPath.isNullOrBlank() && (b.maxSize ?: 0) > 1 }?.let { b ->
                    BatchConfig(b.body!!, b.listPath!!, b.itemField.orEmpty(), b.jsonText == true, b.maxSize!!)
                }
            ) to LanguageCodes.entries.firstOrNull { c -> c.name.equals(it.languageCodes, ignoreCase = true) }
        }
    }
}

/**
 * How an engine wants a locale written. The project's labels (`en`, `pt_BR`, `zh-TW`) go out as they
 * are unless an engine's preset names one of these rules in `languageCodes`.
 */
enum class LanguageCodes {

    /**
     * DeepL: upper case, a source without its region, a target with one of the variants DeepL lists.
     * A bare `EN`, `PT` or `ZH` target gets the region CLDR gives the language (`EN-US`, `PT-BR`,
     * `ZH-HANS`): DeepL calls them unspecified variants, and its own clients refuse `EN` and `PT`.
     * A region DeepL does not know (`fr-BE`) falls back to the language.
     */
    DEEPL {
        override fun source(locale: String): String = parts(locale).first().uppercase()

        override fun target(locale: String): String {
            val parts = parts(locale)
            val language = parts.first().uppercase()
            val variant = parts.drop(1).joinToString("-").uppercase()
            if (language == "ZH") return if (variant in TRADITIONAL_CHINESE) "ZH-HANT" else "ZH-HANS"
            val code = "$language-$variant"
            return when {
                code in DEEPL_TARGET_VARIANTS -> code
                language in DEEPL_DEFAULT_VARIANT -> DEEPL_DEFAULT_VARIANT.getValue(language)
                else -> language
            }
        }
    },

    /** Google Cloud Translation: BCP-47 with a hyphen — `zh-CN`, `pt-BR`, `zh-Hant` — for source and target alike. */
    BCP47 {
        override fun source(locale: String): String = target(locale)

        override fun target(locale: String): String {
            val parts = parts(locale)
            return (listOf(parts.first().lowercase()) + parts.drop(1).map { part ->
                when (part.length) {
                    2 -> part.uppercase()
                    4 -> part.lowercase().replaceFirstChar { it.uppercase() }
                    else -> part
                }
            }).joinToString("-")
        }
    };

    abstract fun source(locale: String): String

    abstract fun target(locale: String): String

    protected fun parts(locale: String): List<String> = locale.trim().split('_', '-').filter { it.isNotEmpty() }.ifEmpty { listOf(locale) }

    private companion object {
        /** https://developers.deepl.com/docs/getting-started/supported-languages — the variants, target only. */
        val DEEPL_TARGET_VARIANTS = setOf(
            "EN-GB", "EN-US", "PT-BR", "PT-PT", "ES-ES", "ES-419", "FR-CA", "FR-FR", "DE-DE", "DE-CH"
        )
        val DEEPL_DEFAULT_VARIANT = mapOf("EN" to "EN-US", "PT" to "PT-BR")
        val TRADITIONAL_CHINESE = setOf("HANT", "TW", "HK", "MO", "HANT-TW", "HANT-HK", "HANT-MO")
    }
}
