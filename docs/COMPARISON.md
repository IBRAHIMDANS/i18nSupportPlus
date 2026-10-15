# Coming from i18n Ally, Easy I18n, I18n Support or Sherlock?

_Last verified: 2026-10-15. Re-check at every major release of any of the tools below._

This page compares **I18n Support Plus** with the two tools people most often use for i18n keys in an editor:
**i18n Ally** (by Lokalise, for VS Code and for JetBrains IDEs) and **Easy I18n** (for JetBrains IDEs). It also covers
**I18n Support**, the plugin I18n Support Plus started from, and two other VS Code extensions: **i18n Ally Next** and
**Sherlock** (by inlang). Each claim about another tool links to its documentation, its source
code or its issue tracker at the version below. If one is wrong or out of date,
[open an issue](https://github.com/IBRAHIMDANS/i18nSupportPlus/issues) and it will be fixed.

| Tool | Version compared | Released | Source |
|---|---|---|---|
| I18n Support Plus | 1.12.0 | 2026-10-15 | [CHANGELOG](../CHANGELOG.md) |
| Easy I18n | 5.0.2 | 2026-05-03 | [Marketplace](https://plugins.jetbrains.com/plugin/16316-easy-i18n), [docs](https://marhali.github.io/easy-i18n/), [GitHub](https://github.com/marhali/easy-i18n) |
| i18n Ally for JetBrains | 1.4.3.2 | 2024-10-12 | [Marketplace](https://plugins.jetbrains.com/plugin/17212-i18n-ally) |
| i18n Ally for VS Code | 2.13.1 | 2024-12-13 | [VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=lokalise.i18n-ally), [wiki](https://github.com/lokalise/i18n-ally/wiki), [GitHub](https://github.com/lokalise/i18n-ally) |
| I18n Support | 2.8.4 | 2023-03-28 | [Marketplace](https://plugins.jetbrains.com/plugin/12981-i18n-support/versions), [GitHub](https://github.com/nyavro/i18nPlugin) |
| i18n Ally Next | 3.0.15 | 2026-03-19 | [VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=lydanne.i18n-ally-next), [docs](https://lydanne.github.io/i18n-ally-next/), [GitHub](https://github.com/lydanne/i18n-ally-next) |
| Sherlock | 2.5.1 | 2026-10-15 | [VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=inlang.vs-code-extension), [GitHub](https://github.com/opral/sherlock) |

In the tables, **—** means the tool's documentation says nothing about it at that version. It does not mean the
feature is missing.

## i18n Ally alternative for JetBrains IDEs (WebStorm, IntelliJ IDEA, PhpStorm)

i18n Ally exists for JetBrains IDEs too, as a separate plugin with its own feature set. Its latest version, 1.4.3.2,
installs on **builds 241 to 242 only, i.e. 2024.1 and 2024.2**. It cannot be installed on 2024.3, 2025.x or 2026.x
([Marketplace versions](https://plugins.jetbrains.com/plugin/17212-i18n-ally/versions),
[issue #1379](https://github.com/lokalise/i18n-ally/issues/1379), open). I18n Support Plus runs on 2025.2 to 2026.3
(builds 252–263).

## Coming from I18n Support?

**I18n Support** (`com.eny.i18n`, by Evgeniy Nyavro, 79 697 downloads on 2026-10-15) has not been updated since
2.8.4, released on 2023-03-28. That version installs on **builds 221 to 231 only, i.e. 2022.1 to 2023.1**
([Marketplace versions](https://plugins.jetbrains.com/plugin/12981-i18n-support/versions),
[`gradle.properties`](https://github.com/nyavro/i18nPlugin/blob/master/gradle.properties)). Its companion
**Vue-I18n Support** (`com.eny.i18nVue`) stopped at 1.0.3, released on 2022-12-08, for builds 221 to 223
([Marketplace versions](https://plugins.jetbrains.com/plugin/20194-vue-i18n-support/versions)).

I18n Support Plus started as a fork of I18n Support. It is a separate plugin with its own id
(`com.ibrahimdans.i18n`), not an update: install it from the Marketplace.

**What is carried over.** Everything I18n Support 2.8.4 lists in its
[README](https://github.com/nyavro/i18nPlugin/blob/master/README.md) and on the
[Marketplace](https://plugins.jetbrains.com/plugin/12981-i18n-support): key annotation, navigation from the key to its
value and back, completion, quick fixes for a missing namespace or key, extraction of a string, the value on
Ctrl+hover, YAML files, PHP and Vue. vue-i18n is built in: Vue-I18n Support is not needed.

**What is new.** Everything in the tables below that I18n Support did not have, among others: modules for a monorepo,
the setup wizard, `fallbackNS`, next-intl, Transloco and Laravel, the plural forms each language needs, renaming
and moving keys, the tool window, value checks, unused keys, CSV and XLIFF, reviews and machine translation.

**Do you have to reconfigure?** No. I18n Support Plus keeps the project settings under the same name, `i18nSettings`,
in the same file, `.idea/i18nSettings.xml`, with the same option names
([I18n Support](https://github.com/nyavro/i18nPlugin/blob/master/src/main/kotlin/com/eny/i18n/plugin/ide/settings/Settings.kt),
[I18n Support Plus](../src/main/kotlin/com/ibrahimdans/i18n/plugin/ide/settings/Settings.kt)): separators, default
namespaces, first key segment as namespace, folding, gettext and its aliases, sorted extraction, the partial
translation inspection and the others are read as they are. I18n Support had no translation root setting: while none is set,
a notification offers the setup wizard when the project opens.

## Feature by feature

### Where it runs

| | I18n Support Plus | Easy I18n | i18n Ally (JetBrains) | i18n Ally (VS Code) |
|---|---|---|---|---|
| Editor | WebStorm, IntelliJ IDEA Ultimate, PhpStorm 2025.2+ (needs the bundled JavaScript plugin) | Any IntelliJ-based IDE 2025.2+ | IntelliJ-based IDEs 2024.1–2024.2 | VS Code |
| Code languages | JS, TS, JSX, TSX, Vue SFC, Angular templates, Svelte, PHP, Blade | HTML/XML, JS(X), TS(X), Vue, Svelte, PHP, Ruby, Rust, Go, Dart, Python, Kotlin, Java ([Marketplace](https://plugins.jetbrains.com/plugin/16316-easy-i18n)) | JS, TS, React, Vue, PHP, Blade, Twig ([Marketplace](https://plugins.jetbrains.com/plugin/17212-i18n-ally)) | Per framework, including Flutter, Rails and Ember ([wiki](https://github.com/lokalise/i18n-ally/wiki/Supported-Frameworks)) |
| Translation files | JSON, JSON5, YAML, PO/POT, TS/JS catalogues, Laravel PHP arrays | JSON, JSON5, YAML, Properties | JSON, JS/TS, PHP, PO/POT, XLIFF 1/2, YAML | JSON, YAML, JSON5; INI, properties, POT and read-only JS/TS/PHP when enabled ([wiki](https://github.com/lokalise/i18n-ally/wiki/Locale-Formats)) |
| Laravel PHP array files (`lang/en/*.php`) | Yes, read and written, the file name as namespace; package keys (`package::file.key`) read from the overrides in `lang/vendor/` | Preset reads `resources/lang/{locale}.json` ([presets](https://marhali.github.io/easy-i18n/configuration/presets/)) | Yes | Read-only |
| Monorepo, one configuration per app | Modules (root, path template, key template, preset) | Modules, since 5.0 ([migration](https://marhali.github.io/easy-i18n/migration/v5/)) | — | Multi-root workspaces |
| Setup | Wizard and per-framework presets, Laravel detected from `composer.json` | Per-framework presets | Automatic for Vue, Nuxt, Laravel, Symfony, Yii, CodeIgniter, CakePHP | Framework detected from `package.json` |

### Frameworks

| | I18n Support Plus | Easy I18n | i18n Ally (JetBrains) | i18n Ally (VS Code) |
|---|---|---|---|---|
| i18next namespace `t('ns:key')` | Yes | React i18next preset | — | Yes, `:` or `/` |
| Namespace of `useTranslation('ns')` | Yes, string and array | — | — | — |
| `keyPrefix` of `useTranslation` | Yes | — | — | Open request ([#719](https://github.com/lokalise/i18n-ally/issues/719)) |
| i18next `fallbackNS` | Yes, read from the i18next config | — | — | — |
| i18next v4 plurals (`key_one`, `key_other`) | Resolved; forms a language lacks are reported (CLDR) | — | — | Suffixes count as usages ([source](https://github.com/lokalise/i18n-ally/blob/main/src/frameworks/react-i18next.ts)); [#688](https://github.com/lokalise/i18n-ally/issues/688) open |
| vue-i18n `<i18n-t keypath>`, `v-t` | Resolved, navigable, checked; completion, folding and a constant `:keypath` | — | — | — |
| vue-i18n linked messages `@:key` | Yes | — | — | Yes |
| next-intl `useTranslations` / `getTranslations` | Yes | — | — | next-intl listed in its [source](https://github.com/lokalise/i18n-ally/tree/main/src/frameworks) |
| Transloco | Service, pipe, structural directive and its `read`, scopes | Preset: service and attribute directive | — | Yes |
| Laravel `__()`, `trans()`, `trans_choice()`, `@lang` | Yes, `package::file.key` included | Preset: `__`, `trans`, `trans_choice` | Yes, Blade included | Yes |
| Your own `translate()` wrapper | Key assistance rules, per-module call template | Editor rules, flavor template | — | Custom framework, refactor templates |

### Reading keys in the code

| | I18n Support Plus | Easy I18n | i18n Ally (JetBrains) | i18n Ally (VS Code) |
|---|---|---|---|---|
| Value next to the key | Inlay hint, folding, Ctrl+hover table of every locale; in a translation file, the reference value | Folding, documentation popup | Text instead of keys (toggle) | Inline annotation, hover |
| Status per key | Gutter icon (all / some / no locale), annotation | Unresolved key inspection | Missing strings inspection | Missing translations reported |
| Navigation | Ctrl+click to the value and back, Find Usages | Go To Declaration opens the edit dialog | Ctrl+click to the file | Go to the key |
| Completion of real keys | Yes, by key or by value | — | — | — |
| Why a key is unresolved | Namespace, module and files looked up, in the tooltip | — | — | — |

### Editing keys

| | I18n Support Plus | Easy I18n | i18n Ally (JetBrains) | i18n Ally (VS Code) |
|---|---|---|---|---|
| Extract a hardcoded string | Dialog: reuse an existing key, namespace, parent group, variables, plural forms | Extract translation action | Quick fix, and in bulk | Quick fix, and batch per file |
| Internationalize a file or a folder at once | JS/TS and Vue components, previewed per file, one undo, other locales machine-translated | — | In bulk | Batch per file |
| Rename a key everywhere | `Shift+F6`: every locale and call site, a whole level too, one undo | — | Keys and params | `rename-key` command |
| Move a key to another namespace | Yes | — | — | — |
| Edit a value from the code | Shortcut or hint link | Go To Declaration opens the dialog | — | Hover action |
| Delete a key from every locale | Yes, warns when still used | Yes, from the tool window | — | `delete-key` command |

### Keeping translations healthy

| | I18n Support Plus | Easy I18n | i18n Ally (JetBrains) | i18n Ally (VS Code) |
|---|---|---|---|---|
| Overview | Tool window: tree, editable table, coverage matrix per namespace and locale | Tool window: tree and editable table | — | Sidebar, Editor UI (webview) |
| Missing translations | Inspection, filters, keys synchronizer | Missing filter | Inspection over project, folder or file | Reported |
| Unused keys | Inspection, Scan Orphans, Cleanup Unused Keys | — | — | Usage Report: keys not in use |
| Keys used outside the code | Keep list (exact, prefix, glob) | — | — | `keysInUse` |
| Locales left out of the checks | Yes | — | — | `ignoredLocales`: locales ignored altogether |
| Value checks | Empty, duplicate, placeholders, ICU, markup, copied from reference, punctuation, length | Duplicate filter | — | — |
| CSV / XLIFF export and import | Yes, with preview | — | — | — |
| Translation changes of a branch | Compare with a Git branch | — | — | — |
| Review: approve, request changes, comment | Per key and locale in the table and the key dialog, stored in `.i18n-reviews/`; machine translations marked to review; reviews follow renamed, moved and deleted keys | — | — | Approve, request changes, comment, suggest a value, from the editor, side panel and Editor UI; stored in `.vscode/i18n-ally-reviews.yml` ([wiki](https://github.com/lokalise/i18n-ally/wiki/Review-&-Collaboration-System), [source](https://github.com/lokalise/i18n-ally/blob/main/src/core/types.ts)) |
| Checks in CI | Inspections in Qodana or a headless IDE, ready-made configuration | — | — | — |

### Machine translation

| | I18n Support Plus | Easy I18n | i18n Ally (JetBrains) | i18n Ally (VS Code) |
|---|---|---|---|---|
| Engines | Ollama (local), any OpenAI-compatible API, DeepL, LibreTranslate, Google | — | — | Google (default), DeepL, LibreTranslate, OpenAI (custom API root), Baidu ([wiki](https://github.com/lokalise/i18n-ally/wiki/Machine-Translation), [source](https://github.com/lokalise/i18n-ally/tree/main/src/translators/engines)) |
| Opt-in | Yes: nothing is sent until enabled for the project | — | — | No: Google is the default engine |
| Placeholders and tags kept | Masked before sending | — | — | — |
| Plural forms of the target language | Yes | — | — | — |
| AI agents (Codex, Claude, any MCP client) | Tools on the IDE's MCP server: the agent reads the missing keys and writes them through the plugin — never over an existing value, placeholders and plural forms checked, one undo ([setup](../README.md#ai-agents-mcp)) | — | — | — ([#1328](https://github.com/lokalise/i18n-ally/issues/1328), open) |
| Preview before writing | Yes, one undo, the engine that translated each proposal | — | — | Can save as review candidates |
| Fill a whole locale | Yes, every locale at once | — | — | `fulfill-keys` |
| Glossary | Terms kept as written for every engine; translated terms sent to the OpenAI-compatible prompt and as a DeepL glossary | — | — | — |

## i18n Ally Next and Sherlock (VS Code)

**i18n Ally Next** is a fork of i18n Ally for VS Code
([announcement](https://github.com/lydanne/i18n-ally-next/blob/main/docs/blog/introducing-i18n-ally-next.md)),
installed 1 151 times on 2026-10-15. **Sherlock** is the inlang extension for VS Code, built on the inlang project
format ([README](https://github.com/opral/sherlock)), installed 51 740 times on 2026-10-15. Neither runs in JetBrains
IDEs.

| | I18n Support Plus | i18n Ally Next 3.0.15 | Sherlock 2.5.1 |
|---|---|---|---|
| Setup | Wizard and per-framework presets | Framework detected from `package.json` ([README](https://github.com/lydanne/i18n-ally-next)) | A `project.inlang/settings.json` and a Git repository; a module per file format, a matcher per call syntax ([README](https://github.com/opral/sherlock)) |
| Value next to the key | Inlay hint, folding, Ctrl+hover | Inline annotation, hover | Inline annotation, hover |
| Missing translations | Inspection, filters, keys synchronizer | Usage report: missing keys ([docs](https://github.com/lydanne/i18n-ally-next/blob/main/docs/guide/usage-report.md)) | Message lint: missing translations and other issues ([README](https://github.com/opral/sherlock)) |
| Unused keys | Inspection, Scan Orphans, Cleanup Unused Keys | Usage report: idle keys | — |
| Extract a hardcoded string | Dialog, file or folder at once | Quick fix, per file, and the whole project at once ([docs](https://github.com/lydanne/i18n-ally-next/blob/main/docs/guide/extraction.md), [announcement](https://github.com/lydanne/i18n-ally-next/blob/main/docs/blog/introducing-i18n-ally-next.md)) | One click |
| Review | Tool window table and key dialog, `.i18n-reviews/` | Gutter and comment threads in the translation file, `.vscode/i18n-ally-next-reviews.yml` ([docs](https://github.com/lydanne/i18n-ally-next/blob/main/docs/guide/review.md)) | — |
| Translations outdated by a changed source text | — | Snapshot of the source text per key, compared on demand ([announcement](https://github.com/lydanne/i18n-ally-next/blob/main/docs/blog/introducing-i18n-ally-next.md)) | — |
| Machine translation | Ollama, OpenAI-compatible API, DeepL, LibreTranslate, Google; opt-in | Google (default), DeepL, Baidu, LibreTranslate, OpenAI, Ollama, the editor's built-in model through Copilot ([docs](https://github.com/lydanne/i18n-ally-next/blob/main/docs/guide/translation.md)) | The command opens an inlang Cloud page, announced as coming, and points to the inlang CLI ([source](https://github.com/opral/sherlock/blob/main/src/commands/cloud.ts)) |
| AI agents (MCP) | Tools on the IDE's MCP server | — | — |
| Your own translation function | Key assistance rules, call template | Custom framework in YAML ([docs](https://github.com/lydanne/i18n-ally-next/blob/main/docs/guide/custom-framework.md)) | — (matcher plugins exist for `m()`, `t()` and next-intl) |
| Migration from i18n Ally | Settings table [below](#from-i18n-ally) | Command that renames the `i18n-ally.*` settings, custom framework and reviews files ([docs](https://github.com/lydanne/i18n-ally-next/blob/main/docs/guide/migration.md)) | — |

## What the others do better

- **i18n Ally, i18n Ally Next and Sherlock run in VS Code.** If your team uses VS Code, I18n Support Plus is not an
  option: it is a JetBrains plugin.
- **i18n Ally Next detects translations left behind by a changed source text** and offers to translate them again,
  one by one or all at once ([announcement](https://github.com/lydanne/i18n-ally-next/blob/main/docs/blog/introducing-i18n-ally-next.md)).
  I18n Support Plus compares translations with a Git branch, not with the source text they were made from.
- **i18n Ally reviews from the editor**: approvals, requested changes, comments and suggested values in the gutter, the side panel and its Editor UI,
  stored in the repository ([wiki](https://github.com/lokalise/i18n-ally/wiki/Review-&-Collaboration-System)).
  I18n Support Plus reviews from the tool window table and the key dialog, not from the code editor.
- **i18n Ally has a full-screen Editor UI** for one key across every locale
  ([wiki](https://github.com/lokalise/i18n-ally/wiki/Editor-UI)).
- **i18n Ally covers more frameworks**: Flutter, Rails, Ember, Polyglot, SAP UI5, Chrome extensions and a custom
  framework definition.
- **Easy I18n works outside JavaScript**: Java, Kotlin, Python, Go, Rust, Ruby, Dart, with a Spring Boot preset and
  `.properties` files, in any IntelliJ-based IDE including those without the JavaScript plugin.
  I18n Support Plus needs WebStorm, IntelliJ IDEA Ultimate or PhpStorm.
- **i18n Ally for JetBrains reads XLIFF translation files and Twig templates.** I18n Support Plus exports and
  imports XLIFF but does not read it as a translation source, and does not read Twig.

## Migrate

### From i18n Ally

| i18n Ally setting | I18n Support Plus |
|---|---|
| `i18n-ally.localesPaths` | *Translations root directory*, or one module per app with a **path template** (`locales/{lang}/{ns}.json`) |
| `i18n-ally.keystyle` `"flat"` / `"nested"` | *Treat keys as flat* off for nested, on for flat |
| `i18n-ally.namespace`, `i18n-ally.pathMatcher` | The module's **path template** and **key template** (`{ns}:{key}`, `{ns}.{key}`, `{key}`) |
| `i18n-ally.defaultNamespace` | *Default namespace* |
| `i18n-ally.sourceLanguage` | The module's reference locale |
| `i18n-ally.displayLanguage` | *Preview locale*, also in the status bar |
| `i18n-ally.keysInUse` | *Keys used outside the code* |
| `i18n-ally.ignoredLocales` | *Locales not checked for missing keys* (the locale stays visible) |
| `i18n-ally.translate.engines` | *Settings → Tools → i18n Support Plus → Machine Translation*: engines tried in order |
| `i18n-ally.refactor.templates` | The module's *Call template* (`translate({key})`, `t({key}, {text})` with the text as default value) |
| `i18n-ally.sortKeys` | *Sort keys alphabetically* |
| Custom framework (`.vscode/i18n-ally-custom-framework.yml`) | *Key assistance rules*: a trigger per function name |

### From I18n Support

Nothing to carry over by hand: see [Coming from I18n Support?](#coming-from-i18n-support).

### From Easy I18n

| Easy I18n setting | I18n Support Plus |
|---|---|
| Module | Module |
| Path template `$PROJECT_DIR$/locales/{locale}/{namespace}.json` | Path template `locales/{lang}/{ns}.json`, relative to the module's root directory |
| Key template | Key template (`{ns}:{key}`) |
| Editor rules | Key assistance rules |
| I18n flavor template (`t("{i18nKey}")`) | Call template (`t({key})`) |
| Preview locale | Preview locale |

Then run **Tools → i18n Support Plus → Run Setup Wizard**: it detects the framework and the translation files and
fills the rest.
