package com.ibrahimdans.i18n.plugin.format

import com.ibrahimdans.i18n.SourceDiscovery
import com.ibrahimdans.i18n.plugin.utils.LocaleNames

/**
 * Java resource bundles (`messages.properties`, `messages_fr.properties`, `messages_pt_BR.properties`):
 * the locale is the end of the file's name, after its base name, which neither the folder nor the
 * locale guess of the other formats can tell. The plugin's `PropertiesBundles` and the CLI's
 * `PropertiesSources` declare them through [placementsIn], the rule both share.
 *
 * A `.properties` file is a bundle file only when its base name has at least one localized file
 * beside it: `application.properties` or `gradle.properties` alone are configuration, not translations.
 */
object PropertiesBundle {

    const val EXTENSION = "properties"

    /** The folders never searched: the usual build outputs, Maven's `target` included, whose copies are no sources. */
    val EXCLUDED_DIRECTORIES: Set<String> = SourceDiscovery.DEFAULT_EXCLUDED_DIRECTORIES + "target"

    /** A bundle file's [base] name and [locale], null for the bundle's default file (`messages.properties`). */
    data class Name(val base: String, val locale: String?)

    /** Where a bundle file's keys go: its [locale], and its [namespace], null for the default one. */
    data class Placement(val locale: String, val namespace: String?)

    /**
     * [stem] split at the first `_` followed by a locale: `messages_pt_BR` → (`messages`, `pt_BR`),
     * `my_app_fr` → (`my_app`, `fr`); `messages` → (`messages`, null).
     */
    fun nameOf(stem: String): Name {
        val split = stem.indices
            .filter { stem[it] == '_' && it > 0 }
            .firstOrNull { LocaleNames.looksLikeLocale(stem.substring(it + 1)) }
            ?: return Name(stem, null)
        return Name(stem.substring(0, split), stem.substring(split + 1))
    }

    /**
     * The bundle files among the [stems] of one folder, each with its placement.
     *
     * - A base name with no localized file is no bundle: none of its files is listed.
     * - The default file (`messages.properties`) holds [referenceLocale], unless a file of that
     *   locale exists (`messages_en.properties`): it is then Java's fallback, left out.
     * - The base name is the namespace when the folder holds several bundles (`messages`, `errors`);
     *   a folder of a single bundle holds the default namespace, as Spring's `getMessage("key")` reads it.
     */
    fun placementsIn(stems: Collection<String>, referenceLocale: String): Map<String, Placement> {
        val bundles = stems.groupBy { nameOf(it).base }
            .filterValues { group -> group.any { nameOf(it).locale != null } }
        val several = bundles.size > 1
        return buildMap {
            for ((base, group) in bundles) {
                val namespace = base.takeIf { several }
                val hasReference = group.any { nameOf(it).locale?.let { locale -> sameLocale(locale, referenceLocale) } == true }
                for (stem in group) {
                    val locale = nameOf(stem).locale ?: referenceLocale.takeUnless { hasReference } ?: continue
                    put(stem, Placement(locale, namespace))
                }
            }
        }
    }

    private fun sameLocale(a: String, b: String) = a.replace('-', '_').equals(b.replace('-', '_'), ignoreCase = true)
}
