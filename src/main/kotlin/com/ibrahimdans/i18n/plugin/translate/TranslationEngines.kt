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

    val presets: List<EngineConfig> by lazy { load() }

    fun preset(id: String): EngineConfig? = presets.firstOrNull { it.id == id }

    /** [engines] as one provider, tried in order, each with the key [apiKeyOf] gives it. */
    fun provider(engines: List<EngineConfig>, apiKeyOf: (EngineConfig) -> String, transport: HttpTransport = PlatformHttpTransport): TranslationProvider =
        FallbackTranslationProvider(engines.map { HttpTranslationProvider(it, apiKeyOf(it), transport) })

    /** One entry as written in the file: Gson leaves an absent field null, whatever Kotlin declares. */
    private class Entry(
        val id: String?, val name: String?, val url: String?, val headers: Map<String, String>?,
        val body: String?, val responsePath: String?, val unescapeHtml: Boolean?, val local: Boolean?
    )

    private fun load(): List<EngineConfig> {
        val text = TranslationEngines::class.java.getResourceAsStream(RESOURCE)?.bufferedReader()?.use { it.readText() }
            ?: return emptyList()
        val type = object : TypeToken<List<Entry>>() {}.type
        return Gson().fromJson<List<Entry>>(text, type).map {
            EngineConfig(
                id = it.id.orEmpty(), name = it.name.orEmpty(), url = it.url.orEmpty(), headers = it.headers.orEmpty(),
                body = it.body.orEmpty(), responsePath = it.responsePath.orEmpty(),
                unescapeHtml = it.unescapeHtml == true, local = it.local == true
            )
        }
    }
}
