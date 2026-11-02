# Symfony (YAML and XLIFF catalogues, Twig)

Manual testing target for the **i18n Support Plus** plugin on a [Symfony](https://symfony.com/doc/current/translation.html)
project: catalogues in `translations/`, three domains, two locales, PHP calls and Twig templates.

Symfony names a catalogue `<domain>.<locale>.<format>`: `messages.fr.yaml` holds the `messages` domain in French. The
domain is the namespace of a key, given by the call — `trans('key', [], 'admin')` — and `messages` when it names none.
The plugin reads them only in a Symfony project, one whose `composer.json` (outside `vendor/`) requires
`symfony/translation`: elsewhere `trans` is any method, and Drupal's `|trans` holds a text.

## Run it

Nothing to install. The plugin reads `composer.json`, not `vendor/`: this is a Symfony-shaped tree, not a runnable
application (no `bin/console`, no `config/`). The IDE needs:

- the **PHP** plugin, for `src/**/*.php`;
- the **Twig** plugin, for `templates/**/*.twig` (bundled in PhpStorm; `./gradlew runIde` loads both).

Open this folder itself (`examples/symfony`), not `examples/`, so that its `composer.json` makes it a Symfony project.

## What the catalogues cover

| Case | Where |
|---|---|
| Nested key | `home.title`, `home.greeting` in `translations/messages.*.yaml` |
| Key written whole, as `translation:extract` writes YAML | `footer.copyright` |
| Message with a parameter (`%name%`) | `home.greeting` |
| ICU catalogue, the same `messages` domain | `cart.items` in `translations/messages+intl-icu.*.yaml` |
| Another domain | `dashboard.title` in `translations/admin.*.yaml` |
| XLIFF keyed by `resname` | `contact.email.invalid` in `translations/validators.*.xlf` |
| XLIFF keyed by its source text, untranslated in English | `This value is too short.` |
| Missing in a locale, on purpose | `only_in_english` (not in `messages.fr.yaml`) |

## What the call sites cover

| Case | Where |
|---|---|
| `$translator->trans('home.title')`, the domain named or not | `src/Controller/HomeController.php` |
| A domain as third argument, `trans('dashboard.title', [], 'admin')` | `HomeController.php` |
| `t('home.title')` and `new TranslatableMessage('dashboard.title', [], 'admin')` | `HomeController.php` |
| `{{ 'home.title'\|trans }}`, with parameters, with a domain, with `domain=` | `templates/home.html.twig` |
| `{% trans %}only_in_english{% endtrans %}` | `templates/home.html.twig` |
| `{% trans_default_domain 'admin' %}` and `{% trans from 'messages' %}` | `templates/admin/dashboard.html.twig` |
| A missing key, reported | `home.subtitle` in `HomeController.php` and `home.html.twig` |

## What to try

- Hover a key, or turn on inlay hints: its value in the preview locale.
- Ctrl+click `'dashboard.title'` in a template: `translations/admin.en.yaml` opens.
- Type `{{ 'home.|'|trans }}`: the keys of the `messages` domain are offered.
- *Create i18n key* on `home.subtitle`, then pick the `messages` catalogues to write it into.
- Run the setup wizard: *Symfony (PHP, Twig)* is ticked from `composer.json`.
