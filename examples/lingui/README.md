# Lingui

Manual testing target for the **i18n Support Plus** plugin on a [Lingui](https://lingui.dev) 5 project: React, Vite
and TypeScript, two locales, catalogues in Lingui's default PO format under `src/locales/<locale>/messages.po`.

A Lingui message is looked up by its id: an explicit one (`i18n._("app.greeting")`, `msg({ id: "nav.home" })`), or,
for `<Trans>Hello world!</Trans>`, the source text itself, which a PO catalogue keeps as the msgid. PO has no nesting:
`user.profile.title` is one msgid, and the plugin reads it whole once *GetText mode* is on.

## Run it

```bash
npm install
npm run dev
```

The Vite plugin compiles the catalogues on import, so no `lingui compile` is needed. `lingui extract` is not one of
the scripts on purpose: it would add to the catalogues the keys this example leaves out of them.

## What the messages cover

| Case | Where |
|---|---|
| PO header (`msgid ""`): no key | top of each `messages.po` |
| Explicit id, flagged `#. js-lingui-explicit-id` as `lingui extract` writes it | `app.greeting`, `nav.home`, … |
| Source text as the msgid, from `<Trans>` | `Hello world!` |
| Expression inside `<Trans>`, numbered by Lingui | `Your total is {0}` |
| Message with a parameter | `app.greeting` (`Hello {name}!`) |
| Dotted id, one flat msgid | `user.profile.title` |
| ICU plural in a single `msgstr` | `cart.items` |
| Missing in a locale, on purpose | `about.beta` (not in `fr/messages.po`) |
| Unknown key at the call site, on purpose | `i18n._("checkout.coupon")` in `src/App.tsx` |
| Origin lines `#: src/App.tsx:27` above the msgid, as `lingui extract` writes them | `app.greeting`, `user.profile.title` |

## What the call sites cover

| Case | Where |
|---|---|
| `useLingui()` from `@lingui/react`, then `i18n._("id")` | `src/App.tsx` |
| `i18n._("id", values)`: a parameter, a plural count | `src/App.tsx` |
| Lingui's global `i18n` from `@lingui/core`, outside any component | `src/notices.ts` |
| `msg({ id })` from `@lingui/core/macro`, translated later with `i18n._(item)`: checked, no Ctrl+click | `src/nav.ts` |
| `<Trans>` holding plain text, from `@lingui/react/macro`: checked, no Ctrl+click | `src/App.tsx` |
| `<Trans>` holding an expression, read as `{0}` like Lingui does | `src/App.tsx` |
| A local `msg` in a file importing nothing from `@lingui/`: no key | `src/notLingui.ts` |

## Manual checks

1. Open this folder with `./gradlew runIde` and accept the setup wizard: it detects *lingui*, finds both
   `messages.po`, sets the default namespace to `messages` and ticks *GetText mode* — keep it, it is what keeps
   `user.profile.title` one key rather than three levels. The sandbox ships the GNU GetText plugin PO files need.
2. In `src/App.tsx`, only `checkout.coupon` is underlined, as *Unresolved key*. Nothing is in `src/notLingui.ts`.
3. `about.beta` in `src/notices.ts`: the gutter icon reports it in some locales only. With *Enable partially
   translated keys inspection* on (Settings → i18n Support Plus), it is underlined as *Partially translated key*.
4. Ctrl+click on `"user.profile.title"` in `src/App.tsx` opens its msgid in `en/messages.po` or `fr/messages.po`;
   Ctrl+click on that msgid leads back to the call. `<Trans>` and `msg({ id })` are checked but carry no reference:
   Ctrl+click on them goes nowhere, and the msgid does not lead back to them.
5. With *Enable folding* on, `i18n._("app.greeting", …)` folds to its English value, `Hello {name}!`.
6. Alt+Enter on `checkout.coupon` → *Create i18n key*: the msgid is added to the catalogue, flagged
   `#. js-lingui-explicit-id` as `lingui extract` would, and the underline goes. One Ctrl+Z removes it. Created from a
   `<Trans>`, a msgid gets no flag: it is the source text.
7. In `i18n._("")`, Ctrl+Space offers every msgid with its English value.
8. Shift+F6 on `"user.profile.title"` in `src/App.tsx` renames the call and the msgid in both catalogues.

The `i18n-check` command line (root README, *Command Line Check*) reads JSON
and YAML only, so it does not apply to this layout.

Left out because the plugin reads them differently from Lingui: `<Trans id="…">` (the plugin takes the children, not
the `id`), `<Trans>Hello {name}!</Trans>` (Lingui keeps `{name}` for a bare variable, the plugin writes `{0}`), the
`t` macro (the plugin gives `t` to i18next, not to Lingui), and `msg({ id, message })` (the `message` string is read
as a second key).
