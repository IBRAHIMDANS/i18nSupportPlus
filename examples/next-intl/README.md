# next-intl

Manual testing target for the **i18n Support Plus** plugin on a [next-intl](https://next-intl.dev) project: Next.js
App Router and TypeScript, two locales, one message file per locale with a top-level object per namespace.

next-intl hands out a `t` bound to a namespace: `useTranslations('Cart')` in a Client Component,
`await getTranslations('HomePage')` in a Server Component, and `t('title')` then reads `Cart.title`. The plugin reads
that namespace as a key prefix, so every call site below resolves into `messages/{locale}.json`.

## Run it

```bash
npm install
npm run dev     # http://localhost:3000, redirected to /en; /fr for French
npm run build
```

The keys missing or unknown on purpose make next-intl log `MISSING_MESSAGE` and print the key instead of a text; the
argument left out on purpose logs `FORMATTING_ERROR`. Neither stops the build.

## What the messages cover

| Case | Where |
|---|---|
| Namespaces as top-level objects | `Common`, `Metadata`, `HomePage`, `Cart`, `Settings` in `messages/*.json` |
| Plain message, message with a parameter | `HomePage.title`, `HomePage.greeting` (`{name}`) |
| Nested key under a namespace | `HomePage.links.docs`, `Cart.checkout.button.label` |
| ICU plural | `HomePage.visits`, `Cart.items`, `Settings.Privacy.trackers` |
| Namespace nested two levels deep | `Settings.Privacy.*` |
| Missing in a locale, on purpose | `HomePage.onlyInEnglish`, `Cart.saveForLater`, `Settings.Privacy.cookies.hint` (not in `fr.json`) |

## What the call sites cover

| Case | Where |
|---|---|
| `await getTranslations()` without a namespace, full key `t('Common.appName')` | `app/[locale]/layout.tsx` |
| `getTranslations({ locale, namespace: 'Metadata' })` in `generateMetadata` | `app/[locale]/page.tsx` |
| `await getTranslations('HomePage')` in a Server Component | `app/[locale]/page.tsx` |
| `useTranslations('Cart')` in a Client Component | `app/[locale]/Cart.tsx` |
| `useTranslations('Settings.Privacy')`, a namespace with nested segments, then `t('cookies.label')` | `app/[locale]/PrivacySettings.tsx` |
| Parameter passed: `t('greeting', { name })`, `t('updatedBy', { user })`, `t('total', { amount })` | `page.tsx`, `PrivacySettings.tsx`, `Cart.tsx` |
| Plural with its `count`: `t('visits', { count })`, `t('items', { count })`, `t('trackers', { count })` | `page.tsx`, `Cart.tsx`, `PrivacySettings.tsx` |
| Parameter not passed, on purpose: `t('total')` | `app/[locale]/Cart.tsx` |
| Key missing in `fr`, on purpose: `t('onlyInEnglish')`, `t('saveForLater')`, `t('cookies.hint')` | `page.tsx`, `Cart.tsx`, `PrivacySettings.tsx` |
| Unknown key, on purpose: `t('doesNotExist')`, `t('discount')`, `t('cookies.expiry')` | `page.tsx`, `Cart.tsx`, `PrivacySettings.tsx` |

## Manual checks

Open this folder with `./gradlew runIde` and accept the setup wizard: it detects *next-intl* from `package.json`, finds
`messages/`, and sets the module's preset to `next-intl` — the preset that reads `{name}` as an ICU argument. Then:

1. **Resolution**: no key is underlined in `app/[locale]/` but the ones placed on purpose below. `t('title')` in
   `page.tsx`, `Cart.tsx` and `PrivacySettings.tsx` resolves to three different keys.
2. **Navigation**: Ctrl+click `t('cookies.label')` in `PrivacySettings.tsx` lands on `Settings.Privacy.cookies.label`
   in `messages/en.json`. Back: Alt+F7 (Find Usages) on `"label"` there lists that call, and only it — not
   `Cart.checkout.button.label`.
3. **Completion**: inside `t('')` in `Cart.tsx`, Ctrl+Space offers `title`, `items`, `checkout`… — the keys of `Cart`,
   not those of `HomePage`. Inside `t('')` in `PrivacySettings.tsx`, the keys of `Settings.Privacy`.
4. **Folding and inlay**: *Show Translations Inline* (or the inlay hints) shows `Your cart` in place of
   `t('title')` in `Cart.tsx`; the preview locale widget switches it to `Votre panier`.
5. **Unresolved key**: `doesNotExist`, `discount` and `cookies.expiry` are underlined as *Unresolved key*;
   `onlyInEnglish`, `saveForLater` and `cookies.hint` as *Partially translated key*.
6. **Parameters**: `t('total')` in `Cart.tsx` is reported as *Variable(s) used by the translation but not passed to
   the call: amount*; `t('total', { amount: … })` and the plurals given their `count` are not.
7. **Create key**: Alt+Enter on `discount` → *Create i18n key*: `Cart.discount` is written under `Cart` in
   `messages/*.json` and the underline goes. One Ctrl+Z removes it from every file.
8. **Rename**: Shift+F6 on `title` in `Cart.tsx`, rename it `heading`: `Cart.title` becomes `Cart.heading` in both
   files, `HomePage.title` and `Settings.Privacy.title` stay. One Ctrl+Z restores the call and both files.
9. **Command line**: from the repository root, after `./gradlew :cli:installDist`,
   `cli/build/install/i18n-check/bin/i18n-check examples/next-intl --locales examples/next-intl/messages` reports the
   three keys missing from `fr`. `--code` is left out: it reads i18next's `useTranslation`, not next-intl's
   namespaces, and would report the keys written under one as unknown.

`t.rich`, `t.markup`, `t.raw` and `t.has` are not read: their keys are neither resolved nor underlined, so this example
does not call them.
