# Vue I18n

Manual testing target for the **i18n Support Plus** plugin on a [vue-i18n](https://vue-i18n.intlify.dev) project:
Vue 3, Vite and TypeScript, two locales, global messages in `src/locales/` and component messages in `<i18n>` blocks.

vue-i18n runs in Composition API mode (`legacy: false`): templates call the global `$t`, `<script setup>` calls the
`t` that `useI18n()` returns. A component holding an `<i18n>` block gets its own scope, so it calls `t` in its
template too; that `t` reads the block first, then falls back to the global messages.

## Run it

```bash
npm install
npm run dev
```

`@intlify/unplugin-vue-i18n` compiles the `<i18n>` blocks (JSON and YAML) and the files of `src/locales/` at build
time. The plugin needs none of it: it only reads the files.

## What the messages cover

| Case | Where |
|---|---|
| Nested keys | `src/locales/*.json`, `home.title`, `order.status.paid` |
| Message with a parameter, `{name}` | `home.greeting` |
| Plural in vue-i18n's syntax, `a \| b \| c`: a branch per form the locale's language uses, checked by *vue-i18n plural branches* | `cart.items` |
| Linked message, `@:common.save`: the linked key resolves | `actions.saveHint` |
| Missing in a locale, on purpose | `home.onlyInEnglish` (not in `fr.json`) |
| Top-level keys out of order, on purpose (for *Sort i18n Keys*) | `src/locales/en.json` |
| `<i18n>` block in JSON, `{ "en": {…}, "fr": {…} }` | `src/components/WelcomeBanner.vue` |
| `<i18n lang="yaml">` block | `src/components/HelpPanel.vue` |
| Key that exists only in a block | `welcome`, `tip` (`WelcomeBanner.vue`), `help.title` (`HelpPanel.vue`) |
| Key that exists only in the global files, called from a component with a block | `common.save` (`WelcomeBanner.vue`), `common.cancel` (`HelpPanel.vue`) |

## What the call sites cover

| Case | Where |
|---|---|
| `{{ $t('…') }}` in a template, with named values, with a plural count | `src/App.vue` |
| `$te('…')` | `src/App.vue` (`common.switchLocale`) |
| `v-t` as a string and as an object's `path` (the `args` strings are no keys) | `src/App.vue` |
| `<i18n-t keypath="…">` with a named slot | `src/App.vue` (`terms.accept`) |
| `useI18n()` and `t('…')` in `<script setup lang="ts">` | `src/components/OrderSummary.vue` |
| Key built at runtime, `` t(`order.status.${props.status}`) ``: no key, nothing underlined | `src/components/OrderSummary.vue` |
| `t('…')` in the template and the script of a component with an `<i18n>` block | `WelcomeBanner.vue`, `HelpPanel.vue` |
| Unknown key at the call site, on purpose | `$t('home.doesNotExist')` in `src/App.vue` |
| Unknown key in a component with an `<i18n>` block, on purpose | `t('farewell')` in `WelcomeBanner.vue` |

## Manual checks

Open this folder with `./gradlew runIde`, accept the setup wizard (it detects *vue-i18n* from `package.json`), then:

1. **Resolution, template to global file.** In `src/App.vue`, Ctrl+click `'home.title'`, `'common.cancel'` (in `v-t`)
   and `"terms.accept"` (in `<i18n-t keypath>`): each opens its value in `src/locales/en.json`. Folding shows the
   value in place of the key.
2. **Resolution, script to global file.** In `OrderSummary.vue`, Ctrl+click `"order.title"` in `<script setup>`: it
   opens `src/locales/en.json`.
3. **Resolution to the `<i18n>` block.** In `WelcomeBanner.vue`, Ctrl+click `'welcome'` in the template and `"tip"` in
   the script: each lands on the key under `"en"` in the component's own block. In `HelpPanel.vue`, `'help.title'`
   lands in the YAML block. `'common.save'` in `WelcomeBanner.vue`, absent from the block, still opens the global file.
4. **Linked message.** In `src/locales/en.json`, Ctrl+click `common.save` inside `actions.saveHint`: it opens `save`.
5. **Completion.** Type `{{ $t('ho` in `src/App.vue` and complete: `home.title`, `home.greeting` and the other
   `home.*` keys are offered. Do the same inside `v-t="'…'"` and `keypath="…"`.
6. **Unresolved keys.** `'home.doesNotExist'` in `src/App.vue` and `'farewell'` in `WelcomeBanner.vue` are underlined
   as *Unresolved key*; `home.onlyInEnglish` as missing in `fr`. The runtime key in `OrderSummary.vue` and the `args`
   of `v-t` are not underlined.
7. **Create a key inside a component with a block.** On `'farewell'` in `WelcomeBanner.vue`, Alt+Enter, *Create i18n
   key*: the key lands **under the block's `"en"`**, next to `welcome` and `tip`, never beside the locales (`"en"`,
   `"fr"`, `"farewell"`). One Ctrl+Z removes it.
8. **Rename a key used in a block.** On `'welcome'` in `WelcomeBanner.vue`, Shift+F6, rename it to `greeting`: the
   call and the block's entries follow. One Ctrl+Z restores them.
9. **Sort keys.** In `src/locales/en.json`, right-click, *Sort i18n Keys Alphabetically*: `actions` comes first, the
   nested keys are sorted too. One Ctrl+Z restores the order.

`t` is i18next's name as well as vue-i18n's: without a module, every name applies and `t('…')` is read. A module
whose preset is *vue-i18n* reads vue-i18n's calls only: `$t`, `$tc`, `$te`, and the `t` / `te` a file takes from
`useI18n()` — a `t` of another origin stays unread there.

## Command line check

`i18n-check` compares `src/locales/fr.json` with `en.json` (see *Command Line Check* in the root README):

```bash
./gradlew :cli:installDist
cli/build/install/i18n-check/bin/i18n-check examples/vue-i18n
```

It reports `home.onlyInEnglish` missing in `fr` and exits with `1`. With `--code` it also reads the `.vue` files,
templates included, and reports the two unknown keys, `home.doesNotExist` and `farewell`; the keys of the `<i18n>`
blocks count for their own component's calls.

## Left out

- `$tc`: the plugin reads it, but vue-i18n 11 removed it; `$t('cart.items', 3)` picks the plural form instead.
