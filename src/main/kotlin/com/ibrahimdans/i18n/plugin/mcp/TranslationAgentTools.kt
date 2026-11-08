package com.ibrahimdans.i18n.plugin.mcp

import com.ibrahimdans.i18n.plugin.ide.actions.MachineFill
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TableViewModel
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.review.Review
import com.ibrahimdans.i18n.plugin.review.ReviewLocation
import com.ibrahimdans.i18n.plugin.review.ReviewStore
import com.ibrahimdans.i18n.plugin.translate.GlossaryTerm
import com.ibrahimdans.i18n.plugin.translate.MachineTranslationSettings
import com.ibrahimdans.i18n.plugin.translate.PlaceholderMask
import com.ibrahimdans.i18n.plugin.translate.PluralTranslationPlan
import com.ibrahimdans.i18n.plugin.utils.IcuPluralRewriter
import com.ibrahimdans.i18n.plugin.utils.ReferenceLocale
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * What an AI agent may do with a project's translations, apart from the protocol it is asked through:
 * list the modules, read what a locale lacks or what went stale in it, and write it.
 *
 * Writing goes through *Fill Missing Translations*' and *Retranslate Stale Translations*' own steps,
 * so an agent gets the same guards as the engines: a value is written only where it is missing, or
 * replaces one that is stale when written — still the value reviewed while its source changed since —,
 * never any other one; a value must keep the placeholders of its source, an ICU plural the branches
 * its locale needs — its source is given with them, see [MachineFill.itemOf] —, and the whole call is
 * one undoable command, marked to review when the module keeps reviews.
 *
 * The texts returned are read by the agent, not shown in the IDE: they stay in English.
 */
class TranslationAgentTools(private val project: Project) {

    /** A refused call, its [message] meant for the agent. */
    class Refused(message: String) : Exception(message)

    /** [notChecked]: the [locales] the project does not check for missing keys, in progress or abandoned. */
    data class ModuleInfo(
        val name: String,
        val referenceLocale: String,
        val locales: List<String>,
        val reviews: Boolean,
        val notChecked: List<String>,
    )

    data class Missing(val key: String, val source: String, val pluralFromOther: Boolean, val skipped: String?)

    /**
     * [glossary]: the project's glossary for [locale], `term -> translation`, a term kept as it is mapped to itself.
     * [examples]: translations [locale] already has, `reference text -> translation`, whose reference text shares a
     * word with one of [items] — the terminology in place. Both left out of the answer when there is none.
     */
    data class MissingPage(
        val module: String,
        val sourceLocale: String,
        val locale: String,
        val total: Int,
        val items: List<Missing>,
        val glossary: Map<String, String>? = null,
        val examples: Map<String, String>? = null,
    )

    data class Stale(val key: String, val source: String, val current: String, val skipped: String?)

    data class StalePage(val module: String, val sourceLocale: String, val locale: String, val total: Int, val items: List<Stale>)

    data class WriteResult(val written: List<String>, val rejected: Map<String, String>)

    /** Where a module keeps its reviews; a test points it at a fixture folder. */
    internal var reviewRootOf: (ModuleConfig?) -> VirtualFile? = { ReviewLocation.rootOf(project, it) }

    /** Runs between what a write read and its command; a test edits a value there, as a user could. */
    internal var beforeWrite: () -> Unit = {}

    /** Every translation module, the project as one when none is configured. */
    fun modules(): List<ModuleInfo> = scopes().map { (name, module) ->
        val locales = TranslationDataLoader.discoverLocales(project, module)
        val config = Settings.getInstance(project).config()
        ModuleInfo(name, sourceOf(module, locales).orEmpty(), locales, reviewsOn(module) != null, locales.filter(config::ignoresLocale))
    }

    /**
     * The first [limit] values [locale] lacks in [moduleName], with the source text to translate, the
     * project's glossary for [locale] and the translations already there that share a word with the page.
     */
    fun missing(moduleName: String?, locale: String, limit: Int): MissingPage {
        val (name, module) = scope(moduleName)
        val (source, translations, items) = snapshot(module, locale)
        val page = items.take(limit.coerceAtLeast(1)).map { Missing(it.key, it.sent, it.needsReview, it.skipped) }
        val existing = translations.values.mapNotNull { values ->
            val text = values[source]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            values[locale]?.takeIf { it.isNotBlank() }?.let { text to it }
        }
        val glossary = Terminology.glossary(MachineTranslationSettings.getInstance(project).state.glossary, locale)
        val examples = Terminology.examples(page.map { it.source }, existing)
        return MissingPage(name, source, locale, items.size, page, glossary.ifEmpty { null }, examples.ifEmpty { null })
    }

    /**
     * The first [limit] translations of [locale] in [moduleName] that are stale — still the value
     * reviewed while the key's text in the reference locale changed since —, with that text as it
     * reads now and the value it would replace. Refused where the module keeps no reviews: the
     * source a translation was reviewed on is what tells it stale.
     */
    fun stale(moduleName: String?, locale: String, limit: Int): StalePage {
        val (name, module) = scope(moduleName)
        val (source, translations, _) = snapshot(module, locale)
        val root = reviewsOn(module) ?: throw Refused(REVIEWS_OFF)
        val items = staleOf(root, module, source, locale, translations)
        val page = items.take(limit.coerceAtLeast(1)).map { Stale(it.key, it.sent, it.current.orEmpty(), it.skipped) }
        return StalePage(name, source, locale, items.size, page)
    }

    /**
     * Writes into [locale] of [moduleName] each of [values] (`key -> text`) that fills a missing
     * value, or replaces a stale one, with the placeholders of its source; every other one is
     * returned with the reason. Staleness is read when writing: a value fixed since it was listed is
     * refused, never replaced. The values written are marked to review as coming from [client] when
     * the module keeps reviews, with the source text the agent was given — none for a plural form
     * read from `other`.
     */
    fun write(moduleName: String?, locale: String, values: Map<String, String>, client: String): WriteResult {
        val (_, module) = scope(moduleName)
        val (source, translations, items) = snapshot(module, locale)
        val root = reviewsOn(module)
        val missing = items.associateBy { it.key }
        val stale = root?.let { staleOf(it, module, source, locale, translations) }.orEmpty().associateBy { it.key }
        val rejected = sortedMapOf<String, String>()
        val accepted = sortedMapOf<String, String>()
        for ((key, text) in values) {
            val item = missing[key] ?: stale[key]
            rejected[key] = when {
                text.isBlank() -> EMPTY
                item == null && !translations[key]?.get(locale).isNullOrBlank() -> ALREADY_FILLED
                item == null -> NOT_MISSING
                item.skipped != null -> ICU
                else -> icuMismatch(item, text, locale)
                    ?: if (!PlaceholderMask.samePlaceholders(item.sent, text)) "$PLACEHOLDERS ${item.sent}"
                    else { accepted[key] = text; continue }
            }
        }
        if (accepted.isEmpty()) return WriteResult(emptyList(), rejected)
        // `key -> value read above`: a stale value is replaced only while it is still that one.
        val replaced = accepted.keys.mapNotNull { key -> stale[key]?.current?.let { key to it } }.toMap()
        beforeWrite()
        // What the write will leave as it is: a value changed since it was read above.
        val now = TranslationDataLoader.loadAllTranslations(project, module)
        val changed = accepted.keys.filter { key ->
            val value = now[key]?.get(locale)
            replaced[key]?.let { value != it } ?: !value.isNullOrBlank()
        }
        changed.forEach { key -> accepted.remove(key); rejected[key] = CHANGED }
        if (accepted.isNotEmpty()) {
            val origin = Review.machineOrigin(client.ifBlank { UNKNOWN_CLIENT })
            val sources = accepted.keys.mapNotNull { key ->
                MachineFill.reviewSourceOf(missing[key] ?: stale.getValue(key), source)?.let { key to it }
            }.toMap()
            val marks = root?.let { MachineFill.ReviewMarks(it, mapOf(locale to accepted.mapValues { origin }), mapOf(locale to sources)) }
            MachineFill.write(project, module, mapOf(locale to accepted), marks, mapOf(locale to replaced))
        }
        return WriteResult(accepted.keys.toList(), rejected)
    }

    /**
     * Why [text], written for [item], is not an ICU plural of [locale]: a block whose branches are not
     * the categories the language needs, or braces that do not balance. Null when it is one, or when
     * [item] is no ICU plural.
     */
    private fun icuMismatch(item: MachineFill.Item, text: String, locale: String): String? {
        if (!PluralTranslationPlan.isIcuPlural(item.source)) return null
        val mismatches = IcuPluralRewriter.mismatches(text, locale) ?: return "$ICU_UNPARSED ${item.sent}"
        return mismatches.takeIf { it.isNotEmpty() }?.let { "$ICU_BRANCHES ${it.joinToString("; ")}. Source: ${item.sent}" }
    }

    /** The stale translations of [locale] read from the reviews under [root], as *Retranslate Stale Translations* finds them. */
    private fun staleOf(
        root: VirtualFile, module: ModuleConfig?, source: String, locale: String, translations: Map<String, Map<String, String>>,
    ): List<MachineFill.Item> {
        val reviews = TableViewModel().loadReviews(project, root, listOf(source, locale), Settings.getInstance(project).config(), module)
        return MachineFill.staleItemsOf(translations, reviews, locale)
    }

    /** [module]'s source locale and translations (`key -> locale -> value`), and what [locale] lacks, sorted by key. */
    private data class Snapshot(val source: String, val translations: Map<String, Map<String, String>>, val items: List<MachineFill.Item>)

    private fun snapshot(module: ModuleConfig?, locale: String): Snapshot {
        val locales = TranslationDataLoader.discoverLocales(project, module)
        if (locale !in locales) throw Refused("Unknown locale '$locale'. Locales: ${locales.joinToString()}")
        val source = sourceOf(module, locales) ?: throw Refused("No translations found")
        if (locale == source) throw Refused("'$locale' is the reference locale: it is translated from, not into")
        val config = Settings.getInstance(project).config()
        val translations = TranslationDataLoader.loadAllTranslations(project, module)
        return Snapshot(source, translations, MachineFill.itemsOf(translations, source, locale, config.keySeparator, config.parentLocale(locale)))
    }

    private fun sourceOf(module: ModuleConfig?, locales: List<String>): String? =
        ReferenceLocale.of(module, Settings.getInstance(project).config(), locales) ?: locales.firstOrNull()

    /** The review root of [module], only when reviews are turned on there: an agent never turns them on. */
    private fun reviewsOn(module: ModuleConfig?): VirtualFile? =
        reviewRootOf(module)?.takeIf { ReviewStore.getInstance(project).isEnabled(it) }

    /** The modules by name, as *Fill Missing Translations* offers them; one unnamed scope without any. */
    private fun scopes(): List<Pair<String, ModuleConfig?>> {
        val modules = Settings.getInstance(project).config().modules.filter { it.rootDirectory.isNotBlank() }
        if (modules.size < 2) return listOf(PROJECT to null)
        return modules.map { it.name.ifBlank { it.rootDirectory } to it }
    }

    private fun scope(name: String?): Pair<String, ModuleConfig?> {
        val scopes = scopes()
        if (name.isNullOrBlank()) {
            return scopes.singleOrNull() ?: throw Refused("Several modules: name one of ${scopes.joinToString { it.first }}")
        }
        return scopes.firstOrNull { it.first == name }
            ?: throw Refused("Unknown module '$name'. Modules: ${scopes.joinToString { it.first }}")
    }

    companion object {
        const val PROJECT = "project"
        const val UNKNOWN_CLIENT = "agent"
        const val EMPTY = "Empty value"
        const val ALREADY_FILLED = "Already filled and not stale: only a stale value is replaced"
        const val CHANGED = "Changed since it was read: left as it is, list it again"
        const val REVIEWS_OFF = "Reviews are off in this module: a translation is stale against the source it was reviewed on, " +
            "and only a module with a .i18n-reviews folder keeps one. Turn reviews on in the IDE to find stale translations"
        const val NOT_MISSING = "Not a missing key of this locale"
        const val ICU = "ICU plural nested more than one level deep, without an 'other' branch, or in a plural group: " +
            "not filled by the plugin, edit it in the file"
        const val ICU_BRANCHES = "ICU plural branches are not those of this locale:"
        const val ICU_UNPARSED = "Not a valid ICU message, its braces do not balance. Source:"
        const val PLACEHOLDERS = "Placeholders differ from the source:"
    }
}

/**
 * The terminology a page of missing values is translated with, so that an agent keeps the words a
 * locale already uses (a term left in English, one translated a given way) rather than its own.
 */
internal object Terminology {

    /** At most this many translations in place joined to a page: enough to show its terms, few tokens. */
    const val MAX_EXAMPLES = 10

    /** Shorter words — articles, prepositions, placeholder tags once masked — tell nothing of a term. */
    private const val MIN_WORD = 4

    private val WORD = Regex("""[\p{L}\p{N}]+""")

    /** [text]'s words in lower case, its placeholders and tags left out. */
    fun words(text: String): Set<String> =
        WORD.findAll(PlaceholderMask.mask(text).text).map { it.value.lowercase() }.filter { it.length >= MIN_WORD }.toSet()

    /**
     * Of [existing] (`reference text to translation`), at most [max] whose reference text shares a word
     * with one of [page]; a word shared by few of them weighs more, as a term does against a common word.
     */
    fun examples(page: List<String>, existing: List<Pair<String, String>>, max: Int = MAX_EXAMPLES): Map<String, String> {
        val wanted = page.flatMapTo(HashSet(), ::words)
        if (wanted.isEmpty()) return emptyMap()
        val sharing = existing.map { it to (words(it.first) intersect wanted) }.filter { it.second.isNotEmpty() }
        val frequency = sharing.flatMap { it.second }.groupingBy { it }.eachCount()
        return sharing.sortedByDescending { (_, shared) -> shared.sumOf { 1.0 / frequency.getValue(it) } }
            .asSequence().map { it.first }.distinctBy { it.first }.take(max).toMap()
    }

    /** [terms] for [locale], `term -> translation`: a term kept as it is maps to itself, one not translated there is left out. */
    fun glossary(terms: List<GlossaryTerm>, locale: String): Map<String, String> =
        GlossaryTerm.glossary(terms).terms.mapNotNull { term ->
            if (term.kept) term.term to term.term else term.translationFor(locale)?.let { term.term to it }
        }.toMap()
}
