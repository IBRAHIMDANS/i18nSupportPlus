package com.ibrahimdans.i18n.plugin.utils

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.I18nBundle"

/**
 * The plugin's user-visible strings.
 *
 * Backed by [DynamicBundle] rather than `AbstractBundle`: the latter resolves the bundle
 * against the JVM's default locale, so a translated `.properties` shipped next to the base
 * one would only ever be picked up on a machine whose JVM runs in that language.
 * [DynamicBundle] resolves against [DynamicBundle.getLocale] instead — the locale the IDE
 * itself runs in, which is what a language pack changes. Without it, translating the plugin
 * would have no visible effect for the users it is meant for.
 *
 * Prefer [message] over the inherited `getMessage`: its key is annotated with [PropertyKey],
 * so a key absent from the bundle is reported in the editor as you type it. Nothing else
 * catches it — a missing key is not a compile error, and at runtime the platform renders it
 * as `!some.key!` in the interface without logging anything. The remaining `getMessage`
 * callers belong to packages this batch does not cover and move over with their own batch.
 */
object PluginBundle : DynamicBundle(BUNDLE) {

    /** The translated string for [key], with [params] substituted into its placeholders. */
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
