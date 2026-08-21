package com.ibrahimdans.i18n.plugin.utils.generator.code

/**
 * Generates TSX code using react-intl's imperative API: `intl.formatMessage({ id: '…' })`.
 * The [key] parameter is expected to carry its own quotes, like the other generators.
 */
class ReactIntlCodeGenerator : CodeGenerator {

    override fun ext(): String = "tsx"

    override fun generate(key: String, index: Int): String = """
        export const test$index = (intl: {formatMessage: Function}) => {
            return intl.formatMessage({ id: $key });
        };
    """

    /**
     * Same message descriptor shape, but passed to a function that is not `formatMessage`,
     * so no key must be extracted.
     */
    override fun generateInvalid(key: String): String = """
        const key = (s: Function) => s({ id: $key });
    """

    override fun generateBlock(text: String, index: Int): String = """
        export const test$index = (intl: {formatMessage: Function}) => {
            return (<div>$text</div>);
        };
    """

    /**
     * Message descriptor carrying a `defaultMessage` alongside the id — the default message
     * is source text, never a translation key.
     */
    fun generateWithDefaultMessage(key: String, defaultMessage: String, index: Int = 0): String = """
        export const test$index = (intl: {formatMessage: Function}) => {
            return intl.formatMessage({ id: $key, defaultMessage: "$defaultMessage" });
        };
    """

    /**
     * Destructured form: `const { formatMessage } = useIntl()` then a bare `formatMessage(…)` call.
     */
    fun generateDestructured(key: String, index: Int = 0): String = """
        export const test$index = () => {
            const { formatMessage } = useIntl();
            return formatMessage({ id: $key });
        };
    """
}
