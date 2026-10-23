package com.ibrahimdans.i18n.plugin.review

import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileKeys
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileScope
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.KeySpelling
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.of
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement

/** Where the review of a translation node lives: [root]'s reviews folder, [locale]'s file, under [key]. */
data class ReviewSpot(val root: VirtualFile, val locale: String, val key: ReviewKey)

/** The reviews at [from] — the key's own and every key below it — to be filed under [to]. */
data class ReviewMove(val from: ReviewSpot, val to: ReviewKey)

/**
 * Keeps the reviews of a key with the key when it is renamed, moved to another namespace or deleted.
 *
 * Those operations work on the PSI of the translation files, not on the tool window's spelling the
 * reviews are filed under ([ReviewKey.fromSpelling]): [spotOf] and [moveOf] rebuild that spelling from
 * a node of a translation file — its locale, its namespace (the default one made explicit), its module.
 * The caller computes them *before* touching the PSI, then calls [move] or [remove] inside its own
 * command, so that one Ctrl+Z restores the translations and their reviews together.
 *
 * A review keeps its hash when it moves: the value did not change, an approval still holds.
 *
 * Nothing is ever purged automatically: a key whose translation is gone by other means keeps an
 * orphan review, hidden by [shown], which [orphans] lists for the person to confirm — a translation
 * load that comes back empty must never wipe the reviews.
 */
object ReviewMigration {

    // ── Pure edits ─────────────────────────────────────────────────────────────

    /**
     * [reviews] with every key covered by a move's source ([covers]) filed under the move's target,
     * the rest of its path kept: `button.save` follows `button` → `actions` to `actions.save`. All
     * moves read the reviews as they were, so a chain (`a` → `b`, `b` → `c`) cannot move a review twice.
     */
    fun moved(reviews: Map<ReviewKey, Review>, moves: List<Pair<ReviewKey, ReviewKey>>, levelSeparator: String?): Map<ReviewKey, Review> {
        val result = reviews.toMutableMap()
        val arrived = mutableMapOf<ReviewKey, Review>()
        for ((from, to) in moves) {
            if (from == to) continue
            reviews.filterKeys { covers(from, it, levelSeparator) }.forEach { (key, review) ->
                result.remove(key)
                arrived[ReviewKey(to.namespace, to.path + key.path.removePrefix(from.path))] = review
            }
        }
        return result + arrived
    }

    /** [reviews] without those of [keys] nor of any key below them: deleting a property deletes its children. */
    fun removed(reviews: Map<ReviewKey, Review>, keys: Collection<ReviewKey>, levelSeparator: String?): Map<ReviewKey, Review> =
        reviews.filterKeys { candidate -> keys.none { covers(it, candidate, levelSeparator) } }

    /** True when [candidate] is [key] or lies below it, one level separator further down. */
    fun covers(key: ReviewKey, candidate: ReviewKey, levelSeparator: String?): Boolean =
        candidate == key || (levelSeparator != null && candidate.namespace == key.namespace &&
            candidate.path.startsWith(key.path + levelSeparator))

    /** A review whose key no longer holds a value in its locale. */
    data class Orphan(val locale: String, val key: ReviewKey, val review: Review)

    /**
     * The reviews of [reviewsByLocale] whose key has no value in their locale among [translations]
     * — the tool window's load: spelled key → locale → value.
     *
     * The guard against a load gone wrong: nothing is an orphan when [translations] is empty, nor in
     * a locale it holds no value for at all. A failed load or a format the loader cannot read yet
     * (Laravel before #493) would otherwise offer every review for deletion.
     */
    fun orphans(
        reviewsByLocale: Map<String, Map<ReviewKey, Review>>,
        translations: Map<String, Map<String, String>>,
        defaultNamespaces: List<String>,
    ): List<Orphan> {
        if (translations.isEmpty()) return emptyList()
        val loadedLocales = translations.values.flatMapTo(mutableSetOf()) { it.keys }
        return reviewsByLocale.filterKeys { it in loadedLocales }.flatMap { (locale, reviews) ->
            reviews.filter { (key, _) -> translations[key.spelling(defaultNamespaces)]?.get(locale) == null }
                .map { (key, review) -> Orphan(locale, key, review) }
        }.sortedWith(compareBy<Orphan> { it.locale }.thenBy { it.key.stored })
    }

    /** The separator between two levels of a key, or null when keys are flat — as [KeySpelling] joins them. */
    fun levelSeparator(config: Config): String? = config.keySeparator.takeUnless { config.usesFlatKeys() || it.isEmpty() }

    // ── From the PSI (read access) ─────────────────────────────────────────────

    /**
     * Where the review of [element] — a node of a translation file, property, name or value — lives.
     * Null when reviews are off for its module (no `.i18n-reviews` folder), or it is no translation.
     */
    fun spotOf(element: PsiElement): ReviewSpot? = placeOf(element)?.let { ReviewSpot(it.root, it.locale, it.key(it.namespace, it.path)) }

    /**
     * The move of [element]'s reviews to [namespace] (its own when null) and the path [path] makes of
     * its own; null when reviews are off for it or nothing changes.
     */
    fun moveOf(element: PsiElement, namespace: String? = null, path: (List<String>) -> List<String> = { it }): ReviewMove? {
        val place = placeOf(element) ?: return null
        val from = ReviewSpot(place.root, place.locale, place.key(place.namespace, place.path))
        val to = place.key(namespace ?: place.namespace, path(place.path))
        return if (to == from.key) null else ReviewMove(from, to)
    }

    /** [elements]' spots, the elements of reviews-free modules left out. */
    fun spotsOf(elements: Collection<PsiElement>): List<ReviewSpot> = elements.mapNotNull(::spotOf).distinct()

    // ── Writes (write action, inside the caller's command) ─────────────────────

    /** Files the reviews of [moves] under their new keys. Returns false when a review file could not be written. */
    fun move(project: Project, moves: Collection<ReviewMove>): Boolean {
        if (moves.isEmpty()) return true
        val separator = levelSeparator(Settings.getInstance(project).config())
        val store = ReviewStore.getInstance(project)
        return moves.distinct().groupBy { it.from.root to it.from.locale }.map { (place, inFile) ->
            store.update(place.first, place.second) { moved(it, inFile.map { move -> move.from.key to move.to }, separator) }
        }.all { it }
    }

    /** Deletes the reviews at [spots], and those of the keys below them. Returns false when a review file could not be written. */
    fun remove(project: Project, spots: Collection<ReviewSpot>): Boolean {
        if (spots.isEmpty()) return true
        val separator = levelSeparator(Settings.getInstance(project).config())
        val store = ReviewStore.getInstance(project)
        return spots.groupBy { it.root to it.locale }.map { (place, inFile) ->
            store.update(place.first, place.second) { removed(it, inFile.map(ReviewSpot::key), separator) }
        }.all { it }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private class Place(val root: VirtualFile, val locale: String, val namespace: String, val path: List<String>, val config: Config) {

        /** [keyPath] in [inNamespace], as the tool window spells it then files its review. */
        fun key(inNamespace: String, keyPath: List<String>): ReviewKey {
            val defaults = config.defaultNamespaces()
            val prefix = if (inNamespace in defaults) "" else inNamespace + KeySpelling.NAMESPACE_SEPARATOR
            val spelled = prefix + keyPath.fold("") { spelling, segment -> KeySpelling.child(config, spelling, segment) }
            return ReviewKey.fromSpelling(spelled, defaults.first())
        }
    }

    /**
     * What [element] is in the review files, the module's folder checked first: a project without
     * reviews pays no source lookup.
     */
    private fun placeOf(element: PsiElement): Place? {
        val file = element.containingFile ?: return null
        val virtualFile = file.originalFile.virtualFile ?: return null
        val project = element.project
        val config = Settings.getInstance(project).config()
        val module = if (config.modules.isEmpty()) null
            else ModuleSources.owner(config.modules, ModuleSources.FilePath.of(virtualFile, project.basePath ?: ""))
        val root = ReviewLocation.rootOf(project, module)?.takeIf { ReviewStore.getInstance(project).isEnabled(it) } ?: return null
        val path = TranslationFileKeys.pathOf(element).takeIf { it.isNotEmpty() } ?: return null
        val source = TranslationFileScope.sourceOf(file) ?: return null
        val namespace = TranslationDataLoader.extractNamespace(source, config.defaultNamespaces().first())
        return Place(root, TranslationDataLoader.extractLocale(source), namespace, path, config)
    }
}
