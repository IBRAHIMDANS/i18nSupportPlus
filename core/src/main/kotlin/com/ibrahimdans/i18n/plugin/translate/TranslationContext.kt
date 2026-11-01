package com.ibrahimdans.i18n.plugin.translate

import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.toolwindow.KeySpelling
import java.util.Locale

/**
 * What an engine is told besides the texts themselves: their keys, then what the project's own
 * translations say about them (#702). The key alone rarely tells a switch caption from a verb:
 * "Generate narration" came back as "Erzeuge Erzählung" next to an existing "Bilder generieren".
 *
 * Two kinds of examples, both read from the translation files — never from the source code:
 * - the keys under the same parent (`wizard.generation.images` for `wizard.generation.narration`)
 *   already translated in the target locale, which tell the role and the wording of the group;
 * - other pairs of the project sharing words with the texts, a plain translation memory that keeps
 *   a term translated the same way beyond one group.
 *
 * Before the examples, the key sentence may say where the code shows the text ([withRoles]): the
 * names the IDE read around its usages, not the code.
 *
 * Bounded by [BUDGET] characters past the key sentence, whatever the size of the files: the
 * context goes with every request, and an LLM pays for it. With nothing to add the context is the
 * key sentence alone, the request it always was.
 */
object TranslationContext {

    /** At most this many characters of examples after the key sentence. */
    const val BUDGET = 1000

    /** At most this many keys of the same parent. */
    const val MAX_SIBLINGS = 8

    /** At most this many pairs sharing words with the texts. */
    const val MAX_SIMILAR = 6

    /** At most this many places the code shows a text in: the first ones say enough. */
    const val MAX_ROLES = 2

    /** A role written longer than this is not one the markup named. */
    private const val MAX_ROLE = 100

    /** A pair written longer than this is left out: one paragraph would take the whole budget. */
    private const val MAX_PAIR = 200

    /** A shorter word is too common to say two texts are about the same thing. */
    private const val MIN_WORD = 3

    /** Words too common to match on, in English, the language most projects translate from. */
    private val STOP_WORDS = setOf(
        "the", "and", "for", "you", "your", "with", "this", "that", "are", "was", "from", "have", "not",
        "but", "all", "can", "will", "our", "its", "has", "into", "out", "any", "here", "there", "been",
    )

    /** A placeholder (`{{name}}`, `{count}`) or a tag: its name says nothing of the text's meaning. */
    private val MARKUP = Regex("""\{+[^{}]*}+|<[^>]*>""")
    private val WORD_BREAK = Regex("""[^\p{L}\p{N}]+""")

    /** The sentence every request carries: what the texts are, and their [keys] in order. */
    fun header(keys: List<String>): String =
        if (keys.size == 1) "A UI string of an application, under the i18n key ${keys.single()}"
        else "UI strings of an application, under the i18n keys, in order: ${keys.joinToString(", ")}"

    /**
     * [header] followed by where the code shows the text, [roles] as the markup names them
     * (`caption of <SettingsOption>`): what the key leaves open — a button, a heading, a placeholder.
     * Worked out from the code by the IDE, never the code itself; at most [MAX_ROLES], none left as is.
     */
    fun withRoles(header: String, roles: List<String>): String {
        val shown = roles.map(String::trim).filter { it.isNotEmpty() && it.length <= MAX_ROLE }.distinct().take(MAX_ROLES)
        return if (shown.isEmpty()) header else "$header${SENTENCE_BREAK}Shown in the interface as: ${shown.joinToString(SEPARATOR)}"
    }

    /**
     * [header] followed by the examples [known] (`key -> locale -> value`, spelled as [keys] are)
     * gives for translating [texts], under [keys], from [source] to [target]. A pair counts only
     * when both locales hold a value; [keys] themselves are never examples — their target value is
     * the one being replaced. [config] tells how a key is split into levels: a flat key has no parent.
     *
     * Sentences are joined without a final period: the engine's prompt adds its own.
     */
    fun of(
        header: String, keys: List<String>, texts: List<String>, known: Map<String, Map<String, String>>,
        source: String, target: String, config: Config,
    ): String {
        val excluded = keys.toSet()
        val examples = known.entries.sortedBy { it.key }.mapNotNull { (key, values) ->
            if (key in excluded) return@mapNotNull null
            val from = values[source]?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val to = values[target]?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Example(key, from, to)
        }.filter { it.pair.length <= MAX_PAIR }
        if (examples.isEmpty()) return header

        val parents = keys.mapNotNull { parentOf(it, config) }.toSet()
        val siblings = examples.filter { parentOf(it.key, config) in parents }.distinctBy { it.pair }.take(MAX_SIBLINGS)
        val words = texts.flatMap(::wordsOf).toSet()
        val shown = siblings.map { it.pair }.toSet()
        val similar = examples.asSequence()
            .filter { it.pair !in shown }
            .map { it to wordsOf(it.from).count(words::contains) }
            .filter { (_, shared) -> shared > 0 }
            .sortedWith(compareByDescending<Pair<Example, Int>> { it.second }.thenBy { it.first.pair.length })
            .map { it.first.pair }
            .distinct().take(MAX_SIMILAR).toList()

        var left = BUDGET
        val sections = listOf(
            "Already translated under the same parent key: " to siblings.map { "${KeySpelling.segmentsOf(it.key, config).last()}: ${it.pair}" },
            "Other translations of this project sharing its words: " to similar,
        ).mapNotNull { (intro, items) ->
            val kept = mutableListOf<String>()
            var cost = SENTENCE_BREAK.length + intro.length
            for (item in items) {
                val next = cost + item.length + if (kept.isEmpty()) 0 else SEPARATOR.length
                if (next > left) continue
                kept += item
                cost = next
            }
            if (kept.isEmpty()) return@mapNotNull null
            left -= cost
            intro + kept.joinToString(SEPARATOR)
        }
        return (listOf(header) + sections).joinToString(SENTENCE_BREAK)
    }

    private const val SENTENCE_BREAK = ". "
    private const val SEPARATOR = "; "

    /** A translation the project already holds: [from] in the source locale, [to] in the target one. */
    private class Example(val key: String, val from: String, to: String) {
        val pair = "\"$from\" → \"$to\""
    }

    /** [key]'s namespace and levels but the last, or null for a key on one level (or flat). */
    private fun parentOf(key: String, config: Config): Pair<String?, List<String>>? {
        val segments = KeySpelling.segmentsOf(key, config)
        return if (segments.size < 2) null else KeySpelling.namespaceOf(key) to segments.dropLast(1)
    }

    /** The meaningful words of [text], in lower case: markup, short and common words left out. */
    internal fun wordsOf(text: String): Set<String> =
        MARKUP.replace(text, " ").lowercase(Locale.ROOT).split(WORD_BREAK)
            .filter { it.length >= MIN_WORD && it !in STOP_WORDS }.toSet()
}
