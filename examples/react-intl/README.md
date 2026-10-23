# react-intl (FormatJS)

Manual testing target for the **i18n Support Plus** plugin on a [react-intl](https://formatjs.github.io/docs/react-intl)
project: Vite, React and TypeScript, two locales, ICU messages in one JSON file per locale.

react-intl names a message by the `id` of a descriptor (`formatMessage({ id: "cart.summary" })`,
`<FormattedMessage id="app.title" />`, `defineMessages({ … })`), and stores its ids flat: `"profile.header.title"` is
one property of `src/lang/en.json`, not three nested levels. The plugin reads it that way once **Treat keys as flat**
is on, which the setup wizard does by itself when it detects react-intl.

## Run it

```bash
npm install
npm run dev     # or: npm run build
```

The plugin needs neither: it reads `package.json` to detect react-intl, and the files under `src/`.

## What the messages cover

| Case | Where |
|---|---|
| One flat JSON file per locale, at the FormatJS default place (`lang/{lang}.json`) | `src/lang/en.json`, `src/lang/fr.json` |
| Plain message | `app.title` |
| Message with a parameter, `{name}` | `home.greeting` |
| ICU plural, `{count, plural, one {…} other {…}}` | `cart.summary` |
| Dotted id, one property rather than nested objects | `profile.header.title` |
| Missing in a locale, on purpose | `home.only_in_english` (not in `fr.json`) |

## What the call sites cover

| Case | Where |
|---|---|
| `<FormattedMessage id="…" />` | `src/App.tsx` |
| `<FormattedMessage>` with `values`, with `defaultMessage` | `src/App.tsx` |
| `intl.formatMessage({ id })` from `const intl = useIntl()`, with values | `src/Cart.tsx` |
| Descriptor with a `defaultMessage` | `cart.checkout` in `src/Cart.tsx` |
| A plural called without its `count`, on purpose | second `cart.summary` in `src/Cart.tsx` |
| Bare `formatMessage(…)` destructured from `useIntl()` | `src/Profile.tsx` |
| `defineMessages({ … })` with `defaultMessage` and `description`, `defineMessage({ … })` | `src/Orders.tsx` |
| A descriptor passed by reference, `intl.formatMessage(message)`: no key at the call | `src/Orders.tsx` |
| Unknown key at the call site, on purpose, once per syntax | `home.does_not_exist` (`src/App.tsx`), `cart.does_not_exist` (`src/Cart.tsx`), `orders.status.does_not_exist` (`src/Orders.tsx`) |
| A descriptor-shaped object outside `defineMessages`, an `id` on another tag: no key | `src/NotIntl.tsx` |

## Manual checks

Open this folder with `./gradlew runIde`, then:

1. The setup wizard detects *react-intl (FormatJS)*, proposes `src/lang` with `{lang}.json`, and lists *Treat keys as
   flat (react-intl stores flat ids)*. Apply it. Without that setting every id is unresolved.
2. Resolution: no id is underlined in `src/App.tsx`, `src/Cart.tsx`, `src/Profile.tsx` and `src/Orders.tsx`, apart
   from the cases below; neither is any `defaultMessage` or `description`, nor anything in `src/NotIntl.tsx`.
3. Unresolved key: `home.does_not_exist`, `cart.does_not_exist` and `orders.status.does_not_exist` are underlined as
   *Unresolved key*; `home.only_in_english` as *Partially translated key*, and `src/lang/fr.json` reports it as
   *Key 'home.only_in_english' of reference locale 'en' is missing here*.
4. Parameters: the second `cart.summary` in `src/Cart.tsx` is flagged *Variable(s) used by the translation but not
   passed to the call: count*; the first one, and `home.greeting` with its `values`, are not.
5. Value of a key: hover an id (or Ctrl+Q) for its value in each locale; turn on *Editor › Inlay Hints › i18n
   translations* to read `↦ Hello {name}!` after `home.greeting`. The plural shows its `other` branch,
   `{count} items in your cart`.
6. Completion: Ctrl+Space between the quotes of an `id` (attribute or descriptor) lists the ids with their English
   value, and finds `cart.summary` by typing `items`.
7. From the translation file to the code: Ctrl+click `cart.summary` in `src/lang/en.json` lists both `id` literals of
   `src/Cart.tsx`; `orders.status.paid` leads to `src/Orders.tsx`.
8. Create key: Alt+Enter on `home.does_not_exist` › *Create i18n key*, enter a value: the id is written as one flat
   property. One Ctrl+Z removes it again.
9. Create missing translations: Alt+Enter on `home.only_in_english` › *Create missing translations* adds it to
   `src/lang/fr.json`. One Ctrl+Z removes it again.

Not supported yet for react-intl, so left out of the checks: Ctrl+click from an `id` to its translation, folding an
id to its value, renaming a key with Shift+F6 (from the call site, or with its call sites following from the
translation file), the `<FormattedMessage id>` attributes among the call sites of step 7, *Extract I18n* writing
react-intl's syntax (it writes `t('…')`), and the key of `intl.formatMessage(message)` read from its descriptor.

## Command line check

`i18n-check` (root README, *Command Line Check*) compares the two locale files without the IDE:

```bash
./gradlew :cli:installDist
cli/build/install/i18n-check/bin/i18n-check examples/react-intl
```

It reports `home.only_in_english` missing from `fr` and exits with `1`. Its `--code` option reads i18next calls
only: it does not apply to this project.
