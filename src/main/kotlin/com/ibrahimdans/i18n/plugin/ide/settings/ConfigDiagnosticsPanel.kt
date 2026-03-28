package com.ibrahimdans.i18n.plugin.ide.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import java.awt.BorderLayout
import java.awt.Font
import java.io.File
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Diagnostic panel displayed at the top of the Settings panel when the config has issues.
 * Performs checks on the current [Config] and shows warnings/errors inline.
 *
 * Call [refresh] whenever the config may have changed (e.g. on [SettingsPanel.reset]).
 */
class ConfigDiagnosticsPanel(private val project: Project) : JPanel() {

    private val messagesPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    init {
        layout = BorderLayout(8, 4)
        background = JBColor(0xFFF3CD, 0x5C4A00)  // yellow warning background (light/dark)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor(0xFFC107, 0xFFAB00), 1),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)
        )

        val iconLabel = JLabel("\u26A0").apply {  // ⚠ Unicode warning sign
            font = font.deriveFont(Font.BOLD, 16f)
            border = BorderFactory.createEmptyBorder(0, 0, 0, 6)
        }
        add(iconLabel, BorderLayout.WEST)
        add(messagesPanel, BorderLayout.CENTER)

        isVisible = false
    }

    /**
     * Runs all diagnostic checks against [config] and updates the panel visibility and messages.
     */
    fun refresh(config: Config) {
        val issues = buildList {
            checkTranslationsRoot(config)
            checkDefaultNs(config)
            checkKeySeparator(config)
        }

        messagesPanel.removeAll()
        if (issues.isEmpty()) {
            isVisible = false
        } else {
            issues.forEach { message ->
                messagesPanel.add(JLabel(message).apply {
                    border = BorderFactory.createEmptyBorder(1, 0, 1, 0)
                })
            }
            isVisible = true
        }
        revalidate()
        repaint()
    }

    // --- individual checks ---

    private fun MutableList<String>.checkTranslationsRoot(config: Config) {
        val root = config.translationsRoot
        if (root.isBlank()) return

        val basePath = project.basePath ?: ""
        val dirPath = if (root.startsWith("/")) root else "$basePath/$root"
        val dir = LocalFileSystem.getInstance().findFileByPath(dirPath)
            ?: LocalFileSystem.getInstance().findFileByIoFile(File(dirPath))

        if (dir == null || !dir.exists() || !dir.isDirectory) {
            add("Translation directory not found: $root")
            return
        }

        // check for .json/.yaml files inside the directory (recursive)
        val hasTranslationFiles = dir.children.any { child ->
            !child.isDirectory && (child.extension == "json" || child.extension == "yaml" || child.extension == "yml")
        } || dir.children.filter { it.isDirectory }.any { locale ->
            locale.children.any { file ->
                !file.isDirectory && (file.extension == "json" || file.extension == "yaml" || file.extension == "yml")
            }
        }

        if (!hasTranslationFiles) {
            add("No translation files found in $root")
        }
    }

    private fun MutableList<String>.checkDefaultNs(config: Config) {
        if (config.defaultNs.isBlank()) {
            add("Default namespace is empty")
        }
    }

    private fun MutableList<String>.checkKeySeparator(config: Config) {
        if (config.keySeparator.isBlank()) {
            add("Key separator is empty, using flat keys only")
        }
    }
}
