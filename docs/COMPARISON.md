# Coming from i18n Ally, Easy I18n, I18n Support or Sherlock?

_Last verified: 2026-11-14. Re-check at every major release of any of the tools below._

This page compares **I18n Support Plus** with the two tools people most often use for i18n keys in an editor:
**i18n Ally** (by Lokalise, for VS Code and for JetBrains IDEs) and **Easy I18n** (for JetBrains IDEs). It also covers
**I18n Support**, the plugin I18n Support Plus started from, three other VS Code extensions: **i18n Ally Next**,
**Sherlock** (by inlang) and **x18n**, the JetBrains plugin **i18n Studio**, and the Laravel support built into PhpStorm. Each claim about another tool links to its documentation, its source
code or its issue tracker at the version below. If one is wrong or out of date,
[open an issue](https://github.com/IBRAHIMDANS/i18nSupportPlus/issues) and it will be fixed.

| Tool | Version compared | Released | Source |
|---|---|---|---|
| I18n Support Plus | 1.21.0 | 2026-11-12 | [CHANGELOG](../CHANGELOG.md) |
| Easy I18n | 5.0.2 | 2026-05-03 | [Marketplace](https://plugins.jetbrains.com/plugin/16316-easy-i18n), [docs](https://marhali.github.io/easy-i18n/), [GitHub](https://github.com/marhali/easy-i18n) |
| i18n Ally for JetBrains | 1.4.3.2 | 2024-10-12 | [Marketplace](https://plugins.jetbrains.com/plugin/17212-i18n-ally) |
| i18n Ally for VS Code | 2.13.1 | 2024-12-13 | [VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=lokalise.i18n-ally), [wiki](https://github.com/lokalise/i18n-ally/wiki), [GitHub](https://github.com/lokalise/i18n-ally) |
| I18n Support | 2.8.4 | 2023-03-28 | [Marketplace](https://plugins.jetbrains.com/plugin/12981-i18n-support/versions), [GitHub](https://github.com/nyavro/i18nPlugin) |
| i18n Ally Next | 3.0.17 | 2026-11-08 | [VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=lydanne.i18n-ally-next), [docs](https://lydanne.github.io/i18n-ally-next/), [GitHub](https://github.com/lydanne/i18n-ally-next) |
| Sherlock | 2.6.0 | 2026-11-04 | [VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=inlang.vs-code-extension), [GitHub](https://github.com/generaltranslation/sherlock) |
| x18n | 0.1.14 | 2026-11-10 | [VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=antfu.x18n), [docs](https://github.com/x18n-dev/x18n/tree/main/docs/content), [GitHub](https://github.com/x18n-dev/x18n) |
| i18n Studio | 1.0.1 | 2026-11-11 | [Marketplace](https://plugins.jetbrains.com/plugin/35461-i18n-studio) |
| Laravel Idea, bundled with PhpStorm | PhpStorm 2026.3 | — | [PhpStorm help](https://www.jetbrains.com/help/phpstorm/laravel-navigation-and-coding-assistance.html), [docs](https://laravel-idea.com/docs/localization) |

In the tables, **—** means the tool's documentation says nothing about it at that version. It does not mean the
feature is missing. <sup>next</sup> marks what the next release brings: it is on `main` and in the
[Unreleased](../CHANGELOG.md#unreleased) section of the changelog, not yet on the Marketplace.

## i18n Ally alternative for JetBrains IDEs (WebStorm, IntelliJ IDEA, PhpStorm)

i18n Ally exists for JetBrains IDEs too, as a separate plugin with its own feature set. Its latest version, 1.4.3.2,
installs on **builds 241 to 242 only, i.e. 2024.1 and 2024.2**. It cannot be installed on 2024.3, 2025.x or 2026.x
([Marketplace versions](https://plugins.jetbrains.com/plugin/17212-i18n-ally/versions),
[issue #1379](https://github.com/lokalise/i18n-ally/issues/1379), open). I18n Support Plus runs on 2025.2 to 2026.3
(builds 252–263).

## Coming from I18n Support?

**I18n Support** (`com.eny.i18n`, by Evgeniy Nyavro, 79 758 downloads on 2026-10-25) has not been updated since
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
| Editor | Any IntelliJ-based IDE 2025.2+; the keys in code need the JavaScript plugin (WebStorm, IntelliJ IDEA Ultimate, PhpStorm) | Any IntelliJ-based IDE 2025.2+ | IntelliJ-based IDEs 2024.1–2024.2 | VS Code |
| Code languages | JS, TS, JSX, TSX, Vue SFC, Angular templates, Svelte, PHP, Blade, Twig, Astro with the Astro plugin | HTML/XML, JS(X), TS(X), Vue, Svelte, PHP, Ruby, Rust, Go, Dart, Python, Kotlin, Java ([Marketplace](https://plugins.jetbrains.com/plugin/16316-easy-i18n)) | JS, TS, React, Vue, PHP, Blade, Twig ([Marketplace](https://plugins.jetbrains.com/plugin/17212-i18n-ally)) | Per framework, including Flutter, Rails and Ember ([wiki](https://github.com/lokalise/i18n-ally/wiki/Supported-Frameworks)) |
| Translation files | JSON, JSON5, YAML, PO/POT, TS/JS catalogues, Laravel PHP arrays, Flutter ARB, Java `.properties` bundles, XLIFF 1.2 and 2.0 in an Angular or Symfony project | JSON, JSON5, YAML, Properties | JSON, JS/TS, PHP, PO/POT, XLIFF 1/2, YAML | JSON, YAML, JSON5; INI, properties, POT and read-only JS/TS/PHP when enabled ([wiki](https://github.com/lokalise/i18n-ally/wiki/Locale-Formats)) |
| Laravel PHP array files (`lang/en/*.php`) | Yes, read and written, the file name as namespace; package keys (`package::file.key`) read from the overrides in `lang/vendor/` | Preset reads `resources/lang/{locale}.json` ([presets](https://marhali.github.io/easy-i18n/configuration/presets/)) | Yes | Read-only |
| Monorepo, one configuration per app | Modules (root, path template, key template, preset), rooted at each application by the setup wizard; an *Active* box sets one aside, and a key's usages are those of its module | Modules, since 5.0 ([migration](https://marhali.github.io/easy-i18n/migration/v5/)) | — | Multi-root workspaces |
| Setup | Wizard and per-framework presets, Laravel and Symfony detected from `composer.json` | Per-framework presets | Automatic for Vue, Nuxt, Laravel, Symfony, Yii, CodeIgniter, CakePHP | Framework detected from `package.json` |

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
| next-intl `useTranslations` / `getTranslations` | Yes; `t.rich`, `t.markup`, `t.raw` and `t.has` too | — | — | next-intl listed in its [source](https://github.com/lokalise/i18n-ally/tree/main/src/frameworks) |
| react-intl `<FormattedMessage id>`, `formatMessage({ id })` | Resolved; Ctrl+click, usages and Shift+F6; extracted as `<FormattedMessage>` or `intl.formatMessage` | — ([presets](https://marhali.github.io/easy-i18n/configuration/presets/)) | — | Ids read; extraction writes `t('key')` ([source](https://github.com/lokalise/i18n-ally/blob/main/src/frameworks/react-intl.ts)) |
| Paraglide JS (inlang) `m.hello()` | Yes: resolved, completed, folded, navigable to the message, usages counted, renamed, extracted as `m.key()`; `project.inlang/settings.json` read | Open request ([#404](https://github.com/marhali/easy-i18n/issues/404)) | — | Open request ([#1330](https://github.com/lokalise/i18n-ally/issues/1330)) |
| Transloco | Service, pipe, structural and attribute directives, `read`, scopes; inline `template:` too | Preset: service and attribute directive | — | Yes |
| Laravel `__()`, `trans()`, `trans_choice()`, `@lang` | Yes, `package::file.key` included | Preset: `__`, `trans`, `trans_choice` | Yes, Blade included | Yes |
| Symfony `trans()`, Twig `\|trans` and `{% trans %}` | Yes, the domain as namespace; YAML, XLIFF, PHP, JSON and PO catalogues | — | Yes, Twig included | — |
| Your own `translate()` wrapper | Key assistance rules, per-module call template, the extracted text as default value (`t({key}, {text})`) | Editor rules, flavor template | — | Custom framework, refactor templates |

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
| Extract a hard-coded string | Dialog: reuse an existing key, namespace, parent group, variables, plural forms | Extract translation action | Quick fix, and in bulk | Quick fix, and batch per file |
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
| Unused keys | Inspection, *Count Key Usages*, *Clean Up Keys with No Reference…* | — | — | Usage Report: keys not in use |
| Keys used outside the code | Keep list (exact, prefix, glob) | — | — | `keysInUse` |
| Locales left out of the checks | Yes | — | — | `ignoredLocales`: locales ignored altogether |
| Value checks | Empty, duplicate, placeholders, ICU, markup, copied from reference, punctuation, length, variables not passed to the call, hard-coded text in JSX and Vue templates; key names against a naming convention, renamed by a quick fix | Duplicate filter | — | — |
| CSV / XLIFF export and import | Yes, with preview | — | — | — |
| Translation changes of a branch | Compare with a Git branch; the locales lagging behind the reference retranslated in one go | — | — | — |
| Review: approve, request changes, comment | Per key and locale in the table, the key dialog and the gutter of a JSON or YAML translation file, stored in `.i18n-reviews/`; machine translations marked to review; reviews follow renamed, moved and deleted keys; a translation whose source changed since its review flagged in the table, the key dialog and an inspection, and retranslated in one go; a change request can carry a suggested value, applied in one click, and a thread takes replies and is resolved | — | — | Approve, request changes, comment, suggest a value, from the editor, side panel and Editor UI; stored in `.vscode/i18n-ally-reviews.yml` ([wiki](https://github.com/lokalise/i18n-ally/wiki/Review-&-Collaboration-System), [source](https://github.com/lokalise/i18n-ally/blob/main/src/core/types.ts)) |
| Checks in CI | Inspections in Qodana or a headless IDE, ready-made configuration; `i18n-check` on the command line, as a GitHub Action that annotates pull requests and as a Docker image published on request: missing, orphan and empty keys per locale (a locale whose file holds no value included), placeholders and ICU in values, unused and unknown keys in code, path aliases followed through `tsconfig` `extends`; Angular's XLIFF and `@@id`s read too ([documentation](checks.md#command-line-check)) | — | — | — |

### Machine translation

| | I18n Support Plus | Easy I18n | i18n Ally (JetBrains) | i18n Ally (VS Code) |
|---|---|---|---|---|
| Engines | Ollama (local), any OpenAI-compatible API, DeepL, LibreTranslate, Google | — | — | Google (default), DeepL, LibreTranslate, OpenAI (custom API root), Baidu ([wiki](https://github.com/lokalise/i18n-ally/wiki/Machine-Translation), [source](https://github.com/lokalise/i18n-ally/tree/main/src/translators/engines)) |
| Opt-in | Yes: nothing is sent until enabled for the project | — | — | No: Google is the default engine |
| Placeholders and tags kept | Masked before sending | — | — | — |
| Plural forms of the target language | Yes | — | — | — |
| AI agents (Codex, Claude, any MCP client) | Tools on the IDE's MCP server: the agent reads the missing keys and writes them through the plugin — never over an existing value but a stale one, placeholders and plural forms checked, one undo; it finds an existing key by name or text before creating one ([setup](translation.md#ai-agents-mcp)) | — | — | — ([#1328](https://github.com/lokalise/i18n-ally/issues/1328), open) |
| Preview before writing | Yes, one undo, the engine that translated each proposal | — | — | Can save as review candidates |
| Fill a whole locale | Yes, every locale at once | — | — | `fulfill-keys` |
| Glossary | Terms kept as written for every engine; translated terms sent to the OpenAI-compatible prompt and as a DeepL glossary | — | — | — |

## i18n Ally Next, Sherlock and x18n (VS Code)

**i18n Ally Next** is a fork of i18n Ally for VS Code
([announcement](https://github.com/lydanne/i18n-ally-next/blob/main/docs/blog/introducing-i18n-ally-next.md)),
installed 1 314 times on 2026-11-14. **Sherlock** is the inlang extension for VS Code, built on the inlang project
format ([README](https://github.com/generaltranslation/sherlock)), installed 52 427 times on 2026-11-14. **x18n**, by Anthony Fu, calls itself "the spiritual
successor of i18n-ally" ([README](https://github.com/x18n-dev/x18n)): one engine behind a CLI, a browser UI, a VS Code extension, a language
server for other editors and an MCP server. Its VS Code extension was installed 229 times on 2026-11-14. None of the
three ships for JetBrains IDEs; a pull request documenting how to use x18n's language server there, through LSP4IJ,
is open ([#154](https://github.com/x18n-dev/x18n/pull/154)).

| | I18n Support Plus | i18n Ally Next 3.0.17 | Sherlock 2.6.0 | x18n 0.1.14 |
|---|---|---|---|---|
| Setup | Wizard and per-framework presets | Framework detected from `package.json` ([README](https://github.com/lydanne/i18n-ally-next)) | A `project.inlang/settings.json` and a Git repository; a module per file format, a matcher per call syntax ([README](https://github.com/generaltranslation/sherlock)) | 45+ presets detected from the project, no config ([README](https://github.com/x18n-dev/x18n#features)) |
| Value next to the key | Inlay hint, folding, Ctrl+hover | Inline annotation, hover | Inline annotation, hover | Inline annotation, hover with every locale ([docs](https://github.com/x18n-dev/x18n/blob/main/docs/content/2.surfaces/2.vscode.md)) |
| Missing translations | Inspection, filters, keys synchronizer | Usage report: missing keys ([docs](https://github.com/lydanne/i18n-ally-next/blob/main/docs/guide/usage-report.md)) | Message lint: missing translations and other issues ([README](https://github.com/generaltranslation/sherlock)) | `x18n check` and the editor's diagnostics ([README](https://github.com/x18n-dev/x18n#features)) |
| Unused keys | Inspection, *Count Key Usages*, *Clean Up Keys with No Reference…* | Usage report: idle keys | — | `x18n check` ([README](https://github.com/x18n-dev/x18n#features)) |
| Extract a hard-coded string | Dialog, file or folder at once | Quick fix, per file, and the whole project at once ([docs](https://github.com/lydanne/i18n-ally-next/blob/main/docs/guide/extraction.md), [announcement](https://github.com/lydanne/i18n-ally-next/blob/main/docs/blog/introducing-i18n-ally-next.md)) | One click | Code action, one string or the whole file ([docs](https://github.com/x18n-dev/x18n/blob/main/docs/content/2.surfaces/2.vscode.md)) |
| Review | Tool window table, key dialog and translation file gutter, `.i18n-reviews/` | Gutter and comment threads in the translation file, `.vscode/i18n-ally-next-reviews.yml` ([docs](https://github.com/lydanne/i18n-ally-next/blob/main/docs/guide/review.md)) | — | Comment threads in the locale file, from the editor, the CLI, the browser UI and agents; `.x18n/reviews.yml` ([docs](https://github.com/x18n-dev/x18n/blob/main/docs/content/1.guide/5.reviews.md)) |
| Translations outdated by a changed source text | Where reviews are kept: source text recorded with each decision, changed ones flagged in the table, the key dialog and an inspection, retranslated in one go. Everywhere: the locales lagging behind a change of the reference, in the working copy or against a branch, retranslated in one go | Snapshot of the source text per key, compared on demand ([announcement](https://github.com/lydanne/i18n-ally-next/blob/main/docs/blog/introducing-i18n-ally-next.md)) | — | From the git history of the locale files, without a prior review ([docs](https://github.com/x18n-dev/x18n/blob/main/docs/content/9.checks/stale-translation.md)) |
| Machine translation | Ollama, OpenAI-compatible API, DeepL, LibreTranslate, Google; opt-in | Google (default), DeepL, Baidu, LibreTranslate, OpenAI, Ollama, the editor's built-in model through Copilot ([docs](https://github.com/lydanne/i18n-ally-next/blob/main/docs/guide/translation.md)) | The command opens an inlang Cloud page, announced as coming, and points to the inlang CLI ([source](https://github.com/generaltranslation/sherlock/blob/main/src/commands/cloud.ts)) | Any model of the AI SDK through `@x18n/translator-ai`; values marked machine-translated until approved ([docs](https://github.com/x18n-dev/x18n/blob/main/docs/content/1.guide/6.machine-translation.md)) |
| AI agents (MCP) | Tools on the IDE's MCP server; stale translations listed and retranslated | — | — | MCP server over stdio (`x18n mcp`) or HTTP next to the browser UI, without an editor open; agent skills shipped with the CLI ([MCP](https://github.com/x18n-dev/x18n/blob/main/docs/content/3.agents/3.mcp.md), [skills](https://github.com/x18n-dev/x18n/blob/main/docs/content/3.agents/4.skill.md)) |
| Paraglide JS `m.hello()` | Yes, from the `project.inlang` settings | Static `m.hello()` calls detected ([changelog](https://github.com/lydanne/i18n-ally-next/blob/main/CHANGELOG.md)) | Its own format: the m-function matcher plugin reads `m.hello()` ([README](https://github.com/generaltranslation/sherlock)) | `paraglide` preset: `m.key()` calls ([docs](https://github.com/x18n-dev/x18n/blob/main/docs/content/4.presets/33.paraglide.md)) |
| Your own translation function | Key assistance rules, call template | Custom framework in YAML ([docs](https://github.com/lydanne/i18n-ally-next/blob/main/docs/guide/custom-framework.md)) | — (matcher plugins exist for `m()`, `t()` and next-intl) | Plugins for formats, scanners, checks and presets ([README](https://github.com/x18n-dev/x18n#features)) |
| Migration from i18n Ally | Settings table [below](#from-i18n-ally) | Command that renames the `i18n-ally.*` settings, custom framework and reviews files ([docs](https://github.com/lydanne/i18n-ally-next/blob/main/docs/guide/migration.md)) | — | `x18n migrate i18n-ally`: settings, custom framework and reviews of i18n Ally and i18n Ally Next ([docs](https://github.com/x18n-dev/x18n/blob/main/docs/content/8.migration/index.md)) |
| Checks in CI | `i18n-check`, GitHub Action | — | — | `x18n check`: pull request annotations, job summary, SARIF for code scanning, a baseline that fails only on new problems ([CI](https://github.com/x18n-dev/x18n/blob/main/docs/content/2.surfaces/5.ci.md), [baseline](https://github.com/x18n-dev/x18n/blob/main/docs/content/2.surfaces/1.cli.md)) |
| Other editors | — (JetBrains IDEs) | — | — | Language server for any LSP editor, Zed extension, browser UI, Vite and Nuxt DevTools panel ([README](https://github.com/x18n-dev/x18n#packages)) |

## Laravel in PhpStorm
PhpStorm supports Laravel out of the box: the Laravel Idea plugin "now comes bundled with the IDE and does not
require additional installation" ([PhpStorm help](https://www.jetbrains.com/help/phpstorm/laravel-navigation-and-coding-assistance.html)).
Its [localization page](https://laravel-idea.com/docs/localization) documents two features for translations: key
completion, and generating a key from PHP code or Blade (`Alt+Insert`). I18n Support Plus works next to it and covers
what that page does not mention.

| | I18n Support Plus | Laravel Idea (PhpStorm) |
|---|---|---|
| Key completion in `__()`, `trans()`, `@lang` | Yes, by key or by value | Yes ([docs](https://laravel-idea.com/docs/localization#keys-completion)) |
| New key from the code | Extract a hard-coded string, written in every locale | Key generation from PHP and Blade ([docs](https://laravel-idea.com/docs/localization#key-generation)) |
| `lang/en/*.php` arrays and `lang/*.json` | Read and written; `package::file.key` from `lang/vendor/` | — |
| Value next to the key, status per locale | Inlay hint, folding, Ctrl+hover table, gutter icon | — |
| Missing translations per locale | Inspection, tool window, keys synchronizer | — |
| Unused keys | Inspection, *Clean Up Keys with No Reference…* | — |
| Rename a key in every locale and call site | `Shift+F6` | — |
| Machine translation, AI agents (MCP) | Yes, opt-in | — |
| Checks in CI | `i18n-check`, GitHub Action | — |

## i18n Studio (JetBrains)

**i18n Studio** (`in.puzaricloud.i18nstudio`, by PuzariCloud) is a closed-source plugin for IntelliJ-based IDEs 2025.2+,
first released on 2026-10-25 and downloaded 9 times on 2026-11-14. It is freemium: a free part, and a Pro part with a
30-day trial. It was not installed for this page: the right column says what its
[Marketplace page](https://plugins.jetbrains.com/plugin/35461-i18n-studio) says, and nothing more.

| | I18n Support Plus | i18n Studio 1.0.1 |
|---|---|---|
| Price | Free, open source (MIT) | Free part; Pro after a 30-day trial |
| Keys in the code | Need the JavaScript plugin (WebStorm, IntelliJ IDEA Ultimate, PhpStorm) | "Works without the JavaScript plugin" |
| Setup | Wizard and per-framework presets | "Needs no setup wizard"; frameworks detected |
| Translation files | JSON, JSON5, YAML, PO/POT, TS/JS catalogues, Laravel PHP arrays, Flutter ARB, `.properties`, XLIFF (Angular, Symfony) | JSON, JSON5, YAML, JS/TS, PHP, PO, XLIFF 1.2/2.0, ARB, `.properties`, Android XML; iOS `.strings` and `.xcstrings` |
| Unknown key in the code | Inspection, quick fix to create it | Free: quick fix to create it or use a similar key |
| Values | Empty, duplicate, placeholders, ICU, markup, copied, punctuation, length, naming… | Free: empty and fuzzy values, duplicate keys; Pro: placeholders, ICU, plural forms |
| Table, rename, move, extract, unused keys, Git branch, CSV/XLIFF, machine translation, AI agents (MCP) | Yes | Pro |

## What the others do better

- **i18n Ally, i18n Ally Next, Sherlock and x18n run in VS Code.** If your team uses VS Code, I18n Support Plus is not an
  option: it is a JetBrains plugin.
- **x18n works without an editor**: a CLI, a browser UI for translators and reviewers, and an MCP server an agent starts
  itself, over stdio, with no IDE open ([MCP](https://github.com/x18n-dev/x18n/blob/main/docs/content/3.agents/3.mcp.md)). In CI it writes SARIF and keeps a baseline,
  so a build fails only on new problems ([CI](https://github.com/x18n-dev/x18n/blob/main/docs/content/2.surfaces/5.ci.md)). I18n Support Plus's agent tools live on the
  IDE's MCP server, and `i18n-check` has neither.
- **i18n Ally Next and x18n detect translations left behind by a changed source text.** i18n Ally Next offers to translate them again,
  one by one or all at once ([announcement](https://github.com/lydanne/i18n-ally-next/blob/main/docs/blog/introducing-i18n-ally-next.md)).
  x18n finds them in the git history of the locale files
  ([docs](https://github.com/x18n-dev/x18n/blob/main/docs/content/9.checks/stale-translation.md)).
  I18n Support Plus records the source text only in modules keeping reviews; elsewhere it catches the locales lagging behind
  a change of the reference, in the working copy or against a branch, not a translation outdated by an older change.
- **i18n Ally reviews from the editor**: approvals, requested changes, comments and suggested values in the gutter, the side panel and its Editor UI,
  stored in the repository ([wiki](https://github.com/lokalise/i18n-ally/wiki/Review-&-Collaboration-System)).
  I18n Support Plus reviews from the tool window table, the key dialog and the gutter of a JSON or YAML translation file;
  not from the source code where a key is used, and it has no side panel.
- **i18n Ally has a full-screen Editor UI** for one key across every locale
  ([wiki](https://github.com/lokalise/i18n-ally/wiki/Editor-UI)).
- **i18n Ally and x18n cover more frameworks**: Flutter, Rails, Ember, Polyglot, SAP UI5, Chrome extensions and a custom
  framework definition; x18n also reads Fluent, INI and .NET `.resx` files ([README](https://github.com/x18n-dev/x18n#features)).
- **i18n Studio reads keys in the code without the JavaScript plugin**, and Android, iOS and Rails translation files,
  according to its [Marketplace page](https://plugins.jetbrains.com/plugin/35461-i18n-studio).
- **Easy I18n works outside JavaScript**: Java, Kotlin, Python, Go, Rust, Ruby, Dart, with a Spring Boot preset and
  `.properties` files, in any IntelliJ-based IDE including those without the JavaScript plugin.
  I18n Support Plus installs there too, with the tool window, the inspections and the agent tools on the translation
  files; its keys in code need the JavaScript plugin. It reads `.properties` bundles and leaves Java calls to IntelliJ
  IDEA's own support.
- **i18n Ally for JetBrains reads XLIFF translation files in any project.** I18n Support Plus reads XLIFF as a
  translation source in an Angular project, the files `angular.json` lists, and in a Symfony project, the catalogues of
  `translations/`; elsewhere it exports and imports XLIFF.

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
| `i18n-ally.translate.engines` | *Settings | Tools | i18n Support Plus | Machine Translation*: engines tried in order |
| `i18n-ally.refactor.templates` | The module's *Call template* (`translate({key})`, `t({key}, {text})` with the text as default value) |
| `i18n-ally.sortKeys` | *Re-sort JSON files after each new key* (or *Sort i18n Keys* on demand), in the *Key order* you choose |
| Custom framework (`.vscode/i18n-ally-custom-framework.yml`) | *Key assistance rules*: a trigger per function name; *Import from i18n Ally…* reads the file and shows what it takes over |

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

Then run **Tools › i18n Support Plus › Run Setup Wizard…**: it detects the framework and the translation files and
fills the rest.
