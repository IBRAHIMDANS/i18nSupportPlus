package com.ibrahimdans.i18n.plugin.utils.generator.code

/**
 * Generates TSX code using Lingui's source-based <Trans> component.
 * The [key] parameter is the visible text — it becomes the msgid in .po files.
 *
 * Example: generate("Hello world!") → `const Trans0 = () => (<Trans>Hello world!</Trans>);`
 */
class LinguiTransSourceGenerator : CodeGenerator {

    override fun ext(): String = "tsx"

    override fun generate(key: String, index: Int): String =
        "const Trans$index = () => (<Trans>$key</Trans>);"

    override fun generateInvalid(key: String): String =
        "const Invalid = () => (<div>$key</div>);"

    override fun generateBlock(text: String, index: Int): String =
        "export const Block$index = () => (<div>$text</div>);"
}
