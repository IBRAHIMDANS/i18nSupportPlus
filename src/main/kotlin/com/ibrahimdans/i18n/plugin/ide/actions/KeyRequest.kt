package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.parser.KeyParserBuilder
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages

/**
 * Key request result
 */
data class KeyRequestResult(val key: FullKey?, val isCancelled: Boolean)

/**
 * What the user wants done with a text some keys already hold: see [KeyRequest.choose].
 */
sealed interface KeyChoice {
    /** Point the code at [key], which already exists: nothing is written to the translations. */
    data class Existing(val key: String) : KeyChoice

    /** Ask for a new key and create it, as when no key holds the text. */
    data object New : KeyChoice

    data object Cancelled : KeyChoice
}

/**
 * Requests i18n key from user
 */
class KeyRequest {

    /**
     * Requests key
     */
    fun key(project: Project, text: String): KeyRequestResult {
        val config = Settings.getInstance(project).config()
        val keyStr = Messages.showInputDialog(
            project,
            String.format(PluginBundle.getMessage("action.intention.extract.key.hint"), text),
            PluginBundle.getMessage("action.intention.extract.key.input.key"),
            Messages.getQuestionIcon(),
            null,
            isValidKey())
        return if(keyStr == null) {
            KeyRequestResult(null, true)
        } else {
            KeyRequestResult(
                (if(config.usesFlatKeys()) KeyParserBuilder.withoutTokenizer() else KeyParserBuilder.withSeparators(config.nsSeparator, config.keySeparator)).build()
                    .parse(
                        RawKey(listOf(KeyElement.literal(keyStr))),
                        emptyNamespace = config.usesFlatKeys(),
                        firstComponentNamespace = config.firstComponentNs
                    ),
                false
            )
        }
    }

    /**
     * Offers the [existingKeys] holding [text] first, and creating a new key last.
     *
     * [Messages.showChooseDialog] rather than a popup: it is modal like the input dialog that
     * follows it, and in tests it answers through `TestDialogManager.setTestDialog`, the returned
     * code being the index of the chosen option — the harness the rest of the extraction already
     * runs under, where a `JBPopup` would need a UI to click. No dialog at all when no key holds
     * the text: that path stays exactly what it was.
     */
    fun choose(project: Project, text: String, existingKeys: List<String>): KeyChoice {
        if (existingKeys.isEmpty()) return KeyChoice.New
        val options = existingKeys.map { PluginBundle.message("action.intention.extract.key.reuse.option", it) } +
            PluginBundle.message("action.intention.extract.key.reuse.create")
        val index = Messages.showChooseDialog(
            project,
            PluginBundle.message("action.intention.extract.key.reuse.message", text),
            PluginBundle.message("action.intention.extract.key.reuse.title"),
            Messages.getQuestionIcon(),
            options.toTypedArray(),
            options.first()
        )
        return when (index) {
            in existingKeys.indices -> KeyChoice.Existing(existingKeys[index])
            existingKeys.size -> KeyChoice.New
            else -> KeyChoice.Cancelled
        }
    }

    private fun isValidKey() = object : InputValidator {
        override fun checkInput(inputString: String?): Boolean {
            return (inputString ?: "").isNotEmpty()
        }
        override fun canClose(inputString: String?): Boolean = true
    }
}