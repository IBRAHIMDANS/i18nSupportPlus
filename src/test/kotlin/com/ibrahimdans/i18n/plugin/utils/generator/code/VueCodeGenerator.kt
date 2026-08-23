package com.ibrahimdans.i18n.plugin.utils.generator.code

/**
 * Builds Vue single-file components calling vue-i18n's `$t`.
 *
 * Deliberately absent from the shared generator lists in `TestDataProviders`: a `.vue` file only
 * parses when the Vue plugin is loaded, and the parameterized suites run against every language
 * the plugin supports. Vue tests ask for this generator by name instead, behind `runVue`.
 *
 * [generate] puts the call in the `<script>` block, where the key is ordinary JS PSI.
 * [generateTemplate] puts it in a `{{ }}` interpolation, which is injected JS — a different
 * PSI path that the editor resolves through the injected file, not the host one.
 */
class VueCodeGenerator : CodeGenerator {

    override fun ext(): String = "vue"

    override fun generate(key: String, index: Int): String = component(
        script = "label$index() { return this.\$t($key); }"
    )

    override fun multiGenerate(vararg keys: String): String = component(
        script = keys.mapIndexed { index, key -> "label$index() { return this.\$t($key); }" }.joinToString(",\n        ")
    )

    override fun generateInvalid(key: String): String = component(
        script = "label() { return \"$key\"; }"
    )

    override fun generateBlock(text: String, index: Int): String = component(
        script = "label$index() { return \"$text\"; }"
    )

    /** The same call inside a `{{ }}` interpolation, where Vue injects a JS fragment. */
    fun generateTemplate(key: String): String = component(
        template = "<p>{{ \$t($key) }}</p>",
        script = "label() { return null; }"
    )

    /** A call through one of the other names vue-i18n publishes: `$tc`, `$te`. */
    fun generateWith(function: String, key: String): String = component(
        script = "label() { return this.$function($key); }"
    )

    private fun component(script: String, template: String = "<p>static</p>"): String = """
        <template>
          $template
        </template>
        <script>
        export default {
          methods: {
            $script
          }
        }
        </script>
    """.trimIndent()
}
