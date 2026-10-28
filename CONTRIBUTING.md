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

The sandbox runs IntelliJ IDEA Ultimate, which needs a licence or a trial. If yours cannot
start one, run the plugin in a locally installed WebStorm instead:
`./gradlew runWebStorm -PwebStormPath=/path/to/webstorm` (the task only exists when
`webStormPath` is set, on the command line or in `~/.gradle/gradle.properties`).

To try your build in your own IDE: `./gradlew buildPlugin`, then
*Settings › Plugins › ⚙ › Install Plugin from Disk* with the ZIP from `build/distributions/`.

## UI screenshots

The first automated README capture is `docs/img/toolwindow-table.png`. It uses JetBrains Starter +
Driver to start a clean, licensed **WebStorm 2025.3.4**, opens a disposable copy of
`examples/react-multi-namespace/`, then captures the exact Swing bounds of the i18n tool window.
It is intentionally outside `check`: a cold IDE download and start take several minutes.

Set `LICENSE_KEY` to the Base64-encoded contents of a WebStorm key file, as required by Starter's
`setLicense`. An activated WebStorm already has one in `~/.config/JetBrains/WebStorm<version>/webstorm.key`.
Then run under Xvfb; the fake `WAYLAND_DISPLAY` keeps the IDE off your desktop session:

```bash
export LICENSE_KEY="$(base64 -w0 ~/.config/JetBrains/WebStorm2026.2/webstorm.key)"
export WAYLAND_DISPLAY=bogus XDG_SESSION_TYPE=x11

xvfb-run -a --server-args="-screen 0 1920x1440x24" \
  ./gradlew generateUiScreenshots --no-daemon

xvfb-run -a --server-args="-screen 0 1920x1440x24" \
  ./gradlew verifyUiScreenshots --no-daemon
```

`generateUiScreenshots` takes the same capture in two fresh IDE sessions, requires pixel-identical
output, and only then replaces `docs/img/toolwindow-table.png`. Review that image diff before
committing it. `verifyUiScreenshots` repeats the two-run check and compares the result with the
committed PNG. Both write their disposable projects and candidates below `build/ui-screenshots/`.

The manual **UI screenshots** workflow is disabled for now (`if: false`): it would need the license
as the repository secret `JETBRAINS_LICENSE_KEY`, and a personal license must not go there. Never
put license material in a Gradle property, checked-in file, or workflow input.
The former `runWebStorm` + xdotool process remains the fallback for captures that have not yet
joined this suite.

## Run tests

```bash
# full test suite
./gradlew test

# one test class
./gradlew test --tests com.ibrahimdans.i18n.plugin.ide.actions.ExtractI18nIntentionActionTest

# tests + coverage report (build/reports/jacoco/test/html/index.html)
./gradlew check
```

Test fixtures live in `src/test/resources/` (`keyExtraction/`, `references/`, `folding/`…). Most
plugin tests extend `PlatformBaseTest`; `TestsCommon.kt` holds the shared helpers
(`runWithConfig`, `launchActionAndWait`).

## Architecture in five lines

1. `Localization` extensions define how translation files are recognized, read into trees, and written.
2. `Lang` extensions define how a code language extracts raw i18n keys and provides language-specific editor integrations.
3. `Technology` extensions define framework behavior (function names, source discovery, framework presets).
4. A key path is parsed from call-site text (`RawKeyParser`/normalizers) into a structured `FullKey`.
5. Resolution walks localization trees by key segments (`CompositeKeyResolver`) to power annotate/navigation/completion/hints/actions.

## Conventions

- Branch names: `<type>/<short-description>` (example: `feat/extract-dialog-plurals`), with
  `feat`, `fix`, `refactor`, `chore` or `test` as the type
- Commits: Conventional Commits (example: `feat(extraction): support plural forms`)
- Add a `CHANGELOG.md` entry under **`## Unreleased`** for user-visible changes; the version is
  bumped at release time only
- Pull requests target `main`, which is protected (no direct push), and reference the issue
  they close: `Fixes #123`
- To report a problem, use the [issue templates](https://github.com/IBRAHIMDANS/i18nSupportPlus/issues/new/choose)

## Optional plugin dependencies and test sandbox notes

The plugin integrates with optional IDE plugins such as **PHP**, **YAML**, and **Vue**.

The plugin must keep working without them: each is wired through its own config file
(`phpConfig.xml`, `ymlConfig.xml`, `vueConfig.xml`), and no class of an optional plugin may be
referenced from code `plugin.xml` loads unconditionally.

The test sandbox installs them through workarounds in `build.gradle.kts` (the
`prepareTestSandbox` task). A failure appearing only in those integrations —
`NoClassDefFoundError`, `Unresolved reference 'yaml'`, a Vue test skipped — usually comes from
that setup rather than from your change: run `./gradlew --stop`, then try again.
