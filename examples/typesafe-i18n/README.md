# typesafe-i18n

Manual testing target for the **i18n Support Plus** plugin on a [typesafe-i18n](https://github.com/ivanhofer/typesafe-i18n)
project: React, Vite and TypeScript, two locales, one module per locale.

typesafe-i18n reads no key as a string: each locale module default-exports its messages
(`src/i18n/<locale>/index.ts`), the generator turns them into types, and the code reads a message as a member chain,
`LL.home.title()`. The plugin finds the locale modules from `.typesafe-i18n.json` (`outputPath`, `./src/i18n/` by
default) or from the files the generator wrote, and reads `LL.a.b` as the key `a.b`.

## Run it

```bash
npm install
npm run dev     # generates src/i18n/i18n-*.ts, then http://localhost:5173
```

The generated files are not committed (`.gitignore`): the plugin does not need them, the app does.

## What the messages cover

| Case | Where |
|---|---|
| Message with a typed argument | `HI` (`{name:string}`) |
| Nested messages | `home.title`, `cart.checkout.button` |
| Plural | `cart.items` (`{{s}}`) |
| Missing in a locale, on purpose | `home.onlyInEnglish` (not in `fr/index.ts`) |

## What the call sites cover (`src/App.tsx`)

| Case | Where |
|---|---|
| `LL` from `useI18nContext()` | every read |
| Top-level and nested messages | `LL.HI({ name })`, `LL.home.title()`, `LL.cart.checkout.button()` |
| Missing in `fr`, on purpose | `LL.home.onlyInEnglish()` |
| Unknown key, on purpose | `LL.home.doesNotExist()` — `tsc` rejects it too, so `npm run build` skips `tsc` |

## Manual checks

Open this folder with `./gradlew runIde`. Then, in `src/App.tsx`:

1. **Resolution**: only `doesNotExist` is underlined as an unknown key; with *Partially translated keys* on,
   `onlyInEnglish` is reported as missing in `fr`.
2. **Completion**: after `LL.`, Ctrl+Space offers `HI`, `home`, `cart`; after `LL.home.`, `title`, `subtitle`,
   `onlyInEnglish` — each message with its English value.
3. **A message added to `en/index.ts`** is offered and resolved at once, before the generator runs again.

Not covered: Ctrl+click goes to the generated types rather than to the message, Shift+F6 does not rename a message,
and folding and inlay hints do not show the value of `LL.…` calls.
