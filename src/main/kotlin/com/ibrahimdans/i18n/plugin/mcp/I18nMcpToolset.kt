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
 * rather than by rewriting files. [TranslationAgentTools] and [KeyMaintenanceTools] do the work; this
 * class only adapts the protocol, and is the one class loaded with the MCP server plugin.
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
        description = "Use this instead of reading the translation files to find what a locale lacks. " +
            "Lists the keys a locale lacks, with the reference text to translate, sorted by key. Plural forms " +
            "the locale needs are listed one by one; 'pluralFromOther' means the source has no form of that category " +
            "and gives its 'other'. An ICU message ({count, plural, …}, select, selectordinal) is listed whole: write it " +
            "with the branches the locale needs. Items with 'skipped' (an ICU form the plugin does not handle) cannot " +
            "be written by i18n_write_translations. 'glossary' (term → translation, a term mapped to itself stays as " +
            "it is) and 'examples' (reference text → translation already in the locale) give the terminology in " +
            "place: translate with the same words."
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
            "from the source each review recorded. Items with 'skipped' (an ICU form the plugin does not handle) cannot " +
            "be written."
    )
    suspend fun i18n_stale_translations(
        @McpDescription(description = "Locale to check, as listed by i18n_list_modules, e.g. 'de'") locale: String,
        @McpDescription(description = "Module name from i18n_list_modules; omit when there is only one") module: String? = null,
        @McpDescription(description = "At most this many keys; 'total' tells how many are stale in all") limit: Int = 100,
    ): String = call { tools, _ -> readAction { tools.stale(module, locale, limit) } }

    @McpTool(name = "i18n_write_translations")
    @McpDescription(
        description = "Use this instead of editing the translation files to add or update translations. " +
            "Writes translations into a locale, in one command the user can undo. A value is written where the " +
            "key is missing, or replaces the existing one only while it is stale when written (see " +
            "i18n_stale_translations); any other existing value is never replaced, and a stale one fixed since it was " +
            "listed is refused. Each value must keep exactly the placeholders and tags of its reference text ({{name}}, " +
            "{name}, %s, <0>…</0>), which may move. An ICU plural must carry the plural categories of the locale's " +
            "language (ru: one, few, many, other; ja: other) and is refused otherwise. Not written, and listed as " +
            "'skipped': a selectordinal that keeps the source's branches, an ICU message nested more than one level, an " +
            "ICU message inside an i18next plural group. Refused keys come back with the reason; fix and send them again. " +
            "Values are marked to review when the module keeps reviews, with the reference text they were translated from."
    )
    suspend fun i18n_write_translations(
        @McpDescription(description = "Locale to write, as listed by i18n_list_modules") locale: String,
        @McpDescription(description = "Key to translated text, keys as returned by i18n_missing_translations or i18n_stale_translations") values: Map<String, String>,
        @McpDescription(description = "Module name from i18n_list_modules; omit when there is only one") module: String? = null,
    ): String = call { tools, client ->
        val plan = readAction { tools.planWrite(module, locale, values, client) }
        withContext(Dispatchers.EDT) { tools.applyWrite(plan) }
    }

    @McpTool(name = "i18n_check")
    @McpDescription(
        description = "Checks the translations of every module, or of one: per locale, the keys it lacks ('missing'), " +
            "the keys the reference locale does not hold ('orphans') and the blank values ('empty'), plural forms " +
            "compared with what each language needs; per locale, what is wrong with the placeholders, tags and ICU of " +
            "its values ('values'); and, with unused=true, the keys of the reference locale no code " +
            "uses ('unused'), keys kept by the project's keep list or possibly built at runtime left out. The same " +
            "check as the i18n-check command line. Locales the project does not check are left out."
    )
    suspend fun i18n_check(
        @McpDescription(description = "Module name from i18n_list_modules; omit to check every module") module: String? = null,
        @McpDescription(description = "Also search the code for the keys no code uses; slower on a large project") unused: Boolean = true,
    ): String = maintain { tools, _ ->
        val checks = readAction { tools.check(module) }
        tools.report(if (unused) withContext(Dispatchers.Default) { checks.map(tools::withUnused) } else checks)
    }

    @McpTool(name = "i18n_find_keys")
    @McpDescription(
        description = "Finds existing keys, to reuse one instead of creating its twin. 'query' is read as a key pattern " +
            "with * or ? ('common:actions.*'), else as a key prefix ('common:actions'); when no key matches, as a text " +
            "searched in the values of 'locale' (the reference locale by default), case ignored: equal values first, then " +
            "whole words, then the rest. 'match' tells which way it matched. Each key comes with its value in every " +
            "locale and 'usages', how often the code uses it ('usageNote' when no search can count it; both absent " +
            "while the IDE indexes). Call it before i18n_create_key."
    )
    suspend fun i18n_find_keys(
        @McpDescription(description = "Key pattern, key prefix or text, e.g. 'common:actions.*' or 'Save'") query: String,
        @McpDescription(description = "Module name from i18n_list_modules; omit when there is only one") module: String? = null,
        @McpDescription(description = "Locale whose values are searched; omit for the reference locale") locale: String? = null,
        @McpDescription(description = "At most this many keys; 'total' tells how many match") limit: Int = 20,
    ): String = maintain { tools, _ ->
        val found = readAction { tools.find(module, query, locale, limit) }
        withContext(Dispatchers.Default) { tools.withUsages(found) }
    }

    @McpTool(name = "i18n_key_usages")
    @McpDescription(
        description = "Lists where the code uses a key: file relative to the project, 1-based line and the line of code, " +
            "the key written whole or under a hook's namespace or key prefix. 'dynamic' says a key built at runtime " +
            "(t(`status.\${kind}`)) may also reach it, which no search lists. 'defined' says whether a translation holds it."
    )
    suspend fun i18n_key_usages(
        @McpDescription(description = "Key as i18n_check or i18n_missing_translations spell it, e.g. 'common:actions.save'") key: String,
        @McpDescription(description = "Module name from i18n_list_modules; omit when there is only one") module: String? = null,
        @McpDescription(description = "At most this many usages; 'total' tells how many there are") limit: Int = 50,
    ): String = maintain { tools, _ -> withContext(Dispatchers.Default) { tools.usages(module, key, limit) } }

    @McpTool(name = "i18n_rename_key")
    @McpDescription(
        description = "Renames a key in every locale and at every call site, in one command the user can undo, as Shift+F6 " +
            "does: only its last segment changes ('common:button.save' to 'common:button.store'); a level holding other " +
            "keys ('common:button') or a plural group ('common:item' for item_one, item_other) moves whole. Refused when " +
            "the new key exists. 'dynamicUsages' lists keys built at runtime that may spell the old key: they are not " +
            "rewritten, fix them by hand."
    )
    suspend fun i18n_rename_key(
        @McpDescription(description = "Key to rename, e.g. 'common:button.save'") key: String,
        @McpDescription(description = "The same key with its last segment renamed, e.g. 'common:button.store'") newKey: String,
        @McpDescription(description = "Module name from i18n_list_modules; omit when there is only one") module: String? = null,
    ): String = maintain { tools, _ ->
        val plan = readAction { tools.planRename(module, key, newKey) }
        withContext(Dispatchers.EDT) { tools.applyRename(plan) }
    }

    @McpTool(name = "i18n_create_key")
    @McpDescription(
        description = "Creates a key with its values, in one command the user can undo. Refused when a translation already " +
            "holds the key, when it would sit under a key holding a value or replace a level holding keys, or without a " +
            "value in the module's reference locale. A value of another locale must keep the placeholders and tags of " +
            "the reference value; refused values come back with the reason. When other keys already hold the same " +
            "reference value, nothing is created: 'existing' lists them and 'refused' says so; reuse one of them, or call " +
            "again with allowDuplicate=true when the same text needs its own key."
    )
    suspend fun i18n_create_key(
        @McpDescription(description = "Key to create, e.g. 'common:actions.archive'") key: String,
        @McpDescription(description = "Locale to text, the reference locale's included, e.g. {\"en\": \"Archive\", \"fr\": \"Archiver\"}") values: Map<String, String>,
        @McpDescription(description = "Module name from i18n_list_modules; omit when there is only one") module: String? = null,
        @McpDescription(description = "Create the key even when another key holds the same reference value") allowDuplicate: Boolean = false,
    ): String = maintain { tools, _ ->
        val plan = readAction { tools.planCreate(module, key, values, allowDuplicate) }
        plan.done ?: run {
            withContext(Dispatchers.EDT) { tools.applyCreate(plan) }
            readAction { tools.created(plan) }
        }
    }

    @McpTool(name = "i18n_delete_unused_keys")
    @McpDescription(
        description = "Deletes keys from every locale, their reviews included, in one command the user can undo. Only a key " +
            "no code uses is deleted: one still used, possibly built at runtime, or kept by the project's keep list is " +
            "refused with the reason. Pass the keys i18n_check lists as 'unused'."
    )
    suspend fun i18n_delete_unused_keys(
        @McpDescription(description = "Keys to delete, as i18n_check spells them") keys: List<String>,
        @McpDescription(description = "Module name from i18n_list_modules; omit when there is only one") module: String? = null,
    ): String = maintain { tools, _ ->
        val (deletable, rejected) = withContext(Dispatchers.Default) { tools.deletable(module, keys) }
        withContext(Dispatchers.EDT) { tools.delete(module, deletable, rejected) }
    }

    /** [block] run for the calling project and MCP client, its result as JSON; a refusal as the MCP error the agent reads. */
    private suspend fun call(block: suspend (TranslationAgentTools, String) -> Any): String =
        respond { project, client -> block(TranslationAgentTools(project), client) }

    /** [call] for the key maintenance tools. */
    private suspend fun maintain(block: suspend (KeyMaintenanceTools, String) -> Any): String =
        respond { project, client -> block(KeyMaintenanceTools(project), client) }

    private suspend fun respond(block: suspend (Project, String) -> Any): String {
        val (project, client) = caller() ?: return REQUIRES_UPDATE
        TranslationFilesRefresh.refresh(project)
        val result = try {
            block(project, client)
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
