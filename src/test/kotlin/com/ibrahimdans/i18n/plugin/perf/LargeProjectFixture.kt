package com.ibrahimdans.i18n.plugin.perf

/**
 * A project the size of a real multi-namespace app: [LOCALES] × [NAMESPACES] JSON files laid out
 * as `locales/{lang}/{ns}.json`, and a component calling [KEY_CALLS] keys, half of them naming a
 * namespace (`t('ns7:group1.key3')`), half relying on the default one (`t('group1.key3')`).
 *
 * Every generated key exists in every locale: the benchmark measures the resolved path, which is
 * what a project mostly holds, and asserts it before timing anything.
 */
internal object LargeProjectFixture {

    val LOCALES = listOf("en", "fr", "de", "es", "it", "pt", "nl", "pl", "sv", "ja")
    const val NAMESPACES = 50
    const val GROUPS = 2
    const val KEYS_PER_GROUP = 10
    const val KEY_CALLS = 200

    /** The first namespace is the default one, so a key written without namespace resolves in it. */
    fun namespace(index: Int) = if (index == 0) "translation" else "ns$index"

    /** Every translation file, as (project-relative path, content). */
    fun translationFiles(): List<Pair<String, String>> =
        LOCALES.flatMap { locale ->
            (0 until NAMESPACES).map { ns -> "locales/$locale/${namespace(ns)}.json" to translationContent(locale) }
        }

    private fun translationContent(locale: String): String =
        (0 until GROUPS).joinToString(",\n", "{\n", "\n}") { group ->
            val keys = (0 until KEYS_PER_GROUP).joinToString(",\n") { key -> """    "key$key": "$locale value $group.$key"""" }
            "  \"group$group\": {\n$keys\n  }"
        }

    /**
     * The component under test. [caretMarker] sits in a trailing comment, so typing there changes
     * the PSI — which is what invalidates the plugin's caches — without touching any key.
     */
    fun component(caretMarker: String = "<caret>"): String {
        val calls = (0 until KEY_CALLS).joinToString(",\n") { i ->
            val key = "group${i % GROUPS}.key${i % KEYS_PER_GROUP}"
            if (i % 2 == 0) "        t('${namespace(1 + i % (NAMESPACES - 1))}:$key')" else "        t('$key')"
        }
        return """
            |import { useTranslation } from 'react-i18next';
            |
            |export default function Dashboard() {
            |    const { t } = useTranslation();
            |    const labels = [
            |$calls
            |    ];
            |    return labels.join(' ');
            |}
            |// $caretMarker
        """.trimMargin()
    }

    /** A file the plugin has nothing to do with, to measure what an edit elsewhere costs. */
    fun unrelatedJavaFile(caretMarker: String = "<caret>"): String = """
        |public class Notes {
        |    // $caretMarker
        |}
    """.trimMargin()
}
