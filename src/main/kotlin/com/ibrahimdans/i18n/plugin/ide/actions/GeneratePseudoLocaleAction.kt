package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileKeys
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.PseudoLocalizer
import com.ibrahimdans.i18n.plugin.utils.ReferenceLocale
import com.ibrahimdans.i18n.plugin.utils.hasRecognizedLocale
import com.ibrahimdans.i18n.plugin.utils.isLocaleNamedFile
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile

/**
 * Writes a pseudo-locale next to the reference locale: every translation of it run through
 * [PseudoLocalizer], so that the application, switched to that locale, shows what was never
 * extracted (plain text) and what overflows (text 30 % longer).
 *
 * A file rather than a virtual locale of the preview: the file works in the running application
 * too, and every editor feature already reads it. `locales/en/common.json` gives
 * `locales/en-XA/common.json`, `locales/en.json` gives `locales/en-XA.json`; the structure is kept,
 * only string values change. Regenerating overwrites the files.
 *
 * The default name, `en-XA`, is the pseudo-locale Android and Chrome use. Its region is no ISO
 * country, so the plugin does not take it for a locale: it stays out of the table, the tree and
 * the statistics. A name the plugin does recognise (`qps`, say, is not; `fr-CA` is) would show up
 * there as an ordinary locale — the dialog says so.
 *
 * JSON and YAML files of the reference locale are read; any other format is skipped.
 */
class GeneratePseudoLocaleAction : AnAction() {

    /** A file to write: its [name] in [directory] (created when missing), with [text]. */
    internal data class PseudoFile(val directory: List<String>, val anchor: VirtualFile, val name: String, val text: String) {
        val path: String get() = (listOf(anchor.path) + directory + name).joinToString("/")
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val locale = Messages.showInputDialog(
            project,
            PluginBundle.message("action.generate.pseudo.locale.prompt"),
            PluginBundle.message("action.generate.pseudo.locale.title"),
            null,
            DEFAULT_LOCALE,
            null
        )?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val files = ReadAction.compute<List<PseudoFile>, RuntimeException> { plan(project, locale) }
        val written = write(project, files)
        val content = if (written.isEmpty()) PluginBundle.message("action.generate.pseudo.locale.none")
        else PluginBundle.message("action.generate.pseudo.locale.done", written.joinToString("<br/>") { it.removePrefix("${project.basePath}/") })
        NotificationGroupManager.getInstance()
            .getNotificationGroup("i18n Support Plus")
            .createNotification(
                PluginBundle.message("action.generate.pseudo.locale.title"),
                content,
                if (written.isEmpty()) NotificationType.WARNING else NotificationType.INFORMATION
            )
            .notify(project)
    }

    @Suppress("UNCHECKED_CAST")
    internal companion object {

        const val DEFAULT_LOCALE = "en-XA"

        /**
         * The pseudo-locale files [locale] gets: one per JSON or YAML file of each module's
         * reference locale (the project's when no module is configured). Needs a read action.
         */
        fun plan(project: Project, locale: String): List<PseudoFile> {
            val config = Settings.getInstance(project).config()
            val modules: List<ModuleConfig?> = config.modules.filter { it.rootDirectory.isNotBlank() }.ifEmpty { listOf(null) }
            return modules.flatMap { module ->
                val sources = ModuleSources.sourcesOf(project, module).filter { it.hasRecognizedLocale() }
                val reference = ReferenceLocale.of(module, config, sources.map { it.localeLabel() }.distinct())
                    ?: return@flatMap emptyList()
                sources.filter { it.localeLabel() == reference }.mapNotNull { pseudoFileOf(it, reference, locale) }
            }.distinctBy { it.path }
        }

        /** Writes [files] in a single command; the paths written. */
        fun write(project: Project, files: List<PseudoFile>): List<String> {
            val written = mutableListOf<String>()
            if (files.isEmpty()) return written
            WriteCommandAction.runWriteCommandAction(project, PluginBundle.message("action.generate.pseudo.locale.title"), null, {
                for (file in files) {
                    var directory = file.anchor
                    for (segment in file.directory) {
                        directory = directory.findChild(segment) ?: directory.createChildDirectory(this, segment)
                    }
                    val target = directory.findChild(file.name) ?: directory.createChildData(this, file.name)
                    VfsUtil.saveText(target, file.text)
                    written += target.path
                }
            })
            return written
        }

        /**
         * Where [source], a file of [reference], is mirrored for [locale], and what it holds; null
         * for a format other than JSON or YAML, or a path in which no part names the locale.
         */
        private fun pseudoFileOf(source: LocalizationSource, reference: String, locale: String): PseudoFile? {
            val psiFile = ModuleSources.readableFile(source) ?: return null
            val file = psiFile.virtualFile ?: return null
            val yaml = file.extension?.lowercase() in YAML_EXTENSIONS
            val leaves = TranslationFileKeys.translationLeaves(psiFile).mapValues { (_, value) -> PseudoLocalizer.localize(value) }
            val text = if (yaml) toYaml(leaves) else toJson(leaves)
            if (source.isLocaleNamedFile()) {
                val parent = file.parent ?: return null
                return PseudoFile(emptyList(), parent, "$locale.${file.extension}", text)
            }
            // `locales/en/common.json`, or deeper (`en/LC_MESSAGES/…`): the folder named after the locale.
            val below = mutableListOf<String>()
            var directory = file.parent
            while (directory != null && directory.name != reference) {
                below.add(0, directory.name)
                directory = directory.parent
            }
            val anchor = directory?.parent ?: return null
            return PseudoFile(listOf(locale) + below, anchor, file.name, text)
        }

        private val YAML_EXTENSIONS = setOf("yml", "yaml")

        /** [leaves] nested back into objects, in their order. */
        private fun tree(leaves: Map<List<String>, String>): Map<String, Any> {
            val root = linkedMapOf<String, Any>()
            for ((path, value) in leaves) {
                var node = root
                for (segment in path.dropLast(1)) {
                    node = node.getOrPut(segment) { linkedMapOf<String, Any>() } as? LinkedHashMap<String, Any> ?: break
                }
                node[path.last()] = value
            }
            return root
        }

        internal fun toJson(leaves: Map<List<String>, String>): String {
            val out = StringBuilder()
            fun write(node: Map<String, Any>, indent: String) {
                out.append("{\n")
                node.entries.forEachIndexed { index, (key, value) ->
                    out.append(indent).append("  ").append(jsonString(key)).append(": ")
                    if (value is Map<*, *>) write(value as Map<String, Any>, "$indent  ") else out.append(jsonString(value as String))
                    out.append(if (index < node.size - 1) ",\n" else "\n")
                }
                out.append(indent).append("}")
            }
            write(tree(leaves), "")
            return out.append("\n").toString()
        }

        internal fun toYaml(leaves: Map<List<String>, String>): String {
            val out = StringBuilder()
            fun write(node: Map<String, Any>, indent: String) {
                for ((key, value) in node) {
                    out.append(indent).append(jsonString(key)).append(':')
                    if (value is Map<*, *>) {
                        out.append('\n')
                        write(value as Map<String, Any>, "$indent  ")
                    } else {
                        out.append(' ').append(jsonString(value as String)).append('\n')
                    }
                }
            }
            write(tree(leaves), "")
            return out.toString()
        }

        /** [text] as a double-quoted string, valid in JSON and in YAML alike. */
        private fun jsonString(text: String): String {
            val out = StringBuilder("\"")
            for (char in text) {
                when {
                    char == '"' -> out.append("\\\"")
                    char == '\\' -> out.append("\\\\")
                    char == '\n' -> out.append("\\n")
                    char == '\t' -> out.append("\\t")
                    char == '\r' -> out.append("\\r")
                    char < ' ' -> out.append("\\u%04x".format(char.code))
                    else -> out.append(char)
                }
            }
            return out.append('"').toString()
        }
    }
}
