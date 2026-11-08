package com.ibrahimdans.i18n.plugin.translate

import java.time.Duration

/**
 * How to call one engine, as data: a POST to [url] with [headers] and the JSON [body], the
 * translation read at [responsePath] (`choices[0].message.content`).
 *
 * [url], [headers] and [body] take placeholders: `{text}`, `{source}`, `{target}`, `{context}`,
 * `{glossary}` (the [glossary] terms of the target locale, as a sentence of an LLM prompt), `{instruction}`
 * (the [style]'s form of address and instructions, then the user's [TranslationRequest.instruction], sentences of
 * an LLM prompt too — see [instructionPrompt]), `{formality}` (the form of address as DeepL's `formality`, see
 * [Formality]) and `{apiKey}`. They are replaced by JSON-escaped content *without* quotes, so the template writes the
 * quotes itself and a placeholder can sit inside a sentence — an LLM prompt. [unescapeHtml] decodes
 * the entities an engine asked for HTML output returns (`&#39;`, `&amp;`).
 */
data class EngineConfig(
    val id: String,
    val name: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String,
    val responsePath: String,
    val unescapeHtml: Boolean = false,
    /** Runs on the user's machine: nothing leaves it. */
    val local: Boolean = false,
    /** How to send several texts in one request, when the engine can; null sends them one by one. */
    val batch: BatchConfig? = null,
    /** The project's terms: those kept as they are are masked, the others go in `{glossary}`. */
    val glossary: Glossary = Glossary.EMPTY,
    /**
     * The engine's own glossary API the translated terms go to, named by its preset: `deepl`
     * ([DeeplGlossary]). Null for the others, which read them from `{glossary}` or not at all.
     */
    val nativeGlossary: String? = null,
    /** Where the id of that glossary is kept between sessions: the engine's entry in the settings. */
    val remoteGlossary: RemoteGlossaryStore? = null,
    /** How the project wants its translations written: the form of address, its own instructions. */
    val style: Style = Style.EMPTY,
    /**
     * How long to wait for one answer; null waits [HttpTranslationProvider.readTimeoutFor] the request's
     * number of texts, so a batch sent to a slow local model is not cut short.
     */
    val readTimeout: Duration? = null
)
