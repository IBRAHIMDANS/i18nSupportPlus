<p align="center">
  <a href="https://plugins.jetbrains.com/plugin/30971-i18n-support-plus">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="src/main/resources/META-INF/pluginIcon_dark.svg">
      <img src="src/main/resources/META-INF/pluginIcon.svg" alt="I18n Support Plus logo" width="96" height="96">
    </picture>
  </a>
</p>

<h1 align="center">I18n Support Plus</h1>

<p align="center">
  <b>Your i18n keys, as first-class code symbols.</b><br>
  WebStorm, IntelliJ IDEA Ultimate and PhpStorm 2025.2 and later.
</p>

<p align="center">
  <a href="https://plugins.jetbrains.com/plugin/30971-i18n-support-plus"><img src="https://img.shields.io/jetbrains/plugin/v/30971?label=Marketplace" alt="JetBrains Marketplace version"></a>
  <a href="https://plugins.jetbrains.com/plugin/30971-i18n-support-plus"><img src="https://img.shields.io/jetbrains/plugin/d/30971" alt="Downloads"></a>
  <a href="https://github.com/IBRAHIMDANS/i18nSupportPlus/actions/workflows/ci.yml"><img src="https://github.com/IBRAHIMDANS/i18nSupportPlus/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License: MIT"></a>
</p>

<p align="center">
  <img src="docs/img/marketplace/extract-i18n-key.gif" alt="Extracting a JSX text into a key, its value written in every locale" width="720">
</p>

<p align="center">
  <a href="https://plugins.jetbrains.com/plugin/30971-i18n-support-plus"><img src="https://img.shields.io/badge/Install%20from-JetBrains%20Marketplace-000000?style=for-the-badge&logo=jetbrains&logoColor=white" alt="Install from JetBrains Marketplace"></a>
</p>

<p align="center">
  <a href="docs/COMPARISON.md">Compare with i18n Ally and Easy I18n</a>
  ·
  <a href="examples">Examples</a>
  ·
  <a href="CHANGELOG.md">Changelog</a>
  ·
  <a href="https://github.com/IBRAHIMDANS/i18nSupportPlus/issues/new?template=bug_report.md">Report a bug</a>
  ·
  <a href="https://github.com/IBRAHIMDANS/i18nSupportPlus/issues/new?template=feature_request.md">Request a feature</a>
</p>

<!-- Plugin description -->
Turns i18n keys into first-class code symbols in **WebStorm, IntelliJ IDEA Ultimate and PhpStorm**: see each key's value and status, jump to it, rename it, translate it and keep every locale in sync.

Works with **i18next / react-i18next, vue-i18n (Nuxt), next-intl, lingui, react-intl, ngx-translate, Transloco, Angular i18n (`$localize`), svelte-i18n, i18n-js, Paraglide JS, typesafe-i18n, Meteor universe:i18n and Laravel / Blade**, in JS, TS, JSX, TSX, Vue SFC, Svelte, Astro, Angular templates, PHP and Blade, with **JSON, JSON5, YAML, PO/POT, XLIFF, Laravel PHP arrays, Flutter ARB, Java `.properties`** and plain **TS/JS catalogues**. A setup wizard configures it on first launch, monorepo modules included.

- **See** — gutter status per key (all / some / no locale), inline value hints, folding and hover in a preview locale, completion of real keys, namespaces included
- **Edit safely** — extract hard-coded strings (variables and plural forms included), rename a key or a whole level across every locale and call site (`Shift+F6`), move a key to another namespace; one Ctrl+Z undoes the files and the code
- **Translate** — opt-in machine translation of empty locales, for one key or a whole locale, through a local Ollama (nothing leaves the machine), DeepL, LibreTranslate, Google or any OpenAI-compatible API; variables, tags and the target language's plural forms are kept, and nothing is written before a preview
- **AI agents** — Codex, Claude or any MCP client translates through the plugin's tools on the IDE's MCP server, on your own AI plan: only missing values are written, placeholders and plural forms are checked, and one Ctrl+Z undoes it (2025.2.6+)
- **Plurals done right** — i18next `_one` / `_other` keys and nested plural objects resolve, missing CLDR forms per language are reported, ICU messages are validated
- **Stay healthy** — tool window with tree and table views, filters for missing, empty, duplicate and unreferenced keys, coverage per namespace and locale, a keep list for keys used outside the code, keys synchronizer, cleanup, CSV and XLIFF export / import, comparison with a Git branch
- **In CI** — `i18n-check` runs the same checks from the command line or as a GitHub Action that annotates the pull request, and `i18n-check.json` shares the configuration with the team
- **Your own calls** — rules turn `translate('key')` or `__('key')` into translation calls, and a module can write its own wrapper's call at extraction

**Coming from I18n Support?** This plugin is the maintained successor of the original, which no longer installs on IDEs after 2023.1: install it next to the old one, it reads your project settings as they are — see [the migration notes](https://github.com/IBRAHIMDANS/i18nSupportPlus/blob/main/docs/COMPARISON.md#coming-from-i18n-support).

[Documentation](https://github.com/IBRAHIMDANS/i18nSupportPlus#readme) · [Compare with i18n Ally and Easy I18n](https://github.com/IBRAHIMDANS/i18nSupportPlus/blob/main/docs/COMPARISON.md) · [Examples](https://github.com/IBRAHIMDANS/i18nSupportPlus/tree/main/examples) · [Changelog](https://github.com/IBRAHIMDANS/i18nSupportPlus/blob/main/CHANGELOG.md)
<!-- Plugin description end -->

## Contents

- [Installation](#installation) · [Why this plugin?](#why-this-plugin) · [Features](#features) · [Supported Frameworks](#supported-frameworks)
- [Requirements](#requirements) · [Build from Source](#build-from-source) · [Contributing](#contributing)

The reference, one page per topic, in [`docs/`](docs):

- [Frameworks, languages and file formats](docs/frameworks.md): every syntax read, per framework, and the file formats
- [In the editor](docs/editor.md): key status, navigation, completion, extraction, hints, folding, renaming, quick fixes
- [Tool window and bulk actions](docs/tool-window.md): tree and table, reviews, stats, keys synchronizer, export and import
- [Machine translation and AI agents](docs/translation.md): engines, glossary, tone, MCP tools
- [Checks: inspections, CI and the command line](docs/checks.md): inspections, Qodana, `i18n-check`, GitHub Action, Docker
- [Setup and configuration](docs/configuration.md): setup wizard, modules, every setting, `i18n-check.json`

## Installation

In the IDE, **Settings › Plugins › Marketplace**, search for **I18n Support Plus** (plugin ID `com.ibrahimdans.i18n`) and install it, or click *Install* on its [Marketplace page](https://plugins.jetbrains.com/plugin/30971-i18n-support-plus). It needs WebStorm, IntelliJ IDEA Ultimate or PhpStorm 2025.2 or later ([Requirements](#requirements)).
On first launch, the setup wizard detects the framework and the translation files and configures the rest.

## Why this plugin?

On a real i18next/vue-i18n project, your keys and translations live in files
your editor treats as dead text — so you keep opening JSON files just to check
whether a key exists, in which namespace, and whether it's translated everywhere.

**I18n Support Plus turns i18n keys into first-class code symbols.**

- **See & navigate** — inline resolution status (gutter ✅/⚠️/❌), Ctrl+Click to the
  translation and back, value hints (inlay + folding + hover), autocomplete from your
  real keys (namespaces included), dynamic/wildcard keys supported.
- **Edit safely** — extract hard-coded strings (`Alt+Enter`), rename across all
  locales and code (`Shift+F6`), move a key to another namespace (`Ctrl+Alt+Shift+M`),
  sort keys in the order you choose.
- **Stay healthy** — tool window with tree/table views grouped by namespace, coverage
  stats per namespace and locale, Keys Synchronizer (propagate missing keys in bulk),
  Count Key Usages (find keys no code refers to), CSV and XLIFF export/import, and inspections for missing,
  empty, duplicate or inconsistent values.

Works with **i18next / react-i18next, vue-i18n, next-intl, lingui, react-intl, ngx-translate, Transloco, Angular i18n,
svelte-i18n, i18n-js, Paraglide JS, typesafe-i18n, Meteor universe:i18n and Laravel / Blade**, across JS/TS/JSX/TSX, Vue SFC,
Svelte, Astro, Angular templates, PHP and Blade, with JSON, JSON5, YAML, PO/POT, XLIFF, Laravel PHP arrays, Flutter ARB, Java `.properties` and
TS/JS catalogues.
A setup wizard auto-configures it on first launch, a module per application in a monorepo.

**Coming from i18n Ally or Easy I18n?** See the [feature-by-feature comparison](docs/COMPARISON.md) and how to carry your settings over.

## Features

### See every key where you use it

A gutter icon tells whether a key exists in all, some or no locale. Its value shows as an inline hint, in a Ctrl+hover table of every locale, or folded in place of the key. Ctrl+click opens the translation, and completion offers the keys that really exist, namespaces included.

![Completing a key, with each key's value](docs/img/marketplace/key-completion.gif)

Details: [In the editor](docs/editor.md).

### Extract, rename and move keys safely

`Alt+Enter` turns a hard-coded string into a key, written in every locale; *Internationalize…* does a whole file or folder after a preview. `Shift+F6` renames a key, or a whole level, in every locale and call site, and `Ctrl+Alt+Shift+M` moves a key to another namespace. One Ctrl+Z undoes the files and the code.

![Creating an unknown key with its values](docs/img/marketplace/create-key-quickfix.gif)

Details: [Key Extraction](docs/editor.md#key-extraction), [Rename Refactoring](docs/editor.md#rename-refactoring), [Bulk Actions](docs/tool-window.md#bulk-actions).

### Every key of every locale in one table

The tool window shows the keys as a tree or an editable table, filters the missing, empty, duplicate and unreferenced ones, and gives the coverage of each namespace and locale. It also holds the keys synchronizer, the reviews, CSV and XLIFF export and import, and the comparison with a Git branch.

![The table view](docs/img/toolwindow-table.png)

Details: [Tool window and bulk actions](docs/tool-window.md).

### Translate the missing values, or let an AI agent do it

Machine translation is opt-in, through a local Ollama, DeepL, LibreTranslate, Google or any OpenAI-compatible API. Placeholders, tags and the target language's plural forms are kept, and nothing is written before a preview. Codex, Claude or any MCP client can do the same through the plugin's tools on the IDE's MCP server.

Details: [Machine translation and AI agents](docs/translation.md).

### Catch the problems in the IDE and in CI

Nineteen inspections check the translations and the code: missing, orphan, empty and duplicate keys, placeholders, markup, ICU messages, plural forms, stale translations and hard-coded text. `i18n-check` runs the same checks in CI, as a GitHub Action or a Docker image, and annotates the pull request.

Details: [Checks: inspections, CI and the command line](docs/checks.md).

### Set up in one step

On first launch, the setup wizard detects the framework and the translation files, and creates a module per application in a monorepo. The settings can be shared with the team in `i18n-check.json`.

![The setup wizard](docs/img/Setup-wizard-step-1.png)

Details: [Setup and configuration](docs/configuration.md).

## Supported Frameworks

| Framework | For example |
|-----------|-------------|
| i18next / react-i18next | `t('ns:key')`, `useTranslation('ns')`, `<Trans i18nKey="key">` |
| vue-i18n (Nuxt) | `$t('key')`, `<i18n-t keypath="key">`, `v-t="'key'"` |
| next-intl | `const t = useTranslations('Home')`, then `t('title')` |
| Lingui | `i18n._('key')`, `msg('key')`, `<Trans>Hello world!</Trans>` |
| react-intl (FormatJS) | `formatMessage({ id: 'key' })`, `<FormattedMessage id="key" />` |
| ngx-translate | `translate.instant('key')`, `{{ 'key' \| translate }}` |
| Transloco | `translocoService.translate('key')`, `{{ 'key' \| transloco }}` |
| Angular i18n | `<h1 i18n="@@home.title">`, `` $localize`:@@home.title:Welcome` `` |
| svelte-i18n | `$_('key')` |
| i18n-js | `i18n.t('key')` |
| Paraglide JS | `m.hello()` |
| typesafe-i18n | `LL.home.title()` |
| Meteor universe:i18n | `const t = useTranslator('Home')`, then `t('title')` |
| Laravel / Blade | `__('key')`, `trans_choice('key', 2)`, `@lang('key')` |

The keys are read in JS, TS, JSX, TSX, Vue SFC, Svelte, Astro, Angular templates, PHP and Blade, and the translations in JSON, JSON5, YAML, PO/POT, XLIFF, Laravel PHP arrays, Flutter ARB, Java `.properties` and TS/JS catalogues. Every syntax, language and format: [Frameworks, languages and file formats](docs/frameworks.md).

## Requirements

- IntelliJ IDEA 2025.2+ (build 252–263.*), verified against 2025.2, 2025.3, 2026.1, 2026.2 and 2026.3
- On 2025.1, the last compatible version is 1.11.1
- Java 21+

### Plugin Dependencies

| Plugin | Required | Enables |
|--------|:--------:|---------|
| JavaScript | Yes | Core JS/TS support |
| Vue.js | No | vue-i18n support (`$t`, `$tc`, `$te`, `<i18n-t keypath>`, `v-t`) |
| YAML | No | `.yaml`/`.yml` translation files |
| PHP | No | PHP language support |
| Localization | No | PO/POT gettext files |
| Angular | No | Translation pipes and Transloco directives in Angular templates (`.html`) |
| Blade | No | Keys in Blade views (`{{ __('key') }}`, `@lang`) |
| Svelte | No | Keys in `.svelte` components |
| Astro | No | Keys in `.astro` components |
| Git | No | *Compare with Branch* (translation changes of a branch) |
| MCP Server | No | The tools AI agents call (2025.2.6+) |

## Build from Source

```bash
git clone https://github.com/IBRAHIMDANS/i18nSupportPlus.git
cd i18nSupportPlus
./gradlew buildPlugin
# Output: "build/distributions/i18n Support Plus-<version>.zip"
```

Then install via **Settings > Plugins > ⚙️ > Install Plugin from Disk**.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for contributor setup, run/test commands, architecture notes, conventions, and troubleshooting tips.

## Credits

Originally created by [Evgeniy Nyavro](https://github.com/nyavro/i18nPlugin).

Maintained by [Ibrahim Dansoko](https://github.com/IBRAHIMDANS). Licensed under [MIT](LICENSE).
