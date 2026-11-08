# PHP gettext

Manual testing target for the **i18n Support Plus** plugin on plain PHP with GNU gettext: the msgid *is* the key, and
the catalogues live in `translations/<locale>/LC_MESSAGES/messages.po` (compiled `.mo` next to them) for `en_US`,
`nl_NL` and `de_DE`.

## Run it

```bash
php gettext-demo.php
```

It needs `ext-gettext` and the three system locales (the script's header says how to install them on Ubuntu). It
prints each string in each locale, then for `fr_FR`, which has no catalogue.

## What it covers

| Case | Where |
|---|---|
| `gettext('…')`, translated in every locale | `one`, `two` |
| Missing in a locale, on purpose | `three` (not in `en_US`) |
| Missing in every catalogue, on purpose | `four` |
| Plural entry, `msgid` / `msgid_plural` / `msgstr[n]` | `one` / `more` |
| `ngettext('…', '…', n)`: not in the default aliases, so not read as a key | `gettext-demo.php` |

## Manual checks

Open this folder with `./gradlew runIde` (PHP plugin installed; the GNU GetText plugin for full `.po` support), then
turn on *PHP gettext and PO file support* in the plugin settings (*PHP / gettext*; aliases `gettext,_,__` by default).
In `gettext-demo.php`:

1. **Resolution.** Ctrl+click `'one'` or `'two'`: it opens the msgid in a `messages.po`. Folding shows the value.
2. **Missing keys.** `'four'` is underlined as unresolved; `'three'` is marked as missing in `en_US`.
