# monorepo-react

Four React applications with react-i18next in one repository, each with its own `common` namespace in `fr`, `en`, `es`
and `gb`. Open the IDE on **this folder** (the monorepo root), not on one application: it is the case the modules are
for.

```
apps/
  shop/   admin/   blog/   docs/
    package.json
    public/locales/{en,fr,es,gb}/common.json
    src/App.tsx
```

## What each application should report

The files hold five traps. Resolved per application, as each runs on its own, the plugin and `i18n-check` should find:

| Trap | Where | Expected |
| --- | --- | --- |
| Same key, other value | `title` in all four (`Shop`, `Admin`, `Blog`, `Docs`) | the hint in `apps/shop/src/App.tsx` shows `Shop` |
| Key of another application | `t('actions.export')` in `shop`, defined in `admin` only | unknown key in `shop` |
| Translation missing in one application | `actions.save` absent from `shop/public/locales/fr` only | missing in `shop`, `fr` |
| Key used by another application | `posts.empty` defined in `blog` and `docs`, used by `docs` only | unused in `blog` |
| Locale that is not a language | `gb` (the United Kingdom's English is `en-GB`) | read as a locale, or the folder flagged |

## What happens today (2026-10-25)

Measured on this folder:

- Without settings, the four applications are one pool: the 12 `fr`/`en`/`es` files are read together, and the lookup
  of `t('title')` gets the 16 `common.json` files of all four applications. `i18n-check examples/monorepo-react --code`
  answers *No problem found*: none of the traps is seen.
- The setup wizard proposes one module per application, ticked by default: `apps/shop`, `apps/admin`… with the path
  template `public/locales/{lang}/{ns}.json`. With them, `apps/shop/src/App.tsx` belongs to `shop` and resolves against
  `shop`'s files only: the hint, the unknown `actions.export` and the missing `fr` `actions.save` are those of `shop`.
  `i18n-check` with the same modules in `i18n-check.json` reports `shop`'s missing `fr` key, and reads `gb` (a module
  template takes any folder name as the locale).
- Untick a module's *Active* box (settings, or `"enabled": false` in `i18n-check.json`) to put it aside: its files
  are no longer read and its code is not checked.
- Still pooled: the usages seen from a translation file and the unused keys (`posts.empty` in `blog` is not reported),
  in the IDE and with `i18n-check --code`.
- Without module templates, `gb` is not an ISO language code: no locale folder `gb/` is seen as a locale, and
  `i18n-check` names each one it leaves out. *Locale aliases* `gb=en-GB` (settings, or `"localeAliases"` in
  `i18n-check.json`) makes it the `en-GB` locale.

This section is updated as the monorepo support improves: once it is right, the table above is what the plugin and
`i18n-check` report.
