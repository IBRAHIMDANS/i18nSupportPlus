# Frameworks, languages and file formats

Every syntax the plugin reads, per framework, the languages it reads them in, and the translation file formats. Each framework has its section below, with what it needs, its limits and a ready-to-open project from [`examples/`](../examples).

[← Back to the README](../README.md)

## Supported Frameworks

Two levels of support, so that what is promised is what is tested and maintained:

- **Supported**: the framework's calls are read, extracted, renamed and counted, in the editor and by `i18n-check --code`; a bug is fixed in the next weekly release; new features are built for it first. i18next / react-i18next, vue-i18n, next-intl, react-intl, ngx-translate, Transloco and Laravel / Blade: the frameworks the plugin's users have written about, and those of the IDEs it targets (React, Vue and Angular in WebStorm, Laravel in PhpStorm).
- **Read**: what the framework's section below says works, works and is tested; what it does not say is not promised. A bug is fixed when an [issue](https://github.com/IBRAHIMDANS/i18nSupportPlus/issues/new/choose) reports it; a feature is added when one asks for it, and a framework moves up when its users do. Lingui, Angular i18n, svelte-i18n, i18n-js, Paraglide JS, typesafe-i18n, Meteor universe:i18n, Symfony / Twig and Astro.

No framework or file format is added without an issue or a [discussion](https://github.com/IBRAHIMDANS/i18nSupportPlus/discussions/907) asking for it: the plugin collects no usage data, and the list below is already longer than what its users have asked for. This holds at least until May 2027; the time goes to the frameworks above and to what the issues ask.

| Framework | Support | Recognised syntaxes | Config-based | Example project |
|-----------|:-------:|--------------------|:------------:|-----------------|
| [i18next / react-i18next](#i18next--react-i18next) | Supported | `t('ns:key')`, `` t(`key.${suffix}`) ``, `t('key', { ns: 'other' })`, `t('key', { context: 'male' })`, `t('key', { returnObjects: true })`, `t('key', 'Default')` / `{ defaultValue }`, `t(['specific', 'generic'])`, `useTranslation('ns')`, `<Trans i18nKey="key" ns="other">`, `<Trans t={t}>`; in a translation, nesting `$t(key)` and `$t(ns:key, { … })` | Yes | [react-multi-namespace](../examples/react-multi-namespace/README.md), [monorepo-react](../examples/monorepo-react/README.md) |
| [vue-i18n](#vue-i18n) (Nuxt: `@nuxtjs/i18n`) | Supported | `$t('key')`, `$tc('key')`, `$te('key')`, `<i18n-t keypath="key">`, `v-t="'key'"`, `const { t, te } = useI18n()` then `t('key')` | No | [vue-i18n](../examples/vue-i18n/README.md) |
| [next-intl](#next-intl) | Supported | `const t = useTranslations('Home')` (or `await getTranslations('Home')`) then `t('title')`, read as the key `Home.title`; `t.rich('terms', { … })`, `t.markup`, `t.raw` (an object or an array is a value) and `t.has` (a usage, never reported missing) | No | [next-intl](../examples/next-intl/README.md) |
| [Lingui](#lingui) (`@lingui/core`, `@lingui/react`, `@lingui/macro`) | Read | `msg('key')`, `i18n._('key')`, source-based `<Trans>Hello world!</Trans>` | No | [lingui](../examples/lingui/README.md) |
| [react-intl](#react-intl-formatjs) (FormatJS) | Supported | `formatMessage({ id: 'key' })`, `<FormattedMessage id="key" />` | No | [react-intl](../examples/react-intl/README.md) |
| [ngx-translate](#ngx-translate-and-transloco) (Angular) | Supported | `translate.instant('key')`, `.get('key')`, `.stream('key')`, `{{ 'key' \| translate }}`, `<p translate>key</p>`, `translate="key"`, `[translate]="'key'"` | No | — |
| [Transloco](#ngx-translate-and-transloco) (Angular) | Supported | `translocoService.translate('key')`, `.selectTranslate('key')`, standalone `translate('key')` and `translateSignal('key')`, `{{ 'key' \| transloco }}`, `transloco="key"`, `[transloco]="'key'"`, `*transloco="let t"` or `<ng-template transloco let-t>` then `t('key')` | No | [angular-transloco](../examples/angular-transloco/README.md) |
| [Angular i18n](#angular-i18n) (`@angular/localize`) | Read | `<h1 i18n="@@home.title">`, `i18n-alt="@@logo.alt"`, `` $localize`:@@home.title:Welcome` ``, read as the key `home.title` | Yes, `angular.json` | [angular-native-i18n](../examples/angular-native-i18n/README.md) |
| [svelte-i18n](#svelte-i18n) | Read | `$_('key')`, `$t`, `$format`, `$_({ id: 'key' })`, `$json('prefix')`, `_('key')` | No | [svelte-i18n](../examples/svelte-i18n/README.md) |
| [i18n-js](#i18n-js) (React Native / Expo) | Read | `t('key')`, `i18n.t('key')`, plain locale-keyed TS/JS catalogues, nested plurals | No | — |
| [Paraglide JS](#paraglide-js) (inlang) | Read | `m.hello()`, `m["user.profile.title"]()`, once the file imports `m` from `…/paraglide/messages.js` | Yes, `project.inlang/settings.json` | [paraglide-js](../examples/paraglide-js/README.md) |
| [typesafe-i18n](#typesafe-i18n) | Read | `LL.home.title()`, `$LL.home.title()` (Svelte), read as the key `home.title`, once the file imports typesafe-i18n's generated code | Yes, `.typesafe-i18n.json` | [typesafe-i18n](../examples/typesafe-i18n/README.md) |
| [Meteor universe:i18n](#meteor-universei18n) | Read | the application's own hook, `useTranslator('Home')` or `useTranslation('Home')`, then `t('title')`, read as the key `Home.title` | No | — |
| [Laravel / Blade](#laravel-and-blade) | Supported | `__('key')`, `trans('key')`, `trans_choice('key', 2)`, `Lang::get`, `@lang('key')`, `@choice('key', n)` | No | [laravel](../examples/laravel/README.md) |
| [Symfony / Twig](#symfony-and-twig) (`symfony/translation`) | Read | `$translator->trans('key', [], 'admin')`, `t('key')`, `new TranslatableMessage('key')`; in Twig `{{ 'key'\|trans }}`, `'key'\|trans({}, 'admin')`, `trans(domain='admin')`, `{% trans %}key{% endtrans %}`, `{% trans from 'admin' %}`, `{% trans_default_domain 'admin' %}` | No | [symfony](../examples/symfony/README.md) |

Also ready to open: [flutter-arb](../examples/flutter-arb/README.md) (Flutter ARB catalogues), [spring-messages](../examples/spring-messages/README.md) (Java resource bundles), [php](../examples/php/README.md) (PHP gettext) and [simple](../examples/simple/README.md) (a single-namespace JSON project).

### i18next / react-i18next

- `useTranslation` supports both string form (`useTranslation('ns')`) and array form (`useTranslation(['ns1', 'ns2'])`). The namespace can also come from an options object (`t('key', { ns: 'auth' })`) or from the key itself (`t('auth:key')`).
- A `context` option reads the key's variant, as i18next does: `t('friend', { context: 'male' })` reads `friend_male`, else `friend`, and `t('friend', { context: gender })` any `friend_…` variant; those variants are then used, never reported as having no reference in code.
- The `defaultNS` and `fallbackNS` of the project's `i18next.init({…})` or `i18next.config.*` are read when the settings leave them empty — see [Namespaces and separators](configuration.md#namespaces-and-separators).

### vue-i18n

- Needs the **Vue.js** plugin (optional dependency). In a `.vue` file, the `<script>` blocks and the template's `{{ }}` and bound attributes are read.
- Projects writing the namespace as the first segment of the key (`common.save` for `common.json`) set **First component as namespace** in the settings.
- A plural `a | b` with fewer branches than the locale's language has forms is reported by the *vue-i18n plural* inspection.

### next-intl

- The `t` of `useTranslations('Home')` and `await getTranslations('Home' | { namespace })` prefixes its keys with `Home`; `t.rich`, `t.markup` and `t.raw` are read as calls, and a key `t.has` asks for is never reported missing.
- The setup wizard proposes `messages/` as the translations folder when next-intl is detected.

### Lingui

- `msg('key')` and `i18n._('key')` are read in a file importing `@lingui/core`, `@lingui/react` or `@lingui/macro`.
- A source-text `<Trans>Hello {name}!</Trans>` is read under the msgid the macro catalogues it as (`Hello {name}!`, other expressions numbered, nested tags indexed).

### react-intl (FormatJS)

- `formatMessage({ id: 'key' })` and `<FormattedMessage id="key" />` are read; `defaultMessage` and `description` are source text, never keys.
- Catalogues keyed by **flat ids** — one property per key, `"home.title": "Welcome"` — need **Treat keys as flat** in *Settings | Tools | i18n Support Plus*: the whole id is then looked up as a single property, without key-separator splitting or namespace parsing, and translation files are located through the default namespace.
- Extracting a text writes `<FormattedMessage id="key" />` or `intl.formatMessage({ id: 'key' })` — see [Key Extraction](editor.md#key-extraction).

### ngx-translate and Transloco

- Need the **Angular** plugin. The `| translate` and `| transloco` pipes are recognised in JSX/TSX and in standalone Angular templates (`.html`). A template is parsed as Angular only inside an Angular project — a component referencing it through `templateUrl`, with `@angular/core` resolvable; outside one the file stays plain HTML and the pipe is inert text.
- Transloco's service calls are recognised when the object they are called on names the service (`translocoService`, `transloco`): `translate` alone is too common a method name, and a bare `translate('key')` is Transloco's only in a file importing `@jsverse/transloco`.
- Transloco scopes: `admin.title` reads the key `title` of `i18n/admin/en.json`, a folder named after its scope next to the global locale files. The first segment is read as a scope only when such a folder exists, and only in a Transloco project; a key naming no scope is never looked up in a scope file. A scope set by the directive is read: `*transloco="let t; read: 'admin'"` or `prefix: 'admin'`, then `t('title')`, and `[translocoRead]` / `[translocoPrefix]` bound to a string literal on `<ng-template transloco let-t>`.
- Hard-coded text in an Angular template is reported by an inspection, off by default, whose quick fix writes `{{ 'key' | translate }}` (or `| transloco` in a Transloco project).

### Angular i18n

Angular's native i18n (`@angular/localize`) names a message by its custom id, the part after `@@` of the marker's metadata (`meaning|description@@id`), which is one key, never split at its dots; a marker without a custom id names none, as `ng extract-i18n` then computes the id from the text.

- **Messages**: the XLIFF 1.2 or 2.0 files each project of `angular.json` lists under `i18n.locales`, each with its locale, and the file `ng extract-i18n` writes — the `outputPath` of its options, else `messages.xlf` next to the translations — holding the `sourceLocale`, the reference. A locale's file reads each unit's `<target>`, the reference its `<source>`: a unit without a target is missing in that locale, and *Fill Missing Translations…*, *Sync Keys* and the translation dialog write it after the unit's source, or create the unit with the reference's source. An XLIFF file `angular.json` does not list, such as an export, is no source.
- **Extraction**: a template's text is marked with its id and the unit added: `<p>Save</p>` becomes `<p i18n="@@save">Save</p>`, an attribute gains `i18n-placeholder="@@id"`, a text outside any tag is wrapped in `<ng-container i18n="@@id">`; a text holding interpolations is left to `ng extract-i18n`, which writes their placeholders. This is the extraction's form in a module whose preset is *Angular i18n*, and without a preset in a project whose `angular.json` lists translations and that does not use Transloco.
- **Rename**: Shift+F6 on an id — in a marker, a `$localize` or a unit's `id` — renames it whole, dots included, in every marker and every XLIFF file; an id another unit has is refused.
- The setup wizard proposes the *Angular i18n* preset for an `angular.json` listing translations or a dependency on `@angular/localize`.
- Tested: annotation of both markers, completion in a template's marker, Ctrl+click from either marker to its units and from a unit's `id` to the code, the tool window, the missing-key checks, the extraction and the rename. Not tested: folding and inlay hints.

### svelte-i18n

- Needs the [Svelte plugin](https://plugins.jetbrains.com/plugin/12375-svelte) — not bundled with IntelliJ Ultimate; without it a `.svelte` file is plain text to the IDE. With it, both the `<script>` block and `{…}` expressions in the markup are ordinary JavaScript, so keys inside them resolve like any other.
- Hard-coded text in a component's markup is reported by an inspection, off by default, whose quick fix writes `{$_('key')}` and its import.

### i18n-js

- i18n-js pluralizes into a nested object (`{ one: …, other: … }`) rather than through i18next's flat `key_one` suffixes; such a key is treated as resolved.
- Its `%{count}` placeholders are displayed verbatim, as the plugin interprets no interpolation syntax.
- Its catalogues are TS/JS objects keyed by locale — see [Translation File Formats](#translation-file-formats).

### Paraglide JS

Paraglide compiles each message into a function, so the key is the member of the message object, not a string.

- **Read** in a file importing that object from a path ending in `paraglide/messages` — `{ m }`, `{ m as msg }`, or Paraglide 1's `import * as m` — in `.js`, `.ts`, Svelte components (with the Svelte plugin) and Astro components (with the Astro plugin).
- **Works**: annotation, completion after `m.` (with the messages not yet compiled), folding, hover and inlay hints, Ctrl+click to the message rather than the generated function, and from a message file to its call sites, usages, Shift+F6 (in every message file and call site, `m["a.b"]` when the new name is no identifier) and *Extract I18n*, which writes `m.key({ name })` and adds the import.
- **Messages**: read where `project.inlang/settings.json` puts them, one inlang project per folder in a monorepo; the generated folder is never counted as a use.
- **Limits**: a message with variants (a `match` array) is displayed as its catch-all branch and edited as raw JSON; `m[variable]` names no key. In Svelte components only annotation, folding and usages are tested; in Astro components annotation, completion and the navigation from a message file, in the frontmatter and in template expressions.

### typesafe-i18n

typesafe-i18n reads its messages as a member chain, so the key is the path of members after `LL` (or `$LL`, its Svelte store), not a string.

- **Read** in a file importing typesafe-i18n's generated adapter or utilities (`…/i18n-react`, `…/i18n-svelte`, `…/i18n-util`, …).
- **Messages**: the object each `<locale>/index.ts` of the output folder default-exports — `outputPath` in `.typesafe-i18n.json`, `./src/i18n/` by default — each locale holding the default namespace.
- **Works**: annotation (unknown key, key missing in a locale) and completion after `LL.` and each level of it, with the messages not yet generated and their values.
- **Not yet**: Ctrl+click to the message (the IDE goes to the generated types), Shift+F6, folding and inlay hints, Node's `L.en.key()`.

### Meteor universe:i18n

- universe:i18n ships no React hook since its v2: its documentation has the application write one, and the plugin reads the argument of `useTranslator` — or of `useTranslation`, unless the file imports it from an i18next package — as a key prefix once `.meteor/packages` declares `universe:i18n`.
- Not read yet: `i18n.__()`, `getTranslation()` and `*.i18n.json` files.

### Laravel and Blade

- **Read** in a Laravel project — a `composer.json` outside `vendor/` requiring `laravel/framework` or `illuminate/translation`: `__()`, `trans()` and `trans_choice()` are translation calls, and extracting a string writes `__('…')`. Elsewhere `__()` is left alone: WordPress passes it a text, not a key. Needs the **PHP** plugin.
- **Blade views** (with the Blade plugin, bundled in PhpStorm): keys inside `{{ … }}` and `{!! … !!}` echoes are read like any PHP key, and so are those of the `@lang('key')` and `@choice('key', n)` directives. Laravel's translator called in PHP — `app('translator')->get('key')`, `->choice('key', n)`, the `Lang` facade — is recognised as well.
- **Catalogues**: the PHP arrays of `lang/<locale>/<file>.php`, the file name as namespace (`messages:welcome` for `__('messages.welcome')`); a JSON phrase holding `.` or `:` — `__('Saved.')`, `__('Hello, :name')` — is looked up whole in `lang/<locale>.json`, as Laravel does. See [Translation File Formats](#translation-file-formats).
- **Works**: values folded in place and shown as inlay hints, in PHP and Blade; Shift+F6 renames a key in the PHP array of every locale. Hard-coded text in a Blade view's HTML is reported by an inspection, off by default, whose quick fix writes `{{ __('key') }}`.

### Symfony and Twig

- **Read** in a project whose `composer.json`, outside `vendor/`, requires `symfony/translation` — what `composer require translation` adds; elsewhere `trans` is any method and Drupal's `|trans` holds a text. Twig needs the **Twig** plugin, bundled in PhpStorm.
- **Catalogues**: the files of every `translations/` folder named `<domain>.<locale>.<format>` — YAML, XLIFF 1.2 and 2.0, PHP, JSON and gettext `.po` (with the Localization plugin) — and `messages+intl-icu.fr.yaml` is the `messages` domain. A catalogue is read flattened, as Symfony reads it: `user.name` is `name` nested under `user:` or a key written `user.name` whole, and an XLIFF unit is keyed by its `resname` (`name` in 2.0), else its `<source>`, its value being its `<target>`, else its `<source>`. A key created in an XLIFF catalogue is written with its `resname`, the key in its `<source>` and the value in its `<target>`.
- **Domains**: the domain is the namespace. A call names it third (`trans('key', [], 'admin')`), a Twig filter second or as `domain=`, a `{% trans %}` tag after `from`; a template's `{% trans_default_domain %}` applies to the keys naming none, and `messages` to the rest. A domain computed at runtime makes no key, as which catalogue holds it is unknown.
- **Works**: annotation, Ctrl+click and the missing key in PHP and in Twig; completion, folding and inlay hints in Twig; YAML (nested and flat), XLIFF, PO and ICU catalogues; the usages and *Unused translation key*; Ctrl+click from a catalogue (an XLIFF `resname` included) to the code; Shift+F6, which renames a key written whole and both the `resname` and the `<source>` of an XLIFF unit. A text of a template's HTML is reported by the *Hard-coded text in a Twig template* inspection (off by default) and extracted into `{{ 'key'|trans }}`, an echo `{{ user.name }}` becoming the parameter `%name%`.
- **Not read**: `.csv`, `.ini` and `.mo` catalogues, `trans()` in JavaScript (`@symfony/ux-translator`).
- The setup wizard proposes the *Symfony* preset — `translations/{ns}.{lang}.yaml` — for a `composer.json` requiring `symfony/translation`.

### Astro

Astro components (`.astro`, with astro-i18next or i18next) are analysed provided the [Astro plugin](https://plugins.jetbrains.com/plugin/20959-astro) is installed — it is not bundled with IntelliJ Ultimate, and without it a `.astro` file is plain text to the IDE. With it, `t('key')` in the `---` frontmatter and in `{…}` template expressions is ordinary JavaScript: annotations, completion, folding, inlay hints and references work as in a `.ts` file. Hard-coded text in the template is reported by an inspection, off by default, whose quick fix writes `{t('key')}` and its import; `{name}` becomes a variable.

## Supported Languages

| Language | Extensions | Annotations | Completion | Folding | Hints | References | Needs |
|----------|-----------|:-----------:|:----------:|:-------:|:-----:|:----------:|-------|
| JavaScript | `.js` | ✓ | ✓ | ✓ | ✓ | ✓ | |
| TypeScript | `.ts` | ✓ | ✓ | ✓ | ✓ | ✓ | |
| JSX | `.jsx` | ✓ | ✓ | ✓ | ✓ | ✓ | |
| TSX | `.tsx` | ✓ | ✓ | ✓ | ✓ | ✓ | |
| Vue SFC | `.vue` | ✓ | ✓ | ✓ | ✓ | ✓ | Vue.js plugin |
| PHP | `.php` | ✓ | ✓ | ✓ | ✓ | ✓ | PHP plugin |
| Blade | `.blade.php` | ✓ | — | ✓ | ✓ | ✓ | PHP and Blade plugins (PhpStorm) |
| Twig (Symfony) | `.twig` | ✓ | ✓ | ✓ | ✓ | ✓ | PHP and Twig plugins (PhpStorm) |
| Angular templates | `.html` | ✓ | ✓ | ✓ | ✓ | ✓ | Angular plugin, inside an Angular project |
| Svelte | `.svelte` | ✓ | ✓ | ✓ | ✓ | ✓ | [Svelte plugin](https://plugins.jetbrains.com/plugin/12375-svelte) |
| Astro | `.astro` | ✓ | ✓ | ✓ | ✓ | ✓ | [Astro plugin](https://plugins.jetbrains.com/plugin/20959-astro) |

**—** means no test covers it yet: it may work, it is not promised. A ✓ is backed by a test. Which plugins are bundled with which IDE: [Plugin Dependencies](../README.md#plugin-dependencies).

## Translation File Formats

| Format | Extensions | References | Content generation |
|--------|-----------|:----------:|:------------------:|
| JSON | `.json`, `.json5` | ✓ | ✓ |
| YAML | `.yaml`, `.yml` | ✓ | ✓ |
| PO/POT (gettext) | `.po`, `.pot` | ✓ | ✓ |
| Laravel PHP arrays | `lang/<locale>/<file>.php`, the file name as namespace | ✓ | ✓ |
| Symfony catalogues | `translations/<domain>.<locale>.yaml`, `.xlf`, `.php`, `.json`, `.po`, the domain as namespace ([Symfony and Twig](#symfony-and-twig)) | ✓ | ✓ |
| Flutter ARB | `.arb` (`lib/l10n/app_<locale>.arb`), the locale from `@@locale` or the file name | — | ✓ |
| Java resource bundles (requires the bundled **Properties** plugin) | `.properties` (`messages_<locale>.properties`), the locale from the file name | — | ✓ |
| XLIFF 1.2 and 2.0 (Angular i18n, Symfony) | `.xlf`, `.xliff`, the files `angular.json` lists ([Angular i18n](#angular-i18n)), a Symfony project's catalogues | ✓ | ✓ |
| TS/JS catalogues (an object keyed by locale, as i18n-js writes) | `.ts`, `.js` | ✓ | ✓ |
| i18next `resources` declared in a TS/JS configuration file | `.ts`, `.js` | ✓ | ✓ |

**—** means no test covers it yet.

- **TS/JS catalogues and `resources`**: a missing key is written into its object literal, in the file's indentation, quotes and trailing commas; an object built in code (a spread, a computed key) is left as it is, with a warning naming the key to add by hand.
- **XLIFF** is read as a translation source in an Angular or a Symfony project only; elsewhere, like CSV, it is exported and imported from *Tools › i18n Support Plus*.
- **Flutter ARB** catalogues feed the tool window, the statistics, the missing-key checks and *Sync Keys*; their `@key` and `@@locale` metadata is no key. A key created in a catalogue that describes its keys, the template, gets its `@key` block, with the `{placeholders}` of its value, and a key renamed or deleted takes its block along. Dart code is not read: `AppLocalizations.of(context)!.greeting` names its key as a getter of the generated class, which the Dart analyzer already checks, so an ARB key is never reported unused. Example: [flutter-arb](../examples/flutter-arb/README.md).
- **Java resource bundles** (`src/main/resources/messages.properties`, `messages_fr.properties`, `messages_pt_BR.properties`) feed the tool window, the statistics, the missing-key and placeholder checks (`{0}`, `{0,number}` compared as `{name}`), *Sync Keys* and *Fill Missing Translations*. A file is a bundle file when its base name has at least one localized file beside it, so `application.properties` stays configuration; `messages.properties` holds the reference locale, unless `messages_en.properties` exists. A folder of several bundles (`messages`, `errors`) gets one namespace per base name. A dotted key (`user.name`) resolves level by level like any other, a new key is written next to its siblings, and values are escaped and unescaped (`\uXXXX`, line continuations) as Java reads them. The calls are IntelliJ IDEA's own: with its Java and Properties plugins, `bundle.getString("key")` and Spring's `messageSource.getMessage("key", …)` already offer Ctrl+click, completion, *Find Usages*, rename and the *Unused property* inspection, which the plugin does not duplicate. The plugin's own usage count does not see them yet, so it never reports a bundle key unused. The setup wizard ticks *Spring* for a `pom.xml` or a `build.gradle(.kts)` naming `org.springframework`. Example: [spring-messages](../examples/spring-messages/README.md).
- **Nested or flat keys**: keys are resolved as nested properties by default (`app.header.title` walks three levels). Projects storing flat ids — one property per key, as react-intl / FormatJS usually do — enable **Treat keys as flat** in *Settings | Tools | i18n Support Plus*, which turns off key-separator splitting and namespace parsing altogether.
