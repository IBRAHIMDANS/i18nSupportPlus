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
     * Offers the [existingKeys] holding [text], then creating a new key, then cancelling — one
     * button each, in that order.
     *
     * [Messages.showDialog] rather than a popup or `showChooseDialog`: it is modal like the input
     * dialog that follows it, and in tests it answers through `TestDialogManager.setTestDialog`,
     * the returned code being the index of the button — the harness the rest of the extraction
     * already runs under. `showChooseDialog` did the same with a combo box, but is deprecated.
     *
     * Buttons do not scale like a list: at most [MAX_OFFERED_KEYS] keys are offered, and the
     * message says how many more hold the text. The default button — the one Enter presses — is
     * the key when exactly one matches, where reusing it is the obvious intent; with several,
     * picking one is a decision Enter should not make, so it is *Create a new key…*, the action
     * the user started. Escape, closing the dialog, and *Cancel* abandon the extraction.
     *
     * No dialog at all when no key holds the text: that path stays exactly what it was.
     */
    fun choose(project: Project, text: String, existingKeys: List<String>): KeyChoice {
        if (existingKeys.isEmpty()) return KeyChoice.New
        val offered = existingKeys.take(MAX_OFFERED_KEYS)
        val buttons = offered.map { PluginBundle.message("action.intention.extract.key.reuse.option", it) } +
            PluginBundle.message("action.intention.extract.key.reuse.create") +
            Messages.getCancelButton()
        val hidden = existingKeys.size - offered.size
        val message = PluginBundle.message("action.intention.extract.key.reuse.message", text) +
            (if (hidden > 0) "\n" + PluginBundle.message("action.intention.extract.key.reuse.more", hidden) else "")
        val index = Messages.showDialog(
            project,
            message,
            PluginBundle.message("action.intention.extract.key.reuse.title"),
            buttons.toTypedArray(),
            if (offered.size == 1) 0 else offered.size,
            Messages.getQuestionIcon()
        )
        return when (index) {
            in offered.indices -> KeyChoice.Existing(offered[index])
            offered.size -> KeyChoice.New
            else -> KeyChoice.Cancelled
        }
    }

    private fun isValidKey() = object : InputValidator {
        override fun checkInput(inputString: String?): Boolean {
            return (inputString ?: "").isNotEmpty()
        }
        override fun canClose(inputString: String?): Boolean = true
    }

    private companion object {
        /** Existing keys offered as buttons; past it, the dialog would outgrow the screen. */
        const val MAX_OFFERED_KEYS = 3
    }
}