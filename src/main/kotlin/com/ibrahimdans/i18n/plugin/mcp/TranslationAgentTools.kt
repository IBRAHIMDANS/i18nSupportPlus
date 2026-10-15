package com.ibrahimdans.i18n.plugin.mcp

import com.ibrahimdans.i18n.plugin.ide.actions.MachineFill
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TableViewModel
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.review.Review
import com.ibrahimdans.i18n.plugin.review.ReviewLocation
import com.ibrahimdans.i18n.plugin.review.ReviewStore
import com.ibrahimdans.i18n.plugin.translate.PlaceholderMask
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
 * never any other one; a value must keep the placeholders of its source, an ICU plural is left alone,
 * and the whole call is one undoable command, marked to review when the module keeps reviews.
 *
 * The texts returned are read by the agent, not shown in the IDE: they stay in English.
 */
class TranslationAgentTools(private val project: Project) {

    /** A refused call, its [message] meant for the agent. */
    class Refused(message: String) : Exception(message)

    data class ModuleInfo(val name: String, val referenceLocale: String, val locales: List<String>, val reviews: Boolean)

    data class Missing(val key: String, val source: String, val pluralFromOther: Boolean, val skipped: String?)

    data class MissingPage(val module: String, val sourceLocale: String, val locale: String, val total: Int, val items: List<Missing>)

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
        ModuleInfo(name, sourceOf(module, locales).orEmpty(), locales, reviewsOn(module) != null)
    }

    /** The first [limit] values [locale] lacks in [moduleName], with the source text to translate. */
    fun missing(moduleName: String?, locale: String, limit: Int): MissingPage {
        val (name, module) = scope(moduleName)
        val (source, _, items) = snapshot(module, locale)
        val page = items.take(limit.coerceAtLeast(1)).map { Missing(it.key, it.source, it.needsReview, it.skipped) }
        return MissingPage(name, source, locale, items.size, page)
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
        val page = items.take(limit.coerceAtLeast(1)).map { Stale(it.key, it.source, it.current.orEmpty(), it.skipped) }
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
                !PlaceholderMask.samePlaceholders(item.source, text) -> "$PLACEHOLDERS ${item.source}"
                else -> { accepted[key] = text; continue }
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
        val keySeparator = Settings.getInstance(project).config().keySeparator
        val translations = TranslationDataLoader.loadAllTranslations(project, module)
        return Snapshot(source, translations, MachineFill.itemsOf(translations, source, locale, keySeparator))
    }

    private fun sourceOf(module: ModuleConfig?, locales: List<String>): String? =
        ReferenceLocale.of(module, Settings.getInstance(project).config(), locales) ?: locales.firstOrNull()

    /** The review root of [module], only when reviews are turned on there: an agent never turns them on. */
    private fun reviewsOn(module: ModuleConfig?): VirtualFile? =
        reviewRootOf(module)?.takeIf { ReviewStore.getInstance(project).isEnabled(it) }

    /** The modules by name, as *Fill Missing Translations* offers them; one unnamed scope without any. */
    private fun scopes(): List<Pair<String, ModuleConfig?>> {
        val modules = Settings.getInstance(project).modules.filter { it.rootDirectory.isNotBlank() }
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
        const val ICU = "ICU plural: not filled by the plugin, edit it in the file"
        const val PLACEHOLDERS = "Placeholders differ from the source:"
    }
}
