# Checking translations in CI

The plugin's inspections run without an editor: in [Qodana](https://www.jetbrains.com/qodana/), or in
any IntelliJ-based IDE started headless (`inspect.sh`). A pull request adding `t('checkout.title')`
without its translation, or dropping a key from `fr/common.json`, fails the build instead of waiting
for someone to open the file.

| What | Reported by |
|------|-------------|
| A key of the reference locale missing from another locale | `I18nMissingKey` |
| A placeholder present in one locale and not in another | `I18nPlaceholderConsistency` |
| A plural form the locale's language needs | `I18nMissingPluralForms` |
| Markup lost in a translation (`<1>…</1>`) | `I18nMarkupConsistency` |
| An ICU plural or select without `other` | `I18nIcuFormat` |
| A call not passing a variable its message needs | `I18nInterpolationArguments` |
| A key or namespace the code uses and no file holds | the plugin's annotator, through `Annotator` |
| A key no code uses (opt-in) | `I18nUnusedKey` |
| A reviewed value whose reference text changed since (opt-in, needs `.i18n-reviews/`) | `I18nStaleTranslation` |
| A key not following the project's naming convention (opt-in) | `I18nKeyNaming` |

The plugin reads the project's settings from `.idea/i18nSettings.xml` (translation root, namespaces,
modules): commit that file, or the run uses the defaults.

## Qodana

1. Copy [`qodana.yaml`](qodana.yaml) to the root of the project.
2. Copy [`qodana-github-workflow.yml`](qodana-github-workflow.yml) to `.github/workflows/qodana.yml`.
3. Save a Qodana Cloud project token as the `QODANA_TOKEN` repository secret.

`qodana.yaml` installs the plugin from JetBrains Marketplace (`com.ibrahimdans.i18n`) and enables
its inspections. Qodana for JS is based on WebStorm and is an **Ultimate** linter: it needs the token
locally as in CI. Locally, with the token exported:

```bash
docker run --rm -v "$PWD":/data/project -e QODANA_TOKEN jetbrains/qodana-js:2026.2
```

`Annotator` is what reports an unresolved key in the code; it also reports the errors of the
languages themselves (TypeScript, JSON). Drop it from `include` if those already fail elsewhere.

Checked with `jetbrains/qodana-js:2026.2` on `examples/react-multi-namespace/`, a key removed from
`fr/common.json` and `t('does.not.exist')` added to `App.tsx`: the plugin is installed from the
Marketplace, and the run fails on the threshold (exit code 255) with

```
Annotator                                           Critical               9
Translation key missing from a locale                   High               3
Translation variables not passed to the call            High               1
```

The `Annotator` problems are the plugin's: `Unresolved key` on `App.tsx`, and `Partially translated
key` / `Unresolved namespace` the example holds on purpose. The log's *Duplicates of problems was
found. inspectionId: Annotator* lines are Qodana merging the same annotation seen twice; each problem
is reported once.

## Headless IDE (`inspect.sh`)

The same inspections, with an IntelliJ IDEA Ultimate or WebStorm installation and the plugin
installed: [`i18n-inspections.xml`](i18n-inspections.xml) is the profile.

```bash
idea.sh inspect "$PWD" docs/ci/i18n-inspections.xml build/i18n-report -v2 -format json
```

Each inspection writes `build/i18n-report/<InspectionName>.json`; the build fails on what you decide,
e.g. `I18nMissingKey.json` holding any problem. Inspections the profile does not list keep the IDE's
defaults, so the report directory also holds the IDE's own findings.

### What a run reports

Checked on `examples/react-multi-namespace/` with IntelliJ IDEA 2025.3.4, a key removed from
`fr/common.json` and `t('does.not.exist')` added to `App.tsx`:

```
I18nMissingKey              public/locales/fr/common.json:3   Key 'actions.edit' of reference locale 'en' is missing here
Annotator                   src/App.tsx:32                    Unresolved key
I18nInterpolationArguments  src/components/Navigation.tsx:14  Variable(s) used by the translation but not passed to the call: count
```
