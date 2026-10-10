package com.ibrahimdans.i18n.plugin.translate

import com.intellij.openapi.progress.ProgressIndicator

/**
 * What to translate: [texts] from [source] to [target] locale, and what helps an engine pick the
 * right meaning — the key path and the reference value, say — in [context].
 */
data class TranslationRequest(
    val texts: List<String>,
    val source: String,
    val target: String,
    val context: String = ""
)

/** The outcome for one text: its translation, or a reason readable by the user. */
sealed interface Translation {
    data class Done(val text: String) : Translation
    data class Failed(val reason: String) : Translation
}

/**
 * A machine translation engine. One [Translation] per text of the request, in order: a failure is
 * per text, so a fallback engine is asked only for what the first one could not do.
 *
 * Blocking, network included: never called on the EDT. [indicator] cancels between texts.
 */
interface TranslationProvider {
    fun translate(request: TranslationRequest, indicator: ProgressIndicator? = null): List<Translation>
}

/**
 * [providers] tried in order, each one only on the texts the previous ones failed: a local Ollama
 * first, DeepL when it does not answer. The reason kept for a text failing everywhere is the last one.
 */
class FallbackTranslationProvider(private val providers: List<TranslationProvider>) : TranslationProvider {

    override fun translate(request: TranslationRequest, indicator: ProgressIndicator?): List<Translation> {
        val results = MutableList<Translation>(request.texts.size) { Translation.Failed("") }
        var pending = request.texts.indices.toList()
        for (provider in providers) {
            if (pending.isEmpty()) break
            indicator?.checkCanceled()
            val answers = provider.translate(request.copy(texts = pending.map { request.texts[it] }), indicator)
            pending.zip(answers).forEach { (index, answer) -> results[index] = answer }
            pending = pending.filter { results[it] is Translation.Failed }
        }
        return results
    }
}
