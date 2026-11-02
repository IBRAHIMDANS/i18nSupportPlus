# Frameworks, languages and file formats

Every syntax the plugin reads, per framework, the languages it reads them in, and the translation file formats.

[← Back to the README](../README.md)

## Supported Frameworks

| Framework | Recognised syntaxes | Config-based |
|-----------|--------------------|:------------:|
| i18next / react-i18next | `t('ns:key')`, `` t(`key.${suffix}`) ``, `t('key', { ns: 'other' })`, `t('key', { context: 'male' })`, `useTranslation('ns')`, `<Trans i18nKey="key" ns="other">`, `<Trans t={t}>`; in a translation, nesting `$t(key)` and `$t(ns:key, { … })` | Yes |
| vue-i18n (Nuxt: `@nuxtjs/i18n`) | `$t('key')`, `$tc('key')`, `$te('key')`, `<i18n-t keypath="key">`, `v-t="'key'"`, `const { t, te } = useI18n()` then `t('key')` | No |
| next-intl | `const t = useTranslations('Home')` (or `await getTranslations('Home')`) then `t('title')`, read as the key `Home.title`; `t.rich('terms', { … })`, `t.markup`, `t.raw` (an object or an array is a value) and `t.has` (a usage, never reported missing) | No |
| lingui (`@lingui/core`, `@lingui/react`, `@lingui/macro`) | `msg('key')`, `i18n._('key')`, source-based `<Trans>Hello world!</Trans>` | No |
| react-intl (FormatJS) | `formatMessage({ id: 'key' })`, `<FormattedMessage id="key" />` | No |
| ngx-translate (Angular) | `translate.instant('key')`, `.get('key')`, `.stream('key')`, `{{ 'key' \| translate }}` <sup>1</sup>, `<p translate>key</p>`, `translate="key"`, `[translate]="'key'"` | No |
| Transloco (Angular) | `translocoService.translate('key')`, `.selectTranslate('key')`, standalone `translate('key')` and `translateSignal('key')`, `{{ 'key' \| transloco }}` <sup>1</sup>, `transloco="key"`, `[transloco]="'key'"`, `*transloco="let t"` or `<ng-template transloco let-t>` then `t('key')` <sup>4</sup> | No |
| svelte-i18n | `$_('key')`, `$t`, `$format`, `$_({ id: 'key' })`, `$json('prefix')`, `_('key')` <sup>2</sup> | No |
| i18n-js (React Native / Expo) | `t('key')`, `i18n.t('key')`, plain locale-keyed TS/JS catalogues, nested plurals <sup>3</sup> | No |
| Paraglide JS (inlang) | `m.hello()`, `m["user.profile.title"]()`, once the file imports `m` from `…/paraglide/messages.js` <sup>5</sup> | Yes, `project.inlang/settings.json` |
| typesafe-i18n | `LL.home.title()`, `$LL.home.title()` (Svelte), read as the key `home.title`, once the file imports typesafe-i18n's generated code <sup>7</sup> | Yes, `.typesafe-i18n.json` |
| Meteor universe:i18n | the application's own hook, `useTranslator('Home')` or `useTranslation('Home')`, then `t('title')`, read as the key `Home.title` <sup>6</sup> | No |
| Angular i18n (`@angular/localize`) | `<h1 i18n="@@home.title">`, `i18n-alt="@@logo.alt"`, `` $localize`:@@home.title:Welcome` ``, read as the key `home.title` <sup>8</sup> | Yes, `angular.json` |

`useTranslation` supports both string form (`useTranslation('ns')`) and array form (`useTranslation(['ns1', 'ns2'])`). The namespace can also come from an options object (`t('key', { ns: 'auth' })`) or from the key itself (`t('auth:key')`). A `context` option reads the key's variant, as i18next does: `t('friend', { context: 'male' })` reads `friend_male`, else `friend`, and `t('friend', { context: gender })` any `friend_…` variant; those variants are then used, never reported as having no reference in code.

<sup>1</sup> The `| translate` and `| transloco` pipes are recognised in JSX/TSX and in standalone Angular templates (`.html`). A template is parsed as Angular only inside an Angular project — a component referencing it through `templateUrl`, with `@angular/core` resolvable; outside one the file stays plain HTML and the pipe is inert text.

<sup>2</sup> `.svelte` single-file components are analysed as well, provided the [Svelte plugin](https://plugins.jetbrains.com/plugin/12375-svelte) is installed — it is not bundled with IntelliJ Ultimate, and without it a `.svelte` file is plain text to the IDE. With it, both the `<script>` block and `{…}` expressions in the markup are ordinary JavaScript, so keys inside them resolve like any other.

<sup>3</sup> i18n-js pluralizes into a nested object (`{ one: …, other: … }`) rather than through i18next's flat `key_one` suffixes; such a key is treated as resolved. Its `%{count}` placeholders are displayed verbatim, as the plugin interprets no interpolation syntax.

<sup>4</sup> Transloco's service calls are recognised when the object they are called on names the service (`translocoService`, `transloco`): `translate` alone is too common a method name, and a bare `translate('key')` is Transloco's only in a file importing `@jsverse/transloco`. Scoped keys resolve in their scope file: `admin.title` reads the key `title` of `i18n/admin/en.json`, a folder named after its scope next to the global locale files. The first segment is read as a scope only when such a folder exists, and only in a Transloco project; a key naming no scope is never looked up in a scope file. A scope set by the directive is read: `*transloco="let t; read: 'admin'"` or `prefix: 'admin'`, then `t('title')`, and `[translocoRead]` / `[translocoPrefix]` bound to a string literal on `<ng-template transloco let-t>`.

<sup>5</sup> Paraglide compiles each message into a function, so the key is the
member of the message object, not a string. It is read in a file importing that object from a path ending in `paraglide/messages` — `{ m }`,
`{ m as msg }`, or Paraglide 1's `import * as m` — in `.js`, `.ts`, Svelte components (with the Svelte plugin) and Astro components (with the Astro plugin).
Annotation, completion after `m.` (with the messages not yet compiled), folding, hover and inlay hints, Ctrl+click to
the message rather than the generated function, and from a message file to its call sites, usages, Shift+F6 (in every message file and call site, `m["a.b"]` when the new
name is no identifier) and *Extract I18n* (which writes `m.key({ name })` and adds the import) all work on it. The message files are read where `project.inlang/settings.json` puts them, one inlang
project per folder in a monorepo, and the generated folder is never counted as a use. Limits: a message with variants
(a `match` array) is displayed as its catch-all branch and edited as raw JSON; `m[variable]` names no key; in Svelte components
only annotation, folding and usages are tested, in Astro components annotation, completion and the navigation from a
message file, in the frontmatter and in template expressions. A ready-to-open project:
[examples/paraglide-js](../examples/paraglide-js/README.md).

<sup>6</sup> universe:i18n ships no React hook since its v2: its documentation has the application write one, and the plugin reads the argument of `useTranslator` — or of `useTranslation`, unless the file imports it from an i18next package — as a key prefix once `.meteor/packages` declares `universe:i18n`. `i18n.__()`, `getTranslation()` and `*.i18n.json` files are not read yet.

<sup>7</sup> typesafe-i18n reads its messages as a member chain, so the key is the path of members after `LL` (or `$LL`, its Svelte store), not a string. It is read in a file importing typesafe-i18n's generated adapter or utilities (`…/i18n-react`, `…/i18n-svelte`, `…/i18n-util`, …). The messages are the object each `<locale>/index.ts` of the output folder default-exports — `outputPath` in `.typesafe-i18n.json`, `./src/i18n/` by default — each locale holding the default namespace. Annotation (unknown key, key missing in a locale) and completion after `LL.` and each level of it, with the messages not yet generated and their values, work on it. Not yet: Ctrl+click to the message (the IDE goes to the generated types), Shift+F6, folding and inlay hints, Node's `L.en.key()`. A ready-to-open project: [examples/typesafe-i18n](../examples/typesafe-i18n/README.md).

<sup>8</sup> Angular's native i18n names a message by its custom id, the part after `@@` of the marker's metadata
(`meaning|description@@id`), which is one key, never split at its dots; a marker without a custom id names none, as
`ng extract-i18n` then computes the id from the text. The messages are the XLIFF 1.2 or 2.0 files each project of
`angular.json` lists under `i18n.locales`, each with its locale, and the file `ng extract-i18n` writes — the
`outputPath` of its options, else `messages.xlf` next to the translations — holding the `sourceLocale`, the reference.
A locale's file reads each unit's `<target>`, the reference its `<source>`: a unit without a target is missing in that
locale, and *Fill Missing Translations…*, *Sync Keys* and the translation dialog write it after the unit's source, or
create the unit with the reference's source. An XLIFF file `angular.json` does not list, such as an export, is no
source. Extracting a template's text marks it with its id and adds the unit: `<p>Save</p>` becomes
`<p i18n="@@save">Save</p>`, an attribute gains `i18n-placeholder="@@id"`, a text outside any tag is wrapped in
`<ng-container i18n="@@id">`; a text holding interpolations is left to `ng extract-i18n`, which writes their
placeholders. This is the extraction's form in a module whose preset is *Angular i18n*, and without a preset in a project
whose `angular.json` lists translations and that does not use Transloco. Shift+F6 on an id — in a marker, a
`$localize` or a unit's `id` — renames it whole, dots included, in every marker and every XLIFF file; an id another
unit has is refused. The setup wizard proposes the *Angular i18n* preset for an `angular.json` listing translations or a
dependency on `@angular/localize`. Tested: annotation of both markers, completion in a template's marker, Ctrl+click
from either marker to its units and from a unit's `id` to the code, the tool window, the missing-key checks, the
extraction and the rename. Not tested: folding and inlay hints. A ready-to-open project:
[examples/angular-native-i18n](../examples/angular-native-i18n/README.md).

## Supported Languages

| Language | Extensions | Annotations | Completion | Folding | Hints | References |
|----------|-----------|:-----------:|:----------:|:-------:|:-----:|:----------:|
| JavaScript | `.js` | ✓ | ✓ | ✓ | ✓ | ✓ |
| TypeScript | `.ts` | ✓ | ✓ | ✓ | ✓ | ✓ |
| JSX | `.jsx` | ✓ | ✓ | ✓ | ✓ | ✓ |
| TSX | `.tsx` | ✓ | ✓ | ✓ | ✓ | ✓ |
| Vue SFC | `.vue` | ✓ | ✓ | ✓ | ✓ | ✓ |
| PHP | `.php` | ✓ | ✓ | ✓ | ✓ | ✓ |
| Blade | `.blade.php` | ✓ | — | ✓ | ✓ | ✓ |
| Angular templates | `.html` | ✓ | ✓ | — | — | ✓ |
| Svelte | `.svelte` | ✓ | — | ✓ | — | — |
| Astro | `.astro` | ✓ | ✓ | ✓ | — | ✓ |

**—** means no test covers it yet: it may work, it is not promised. A ✓ is backed by a test.

> Vue support requires the Vue.js plugin (optional dependency).
> PHP support requires the PHP plugin (optional dependency); Blade views, the Blade plugin, bundled in PhpStorm.
> Angular templates require the Angular plugin, and are read inside an Angular project only (see <sup>1</sup> above).
> Svelte components require the [Svelte plugin](https://plugins.jetbrains.com/plugin/12375-svelte) (see <sup>2</sup> above).
>
> Astro components (`.astro`, with astro-i18next or i18next) are analysed provided the [Astro plugin](https://plugins.jetbrains.com/plugin/20959-astro) is installed — it is not bundled with IntelliJ Ultimate, and without it a `.astro` file is plain text to the IDE. With it, `t('key')` in the `---` frontmatter and in `{…}` template expressions is ordinary JavaScript: annotations, completion, folding and references work as in a `.ts` file.
>
> In a Laravel project — a `composer.json` outside `vendor/` requiring `laravel/framework` or `illuminate/translation` — `__()`, `trans()` and `trans_choice()` are translation calls too, and extracting a string writes `__('…')`. Elsewhere `__()` is left alone: WordPress passes it a text, not a key. In Blade views (with the Blade plugin, bundled in PhpStorm), keys inside `{{ … }}` and `{!! … !!}` echoes are read like any PHP key, and so are those of the `@lang('key')` and `@choice('key', n)` directives. Laravel's translator called in PHP — `app('translator')->get('key')`, `->choice('key', n)`, the `Lang` facade — is recognised as well. Their values are folded in place and shown as inlay hints, in PHP and Blade; Shift+F6 renames a key in the PHP array of every locale; and a JSON phrase holding `.` or `:` — `__('Saved.')`, `__('Hello, :name')` — is looked up whole in `lang/<locale>.json`, as Laravel does.

## Translation File Formats

| Format | Extensions | References | Content generation |
|--------|-----------|:----------:|:------------------:|
| JSON | `.json`, `.json5` | ✓ | ✓ |
| YAML | `.yaml`, `.yml` | ✓ | ✓ |
| PO/POT (gettext) | `.po`, `.pot` | ✓ | ✓ |
| Laravel PHP arrays | `lang/<locale>/<file>.php`, the file name as namespace | ✓ | ✓ |
| Flutter ARB | `.arb` (`lib/l10n/app_<locale>.arb`), the locale from `@@locale` or the file name | — | ✓ |
| XLIFF 1.2 and 2.0 (Angular i18n) | `.xlf`, `.xliff`, the files `angular.json` lists (<sup>8</sup>) | ✓ | ✓ |
| TS/JS catalogues (an object keyed by locale, as i18n-js writes) | `.ts`, `.js` | ✓ | ✓ |
| i18next `resources` declared in a TS/JS configuration file | `.ts`, `.js` | ✓ | ✓ |

**—** means no test covers it yet. In the last two formats, a missing key is written into its object literal, in the
file's indentation, quotes and trailing commas; an object built in code (a spread, a computed key) is left as it is,
with a warning naming the key to add by hand. XLIFF is read as a translation source in an
Angular project only; elsewhere, like CSV, it is exported and imported from *Tools › i18n Support Plus*.

Flutter ARB catalogues feed the tool window, the statistics, the missing-key checks and *Sync Keys*; their `@key`
and `@@locale` metadata is no key. A key created in a catalogue that describes its keys, the template, gets its
`@key` block, with the `{placeholders}` of its value, and a key renamed or deleted takes its block along. Dart code is not read: `AppLocalizations.of(context)!.greeting` names its key as a
getter of the generated class, which the Dart analyzer already checks, so an ARB key is never reported unused.

Keys are resolved as nested properties by default (`app.header.title` walks three levels).
Projects storing **flat ids** — one property per key, as react-intl / FormatJS usually do —
should enable **Treat keys as flat** in *Settings | Tools | i18n Support Plus*.
The setting turns off key-separator splitting and namespace parsing altogether, so the whole
id is looked up as a single property and translation files are located through the default
namespace.
