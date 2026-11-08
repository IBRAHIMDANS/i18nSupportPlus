# i18n-support-plus

`i18n-check`, the translation checks of the [I18n Support Plus](https://plugins.jetbrains.com/plugin/30971-i18n-support-plus)
IDE plugin, from a terminal, a pre-commit hook or a CI job. A native binary: no Java to install.

```sh
npx i18n-support-plus --code
```

It compares the translation files of the project in the current directory to the reference locale: missing
and empty values, placeholders, tags and ICU messages that differ, plural forms a language needs. `--code`
also reads the code, and reports the keys it calls that no translation file holds. It exits `0` when
nothing is wrong, `1` on a problem, `2` when it cannot check.

```text
i18n-support-plus [project] [--locales <dir> | --code] [--reference <locale>] [--format text|json|github] [--max-problems N] [--no-values]
```

The options, the settings it reads (`.idea/i18nSettings.xml` or `i18n-check.json`), the GitHub Action and the
Docker image are described in [docs/checks.md](https://github.com/IBRAHIMDANS/i18nSupportPlus/blob/main/docs/checks.md).

## Platforms

npm installs the one binary of the machine, as an optional dependency: Linux x64 and arm64 (glibc), macOS x64
and arm64, Windows x64. There is none for Alpine (musl): use the Docker image there, or the zip of each
[GitHub release](https://github.com/IBRAHIMDANS/i18nSupportPlus/releases), which runs anywhere Java 21 does.

An install with `--no-optional` or `--omit=optional`, or from a lockfile written on another platform, leaves
the binary out: `i18n-support-plus` then says which package it lacks.
