package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.utils.ModulePresets
import com.intellij.lang.javascript.JavascriptLanguage
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper

/**
 * What an extraction writes in a Paraglide JS project: `m.greeting({ name })` rather than
 * `t('greeting', { name })`, and the import of the message object when the file lacks it.
 *
 * Paraglide applies where the module's preset says so, or — in a module without preset — once the
 * file imports Paraglide's message object; the call then uses the name the file binds it to
 * (`msg` under `import { m as msg }`).
 */
internal object ParaglideCall {

    const val PRESET = "paraglide"
    private const val DEFAULT_NAME = "m"
    private const val DEFAULT_SPECIFIER = "./paraglide/messages.js"
    private val IDENTIFIER = Regex("[A-Za-z_$][\\w$]*")
    private val IMPORT = Regex("""from\s*['"]([^'"]*paraglide/messages(?:\.js)?)['"]""")
    private val LAST_IMPORT = Regex("""(?m)^import\s[^\n]*\n(?!import\s)""")
    private val SCRIPT_TAG = Regex("""<script[^>]*>\n?""")

    /** Whether an extraction at [element] writes a Paraglide call. */
    fun applies(element: PsiElement): Boolean {
        val preset = ModulePresets.presetOf(element)
        return preset == PRESET || (preset == null && ParaglideImports.messageObjectNames(element).isNotEmpty())
    }

    /** The name [element]'s file binds the message object to: `m` when it imports it so, or none. */
    fun objectName(element: PsiElement): String {
        val names = ParaglideImports.messageObjectNames(element)
        return if (DEFAULT_NAME in names || names.isEmpty()) DEFAULT_NAME else names.first()
    }

    /** `m.greeting({ name })`; `m["user.profile.title"]()` for a key that is no identifier. */
    fun call(messages: String, key: String, inputs: String): String {
        val member = if (IDENTIFIER.matches(key)) "$messages.$key" else "$messages[\"$key\"]"
        return "$member($inputs)"
    }

    /**
     * Adds `import { m } from '…'` to [file] when it imports no message object: after its last
     * import, or inside the `<script>` of a Svelte or Vue component. The specifier is the one the
     * project already imports Paraglide with (`$lib/paraglide/messages.js`), else the relative path
     * to the compiled `messages.js`, else `./paraglide/messages.js`.
     */
    fun addImport(file: PsiFile, document: Document) {
        if (ParaglideImports.messageObjectNames(file).isNotEmpty()) return
        val statement = "import { $DEFAULT_NAME } from '${specifier(file)}';\n"
        val text = document.charsSequence
        val offset = if (file.language.isKindOf(JavascriptLanguage)) {
            LAST_IMPORT.findAll(text).lastOrNull()?.range?.last?.plus(1) ?: 0
        } else {
            SCRIPT_TAG.find(text)?.range?.last?.plus(1) ?: return
        }
        document.insertString(offset, statement)
    }

    private fun specifier(file: PsiFile): String {
        val project = file.project
        val scope = GlobalSearchScope.projectScope(project)
        val written = mutableListOf<String>()
        // The specifier is a string: a search of the code alone would never reach it.
        PsiSearchHelper.getInstance(project).processAllFilesWithWordInLiterals("paraglide", scope) { other ->
            if (other != file) IMPORT.find(other.viewProvider.contents)?.let { written += it.groupValues[1] }
            written.none { !it.startsWith(".") }
        }
        // An alias (`$lib/…`, `@/…`) reads the same from every file; a relative path does not.
        written.firstOrNull { !it.startsWith(".") }?.let { return it }
        val from = file.virtualFile?.parent ?: return DEFAULT_SPECIFIER
        val compiled = compiledMessages(project, scope) ?: return DEFAULT_SPECIFIER
        val relative = VfsUtilCore.findRelativePath(from, compiled, '/') ?: return DEFAULT_SPECIFIER
        return if (relative.startsWith(".")) relative else "./$relative"
    }

    /** The `messages.js` Paraglide compiled: the one next to a `runtime.js`, outside `node_modules`. */
    private fun compiledMessages(project: Project, scope: GlobalSearchScope): VirtualFile? =
        FilenameIndex.getVirtualFilesByName("runtime.js", scope)
            .filter { "/node_modules/" !in it.path }
            .firstNotNullOfOrNull { it.parent?.findChild("messages.js") }
}
