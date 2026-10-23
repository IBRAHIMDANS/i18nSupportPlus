# Angular: Transloco and ngx-translate

Manual testing target for the **i18n Support Plus** plugin on Angular 22 standalone components, translated with
[Transloco](https://jsverse.gitbook.io/transloco) in one app and [ngx-translate](https://ngx-translate.org) in the
other: two locales, one JSON file per locale, variables written `{{name}}`.

| App | Folder |
|---|---|
| Transloco (`@jsverse/transloco`), with a scope | [`transloco/`](transloco) |
| ngx-translate (`@ngx-translate/core`) | [`ngx-translate/`](ngx-translate) |

They are two apps, each opened as its own project, rather than one app using both libraries. A key naming no
namespace (`'home.title'`) is looked up in every translation file of the project, so the two catalogues side by side
would resolve each other's keys and hide the unknown and partially translated cases. Transloco's scopes are also
switched on for the whole project as soon as one `package.json` depends on `@jsverse/transloco`.

The IDE parses an `.html` as an Angular template only when a component points at it through `templateUrl` and
`@angular/core` resolves: run `npm install` before opening a folder, or every template stays plain HTML with no key
to read.

## Run it

```bash
cd transloco        # or ngx-translate
npm install
npm run dev
```

`npm run build` writes `dist/` (gitignored), and the Angular CLI caches its builds in `.angular/` (gitignored).

## What the messages cover

| Case | Transloco | ngx-translate |
|---|---|---|
| One file per locale, loaded over HTTP | `src/assets/i18n/*.json`, `src/app/transloco-loader.ts` | `src/assets/i18n/*.json`, `src/main.ts` |
| A scope: its own files, keys written `admin.…` | `src/assets/i18n/admin/*.json` | — |
| Plain message, message with a parameter (`{{name}}`) | `home.title`, `home.greeting` | `home.title`, `home.greeting` |
| Nested key, three levels deep | `menu.settings.title` | `menu.settings.title` |
| Nested key with a parameter, in the scope | `admin.users.count` | — |
| Missing in a locale, on purpose | `home.onlyInEnglish`, `admin.onlyInEnglish` (not in `fr.json`) | `home.onlyInEnglish` (not in `fr.json`) |

## What the call sites cover

Transloco, in `transloco/src/app/`:

| Case | Where |
|---|---|
| The `\| transloco` pipe, with and without a parameter | `home.title`, `home.greeting` in `app.component.html` |
| `t('key')` from `*transloco="let t"` | `menu.*`, `home.onlyInEnglish` in `app.component.html` |
| `t('key')` from `*transloco="let t; read: 'admin'"`: the key of the scope | `title`, `users.count`, `onlyInEnglish` in `admin/admin-panel.component.html` |
| A scope key written in full under `*transloco="let t"`, next to a global key | `admin.title`, `menu.home` in `admin/admin-panel.component.html` |
| `transloco.selectTranslate('key')` | `home.title` in `app.component.ts` |
| `transloco.translate('admin.key', { … })`: a scope key from TypeScript | `admin.saved` in `admin/admin-panel.component.ts` |
| Unknown key at the call site, on purpose | `'home.doesNotExist' \| transloco` in `app.component.html`, `t('doesNotExist')` in `admin/admin-panel.component.html` |
| A parameter the message reads and the call does not pass, on purpose | `saveAnonymously()` in `admin/admin-panel.component.ts` |

ngx-translate, in `ngx-translate/src/app/`:

| Case | Where |
|---|---|
| The `\| translate` pipe, with and without a parameter | `home.title`, `home.greeting` in `app.component.html` |
| `translate.stream('key')` | `home.title` in `app.component.ts` |
| `translate.get('key', { … })` | `cart.summary` in `app.component.ts` |
| `translate.instant('key')` | `cart.cleared` in `app.component.ts` |
| Unknown key at the call site, on purpose | `'home.doesNotExist' \| translate` in `app.component.html` |
| A parameter the message reads and the call does not pass, on purpose | `greetAnonymously()` in `app.component.ts` |

## Manual checks

Run `npm install` in the folder, open it with `./gradlew runIde` and accept the setup wizard. In `transloco/` it
detects *Transloco (Angular)* and finds `src/assets/i18n`, which it calls a guess because `admin/` holds files too:
leave *Create 2 modules instead* unticked, `admin/` is a scope of the same catalogue. In `ngx-translate/` it detects
*ngx-translate (Angular)* and finds `src/assets/i18n`.

Transloco (`transloco/`):

1. In `src/app/app.component.html`, hover `'home.title'`, then Ctrl+click it: it opens `title` under `home` in a
   locale file. In `src/app/admin/admin-panel.component.html`, Ctrl+click `t('title')`: it opens `title` in
   `src/assets/i18n/admin/en.json`, not a global key. Ctrl+click `'admin.saved'` in `admin-panel.component.ts`: the
   same scope file.
2. Back from the files: on `"saved"` in `src/assets/i18n/admin/en.json`, Find Usages (Alt+F7) lists both calls of
   `admin-panel.component.ts`; on `"title"` of the same file, `t('title')` under `read: 'admin'` and
   `t('admin.title')` in the footer.
3. Inside `t('')` under `read: 'admin'`, Ctrl+Space proposes the scope's keys (`title`, `users`, `save`, …), and no
   global key such as `home` or `menu`.
4. `t('menu.home')` shows *Home*, folded in place or as an inlay hint after the key (Ctrl+Alt+Shift+T toggles the
   folding); `t('title')` under `read: 'admin'` shows *Administration*.
5. `'home.doesNotExist'` and `t('doesNotExist')` are underlined as *Unresolved key*; `t('home.onlyInEnglish')` and
   `t('onlyInEnglish')` under `read: 'admin'` as *Partially translated key*.
6. On `t('doesNotExist')`, Alt+Enter → *Create i18n key*, type a value, pick `admin/en.json`: the key is written in the
   scope file, and the dialog that follows offers to fill `admin/fr.json`. The underline goes. One Ctrl+Z undoes the
   creation.
7. On `home` of `t('menu.home')` in `app.component.html`, Shift+F6 → `start`: `menu.home` becomes `menu.start` in
   both locale files and at every call site, `t('menu.home')` in the footer of `admin-panel.component.html` included. One Ctrl+Z puts everything
   back.
8. `this.transloco.translate('admin.saved')` in `saveAnonymously()` is reported by *Translation variables not passed
   to the call* (`name`); the call of `save()`, which passes `{ name }`, is not.

ngx-translate (`ngx-translate/`):

1. In `src/app/app.component.html`, hover `'home.greeting'`, then Ctrl+click it: it opens `greeting` under `home`.
   Ctrl+click `'cart.summary'` and `'cart.cleared'` in `app.component.ts`: they open the `cart` keys.
2. On `"cleared"` in `src/assets/i18n/en.json`, Find Usages (Alt+F7) lists `app.component.ts`; on `"title"` under
   `home`, `app.component.html` (the pipe) and `app.component.ts` (`stream`).
3. Inside `this.translate.instant('cart.')`, Ctrl+Space proposes `checkout`, `summary`, `clear` and `cleared`.
4. `'menu.settings.title' | translate` shows *Settings*, folded in place or as an inlay hint after the key; the same
   for `'cart.cleared'` in `app.component.ts`.
5. `'home.doesNotExist'` is underlined as *Unresolved key*, `'home.onlyInEnglish'` as *Partially translated key*.
6. On `'home.doesNotExist'`, Alt+Enter → *Create i18n key*, type a value, pick `en.json`: the key is written there,
   nested under `home`, and the dialog that follows offers to fill `fr.json`. The underline goes. One Ctrl+Z undoes
   the creation.
7. On `cleared` of `'cart.cleared'` in `app.component.ts`, Shift+F6 → `emptied`: both locale files and the call site
   now say `cart.emptied`. One Ctrl+Z puts everything back.
8. `this.translate.instant('home.greeting')` in `greetAnonymously()` is reported by *Translation variables not passed
   to the call* (`name`); `get('cart.summary', { count: … })` is not.

The locale files can also be checked from the command line, without the IDE (see *Command Line Check* in the root
README). The command line does not know Transloco's scopes: check the global files and the scope apart.

```bash
./gradlew :cli:installDist
cli/build/install/i18n-check/bin/i18n-check --locales examples/angular-transloco/transloco/src/assets/i18n
cli/build/install/i18n-check/bin/i18n-check --locales examples/angular-transloco/transloco/src/assets/i18n/admin
cli/build/install/i18n-check/bin/i18n-check examples/angular-transloco/ngx-translate
```

Each reports its `onlyInEnglish` as missing from `fr` and exits with `1`. `--code` reads i18next calls only, so it
says nothing about the unknown `doesNotExist` keys here.

## Left out

- Transloco's attribute directive (`<span transloco="home.title">`, `[transloco]="'home.title'"`) and ngx-translate's
  (`<p translate>home.title</p>`, `[translate]="'home.title'"`): the key sits in an attribute or in the element's text,
  which the plugin does not read in an Angular template.
- `[translocoRead]="'admin'"`: the plugin reads it on the tag carrying `*transloco`, where Angular binds it to that
  element rather than to the directive, and fails the build; the form Angular accepts,
  `<ng-template transloco let-t [translocoRead]="'admin'">`, carries no `*transloco` and is not read. The scope is
  therefore read through `read: 'admin'`, deprecated by Transloco 8 but still working until 9.
- `prefix: 'admin'` and `[translocoPrefix]`, Transloco 8's replacement for `read`: not read yet.
- Transloco's standalone `translate('key')`, `translateSignal('key')` and `translateObject('key')`: only the service's
  `translate` and `selectTranslate` are read, on a variable whose name holds `transloco`. Likewise, ngx-translate's
  `instant`, `get` and `stream` are read on a variable whose name holds `translate`.
- Plurals: neither library pluralises without a MessageFormat compiler (`@jsverse/transloco-messageformat`,
  `ngx-translate-messageformat-compiler`), and the plugin's plural support is i18next's `key_one`/`key_other`.
