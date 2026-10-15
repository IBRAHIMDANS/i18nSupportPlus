@file:Suppress("FunctionName")

package com.ibrahimdans.i18n.plugin.mcp

import com.google.gson.GsonBuilder
import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpCallInfoOrNull
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * The plugin's tools on the IDE's MCP server, for an agent — Codex or Claude in AI Assistant (with
 * *Pass IntelliJ MCP server* checked), Codex CLI, Claude Code — to translate through the plugin
 * rather than by rewriting files. [TranslationAgentTools] does the work; this class only adapts the
 * protocol, and is the one class loaded with the MCP server plugin.
 *
 * Built against 2025.3: in 2025.2 the server keeps the calling project elsewhere and its error has
 * another constructor, so a tool there answers that it needs 2025.3 instead of failing.
 */
class I18nMcpToolset : McpToolset {

    @McpTool(name = "i18n_list_modules")
    @McpDescription(
        description = "Lists the project's translation modules: name, reference locale (translated from), locales, " +
            "and whether translation reviews are on. Call it first to know the module and locale names."
    )
    suspend fun i18n_list_modules(): String = call { tools, _ -> readAction { tools.modules() } }

    @McpTool(name = "i18n_missing_translations")
    @McpDescription(
        description = "Lists the keys a locale lacks, with the reference text to translate, sorted by key. Plural forms " +
            "the locale needs are listed one by one; 'pluralFromOther' means the source has no form of that category " +
            "and gives its 'other'. Items with 'skipped' (ICU plurals) cannot be written by i18n_write_translations."
    )
    suspend fun i18n_missing_translations(
        @McpDescription(description = "Locale to fill, as listed by i18n_list_modules, e.g. 'de'") locale: String,
        @McpDescription(description = "Module name from i18n_list_modules; omit when there is only one") module: String? = null,
        @McpDescription(description = "At most this many keys; 'total' tells how many are missing in all") limit: Int = 100,
    ): String = call { tools, _ -> readAction { tools.missing(module, locale, limit) } }

    @McpTool(name = "i18n_write_translations")
    @McpDescription(
        description = "Writes translations of missing keys into a locale, in one command the user can undo. Only missing " +
            "values are written, never an existing one. Each value must keep exactly the placeholders and tags of its " +
            "reference text ({{name}}, {name}, %s, <0>…</0>), which may move. Refused keys come back with the reason; " +
            "fix and send them again. Values are marked to review when the module keeps reviews."
    )
    suspend fun i18n_write_translations(
        @McpDescription(description = "Locale to write, as listed by i18n_list_modules") locale: String,
        @McpDescription(description = "Key to translated text, keys as returned by i18n_missing_translations") values: Map<String, String>,
        @McpDescription(description = "Module name from i18n_list_modules; omit when there is only one") module: String? = null,
    ): String = call { tools, client -> withContext(Dispatchers.EDT) { tools.write(module, locale, values, client) } }

    /** [block] run for the calling project and MCP client, its result as JSON; a refusal as the MCP error the agent reads. */
    private suspend fun call(block: suspend (TranslationAgentTools, String) -> Any): String {
        val (project, client) = caller() ?: return REQUIRES_2025_3
        val result = try {
            block(TranslationAgentTools(project), client)
        } catch (refused: TranslationAgentTools.Refused) {
            throw McpExpectedError(refused.message.orEmpty())
        }
        return GSON.toJson(result)
    }

    /** The calling project and client name; null in 2025.2, where neither is found where this was built to look. */
    private suspend fun caller(): Pair<Project, String>? {
        val context = currentCoroutineContext()
        return try {
            context.project to context.mcpCallInfoOrNull?.clientInfo?.name.orEmpty()
        } catch (_: LinkageError) {
            null
        }
    }

    private companion object {
        val GSON = GsonBuilder().disableHtmlEscaping().create()
        const val REQUIRES_2025_3 = "The i18n Support Plus tools require IntelliJ-based IDEs 2025.3 or later."
    }
}
