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
 * Built against 2025.3, whose API 2025.2.6 already has: in the first 2025.2 builds the server keeps the
 * calling project elsewhere and its error has another constructor, so a tool there answers that the IDE
 * needs an update instead of failing.
 */
class I18nMcpToolset : McpToolset {

    @McpTool(name = "i18n_list_modules")
    @McpDescription(
        description = "Lists the project's translation modules: name, reference locale (translated from), locales, " +
            "and whether translation reviews are on. Call it first to know the module and locale names. 'notChecked' " +
            "lists the locales the project leaves out of its missing-key checks (in progress or abandoned): do not " +
            "fill them unless asked to."
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

    @McpTool(name = "i18n_stale_translations")
    @McpDescription(
        description = "Lists the stale translations of a locale, sorted by key: values still the ones reviewed while the " +
            "reference text of their key changed since. Each item gives 'source', the reference text as it reads now, " +
            "to translate again, and 'current', the value it would replace. Write the new values with " +
            "i18n_write_translations. Refused in a module without reviews (see i18n_list_modules): staleness is read " +
            "from the source each review recorded. Items with 'skipped' (ICU plurals) cannot be written."
    )
    suspend fun i18n_stale_translations(
        @McpDescription(description = "Locale to check, as listed by i18n_list_modules, e.g. 'de'") locale: String,
        @McpDescription(description = "Module name from i18n_list_modules; omit when there is only one") module: String? = null,
        @McpDescription(description = "At most this many keys; 'total' tells how many are stale in all") limit: Int = 100,
    ): String = call { tools, _ -> readAction { tools.stale(module, locale, limit) } }

    @McpTool(name = "i18n_write_translations")
    @McpDescription(
        description = "Writes translations into a locale, in one command the user can undo. A value is written where the " +
            "key is missing, or replaces the existing one only while it is stale when written (see " +
            "i18n_stale_translations); any other existing value is never replaced, and a stale one fixed since it was " +
            "listed is refused. Each value must keep exactly the placeholders and tags of its reference text ({{name}}, " +
            "{name}, %s, <0>…</0>), which may move. Refused keys come back with the reason; fix and send them again. " +
            "Values are marked to review when the module keeps reviews, with the reference text they were translated from."
    )
    suspend fun i18n_write_translations(
        @McpDescription(description = "Locale to write, as listed by i18n_list_modules") locale: String,
        @McpDescription(description = "Key to translated text, keys as returned by i18n_missing_translations or i18n_stale_translations") values: Map<String, String>,
        @McpDescription(description = "Module name from i18n_list_modules; omit when there is only one") module: String? = null,
    ): String = call { tools, client -> withContext(Dispatchers.EDT) { tools.write(module, locale, values, client) } }

    /** [block] run for the calling project and MCP client, its result as JSON; a refusal as the MCP error the agent reads. */
    private suspend fun call(block: suspend (TranslationAgentTools, String) -> Any): String {
        val (project, client) = caller() ?: return REQUIRES_UPDATE
        val result = try {
            block(TranslationAgentTools(project), client)
        } catch (refused: TranslationAgentTools.Refused) {
            throw McpExpectedError(refused.message.orEmpty())
        }
        return GSON.toJson(result)
    }

    /** The calling project and client name; null in the first 2025.2 builds, where neither is found where this was built to look. */
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
        const val REQUIRES_UPDATE = "The i18n Support Plus tools need a more recent IDE: 2025.2.6 or later."
    }
}
