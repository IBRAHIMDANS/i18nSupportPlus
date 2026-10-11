# Coming from i18n Ally or Easy I18n?

_Last verified: 2026-10-11. Re-check at every major release of any of the tools below._

This page compares **I18n Support Plus** with the two tools people most often use for i18n keys in an editor:
**i18n Ally** (by Lokalise, for VS Code and for JetBrains IDEs) and **Easy I18n** (for JetBrains IDEs). Each claim
about another tool links to its documentation, its source code or its issue tracker at the version below. If one is
wrong or out of date, [open an issue](https://github.com/IBRAHIMDANS/i18nSupportPlus/issues) and it will be fixed.

| Tool | Version compared | Released | Source |
|---|---|---|---|
| I18n Support Plus | 1.9.0, plus `main` (marked <sup>next</sup>) | 2026-10-10 | [CHANGELOG](../CHANGELOG.md) |
| Easy I18n | 5.0.2 | 2026-05-03 | [Marketplace](https://plugins.jetbrains.com/plugin/16316-easy-i18n), [docs](https://marhali.github.io/easy-i18n/), [GitHub](https://github.com/marhali/easy-i18n) |
| i18n Ally for JetBrains | 1.4.3.2 | 2024-10-12 | [Marketplace](https://plugins.jetbrains.com/plugin/17212-i18n-ally) |
| i18n Ally for VS Code | 2.13.1 | 2024-12-13 | [VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=lokalise.i18n-ally), [wiki](https://github.com/lokalise/i18n-ally/wiki), [GitHub](https://github.com/lokalise/i18n-ally) |

In the tables, **—** means the tool's documentation says nothing about it at that version. It does not mean the
feature is missing.

## i18n Ally alternative for JetBrains IDEs (WebStorm, IntelliJ IDEA, PhpStorm)

i18n Ally exists for JetBrains IDEs too, as a separate plugin with its own feature set. Its latest version, 1.4.3.2,
installs on **builds 241 to 242 only, i.e. 2024.1 and 2024.2**. It cannot be installed on 2024.3, 2025.x or 2026.x
([Marketplace versions](https://plugins.jetbrains.com/plugin/17212-i18n-ally/versions),
[issue #1379](https://github.com/lokalise/i18n-ally/issues/1379), open). I18n Support Plus runs on 2025.1 to 2026.3
(builds 251–263).

## Feature by feature

### Where it runs

| | I18n Support Plus | Easy I18n | i18n Ally (JetBrains) | i18n Ally (VS Code) |
|---|---|---|---|---|
| Editor | WebStorm, IntelliJ IDEA Ultimate, PhpStorm 2025.1+ (needs the bundled JavaScript plugin) | Any IntelliJ-based IDE 2025.2+ | IntelliJ-based IDEs 2024.1–2024.2 | VS Code |
| Code languages | JS, TS, JSX, TSX, Vue SFC, Angular templates, Svelte, PHP, Blade | HTML/XML, JS(X), TS(X), Vue, Svelte, PHP, Ruby, Rust, Go, Dart, Python, Kotlin, Java ([Marketplace](https://plugins.jetbrains.com/plugin/16316-easy-i18n)) | JS, TS, React, Vue, PHP, Blade, Twig ([Marketplace](https://plugins.jetbrains.com/plugin/17212-i18n-ally)) | Per framework, including Flutter, Rails and Ember ([wiki](https://github.com/lokalise/i18n-ally/wiki/Supported-Frameworks)) |
| Translation files | JSON, JSON5, YAML, PO/POT, TS/JS catalogues, Laravel PHP arrays <sup>next</sup> | JSON, JSON5, YAML, Properties | JSON, JS/TS, PHP, PO/POT, XLIFF 1/2, YAML | JSON, YAML, JSON5; INI, properties, POT and read-only JS/TS/PHP when enabled ([wiki](https://github.com/lokalise/i18n-ally/wiki/Locale-Formats)) |
| Laravel PHP array files (`lang/en/*.php`) | Yes, read and written, the file name as namespace; package keys (`package::file.key`) not yet <sup>next</sup> | Preset reads `resources/lang/{locale}.json` ([presets](https://marhali.github.io/easy-i18n/configuration/presets/)) | Yes | Read-only |
| Monorepo, one configuration per app | Modules (root, path template, key template, preset) | Modules, since 5.0 ([migration](https://marhali.github.io/easy-i18n/migration/v5/)) | — | Multi-root workspaces |
| Setup | Wizard and per-framework presets | Per-framework presets | Automatic for Vue, Nuxt, Laravel, Symfony, Yii, CodeIgniter, CakePHP | Framework detected from `package.json` |

### Frameworks

| | I18n Support Plus | Easy I18n | i18n Ally (JetBrains) | i18n Ally (VS Code) |
|---|---|---|---|---|
| i18next namespace `t('ns:key')` | Yes | React i18next preset | — | Yes, `:` or `/` |
| Namespace of `useTranslation('ns')` | Yes, string and array | — | — | — |
| `keyPrefix` of `useTranslation` | Yes | — | — | Open request ([#719](https://github.com/lokalise/i18n-ally/issues/719)) |
| i18next `fallbackNS` | Yes, read from the i18next config | — | — | — |
| i18next v4 plurals (`key_one`, `key_other`) | Resolved; forms a language lacks are reported (CLDR) | — | — | Suffixes count as usages ([source](https://github.com/lokalise/i18n-ally/blob/main/src/frameworks/react-i18next.ts)); [#688](https://github.com/lokalise/i18n-ally/issues/688) open |
| vue-i18n `<i18n-t keypath>`, `v-t` | Resolved, navigable, checked; completion and folding <sup>next</sup> | — | — | — |
| vue-i18n linked messages `@:key` | Yes <sup>next</sup> | — | — | Yes |
| next-intl `useTranslations` / `getTranslations` | Yes (`getTranslations` <sup>next</sup>) | — | — | next-intl listed in its [source](https://github.com/lokalise/i18n-ally/tree/main/src/frameworks) |
| Transloco | Service, pipe, structural directive (`read` <sup>next</sup>), scopes | Preset: service and attribute directive | — | Yes |
| Laravel `__()`, `trans()`, `trans_choice()`, `@lang` | Yes | Preset: `__`, `trans`, `trans_choice` | Yes, Blade included | Yes |
| Your own `translate()` wrapper | Key assistance rules, per-module call template | Editor rules, flavor template | — | Custom framework, refactor templates |

### Reading keys in the code

| | I18n Support Plus | Easy I18n | i18n Ally (JetBrains) | i18n Ally (VS Code) |
|---|---|---|---|---|
| Value next to the key | Inlay hint, folding, Ctrl+hover table of every locale | Folding, documentation popup | Text instead of keys (toggle) | Inline annotation, hover |
| Status per key | Gutter icon (all / some / no locale), annotation | Unresolved key inspection | Missing strings inspection | Missing translations reported |
| Navigation | Ctrl+click to the value and back, Find Usages | Go To Declaration opens the edit dialog | Ctrl+click to the file | Go to the key |
| Completion of real keys | Yes, by key or by value | — | — | — |
| Why a key is unresolved | Namespace, module and files looked up, in the tooltip <sup>next</sup> | — | — | — |

### Editing keys

| | I18n Support Plus | Easy I18n | i18n Ally (JetBrains) | i18n Ally (VS Code) |
|---|---|---|---|---|
| Extract a hardcoded string | Dialog: reuse an existing key, namespace, parent group, variables, plural forms | Extract translation action | Quick fix, and in bulk | Quick fix, and batch per file |
| Rename a key everywhere | `Shift+F6`: every locale and call site, a whole level too, one undo | — | Keys and params | `rename-key` command |
| Move a key to another namespace | Yes | — | — | — |
| Edit a value from the code | Shortcut or hint link <sup>next</sup> | Go To Declaration opens the dialog | — | Hover action |
| Delete a key from every locale | Yes, warns when still used | Yes, from the tool window | — | `delete-key` command |

### Keeping translations healthy

| | I18n Support Plus | Easy I18n | i18n Ally (JetBrains) | i18n Ally (VS Code) |
|---|---|---|---|---|
| Overview | Tool window: tree, editable table, coverage matrix per namespace and locale | Tool window: tree and editable table | — | Sidebar, Editor UI (webview) |
| Missing translations | Inspection, filters, keys synchronizer | Missing filter | Inspection over project, folder or file | Reported |
| Unused keys | Inspection, Scan Orphans, Cleanup Unused Keys | — | — | Usage Report: keys not in use |
| Keys used outside the code | Keep list (exact, prefix, glob) | — | — | `keysInUse` |
| Locales left out of the checks | Yes <sup>next</sup> | — | — | `ignoredLocales`: locales ignored altogether |
| Value checks | Empty, duplicate, placeholders, ICU, markup, copied from reference, punctuation, length | Duplicate filter | — | — |
| CSV / XLIFF export and import | Yes, with preview | — | — | — |
| Translation changes of a branch | Compare with a Git branch | — | — | — |

### Machine translation

| | I18n Support Plus | Easy I18n | i18n Ally (JetBrains) | i18n Ally (VS Code) |
|---|---|---|---|---|
| Engines | Ollama (local), any OpenAI-compatible API, DeepL, LibreTranslate, Google | — | — | Google (default), DeepL, LibreTranslate, OpenAI (custom API root), Baidu ([wiki](https://github.com/lokalise/i18n-ally/wiki/Machine-Translation), [source](https://github.com/lokalise/i18n-ally/tree/main/src/translators/engines)) |
| Opt-in | Yes: nothing is sent until enabled for the project | — | — | No: Google is the default engine |
| Placeholders and tags kept | Masked before sending | — | — | — |
| Plural forms of the target language | Yes | — | — | — |
| Preview before writing | Yes, one undo | — | — | Can save as review candidates |
| Fill a whole locale | Yes, every locale at once <sup>next</sup> | — | — | `fulfill-keys` |

## What the others do better

- **i18n Ally runs in VS Code.** If your team uses VS Code, I18n Support Plus is not an option: it is a JetBrains plugin.
- **i18n Ally has a review system**: approve a translation, request a change, comment, stored in the repository
  ([wiki](https://github.com/lokalise/i18n-ally/wiki/Review-&-Collaboration-System)). I18n Support Plus has none.
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
| `i18n-ally.refactor.templates` | The module's *Call template* (`translate({key})`) |
| `i18n-ally.sortKeys` | *Sort keys alphabetically* |
| Custom framework (`.vscode/i18n-ally-custom-framework.yml`) | *Key assistance rules*: a trigger per function name |

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
