# Laravel (PHP arrays and Blade)

Manual testing target for the **i18n Support Plus** plugin on a [Laravel](https://laravel.com) project: PHP
translation files, JSON phrases, two locales, PHP calls and Blade views.

Laravel names a key of `lang/en/messages.php` `messages.welcome`: the file name leads, then the path in the
returned array. The plugin reads the keys this way only in a Laravel project, one whose `composer.json` (outside
`vendor/`) requires `laravel/framework`: elsewhere `__('Hello', 'domain')` is WordPress, a text and not a key.

## Run it

Nothing to install. The plugin reads `composer.json`, not `vendor/`: this is a Laravel-shaped tree, not a runnable
application (no `bootstrap/`, no `artisan`). The IDE needs:

- the **PHP** plugin, for `lang/**/*.php` and the controllers;
- the **Blade** plugin, for `resources/views/*.blade.php` (bundled in PhpStorm; `./gradlew runIde` loads both).

Open this folder itself (`examples/laravel`), not `examples/`, so that its `composer.json` makes it a Laravel project.

## What the messages cover

| Case | Where |
|---|---|
| Plain message | `messages.welcome` in `lang/*/messages.php` |
| Message with a parameter (`:name`, `:seconds`) | `messages.greeting`, `messages.auth.throttle` |
| Nested key, an array in the array | `messages.auth.failed`, `messages.auth.throttle` |
| Plural, Laravel's `{0} …\|{1} …\|[2,*] :count …` | `messages.cart.items` |
| Missing in a locale, on purpose | `messages.only_in_english` (not in `lang/fr/messages.php`), `Free shipping` (not in `lang/fr.json`) |
| JSON phrase, the text being the key | `Log in`, `Thanks for your order` in `lang/*.json` |
| JSON phrase holding `.` or `:` | `Saved.`, `Hello, :name` in `lang/*.json` |
| A package's override, read as `courier::messages.…` | `lang/vendor/courier/en/messages.php` |

## What the call sites cover

| Case | Where |
|---|---|
| `__('messages.welcome')`, `trans('messages.auth.failed')` | `app/Http/Controllers/HomeController.php` |
| A parameter passed as an array, `__('messages.greeting', ['name' => 'Ada'])` | `HomeController.php`, `welcome.blade.php` |
| `trans_choice('messages.cart.items', $count)` | `HomeController.php`, `cart.blade.php` |
| The `Lang` facade, `Lang::get(…)`, and `app('translator')->choice(…)` | `HomeController.php` |
| A JSON phrase, `__('Thanks for your order')`, `{{ __('Log in') }}` | `HomeController.php`, `cart.blade.php` |
| A JSON phrase holding `.` or `:`, `__('Saved.')`, `__('Hello, :name', […])` | `HomeController.php` |
| A package key with an override, `__('courier::messages.sent')` | `HomeController.php`, `cart.blade.php` |
| A package key without override, `__('courier::tracking.link')`: neither resolved nor reported | `HomeController.php` |
| `$request->get('page')`: no translation call, never reported | `HomeController.php` |
| Blade echoes, `{{ __('…') }}` and `{!! trans('…') !!}` | `resources/views/welcome.blade.php` |
| Blade directives, `@lang('…')` and `@choice('…', $count)` | `welcome.blade.php`, `cart.blade.php` |
| `@include('partials.nav')`: a view, never a key | `welcome.blade.php` |
| Unknown key, on purpose | `__('messages.does_not_exist')` in `HomeController.php`, `messages.not_in_any_file` in `welcome.blade.php`, `@choice('messages.cart.lines', …)` in `cart.blade.php` |

## Manual checks

Open this folder with `./gradlew runIde` and accept the setup wizard (it detects *Laravel*), then:

1. **Resolution and navigation.** In `HomeController.php`, Ctrl+click `messages.auth.failed`: it opens
   `lang/en/messages.php` on `'failed'`. Do the same in `welcome.blade.php` on `{{ __('messages.welcome') }}`, on
   `@lang('messages.auth.throttle', …)`, and in `cart.blade.php` on `__('Log in')` (opens `lang/en.json`) and on
   `courier::messages.sent` (opens the override under `lang/vendor/courier/`).
2. **Back to the code.** In `lang/en/messages.php`, Ctrl+click `'welcome'`, or Alt+F7 (Find Usages): the PHP and Blade
   call sites are listed.
3. **Completion.** In `HomeController.php`, type `__('messages.` and press Ctrl+Space: `messages.welcome`,
   `messages.auth`, `messages.cart`… are offered; picking one writes the whole key. Typing `__('cou` offers
   `courier::messages`.
4. **Unresolved keys.** `messages.does_not_exist`, `messages.not_in_any_file` and `messages.cart.lines` are underlined
   as *Unresolved key*; nothing is underlined on `$request->get('page')`, `@include('partials.nav')` or
   `courier::tracking.link`.
5. **Missing in a locale.** `messages.only_in_english` and `Free shipping` show as missing in `fr` in the tool window
   (the statistics count them under `messages` and in the JSON phrases).
6. **Create key.** On `messages.does_not_exist`, Alt+Enter → *Create key*: the key is added to the returned array of
   `lang/en/messages.php` and `lang/fr/messages.php`, and the underline goes away. One Ctrl+Z undoes it.
7. **Rename.** In `HomeController.php`, Shift+F6 on `messages.auth.failed` → `denied`: `'failed'` becomes `'denied'` in
   `lang/en/messages.php` and `lang/fr/messages.php`, under `'auth'`, and the call sites follow. One Ctrl+Z undoes it.
8. **Folding and inlay hints.** In `HomeController.php`, `__('messages.welcome')` folds to *Welcome* (*Show Translations Inline*
   under *i18n* in the editor context menu toggles the folding), or shows `↦ Welcome` after the key with folding off; the same on `@lang(…)` and `{{ __(…) }}`
   in `welcome.blade.php`.
9. **JSON phrases with `.` and `:`.** Ctrl+click `Saved.` and `Hello, :name` in `HomeController.php`: they open
   `lang/en.json`; neither is underlined.
10. **Tool window.** Edit the French value of `messages.welcome` in the table: `lang/fr/messages.php` is rewritten
   in place, quotes escaped (`'…\'…'`). One Ctrl+Z in that file undoes it.

### Command line

`i18n-check` (root README, *Command Line Check*) reads JSON and YAML only. On this layout it checks the JSON phrases,
and leaves the PHP files out:

```bash
./gradlew :cli:installDist
cli/build/install/i18n-check/bin/i18n-check examples/laravel --locales examples/laravel/lang
```

It reports `Free shipping` missing in `fr`, and exits with `1`.

## Not covered

What the plugin does not handle yet on Laravel, left out of the checks above:

- **Parameters** (`:name`, `:count`) are not checked against the arguments passed: the key resolves, nothing more.
- **Plural forms** are one string to the plugin: `messages.cart.items` resolves, its `|` variants are not checked
  per locale.
- **`--code` of `i18n-check`**: it reads i18next calls in JavaScript and TypeScript, not PHP.
