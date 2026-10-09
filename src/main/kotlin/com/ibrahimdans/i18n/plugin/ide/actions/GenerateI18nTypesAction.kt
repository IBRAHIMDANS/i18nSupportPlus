package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.extensions.technology.i18next.I18nextTypesGenerator
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileKeys
import com.ibrahimdans.i18n.plugin.ide.settings.FrameworkDetector
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.utils.ReferenceLocale
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.hasRecognizedLocale
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * Writes an `i18next.d.ts` per i18next module, typing `t('key')` from the reference locale's keys
 * so that `tsc` — in CI, or for a teammate without the plugin — rejects a key that does not exist.
 *
 * The file is meant to be committed, and is regenerated on demand only: no listener rewrites it
 * behind the user's back, so it changes in a commit when someone decides it should.
 *
 * It lands in `<module root>/src/@types/i18next.d.ts` — the place i18next's documentation uses, and
 * one a `tsconfig` including `src` already compiles — or `<module root>/@types/i18next.d.ts` when
 * the module has no `src` folder. The module root is the module's root directory, or the project
 * directory when no module is configured.
 *
 * Offered for i18next modules only: those whose preset is `i18next`, or which have no preset and
 * declare i18next in their `package.json`.
 */
class GenerateI18nTypesAction : AnAction() {

    /** A module to write a declaration for, rooted at [rootPath] (absolute). */
    internal data class Target(val module: ModuleConfig?, val rootPath: String)

    /** What reading a target's reference locale gave: a declaration to write, or why there is none. */
    internal sealed interface Reading {
        /**
         * The [text] to write. [unread] names the namespaces of the reference locale held in a
         * format the generator does not read (PO, a JS object…): their keys are missing from [text].
         */
        data class Declaration(val text: String, val unread: List<String>) : Reading

        /**
         * No file of the reference locale could be read: [wanted] matches none of the [available]
         * locales, or only files in a format the generator does not read. Writing anyway would
         * declare `resources: {}`, which turns every `t('…')` of the module into a type error.
         */
        data class NoReference(val wanted: String, val available: List<String>) : Reading
    }

    /** What was read for a target, and the hand-written declarations of its module it would contradict. */
    private data class Plan(val reading: Reading, val competing: List<String>)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && targetsOf(project).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val targets = targetsOf(project)
        if (targets.isEmpty()) return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, PluginBundle.message("action.generate.types.progress"), false) {
            override fun run(indicator: ProgressIndicator) {
                // Waits for smart mode like the deprecated `DumbService.runReadActionInSmartMode`, but
                // yields to a write action and starts the reading over instead of holding it back.
                val contents = ReadAction.nonBlocking<Map<Target, Plan>> {
                    targets.associateWith { target ->
                        val reading = readReference(project, target)
                        // Nothing will be written without a reference: no reason to ask about the others.
                        val competing = if (reading is Reading.Declaration) competingDeclarations(project, target) else emptyList()
                        Plan(reading, competing)
                    }
                }.inSmartMode(project).executeSynchronously()
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) write(project, contents)
                }
            }
        })
    }

    /**
     * Writes each declaration, asking before replacing a file the plugin did not generate, and
     * before writing next to a hand-written declaration: two `CustomTypeOptions` contradict
     * each other, and only the user knows which one to keep — nothing is deleted for them.
     *
     * A target whose reference locale could not be read is skipped, and a warning says why: the
     * file it would get, or keep, must never be a declaration without keys.
     */
    private fun write(project: Project, contents: Map<Target, Plan>) {
        val written = mutableListOf<String>()
        val unread = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for ((target, plan) in contents) {
            val path = targetFileOf(target)
            val relativePath = path.removePrefix("${project.basePath}/")
            val declaration = when (val reading = plan.reading) {
                is Reading.Declaration -> reading
                is Reading.NoReference -> {
                    skipped += PluginBundle.message(
                        "action.generate.types.no.reference",
                        relativePath,
                        reading.wanted,
                        reading.available.joinToString(", ").ifEmpty { "—" }
                    )
                    continue
                }
            }
            if (plan.competing.isNotEmpty()) {
                val answer = Messages.showYesNoDialog(
                    project,
                    PluginBundle.message(
                        "action.generate.types.competing",
                        plan.competing.joinToString("\n") { it.removePrefix("${project.basePath}/") }
                    ),
                    PluginBundle.message("action.generate.types.title"),
                    Messages.getWarningIcon()
                )
                if (answer != Messages.YES) continue
            }
            val existing = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
            if (existing != null && !VfsUtilCore.loadText(existing).startsWith(I18nextTypesGenerator.GENERATED_BY)) {
                val answer = Messages.showYesNoDialog(
                    project,
                    PluginBundle.message("action.generate.types.overwrite", path),
                    PluginBundle.message("action.generate.types.title"),
                    Messages.getWarningIcon()
                )
                if (answer != Messages.YES) continue
            }
            WriteCommandAction.runWriteCommandAction(project, PluginBundle.message("action.generate.types.title"), null, {
                val directory = VfsUtil.createDirectoryIfMissing(path.substringBeforeLast('/')) ?: return@runWriteCommandAction
                val file = directory.findChild(FILE_NAME) ?: directory.createChildData(this, FILE_NAME)
                VfsUtil.saveText(file, declaration.text)
                written += relativePath
                if (declaration.unread.isNotEmpty()) {
                    unread += PluginBundle.message("action.generate.types.unread", relativePath, declaration.unread.joinToString(", "))
                }
            })
        }
        if (skipped.isNotEmpty()) notify(project, skipped.joinToString("<br/>"), NotificationType.WARNING)
        if (written.isEmpty()) return
        val done = PluginBundle.message("action.generate.types.done", written.joinToString("<br/>"))
        if (unread.isEmpty()) notify(project, done, NotificationType.INFORMATION)
        else notify(project, (listOf(done) + unread).joinToString("<br/><br/>"), NotificationType.WARNING)
    }

    private fun notify(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("i18n Support Plus")
            .createNotification(PluginBundle.message("action.generate.types.title"), content, type)
            .notify(project)
    }

    internal companion object {
        const val FILE_NAME = "i18next.d.ts"
        private const val I18NEXT = "i18next"

        /**
         * The modules to write a declaration for: every i18next module with a root directory, or
         * the project itself when none is configured and it uses i18next.
         */
        fun targetsOf(project: Project): List<Target> {
            val basePath = project.basePath ?: return emptyList()
            val modules = Settings.getInstance(project).config().modules.filter { it.rootDirectory.isNotBlank() }
            if (modules.isEmpty()) {
                return if (declaresI18next(basePath)) listOf(Target(null, basePath)) else emptyList()
            }
            return modules
                .map { Target(it, "$basePath/${it.rootDirectory.trim().trim('/')}".trimEnd('/')) }
                .filter { target ->
                    val preset = target.module?.preset?.trim().orEmpty()
                    preset == I18NEXT || (preset.isEmpty() && declaresI18next(target.rootPath))
                }
        }

        /** Where [target]'s declaration is written. */
        fun targetFileOf(target: Target): String {
            val src = LocalFileSystem.getInstance().findFileByPath("${target.rootPath}/src")
            val folder = if (src != null && src.isDirectory) "${target.rootPath}/src/@types" else "${target.rootPath}/@types"
            return "$folder/$FILE_NAME"
        }

        private fun declaresI18next(rootPath: String): Boolean {
            val packageJson = LocalFileSystem.getInstance().findFileByPath("$rootPath/package.json") ?: return false
            return I18NEXT in FrameworkDetector.detect(VfsUtilCore.loadText(packageJson))
        }

        /**
         * The declaration of [target]'s keys in its reference locale, or why there is none — see
         * [Reading]. Needs a read action and indexes.
         */
        internal fun readReference(project: Project, target: Target): Reading {
            val config = Settings.getInstance(project).config()
            val defaultNamespace = config.defaultNamespaces().first()
            val sources = ModuleSources.sourcesOf(project, target.module).filter { it.hasRecognizedLocale() }
            val wanted = ReferenceLocale.wanted(target.module, config)
            val available = sources.map { it.localeLabel() }.distinct()
            val locale = ReferenceLocale.of(target.module, config, available)
                ?: return Reading.NoReference(wanted, available.sorted())
            val namespaces = sortedMapOf<String, MutableList<List<String>>>()
            val unread = sortedSetOf<String>()
            sources.filter { it.localeLabel() == locale }.forEach { source ->
                val namespace = TranslationDataLoader.extractNamespace(source, defaultNamespace)
                val file = ModuleSources.readableFile(source)
                if (file == null) unread += namespace
                else namespaces.getOrPut(namespace) { mutableListOf() } += TranslationFileKeys.translationLeaves(file).keys
            }
            if (namespaces.isEmpty()) return Reading.NoReference(wanted, available.sorted())
            val keySeparator = if (config.usesFlatKeys()) null else config.keySeparator
            return Reading.Declaration(
                I18nextTypesGenerator.generate(namespaces, defaultNamespace, keySeparator, config.nsSeparator),
                (unread - namespaces.keys).toList()
            )
        }

        /**
         * The `.d.ts` files under [target]'s root, other than the generated one, that already type
         * i18next. Needs a read action and indexes.
         */
        private fun competingDeclarations(project: Project, target: Target): List<String> {
            val ownPath = targetFileOf(target)
            return FilenameIndex.getAllFilesByExt(project, "ts", GlobalSearchScope.projectScope(project))
                .filter { it.name.endsWith(".d.ts") && it.path != ownPath && it.path.startsWith("${target.rootPath}/") }
                .filter { "/node_modules/" !in it.path }
                .filter { I18nextTypesGenerator.declaresCustomTypes(VfsUtilCore.loadText(it)) }
                .map { it.path }
                .sorted()
        }
    }
}
