# svelte-i18n

Manual testing target for the **i18n Support Plus** plugin on a [svelte-i18n](https://github.com/kaisermann/svelte-i18n)
project: Vite, Svelte 5 and TypeScript, two locales, one JSON file per locale.

svelte-i18n formats its messages with ICU MessageFormat: variables are written `{name}`, passed under `values`
(`$_('greeting', { values: { name } })`), and a plural is a `{count, plural, …}` block inside a single string.

The plugin reads `.svelte` files only with the [Svelte plugin](https://plugins.jetbrains.com/plugin/12375-svelte)
(`dev.blachut.svelte.lang`) installed in the IDE: it is not bundled with IntelliJ Ultimate, and without it a component
is plain text, with no key to read. `./gradlew runIde` installs it in the sandbox IDE.

## Run it

```bash
npm install
npm run dev
```

`npm run build` writes `dist/` (gitignored).

## What the messages cover

| Case | Where |
|---|---|
| One file per locale, registered and loaded by `register`/`init` | `src/locales/*.json`, `src/lib/i18n.ts` |
| Plain message, message with a parameter (`{name}`) | `app.title`, `greeting` |
| Nested key, three levels deep | `user.profile.title` |
| ICU plural, `=0`, `one` and `other` in one string | `cart.items` |
| Missing in a locale, on purpose | `only_in_english` (not in `fr.json`) |

## What the call sites cover

| Case | Where |
|---|---|
| `$_('key')` in the markup of a component, `<script lang="ts">` | `src/App.svelte` |
| `$_('key')` in the `<script>` block, inside `$derived(…)` | `user.profile.title` in `src/App.svelte` |
| `$_('key')` in a component with a plain JavaScript `<script>` | `src/lib/Nav.svelte` |
| Parameter and plural passed under `values` | `greeting`, `cart.items` in `src/App.svelte` |
| The descriptor form, `$_({ id: 'key', values })` | `greeting` in `src/App.svelte` |
| The aliases `$t` and `$format`, a `default` that is no key | `nav.settings` in `src/lib/Nav.svelte`, `locale.switch` in `src/App.svelte` |
| `$json('key')`, the messages under a key | `nav` in `src/lib/Nav.svelte` |
| Unknown key at the call site, on purpose | `$_('does.not.exist')` in `src/App.svelte` |
| `$_` outside a component, from `unwrapFunctionStore(_)` | `src/lib/labels.ts` |
| A parameter the message reads and the call does not pass, on purpose | `greetingWithoutName` in `src/lib/labels.ts` |
| A `_` the file does not import from `svelte-i18n`: no key | `src/lib/notSvelteI18n.ts` |

## Manual checks

Open this folder with `./gradlew runIde`, accept the setup wizard (it detects *svelte-i18n* from `package.json` and
finds `src/locales/`), then:

1. In `src/App.svelte`, hover `"app.title"`, then Ctrl+click it: it resolves and opens `app.title` in a locale file.
   From `"title"` in `src/locales/en.json`, Find Usages (Alt+F7) lists `src/App.svelte`; do the same for `nav.home`
   (`src/lib/Nav.svelte`) and `nav.settings` (`src/lib/labels.ts`).
2. Inside `$_("nav.")`, Ctrl+Space proposes `home` and `settings`; the same inside `$t("nav.")` and
   `$_({ id: "nav." })`.
3. `$_("app.title")` shows its value, *svelte-i18n example*, folded in place or as an inlay hint after the key
   (*Show Translations Inline*, under *i18n* in the editor context menu, toggles the folding); the same for `$_("nav.settings")` in `src/lib/labels.ts`.
4. `"does.not.exist"` is underlined as *Unresolved key*, `"only_in_english"` as *Partially translated key*.
5. On `"does.not.exist"`, Alt+Enter → *Create i18n key*, type a value, pick a locale: the key is written there, nested
   as `does` → `not` → `exist`, and the dialog that follows offers to fill the other locale. The underline goes. One
   Ctrl+Z undoes the creation.
6. On `"greeting"` in `src/App.svelte`, Shift+F6 → `welcome`: both locale files and the call sites are renamed,
   `src/lib/labels.ts` included. One Ctrl+Z puts everything back.
7. `$_("greeting")` in `src/lib/labels.ts` is reported by *Translation variables not passed to the call* (`name`);
   `greeting` and `cart.items` in `src/App.svelte`, which pass `values`, are not.
8. Nothing is underlined in `src/lib/notSvelteI18n.ts`.
9. In `src/lib/Nav.svelte`, `$json("nav")` is not reported as a reference to an object, and Ctrl+click on it opens
   `nav`; in `src/App.svelte`, `"Switch language"`, the `default` of `$format`, is not underlined.

The locale files can also be checked from the command line, without the IDE (see *Command Line Check* in the root
README):

```bash
./gradlew :cli:installDist
cli/build/install/i18n-check/bin/i18n-check examples/svelte-i18n
```

It reports `only_in_english` as missing from `fr` and exits with `1`. `--code` reads i18next calls only, so it says
nothing about the unknown `does.not.exist` here.

## Left out

- The number, date and time formatters (`$number`, `$date`, `$time`): they take no message key.
- `$t` is read as svelte-i18n's only where the file imports `svelte-i18n`, as `_` is: elsewhere it is vue-i18n's, whose
  variables are not passed under `values`.
