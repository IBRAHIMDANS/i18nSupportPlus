# React i18next with hooks

Manual testing target for the **i18n Support Plus** plugin on a [react-i18next](https://react.i18next.com) app built
with Create React App (`react-scripts`). `i18next-http-backend` loads the translations over HTTP from
`public/locales/<locale>/<namespace>.json`: locales `en` and `de`, namespaces `translation` (the default), `main`
and `hook`.

## Run it

```bash
npm install --no-package-lock
npm start
```

`build/` is a committed production build; the plugin does not need it.

## What the translations cover

| Case | Where |
|---|---|
| Default namespace, nested keys | `public/locales/*/translation.json`, `description.part2` |
| Markup in a value, rendered through `<Trans>` | `description.part1` (`<1>src/App.js</1>`) |
| Missing in a locale, on purpose | `description.part3` (not in `de`) |
| Key under another name in a locale, on purpose | `example.title` in `en/hook.json`, `example1.title` in `de/hook.json` |
| Namespaces typed for TypeScript (`CustomTypeOptions`) | `src/i18next.d.ts` |

## What the call sites cover

All in `src/MainPage.jsx` unless noted:

| Case | Where |
|---|---|
| `useTranslation()` and `t('…')` in the default namespace | `t('description.part2')` |
| `ns:key` with the default hook | `t('main:header.title')` |
| `useTranslation('hook')`, key without a prefix | `t('example.title')` |
| `<Trans i18nKey="…">`, with and without a namespace | `description.part3`, `main:trans.description` |
| `withTranslation()` on a class component, `this.props.t` | `t('title')` in `src/LegacyWelcomeClass.jsx` |

## Manual checks

Open this folder with `./gradlew runIde` and accept the setup wizard (it detects *react-i18next* from `package.json`),
then in `src/MainPage.jsx`:

1. **Resolution.** Ctrl+click `'description.part2'`, `'main:header.title'` and `'example.title'`: each opens the
   `en` file of its namespace (`translation`, `main`, `hook`). Folding shows the value in place of the key.
2. **`<Trans>`.** Ctrl+click `"main:trans.description"`: it opens `public/locales/en/main.json`.
3. **Missing in a locale.** `description.part3` and `example.title` are marked as missing in `de`.
