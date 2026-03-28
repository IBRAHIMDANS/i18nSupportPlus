package com.ibrahimdans.i18n.plugin.ide.dialog

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.project.Project
import java.awt.BorderLayout
import javax.swing.*

/**
 * Placeholder strategy to apply in remaining locales after a key is created in selected locale(s).
 */
enum class PlaceholderStrategy {
    /** Use an empty string as the placeholder value */
    EMPTY_STRING,
    /** Use the i18n key itself as the placeholder value */
    KEY_NAME,
    /** Copy the value entered for the primary (default) locale */
    COPY_FROM_DEFAULT
}

/**
 * Dialog to choose a placeholder strategy for locales that were not part of the initial key creation.
 *
 * Shown after a key has been created in one or more locales, so the user can decide what to put
 * in the remaining locales: an empty string, the key name, or the same value as the default locale.
 *
 * @param project  the current project
 * @param keyName  the i18n key that was just created (used as label and potential placeholder value)
 * @param defaultValue  the translation value that was entered for the primary locale
 * @param remainingLocales  display names of the locales that will receive the placeholder
 */
class BatchPlaceholderDialog(
    project: Project,
    private val keyName: String,
    private val defaultValue: String,
    private val remainingLocales: List<String>
) : DialogWrapper(project) {

    private val emptyRadio = JRadioButton("Empty string (\"\")")
    private val keyRadio = JRadioButton("Key name (\"$keyName\")")
    private val copyRadio = JRadioButton("Copy from primary locale (\"${defaultValue.take(40)}${if (defaultValue.length > 40) "…" else ""}\")")

    init {
        title = "Fill Remaining Locales"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        // Explanation label
        val localesList = remainingLocales.take(5).joinToString(", ")
        val more = if (remainingLocales.size > 5) " and ${remainingLocales.size - 5} more" else ""
        val infoLabel = JLabel("<html>Choose a placeholder value for the remaining locale(s):<br><b>$localesList$more</b></html>")
        infoLabel.border = BorderFactory.createEmptyBorder(0, 0, 12, 0)
        panel.add(infoLabel)

        // Radio button group
        val group = ButtonGroup()

        emptyRadio.isSelected = true
        group.add(emptyRadio)
        panel.add(emptyRadio)

        group.add(keyRadio)
        panel.add(keyRadio)

        group.add(copyRadio)
        panel.add(copyRadio)

        // Separator + cancel note
        panel.add(Box.createVerticalStrut(8))
        val cancelNote = JLabel("<html><i>Cancel to leave remaining locales unchanged.</i></html>")
        panel.add(cancelNote)

        val wrapper = JPanel(BorderLayout())
        wrapper.add(panel, BorderLayout.NORTH)
        return wrapper
    }

    /**
     * Returns the strategy chosen by the user, or null if dialog was cancelled.
     * Call only after [showAndGet] returns true.
     */
    fun selectedStrategy(): PlaceholderStrategy = when {
        keyRadio.isSelected -> PlaceholderStrategy.KEY_NAME
        copyRadio.isSelected -> PlaceholderStrategy.COPY_FROM_DEFAULT
        else -> PlaceholderStrategy.EMPTY_STRING
    }
}
