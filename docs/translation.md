# Machine translation and AI agents

Filling the missing translations with a translation engine, or letting an AI agent do it through the plugin.

[← Back to the README](../README.md)

## Machine Translation

Off until a project turns it on, in *Settings | Tools | i18n Support Plus | Machine Translation*: the text of the translations is sent to the engines listed there, tried in their order. Out of the box:

- **OpenAI-compatible**, pointing at a local Ollama (`http://localhost:11434`) — nothing leaves the machine — or at OpenAI, Mistral, Groq, OpenRouter or LM Studio;
- **LibreTranslate**, local as well;
- **DeepL** and **Google Cloud Translation**, with your API key.

Each engine's URL, headers, request body and the path of the translation in the answer can be edited, and *Test* translates a sample. The glossary, one term per line, protects a name from translation (`Acme`) or gives a term's translations (`cart = fr: panier; de: Warenkorb`), sent to DeepL as a glossary and to the LLM engines in their prompt.

- *Create Translation* and *Edit Translation*: **Translate Empty Locales**, or *Translate* next to one locale, fills the fields from the reference locale; nothing is written before OK. An *Instruction* (formal tone, a length) is sent along to the OpenAI-compatible engines.
- **Fill Missing Translations…** (*Tools > i18n Support Plus*): the keys a locale lacks, translated from the reference locale, with a preview before writing.
- **Retranslate Stale Translations…**: in a module keeping reviews, the values whose source changed since they were reviewed, next to the value each one replaces.
- *Internationalize…* can translate the new keys into the module's other locales.

Variables, tags and the plural forms of the target language are kept; a proposal that lost a variable comes unchecked.
An ICU plural (`{count, plural, one {# file} other {# files}}`) is sent with the branches of the target language — `few` and `many` added for Russian, `other` alone for Japanese —, `=0` and `offset:` kept; a branch the source lacks is translated from `other` and comes unchecked, to review. One nested in more than one other block, or without `other`, is not translated.

## AI Agents (MCP)

On IntelliJ-based IDEs 2025.2.6 and later, the plugin adds ten tools to the IDE's built-in MCP server, so an AI agent translates, checks and maintains keys through the plugin rather than by rewriting the files — with your own Codex or Claude plan, no API key:

| Tool | What it does |
|------|--------------|
| `i18n_list_modules` | The translation modules, their reference locale, locales, the locales not checked for missing keys, and whether reviews are on |
| `i18n_missing_translations` | The keys a locale lacks, with the reference text to translate and the plural forms the locale needs. Each page also carries the terminology in place: the project's glossary for that locale (`glossary`) and up to 10 translations the locale already has whose reference text shares a word with the page (`examples`), so that a term left in English or translated a given way stays so |
| `i18n_stale_translations` | The stale translations of a locale — values still the ones reviewed while their key's reference text changed since — with that text as it reads now and the value to replace. Only in a module keeping reviews |
| `i18n_write_translations` | Writes translations in one undoable command: fills a missing value, or replaces one that is stale when written — never any other existing value — and a value losing a placeholder or tag of its reference is refused with the reason. Marked *to review* (`machine:<agent>`) with the reference text it was translated from when the module keeps reviews |
| `i18n_check` | The check of `i18n-check`, per module: the keys each locale lacks, holds in excess or leaves blank, and the keys of the reference locale no code uses — those of the keep list, and keys a dynamic key may build, left out |
| `i18n_find_keys` | Finds existing keys by key pattern (`common:actions.*`), key prefix or the text they hold — equal values first, then whole words — with their value in every locale and how often the code uses them, so an agent reuses a key instead of creating its twin |
| `i18n_key_usages` | Where the code uses a key: file, line and the line of code, and whether a key built at runtime may also reach it |
| `i18n_rename_key` | Renames a key's last segment in every locale and call site, as Shift+F6 does — a whole level or a plural group too — in one undoable command; refused when the new key exists, keys built at runtime listed rather than rewritten |
| `i18n_create_key` | Creates a key with its values in one undoable command; refused when it exists, or without a value in the reference locale, and a value losing a placeholder of the reference one is refused with the reason. Not created while another key holds the same reference text: the answer lists it (`existing`), and `allowDuplicate` creates the key anyway |
| `i18n_delete_unused_keys` | Deletes keys from every locale, their reviews included, in one undoable command; a key still used, kept, or possibly built at runtime is refused with the reason |

### Setting it up

**1. Turn the IDE's MCP server on.** **Settings > Tools > MCP Server**, check **Enable MCP Server**. The page shows the server's address, `http://127.0.0.1:64342/stream` by default (the port may differ). Nothing else to install: the server comes with the IDE, and the plugin adds its tools to it.

**2. Connect your agent**, one of:

- **Codex or Claude in AI Assistant**: **Settings > Tools > AI Assistant > Agents**, check **Pass IntelliJ MCP server** (unchecked by default), then start a **new** chat: an agent gets its tools when its session starts.
- **Claude Code**: in the MCP Server page, click **Auto-configure** next to *Claude Code*, or run:
  ```bash
  claude mcp add --transport http jetbrains http://127.0.0.1:64342/stream
  ```
- **Codex CLI**: click **Auto-configure** next to *Codex*, or run:
  ```bash
  codex mcp add jetbrains --url http://127.0.0.1:64342/stream
  ```
  which writes to `~/.codex/config.toml`:
  ```toml
  [mcp_servers.jetbrains]
  url = "http://127.0.0.1:64342/stream"
  ```
- **Cursor, VS Code, Claude Desktop**: click **Auto-configure** next to the client in the MCP Server page.
- **Any other MCP client**: **Copy HTTP Stream Config** (or *SSE* / *STDIO*, whichever it supports) in the MCP Server page and paste it in the client's configuration.

Restart a client that was already running: it reads the list of tools when it connects.

**3. Ask**, from the project folder or naming the project, for instance:

```text
Use the i18n tools to translate the missing German keys.
```

The agent calls `i18n_missing_translations`, translates, then `i18n_write_translations`, and sends again the values refused with their reason. The same goes for keeping the keys tidy:

```text
Use the i18n tools to check the translations and delete the keys no code uses.
```

The agent calls `i18n_check`, then `i18n_delete_unused_keys` with the keys it lists as unused. With several projects open in the IDE, it has to say which one (`projectPath`): give it the project folder if it asks.

**4. Make the tools the default** (optional). Asked to translate without a word about the tools, an agent may edit the files itself: a client such as Claude Code loads a tool's definition only once the agent looks for it. One line in the project's instruction file — `CLAUDE.md` for Claude Code, `AGENTS.md` for Codex — settles it, with your own translations folder:

```text
For translation files (public/locales), use the i18n_* MCP tools instead of editing the JSON files.
```

If you can't edit the project's instruction file, Claude Code can load the server's tools upfront instead: add `"alwaysLoad": true` to the `jetbrains` entry of your MCP configuration (`.mcp.json` or `--mcp-config`). This works just as well, but it loads every tool of the IDE's server, not only the `i18n_*` ones, into each session of the project, translation or not: about 32,000 more tokens of context on the first turn with the 73 tools of WebStorm 2026.3. The plugin can't mark only its own tools to load upfront: the IDE's MCP server gives plugins no way to.

```json
{
  "mcpServers": {
    "jetbrains": {
      "type": "http",
      "url": "http://127.0.0.1:64342/stream",
      "alwaysLoad": true
    }
  }
}
```

### Good to know

- Only a missing value is filled, and only a stale one replaced: staleness is checked again when writing, so a value you fixed or approved after the agent listed it is refused (`Already filled and not stale`), and one changed during the write is left as it is (`Changed since it was read`). To re-translate any other filled value, use *Translate* in the *Edit Translation* dialog.
- Stale translations need reviews: a value is stale when it is still the one reviewed while the reference text recorded with that review changed since. In a module without `.i18n-reviews/`, `i18n_stale_translations` is refused with the reason; *Show Translation Changes* still shows the locales lagging behind the reference.
- ICU plurals (`{count, plural, …}`) are listed with the branches the locale needs, those the reference lacks copied from `other` (`pluralFromOther`); a value whose branches are not exactly those is refused with the reason. One nested in more than one other block, or without `other`, is listed but not written: edit it in the file.
- Reviews: in a module with a `.i18n-reviews/` folder, the values written are marked *to review* as `machine:<agent>`; an agent never creates that folder.
- Renaming, creating and deleting a key are each one command: one Ctrl+Z in the IDE undoes it. A rename only changes a key's last segment; moving a key to another namespace is done with *Move i18n Key to Namespace…*.
- Nothing appears? Check that the plugin's tools are listed by your client (`/mcp` in Claude Code), that the MCP server is enabled, and that the IDE is 2025.2.6 or later.

## What leaves your machine

Nothing by default: the plugin collects no usage statistics, and machine translation is off until a project turns it on. Then, and on your clicks only:

- **Machine translation**: each engine configured receives the texts to translate, their source and target locales and their keys, along with up to 1,000 characters of context taken from the project's translation files (keys of the same group already translated, translations sharing words with the texts) and the names the IDE read around the texts' usages — never the source code. The glossary and the *Instruction* go to the engines whose request holds them; DeepL keeps the glossary on its side. A text the module already translates under another key is taken from there and not sent. With a local Ollama or LibreTranslate, nothing leaves the machine.
- **API keys** are kept in the IDE's password safe (the OS keychain or KeePass) and sent only to their engine.
- **AI agents**: the plugin's MCP tools answer the agent connected to the IDE's local MCP server. The plugin sends nothing itself, but the agent passes what the tools return (keys, values, and the file and line of code of a key's usages) to its own model provider.
- **Error reports**: *Report to JetBrains Marketplace*, in the IDE's error dialog, sends the plugin's exception, your comment and the IDE's configuration to JetBrains and the plugin's author. *Open a GitHub Issue…* then opens a pre-filled form in your browser: nothing reaches GitHub until you submit it, and a stack trace may hold file paths.
- **Suggest this layout as a new preset on GitHub**, in the settings, opens an issue form holding the module's framework and path, file and key templates, never its name or directory.
