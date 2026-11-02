# Checks: inspections, CI and the command line

The checks the IDE runs on translations and code, and how to run them in CI, with or without an IDE.

[← Back to the README](../README.md)

## Inspections

Under *Settings | Editor | Inspections | i18n Support Plus*, those marked *off* to be turned on there (the setup wizard offers the first two):

| Inspection | Reports |
|------------|---------|
| Translation key with no reference in code | A key of a translation file no code refers to, unless the settings keep it (*Keys used outside the code*); quick fixes: delete it, or keep it (off) |
| Hard-coded text in JSX / in a Vue template | A text written as is, `<button>Save</button>`, with a quick fix extracting it; code tags, punctuation alone and technical attributes are never reported (off) |
| Hard-coded text in an Angular template / in a Svelte component | The same in an Angular template, the quick fix writing `{{ 'key' \| translate }}` (or `\| transloco` in a Transloco project), and in a Svelte component's markup, writing `{$_('key')}` and its import; `{{ name }}` and `{name}` become variables, a tag marked `translate`, `transloco` or `i18n` is left alone (off) |
| Hard-coded text in a Blade view / in an Astro component | The same in a Blade view's HTML, the quick fix writing `{{ __('key') }}`, and in an Astro component's template, writing `{t('key')}` and its import; Astro's `{name}` becomes a variable, while a Blade text split by an echo or a directive (`Hello {{ $name }}!`) is left alone (off) |
| Hard-coded string shown to the user in JavaScript | A string passed to `alert`, `confirm` or `toast` (`toast.error('Failed')`), or held by a `title`, `label` or `placeholder` property, in a JS or TS file; both lists are options, and the quick fix extracts it (off) |
| Translation key missing from a locale | In a locale file, each key of the reference locale's file for the same namespace that it lacks, plural forms compared as a group; quick fix: add it empty |
| Translation key absent from the reference locale | A key of a locale that the reference locale's file does not hold — renamed in the reference only, or mistyped — plural forms compared as a group; quick fixes: remove it from the locale, or add it empty to the reference (off) |
| Plural forms missing for the language of the locale | An i18next plural group lacking a category its language needs (`ru`: `item_few`, `item_many`); none is missing when the bare key `item` stands beside the group, i18next's fallback for any form |
| vue-i18n plural with fewer branches than the language has forms | A vue-i18n plural `a \| b` with fewer branches than its locale's language has forms for an integer (`ru`: `one`, `few`, `many`); only in a vue-i18n module, or a project without modules depending on vue-i18n |
| Translation variables not passed to the call | A call that does not pass a variable its message needs: `t('greeting')` for `Hello {{name}}` |
| Placeholder consistency across locales | A `{name}` or `%s` placeholder present in one locale and not in another, or an unbalanced brace |
| Markup consistency across locales | A translation whose tags differ from the reference's, which breaks react-i18next's `<Trans>`; tags may be reordered |
| ICU message format validation | Unbalanced braces, a `plural` block without `one`/`other`, … |
| Value copied from the reference locale | A value of more than one word identical to the reference's, counted as translated but never taken up; *Mark as intended* keeps it |
| Translation much longer than the reference | A value likely to overflow what the reference was sized for; ratio and minimal length are options (off) |
| Final punctuation and whitespace across locales | A final `.`, `:` or `?`, or surrounding whitespace, present in one locale and not in the reference (off) |
| Empty translation value | A key whose value is blank in a translation file (off) |
| Duplicate translation value | Two keys of a file holding the same value (off) |
| Stale translation: reviewed on a source that changed since | Where a module keeps reviews, a value still the one reviewed while the key's reference text changed since (weak warning) |
| Key naming convention | A key not following the project's convention — camelCase, snake_case, kebab-case or a pattern of your own — segment by segment, plural suffixes aside; quick fix: rename it in every locale and call site (off) |

## Continuous Integration

The inspections also run without an editor, in [Qodana](https://www.jetbrains.com/qodana/) or a headless IDE: a pull request adding a key without its translation, or dropping one from a locale, fails the build. Ready-to-copy `qodana.yaml`, GitHub workflow and inspection profile: [docs/ci](ci/README.md).

## Command Line Check

`i18n-check` compares the translation files of a project to the reference locale, without IntelliJ. It needs Java 21 and is not shipped with the plugin: each [GitHub release](https://github.com/IBRAHIMDANS/i18nSupportPlus/releases), from 1.16.0, carries it as `i18n-check-<version>.zip`.

```bash
unzip i18n-check-1.16.0.zip
i18n-check-1.16.0/bin/i18n-check [project] [--locales <dir> | --code] [--reference <locale>] [--format text|json|github] [--max-problems N] [--no-values]
```

`./gradlew :cli:installDist` builds it from this repository instead, in `cli/build/install/i18n-check/`.

It runs the plugin's own checks in CI, with the plugin's settings, whatever the framework. An i18next project that also wants to write the keys the code uses into its files, sync its locales or generate types can pair it with the official [i18next-cli](https://github.com/i18next/i18next-cli) (`extract`, `sync`, `types`).

- `project` (the current directory by default) is searched for translation files as the plugin finds them: a folder or file named after a locale (`locales/en/common.json`, `i18n/fr.yml`), keys of a namespace file reported as `common:key`. `node_modules`, `build`, `dist` and the like are never searched.
- Translation files are read in JSON (`.json`, strict), JSON5 (`.json5`: comments, unquoted keys, single quotes, trailing commas), YAML (`.yml`, `.yaml`), gettext PO (`.po`, `.pot`), Laravel PHP arrays (`.php`), Flutter ARB (`.arb`), Java resource bundles (`.properties`) and as TypeScript / JavaScript catalogues (`.ts`, `.js`). A `.json5` or `.arb` file takes the JSON settings, as in the plugin.
- In a PO catalogue, each `msgid` is a key and its `msgstr` the value; the same `msgid` under a `msgctxt` is another key, reported as `Open (msgctxt: menu)`; a plural entry is one key, empty while any of its `msgstr[n]` is. The header and the obsolete entries (`#~`) are no keys.
- A Laravel file (`lang/fr/messages.php`) holds the array it returns, nested arrays as nested keys and the file name as the namespace (`messages:auth.failed`); entries with an integer key are left out. Its values must be literals, as in a catalogue below: a concatenation, a call, a constant or a variable stop the check with exit `2`, naming the file and the line. A `.php` file that does not return an array is left out. A package's override (`lang/vendor/courier/fr/messages.php`) may be partial: a locale overriding nothing of that package keeps the package's own translations, and its keys are missing only in a locale that overrides the file and leaves one out.
- A Flutter ARB catalogue (`lib/l10n/app_fr.arb`) holds the locale its `@@locale` states, else the one ending its name (`app_pt_BR.arb`); its `@` metadata (`@@locale`, `@greeting`) are no keys. `.dart_tool` is never searched.
- A Java resource bundle (`messages_fr.properties`, `messages_pt_BR.properties`) holds the locale ending its name; the file without a suffix (`messages.properties`) is the reference locale, unless `messages_en.properties` exists. Several bundles in one folder are namespaces named after their base. A `.properties` file with no localized sibling (`application.properties`) is no bundle, and `target` is never searched.
- A Symfony catalogue (`translations/messages.fr.yaml`, `validators+intl-icu.de.xlf`), in a project whose `composer.json` requires `symfony/translation`, holds the locale its name gives and its domain as namespace (`admin:dashboard.title`); `+intl-icu` keeps the same domain. YAML, XLIFF 1.2 and 2.0, PHP and JSON are read; a key nested under `user:` and one written `user.name` whole are the same key, and an XLIFF unit is keyed by its `resname` (`name` in 2.0), else by its `<source>`. PHP and Twig calls are not read by `--code` yet.
- A catalogue is the object literal the file exports: `export default { … }` (or the `const` it names), `module.exports = { … }`, or the only `export const messages = { … }`, with `as const` or `satisfies …` allowed. Keys and values must be literals: a spread (`...common`), a computed key, an imported or built value (`` `Hi ${name}` ``, `t('x')`) stop the check with exit `2`, naming the file and the line, since the keys it hides would be reported missing. A `.ts` or `.js` file exporting no object, a helper beside the catalogues, is left out.
- A folder or file named with a country code beside real locales (`locales/gb/` next to `locales/en/`) is not a locale: the check leaves it out and says so on the error output, naming the path. `"localeAliases": "gb=en-GB"` makes it one, checked with `en-GB`'s plural forms.
- `.idea/i18nSettings.xml`, when the project has one, applies: the translations root, the excluded folders, the default namespaces, the ignored locales (left out of the report), the reference locale (the module's, then the project's — *Project reference locale*, `foldingPreferredLanguage` —, then `en`; never the preview locale), the key and namespace separators, and the modules — with several, each is checked on its own, with its own reference locale. `--reference` overrides it; `en` also finds `en-US`.
- `i18n-check.json`, at the project's root, takes the place of `.idea/i18nSettings.xml` for a project that keeps `.idea/` out of version control; when both exist, it wins and the XML is not read. It takes the names of the IDE's settings, and fails the check (exit `2`) on a name it does not know:

  ```json
  {
    "translationsRoot": "src/locales",
    "keySeparator": ".",
    "ignoredLocales": "xx",
    "modules": [{ "name": "web", "rootDirectory": "apps/web", "pathTemplate": "public/locales/{lang}/{ns}.json", "referenceLocale": "fr" }]
  }
  ```

  Settings: those of [Share the configuration with your team](configuration.md#share-the-configuration-with-your-team), which the IDE reads from the same file, and `previewLocale`; per module the same fields, `enabled` (`false` puts the module aside: neither checked nor reported) included.
- `--locales <dir>` checks only that directory: a file per locale (`en.json`, `fr.yml`) or a folder per locale with a file per namespace (`en/common.json`); other files are left alone.
- For each locale, it reports the keys **missing** compared to the reference, the **orphan** keys the reference does not hold, and the **empty** values. Plural keys (`item_one`, `item_other`) are checked against the forms each language needs: `ja` only `item_other`, `ru` also `item_few` and `item_many`.
- It also checks the **values**, as the inspections do: a placeholder of the reference missing from a translation or not in the reference, an unbalanced brace, tags that differ from the reference's, and an ICU `plural` or `select` block lacking its `other` form, the only one ICU requires (`{n, plural, other {# items}}` passes). A translation with no placeholder at all is left alone, `{count}` may differ in a plural form, and a plural form the reference does not have (`item_few` in Russian) is compared to the reference's `item_other`; the AI agents' `i18n_check` tool returns the same `values`. `--no-values` turns this off. The length, the final punctuation, a value copied from the reference, the naming convention and the other inspections stay in the IDE.
- `--code` also reads the JavaScript, TypeScript, Vue and Svelte files — and the `.html` templates of an Angular project, one whose `package.json` requires `@angular/core`, and the `.php` files and Blade views of a Laravel project, one whose `composer.json` requires `laravel/framework` — and reports the **unused** keys of the reference locale — a key a template literal may build, such as `` t(`status.${kind}`) `` for `status.*`, is not one — and the **unknown** keys, called by the code but absent from the reference locale, with their file and line. It reads:
  - **i18next / react-i18next**: `t('key')`, `i18n.t`, `useTranslation(ns, { keyPrefix })`, `<Trans i18nKey>`;
  - **next-intl**: the `t` of `useTranslations('Ns')` and `await getTranslations('Ns' | { namespace })`, `Ns` prefixing its keys, and `t.rich`, `t.markup`, `t.raw`, `t.has` (a key `t.has` asks for is never unknown);
  - **vue-i18n**: in `.vue` files, the `<script>` blocks and the template's `{{ }}` and bound attributes — `$t`, `$tc`, `$te`, the `t` / `te` of `useI18n()`, `v-t` and `<i18n-t keypath>` — a component's `<i18n>` block holding keys for its own calls;
  - **Transloco**: the `| transloco` pipe, `transloco="key"` and `[transloco]="'key'"`, the `t` of `*transloco="let t; read: 'admin'"` and of `<ng-template transloco let-t [translocoRead]>` (`prefix` too) under its prefix, the service's `translate` / `selectTranslate` on a variable naming `transloco`, and the standalone `translate()` / `translateSignal()` in a file importing Transloco. The keys of a scope file (`assets/i18n/admin/en.json`) are spelled `admin.…`, as the code writes them;
  - **ngx-translate**: the `| translate` pipe, `translate="key"`, `[translate]="'key'"` and `<p translate>key</p>`, and the service's `instant`, `get`, `stream` on a variable naming `translate`;
  - **react-intl**: the `id` of a descriptor passed to `formatMessage` (`intl.formatMessage({ id })`, the bare `formatMessage` of `useIntl()`, `this.props.intl.formatMessage`), the ids a `defineMessages` / `defineMessage` catalogue declares, and `<FormattedMessage id>`; `defaultMessage` and `description` are source text, never keys;
  - **Lingui**: `i18n._('key')`, `msg('key')` and `msg({ id })` in a file importing a `@lingui/` package, and the source-text `<Trans>Hello {name}!</Trans>`, under the msgid the macro catalogues it as (`Hello {name}!`, other expressions numbered, nested tags indexed);
  - **svelte-i18n**: in `.svelte` files, the `<script>` blocks and the markup's expressions — `{…}`, attribute values, `{#if …}`, `{#each … as …}`, `{@html …}` — and in scripts: `$_('key')`, `$t`, `$format`, `$_({ id })` and `$json('key')`, `_` and `$t` only in a file importing `svelte-i18n`;
  - **Laravel**: `__()`, `trans()`, `trans_choice()`, `Lang::get` / `Lang::choice`, `app('translator')->get` / `->choice` and Blade's `@lang()` / `@choice()`. A key of `lang/en/messages.php` is written `messages.welcome` in the code and listed as `messages:welcome`; a JSON phrase (`Saved.`, `Hello, :name`) is the whole text. A package's key (`courier::messages.sent`) is read under the override the project keeps in `lang/vendor/courier/en/messages.php`, listed as `courier::messages:sent`; a package without an override, a key built from a variable and a call in a comment are left out, as in the plugin.

  A module whose `preset` names one of these frameworks is read with its calls only, as in the IDE; without a preset all are read, and another framework's preset is read as i18next. The frameworks are described once, as data shared with the IDE (`FrameworkPresets`): the same names, descriptors and components are read on both sides. Astro components are not read yet. Java and Dart are not read either: the keys of a `.properties` bundle or an ARB catalogue are never reported unused, as the IDE leaves them out, unless a file of a read format holds them too.

  With several modules, a module's keys are used by its own code only, as in the IDE: the files under its folder, or under its *Code directories* (`codeRoots`); a call is unknown when no module holding its file has the key, and a file outside every module is checked against all keys.
- A key the code passes through a constant is read as the IDE reads it, one hop: `t(SAVE_KEY)` on a `const` (or a `let` never reassigned) holding a string, a template without `${…}` or a conditional of strings (each branch counts), `t(KEYS.save)` on a property of an object initializing a `const` (nested, `as const` or not), and `t(Keys.Save)` on a string `enum` member, declared in the file or imported from another one by a relative path (`./keys`, `../i18n` for its `index.ts`) or by a path alias (`@/i18n/keys`) of the `compilerOptions.paths` and `baseUrl` of the `tsconfig.json`, else `jsconfig.json`, at the project's root. An unknown key passed this way is reported at the call. A re-export, a parameter and a computed value are not followed, nor an alias declared through `extends` or in a sub-package's `tsconfig.json`.
- Exit code: `0` when the problems, bad values, unused and unknown keys included, do not exceed `--max-problems` (0 by default), `1` when they do, `2` on a wrong command line, a file that does not parse, an unknown reference locale or no translation file.

`--format json` prints (`unused` and `unknown` with `--code` only, `values` unless `--no-values` is given):

```json
{
  "reference": "en",
  "problems": 6,
  "locales": [
    { "locale": "en", "missing": [], "orphans": [], "empty": [], "values": [] },
    {
      "locale": "fr",
      "missing": ["common:actions.edit"],
      "orphans": ["common:actions.old"],
      "empty": ["navigation:menu.home"],
      "values": [{ "key": "common:greeting", "message": "Placeholder '{name}' of the reference locale is missing" }]
    }
  ],
  "unused": ["common:status.loading"],
  "unknown": [{ "key": "dashboard:stats.user", "file": "src/components/Dashboard.tsx", "line": 27 }]
}
```

A project of several modules lists them under `modules`, each with its `module` name, `reference`, `problems`, `locales` and `unused`; `problems` and `unknown` stay at the top.

A GitHub Actions job failing the build on a missing translation or a key the code calls but no file holds. The action sets up Java 21 and runs the `i18n-check` of the release its tag names:

```yaml
jobs:
  translations:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: IBRAHIMDANS/i18nSupportPlus@v1.16.0
        with:
          args: --code
```

`args` takes the command line's arguments, `version` another release than the tag's.

Unless `args` sets its own `--format`, the action runs `--format github`: each problem becomes a workflow command, which GitHub shows as an annotation on the pull request's *Files changed* tab and on the run's page, followed by the text report for the log:

```text
::error file=src/components/Dashboard.tsx,line=27,title=Unknown key::dashboard:stats.user is called here, but the reference locale en has no such key
::error file=public/locales/fr/common.json,line=1,title=Missing translation::fr lacks common:actions.edit, which the reference locale en holds
::error file=public/locales/en/common.json,line=1,title=Unused key::No code uses common:status.loading
```

- An unknown key is annotated on the line calling it. A missing, orphan or empty key or a bad value is annotated on line 1 of the locale's file holding its namespace, an unused key on the reference locale's file; a locale with no file for that namespace gets an annotation on the run only.
- They are `::error` while the problems exceed `--max-problems`, `::warning` when the check passes. GitHub shows a limited number of annotations per step: the full list stays in the log.
- The run's summary page gets a table of each locale's missing, orphan and empty keys and bad values (`$GITHUB_STEP_SUMMARY`).
- Paths are given from the directory `i18n-check` runs in, the workspace in GitHub Actions.

### Other CI systems

Each release, from the one after 1.16.1, also publishes `i18n-check` as a Docker image with Java 21 included, for Linux x86_64 and arm64: `ghcr.io/ibrahimdans/i18n-check:<version>`, and `:latest`. It checks the directory mounted on `/work` and takes the same arguments:

```bash
docker run --rm -v "$PWD:/work" ghcr.io/ibrahimdans/i18n-check:latest --code
```

In GitLab CI, the image's entry point is cleared so that the job's `script` runs, and `i18n-check` is on the `PATH`:

```yaml
translations:
  image:
    name: ghcr.io/ibrahimdans/i18n-check:latest
    entrypoint: [""]
  script:
    - i18n-check --code
```

Pin a version tag rather than `latest` for a build that does not change on its own. `cli/Dockerfile` builds the same image from `./gradlew :cli:installDist`: `docker build -f cli/Dockerfile -t i18n-check cli/build/install`.
