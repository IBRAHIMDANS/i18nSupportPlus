package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.generator.code.FormattedMessageGenerator
import com.ibrahimdans.i18n.plugin.utils.generator.code.ReactIntlCodeGenerator
import org.junit.jupiter.api.Test

/**
 * Highlighting tests for react-intl (FormatJS).
 *
 * Covers both entry points:
 *   - the imperative API, `intl.formatMessage({ id: '…' })` — handled by ReactIntlExtractor
 *   - the component API, `<FormattedMessage id="…" />` — handled by FormattedMessageExtractor
 *
 * The `defaultMessage` cases are the important ones: in a message descriptor only `id`
 * is a translation key, so the source text carried by `defaultMessage` must never be
 * reported as an unresolved key.
 */
class ReactIntlHighlightingTest : PlatformBaseTest() {

    private val intl = ReactIntlCodeGenerator()
    private val component = FormattedMessageGenerator()

    private val translations = """{"greeting": {"hello": "Bonjour"}}"""

    @Test
    fun testResolvedFormatMessageKey() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("assets/translation.json", translations)
        myFixture.configureByText("test.${intl.ext()}", intl.generate("\"greeting.hello\""))
        myFixture.checkHighlighting(true, true, true, true)
    }

    @Test
    fun testUnresolvedFormatMessageKey() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("assets/translation.json", translations)
        myFixture.configureByText(
            "test.${intl.ext()}",
            intl.generate("\"greeting.<error descr=\"Unresolved key\">missing</error>\"")
        )
        myFixture.checkHighlighting(true, true, true, true)
    }

    @Test
    fun testResolvedDestructuredFormatMessageKey() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("assets/translation.json", translations)
        myFixture.configureByText("test.${intl.ext()}", intl.generateDestructured("\"greeting.hello\""))
        myFixture.checkHighlighting(true, true, true, true)
    }

    @Test
    fun testUnresolvedDestructuredFormatMessageKey() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("assets/translation.json", translations)
        myFixture.configureByText(
            "test.${intl.ext()}",
            intl.generateDestructured("\"greeting.<error descr=\"Unresolved key\">missing</error>\"")
        )
        myFixture.checkHighlighting(true, true, true, true)
    }

    /**
     * Regression guard: `defaultMessage` is source text, not a key. The bare `formatMessage(…)`
     * form passes the generic argument patterns, so without the message-descriptor veto the
     * generic string-literal extractor picks the default message up and reports it unresolved.
     */
    @Test
    fun testDestructuredDefaultMessageIsNotAKey() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("assets/translation.json", translations)
        myFixture.configureByText(
            "test.${intl.ext()}",
            """
            export const test0 = () => {
                const { formatMessage } = useIntl();
                return formatMessage({ id: "greeting.hello", defaultMessage: "Hello there" });
            };
            """
        )
        myFixture.checkHighlighting(true, true, true, true)
    }

    @Test
    fun testDefaultMessageIsNotAKey() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("assets/translation.json", translations)
        myFixture.configureByText(
            "test.${intl.ext()}",
            intl.generateWithDefaultMessage("\"greeting.hello\"", "Hello there")
        )
        myFixture.checkHighlighting(true, true, true, true)
    }

    @Test
    fun testFormatMessageDescriptorOutsideTranslationCall() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("assets/translation.json", translations)
        myFixture.configureByText("test.${intl.ext()}", intl.generateInvalid("\"greeting.missing\""))
        myFixture.checkHighlighting(true, true, true, true)
    }

    @Test
    fun testResolvedFormattedMessageComponent() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("assets/translation.json", translations)
        myFixture.configureByText("test.${component.ext()}", component.generate("\"greeting.hello\""))
        myFixture.checkHighlighting(true, true, true, true)
    }

    @Test
    fun testUnresolvedFormattedMessageComponent() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("assets/translation.json", translations)
        myFixture.configureByText(
            "test.${component.ext()}",
            component.generate("\"greeting.<error descr=\"Unresolved key\">missing</error>\"")
        )
        myFixture.checkHighlighting(true, true, true, true)
    }

    @Test
    fun testFormattedMessageDefaultMessageIsNotAKey() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("assets/translation.json", translations)
        myFixture.configureByText(
            "test.${component.ext()}",
            component.generateWithDefaultMessage("\"greeting.hello\"", "Hello there")
        )
        myFixture.checkHighlighting(true, true, true, true)
    }

    @Test
    fun testIdAttributeOnOtherTagIsIgnored() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("assets/translation.json", translations)
        myFixture.configureByText("test.${component.ext()}", component.generateInvalid("\"greeting.missing\""))
        myFixture.checkHighlighting(true, true, true, true)
    }

    @Test
    fun testResolvedDefineMessagesId() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("assets/translation.json", translations)
        myFixture.configureByText(
            "test.${intl.ext()}",
            """
            const messages = defineMessages({
                greeting: { id: "greeting.hello", defaultMessage: "Hello there", description: "Home page" },
            });
            """
        )
        myFixture.checkHighlighting(true, true, true, true)
    }

    @Test
    fun testUnresolvedDefineMessagesId() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("assets/translation.json", translations)
        myFixture.configureByText(
            "test.${intl.ext()}",
            """
            const messages = defineMessages({
                greeting: { id: "greeting.<error descr="Unresolved key">missing</error>" },
            });
            """
        )
        myFixture.checkHighlighting(true, true, true, true)
    }

    @Test
    fun testResolvedSingularDefineMessageId() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("assets/translation.json", translations)
        myFixture.configureByText(
            "test.${intl.ext()}",
            """
            const message = defineMessage({ id: "greeting.hello", defaultMessage: "Hello there" });
            """
        )
        myFixture.checkHighlighting(true, true, true, true)
    }

    /** A catalogue-shaped object that is not a `defineMessages` call carries no keys. */
    @Test
    fun testPlainCatalogueObjectIsIgnored() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("assets/translation.json", translations)
        myFixture.configureByText(
            "test.${intl.ext()}",
            """
            const messages = { greeting: { id: "greeting.missing", defaultMessage: "Hello there" } };
            """
        )
        myFixture.checkHighlighting(true, true, true, true)
    }
}
