# Angular native i18n (`@angular/localize`)

Manual testing target for the **i18n Support Plus** plugin on Angular's own i18n: messages marked with a custom id in
the template (`i18n="@@home.title"`) and in TypeScript (`` $localize`:@@cart.saved:…` ``), extracted by
`ng extract-i18n` into `src/locale/messages.xlf`, and translated into one XLIFF 1.2 file per locale that
`angular.json` lists under `i18n.locales`.

The plugin reads the XLIFF files `angular.json` lists, plus the extracted file, which holds the `sourceLocale`
(`en-US`), the reference. An XLIFF file it does not list, such as an export, is no source.

The IDE parses an `.html` as an Angular template only when a component points at it through `templateUrl` and
`@angular/core` resolves: run `npm install --no-package-lock` before opening the folder, or the template stays plain
HTML with no key to read.

## Run it

```bash
npm install --no-package-lock
npm run dev        # en-US
npm run dev:fr     # French
npm run extract    # writes src/locale/messages.xlf again
```

## What the files cover

| Case | Where |
|---|---|
| The reference, the `<source>` of each unit | `src/locale/messages.xlf` |
| A locale, the `<target>` of each unit | `src/locale/messages.fr.xlf` |
| A locale given as `{ "translation": … }` in `angular.json` | `de`, `src/locale/messages.de.xlf` |
| A unit copied without its `<target>`: missing in that locale | `cart.terms` in `messages.de.xlf` |
| A unit not copied at all: missing in that locale | `home.intro` in `messages.de.xlf` |
| Entities in a value, shown decoded | `cart.terms`, `home.logo.alt` |
| A placeholder as an inline element | `home.greeting` (`<x id="name"/>`) |

## What the call sites cover

| Case | Where |
|---|---|
| `i18n="@@id"`, with and without meaning and description | `home.title`, `home.intro` in `app.component.html` |
| `i18n-alt="@@id"` on a translated attribute | `home.logo.alt` in `app.component.html` |
| `` $localize`:@@id:…` ``, with and without description and placeholder | `cart.saved`, `home.greeting` in `app.component.ts` |
| An id no file holds, on purpose: *Unresolved key* | `home.doesNotExist` (template), `cart.doesNotExist` (TypeScript) |
| No custom id: no key read | the `<footer>`, `` $localize`Thank you` `` |

## What to check

- Each id above is highlighted as a key, *Unresolved key* for the two ids no file holds.
- Ctrl+click on `home.title` lists its unit in the three files; on a unit's `id` in an XLIFF file, the calls.
- Ctrl+Space inside `i18n="@@"` offers the ids of the reference file.
- The tool window lists `en-US`, `fr` and `de`; `cart.terms` and `home.intro` are missing in `de`.
- *Fill Missing Translations…* on `de` writes `<target>` into the unit of `cart.terms`, and creates the unit of
  `home.intro` with the source of `messages.xlf`.

Not covered: extracting a hard-coded text into a new id, and renaming an id. `ng extract-i18n` owns the ids.
