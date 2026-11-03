package com.ibrahimdans.i18n.plugin.ide.usages

/**
 * A generated project whose key usages are known before any search runs: the translation files of
 * [namespaces], [keysPerNamespace] keys each, and [files] TSX files calling them the ways the plugin
 * counts — `i18n.t('ns:section.key')`, `t('section.key')` under `useTranslation('ns')`, `t('key')`
 * under a hook's `keyPrefix` — in proportions a real project shows.
 *
 * Within each section of [SECTION_SIZE] keys, by position:
 * - the first key is a plural, stored as `_one` and `_other` while the code writes the base key;
 * - every fifth key is never called (an orphan);
 * - the others are called once or twice, the call style rotating between the three above.
 *
 * One section per namespace, [DYNAMIC_SECTION], is named by no call: a single `` t(`ns:status.${'$'}{kind}`) ``
 * reaches all of its keys. Every code file also declares the key names as identifiers
 * (`const k3 = 3`), so that a word search finds them where no key is written, as in a real codebase.
 *
 * Nothing is random: the same parameters always yield the same project, and [expectedCounts] and
 * [dynamicKeys] are the truth a count has to match.
 */
internal class UsageBenchmarkProject(
    val keysPerNamespace: Int,
    val files: Int,
    val namespaces: List<String> = listOf("common", "app"),
) {

    private data class GeneratedKey(val namespace: String, val section: String, val name: String) {
        val path: String get() = "$section.$name"
        val full: String get() = "$namespace:$path"
    }

    private enum class Style { DIRECT, HOOK, PREFIXED }

    private data class Call(val key: GeneratedKey, val style: Style)

    /** The JSON of each translation file, by project-relative path. */
    val translationFiles: Map<String, String>

    /** The TSX of each code file, by project-relative path. */
    val codeFiles: Map<String, String>

    /** The static usage count of every stored key, `ns:section.key` — plural forms with their suffix — orphans and dynamic keys at 0. */
    val expectedCounts: Map<String, Int>

    /** The keys no call names that the dynamic literal reaches. */
    val dynamicKeys: Set<String>

    init {
        val counts = linkedMapOf<String, Int>()
        val dynamic = linkedSetOf<String>()
        val calls = mutableListOf<Call>()
        val json = linkedMapOf<String, LinkedHashMap<String, LinkedHashMap<String, String>>>()

        for (namespace in namespaces) {
            val sections = linkedMapOf<String, LinkedHashMap<String, String>>()
            for (i in 0 until keysPerNamespace) {
                val position = i % SECTION_SIZE
                val key = GeneratedKey(namespace, "s${i / SECTION_SIZE}", "k$position")
                val values = sections.getOrPut(key.section) { linkedMapOf() }
                when {
                    position == 0 -> {
                        values["${key.name}_one"] = "One ${key.path}"
                        values["${key.name}_other"] = "Many ${key.path}"
                        calls += Call(key, Style.DIRECT)
                        counts["${key.full}_one"] = 1
                        counts["${key.full}_other"] = 1
                    }
                    position % 5 == 4 -> {
                        values[key.name] = "Orphan ${key.path}"
                        counts[key.full] = 0
                    }
                    else -> {
                        values[key.name] = "Text ${key.path}"
                        val repeats = 1 + position % 2
                        repeat(repeats) { calls += Call(key, Style.entries[position % Style.entries.size]) }
                        counts[key.full] = repeats
                    }
                }
            }
            val dynamicValues = sections.getOrPut(DYNAMIC_SECTION) { linkedMapOf() }
            for (j in 0 until maxOf(1, keysPerNamespace / 20)) {
                val key = GeneratedKey(namespace, DYNAMIC_SECTION, "k$j")
                dynamicValues[key.name] = "Status ${key.path}"
                counts[key.full] = 0
                dynamic += key.full
            }
            json[namespace] = sections
        }

        translationFiles = json.mapKeys { (namespace, _) -> "locales/en/$namespace.json" }.mapValues { (_, sections) -> jsonOf(sections) }
        codeFiles = (0 until files).associate { index -> "src/components/Component$index.tsx" to codeOf(index, calls.filterIndexed { c, _ -> c % files == index }) }
        expectedCounts = counts
        dynamicKeys = dynamic
    }

    private fun jsonOf(sections: Map<String, Map<String, String>>): String =
        sections.entries.joinToString(",\n", "{\n", "\n}") { (section, values) ->
            values.entries.joinToString(",\n", "  \"$section\": {\n", "\n  }") { (name, value) -> "    \"$name\": \"$value\"" }
        }

    /**
     * One file: the identifiers, a dynamic call in the first file of each namespace's share, one
     * component per call style — hooks grouped by namespace, key-prefixed hooks by section.
     */
    private fun codeOf(index: Int, calls: List<Call>): String {
        val code = StringBuilder()
        code.append("import { useTranslation } from 'react-i18next';\n")
        code.append("import i18n from '../i18n';\n\n")
        for (position in 0 until SECTION_SIZE) code.append("const k$position = $position;\n")
        code.append("const s$index = 's$index';\n\n")

        namespaces.forEachIndexed { n, namespace ->
            if (index == n % files) {
                code.append("export const Status$n = (kind: string) => <div>{i18n.t(`$namespace:$DYNAMIC_SECTION.\${kind}`)}</div>;\n\n")
            }
        }

        val direct = calls.filter { it.style == Style.DIRECT }
        if (direct.isNotEmpty()) {
            code.append("export const Direct$index = () => (\n  <div>\n")
            for (call in direct) code.append("    {i18n.t('${call.key.full}', { count: $index })}\n")
            code.append("  </div>\n);\n\n")
        }

        calls.filter { it.style == Style.HOOK }.groupBy { it.key.namespace }.forEach { (namespace, hooked) ->
            code.append("export const Hook${index}_$namespace = () => {\n")
            code.append("  const { t } = useTranslation('$namespace');\n  return (\n    <div>\n")
            for (call in hooked) code.append("      {t('${call.key.path}')}\n")
            code.append("    </div>\n  );\n};\n\n")
        }

        calls.filter { it.style == Style.PREFIXED }.groupBy { it.key.namespace to it.key.section }.forEach { (group, prefixed) ->
            val (namespace, section) = group
            code.append("export const Prefixed${index}_${namespace}_$section = () => {\n")
            code.append("  const { t } = useTranslation('$namespace', { keyPrefix: '$section' });\n  return (\n    <div>\n")
            for (call in prefixed) code.append("      {t('${call.key.name}')}\n")
            code.append("    </div>\n  );\n};\n\n")
        }
        return code.toString()
    }

    companion object {
        const val SECTION_SIZE = 20
        const val DYNAMIC_SECTION = "status"
    }
}
