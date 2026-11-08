# Simple

A single-folder JSON project with no framework and no `package.json`: the translation files live in
`asset/<locale>/<namespace>.json`, and the code calls a hand-written `i18n.t(…)` or holds plain string keys.

## What the translations cover

| Case | Where |
|---|---|
| Namespaces `sample`, `common`, `yaml` | `asset/en/*.json`, `asset/ru/*.json` |
| Plural forms (`value-1`, `value-2`, `value-5`, `other`) | `ROOT.Key1.plurals` in `asset/ru/sample.json` |
| Keys written flat, `"ROOT.Key1.key2"` | `asset/fl/sample.json` |
| A namespace in YAML only, in a locale without JSON | `asset/de/yaml.yml` |
| Keys present in some locales only | `ROOT.ExtraKey.*` (`ru` and `fl` only), `ROOT.Key1.key21` (`en` only) |

## What the code covers

`src/Modules/Test.ts` lists its cases in end-of-line comments: a resolved key, a missing whole key, a missing part of a
key, a missing namespace (`MissingFile:…`), a reference to a JSON object, a plural key, a template literal resolved from
a constant and one left open (`${unknownInCompileTime}`), and keys without a namespace. `TsxTest.tsx` and `simple.tsx`
call `i18n.t(…)` from TSX. `Asterisk.ts`, `DefaultNamespace.ts` and `Sample.ts` only hold comment tables of key
patterns; `NotAKeys.ts` and `test.js` are empty.

## Automated checks that open it

- **`verifyCommunityFlows`** (`src/uiTest/…/uiflows/CommunityFlowsTest.kt`, run by the *Compatibility* workflow):
  IntelliJ IDEA Community, without the JavaScript plugin, opens a copy of this folder, and the *I18n* tool window's
  *Table* must show `Key41` from `asset/en/sample.json`. The test writes the settings itself (default namespace
  `./asset/`, JSON first, setup wizard dismissed) and checks that `asset/en/sample.json` exists: keep that file and
  that key.
- **`i18n-check`** reads this folder in the *Release* workflow (Docker image, with `--code`) and in the *CLI native*
  workflow (npm package), to prove the binary starts on each platform. `--max-problems 100000` lets the example's own
  problems pass.

## Manual checks

Open this folder with `./gradlew runIde`, then open `src/Modules/Test.ts`: `'sample:ROOT.Key1.key21'` resolves to
`asset/en/sample.json`, `'sample:missing.whole.composite.key'` and `'MissingFile:Ex.Sub.Val'` are underlined, and
Ctrl+click on `` `sample:ROOT.Key1.${unknownInCompileTime}` `` opens the `Key1` object.
