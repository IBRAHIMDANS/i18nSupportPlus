package com.ibrahimdans.i18n.plugin.utils.generator.code

/**
 * Generates TSX code using react-intl's `<FormattedMessage id="…" />` component.
 * The [key] parameter is expected to carry its own quotes, like the other generators.
 */
class FormattedMessageGenerator : CodeGenerator {

    override fun ext(): String = "tsx"

    override fun generate(key: String, index: Int): String =
        "const Message$index = () => (<FormattedMessage id=$key />);"

    /**
     * An `id` attribute on a tag that is not FormattedMessage must not be extracted.
     */
    override fun generateInvalid(key: String): String =
        "const Invalid = () => (<CustomTag id=$key />);"

    override fun generateBlock(text: String, index: Int): String =
        "export const Block$index = () => (<div>$text</div>);"

    /**
     * FormattedMessage carrying a `defaultMessage` attribute alongside the id.
     */
    fun generateWithDefaultMessage(key: String, defaultMessage: String, index: Int = 0): String =
        "const Message$index = () => (<FormattedMessage id=$key defaultMessage=\"$defaultMessage\" />);"
}
