# Contributing to i18nSupportPlus

Thanks for helping improve the plugin 👋

No Kotlin knowledge is required to contribute: docs, fixtures, tests, and behavior changes are all welcome.

## Prerequisites

- **Java 21** (set `JAVA_HOME` to a Java 21 JDK)
- No local IntelliJ Platform SDK setup is needed: Gradle downloads the IDE platform and dependencies

Example:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
java -version
```

## Run the plugin locally

```bash
./gradlew runIde
```

- This launches a sandbox IntelliJ with the plugin loaded.
- First run downloads the platform artifacts (around **700 MB**), then later runs are much faster.

Use the sample projects under `examples/` for manual checks. The main target is:

- `examples/react-multi-namespace/`

## Run tests

```bash
# full test suite
./gradlew test

# one test class
./gradlew test --tests com.ibrahimdans.i18n.plugin.ide.actions.ExtractI18nIntentionActionTest
```

Test fixtures live in `src/test/testData/`. Most plugin tests extend `PlatformBaseTest`.

## Architecture in five lines

1. `Localization` extensions define how translation files are recognized, read into trees, and written.
2. `Lang` extensions define how a code language extracts raw i18n keys and provides language-specific editor integrations.
3. `Technology` extensions define framework behavior (function names, source discovery, framework presets).
4. A key path is parsed from call-site text (`RawKeyParser`/normalizers) into a structured `FullKey`.
5. Resolution walks localization trees by key segments (`CompositeKeyResolver`) to power annotate/navigation/completion/hints/actions.

## Conventions

- Branch names: `<type>/<short-description>` (example: `feat/extract-dialog-plurals`)
- Commits: Conventional Commits (example: `feat(extraction): support plural forms`)
- Add a `CHANGELOG.md` entry under **`## Unreleased`** for user-visible changes

## Optional plugin dependencies and test sandbox notes

The plugin integrates with optional IDE plugins such as **PHP**, **YAML**, and **Vue**.

If a failure appears only in those integrations, check the test sandbox/plugin setup in `build.gradle.kts` (optional dependencies and platform plugin wiring), because these failures can be environment/sandbox related rather than core logic regressions.
