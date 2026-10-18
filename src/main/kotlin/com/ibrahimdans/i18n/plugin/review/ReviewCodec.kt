package com.ibrahimdans.i18n.plugin.review

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Reads and writes a review file, `.i18n-reviews/<locale>.json`:
 *
 * ```json
 * {
 *   "version": 1,
 *   "reviews": {
 *     "common:cart.title": {
 *       "status": "approved",
 *       "hash": "sha256:3f2a9c0e1b7d4a55",
 *       "by": "Alice Martin <alice@example.com>",
 *       "at": "2026-10-11T14:02:11Z",
 *       "origin": "machine:DeepL",
 *       "thread": [
 *         { "id": "r1", "kind": "approve", "by": "…", "at": "…", "text": "" }
 *       ],
 *       "sourceLocale": "en",
 *       "sourceHash": "sha256:9b1e0d77c2aa4e10"
 *     }
 *   }
 * }
 * ```
 *
 * The output is deterministic so that the file merges well in Git: keys sorted, fields in a fixed
 * order, two-space indentation, one property per line, a final `\n`. Two reviews of distant keys never
 * touch the same lines. HTML characters are written as they are (`Alice <alice@…>`, `« Panier »`).
 *
 * `sourceLocale` and `sourceHash` ([Review.source]) are optional, and left out when unknown: a file
 * written before them reads and writes back unchanged, still in [VERSION] 1 — a version that does
 * not know them reads the file all the same. So is `suggestion` on a thread entry
 * ([ReviewEvent.suggestion]). The properties of an entry this version does not know
 * are written back after the others, as they were read ([Review.extra]); so are those of an entry of
 * its thread ([ReviewEvent.extra]).
 */
object ReviewCodec {

    /** The format this version writes; a file of a later one is left alone. */
    const val VERSION = 1

    /**
     * A file as read: its reviews, and whether it could be read at all. An unreadable file — a merge
     * conflict left in it, a format of a later version — reads as no review, and must never be
     * overwritten: [ReviewStore] refuses to write over it rather than lose every review it holds.
     */
    data class Decoded(val reviews: Map<ReviewKey, Review>, val readable: Boolean)

    // Nulls kept: an unknown property set to null is written back as it was read.
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()

    /** The reviews [text] holds; none, and no exception, when it is absent, blank or unreadable. */
    fun read(text: String?): Map<ReviewKey, Review> = decode(text).reviews

    fun decode(text: String?): Decoded {
        if (text.isNullOrBlank()) return Decoded(emptyMap(), readable = true)
        return try {
            val root = JsonParser.parseString(text).asJsonObject
            val version = root.get("version")?.takeIf { it.isJsonPrimitive }?.asInt ?: VERSION
            if (version > VERSION) return Decoded(emptyMap(), readable = false)
            val reviews = root.get("reviews")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return Decoded(emptyMap(), readable = true)
            Decoded(
                reviews.entrySet().mapNotNull { (stored, value) ->
                    val key = ReviewKey.parse(stored) ?: return@mapNotNull null
                    val review = readReview(value) ?: return@mapNotNull null
                    key to review
                }.toMap(),
                readable = true
            )
        } catch (_: RuntimeException) {
            // JsonSyntaxException, IllegalStateException (not an object), NumberFormatException…
            Decoded(emptyMap(), readable = false)
        }
    }

    /** [reviews] as a review file, ready to be written as is. */
    fun write(reviews: Map<ReviewKey, Review>): String {
        val entries = JsonObject()
        reviews.entries.sortedWith(compareBy(ReviewKey.ORDER) { it.key }).forEach { (key, review) ->
            entries.add(key.stored, writeReview(review))
        }
        val root = JsonObject()
        root.addProperty("version", VERSION)
        root.add("reviews", entries)
        return gson.toJson(root) + "\n"
    }

    /** An entry of the file, or null when it lacks what a review must hold (a hand edit gone wrong). */
    private fun readReview(element: JsonElement): Review? {
        if (!element.isJsonObject) return null
        val json = element.asJsonObject
        val state = ReviewState.of(json.string("status")) ?: return null
        val hash = json.string("hash") ?: return null
        val thread = json.get("thread")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { readEvent(it) }.orEmpty()
        val sourceLocale = json.string(SOURCE_LOCALE)
        val sourceHash = json.string(SOURCE_HASH)
        val source = if (sourceLocale != null && sourceHash != null) ReviewSource(sourceLocale, sourceHash) else null
        val extra = json.entrySet().filter { it.key !in KNOWN }.associate { (name, value) -> name to value.toString() }
        return Review(
            state, hash, json.string("by").orEmpty(), json.string("at").orEmpty(),
            json.string("origin") ?: Review.ORIGIN_MANUAL, thread, source, extra
        )
    }

    private fun readEvent(element: JsonElement): ReviewEvent? {
        if (!element.isJsonObject) return null
        val json = element.asJsonObject
        return ReviewEvent(
            json.string("id") ?: return null, ReviewEventKind.of(json.string("kind")),
            json.string("by").orEmpty(), json.string("at").orEmpty(), json.string("text").orEmpty(),
            json.string(SUGGESTION),
            json.entrySet().filter { it.key !in KNOWN_EVENT }.associate { (name, value) -> name to value.toString() }
        )
    }

    private fun writeReview(review: Review): JsonObject = JsonObject().apply {
        addProperty("status", review.state.id)
        addProperty("hash", review.hash)
        addProperty("by", review.by)
        addProperty("at", review.at)
        addProperty("origin", review.origin)
        add("thread", JsonArray().apply { review.thread.forEach { add(writeEvent(it)) } })
        // Never written when unknown: a review read from an older file is written back as it was.
        review.source?.let {
            addProperty(SOURCE_LOCALE, it.locale)
            addProperty(SOURCE_HASH, it.hash)
        }
        review.extra.forEach { (name, raw) -> if (name !in KNOWN) add(name, JsonParser.parseString(raw)) }
    }

    private fun writeEvent(event: ReviewEvent): JsonObject = JsonObject().apply {
        addProperty("id", event.id)
        addProperty("kind", event.kind.id)
        addProperty("by", event.by)
        addProperty("at", event.at)
        addProperty("text", event.text)
        // Never written when absent: an entry read from an older file is written back as it was.
        event.suggestion?.let { addProperty(SUGGESTION, it) }
        event.extra.forEach { (name, raw) -> if (name !in KNOWN_EVENT) add(name, JsonParser.parseString(raw)) }
    }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private const val SOURCE_LOCALE = "sourceLocale"
    private const val SOURCE_HASH = "sourceHash"
    private const val SUGGESTION = "suggestion"

    /** The properties of an entry this version reads; any other one is kept in [Review.extra]. */
    private val KNOWN = setOf("status", "hash", "by", "at", "origin", "thread", SOURCE_LOCALE, SOURCE_HASH)

    /** The properties of a thread entry this version reads; any other one is kept in [ReviewEvent.extra]. */
    private val KNOWN_EVENT = setOf("id", "kind", "by", "at", "text", SUGGESTION)
}
