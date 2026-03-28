# Building and Testing the Plugin

## Prerequisites

- **Java 21** — required by IntelliJ Platform 2024.3+
  ```bash
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
  ```
- **Gradle** — the wrapper `./gradlew` is included, no global installation needed

Check the active Java version:
```bash
java -version   # should display 21.x
```

---

## 1. Launch the Test IDE (fastest way to test)

```bash
./gradlew runIde
```

Starts a sandbox instance of IntelliJ IDEA with the plugin loaded. Then open a JS/TS project with translation files to test.

> The first run downloads IntelliJ IDEA 2024.3.6 (~700 MB) into `~/.gradle/caches/`. Subsequent runs are instant.

---

## 2. Generate the Plugin ZIP

```bash
./gradlew buildPlugin
```

Produces the file:
```
build/distributions/i18n-3.0.0.zip
```

### Install the ZIP in an Existing IDE

1. Open IntelliJ IDEA / WebStorm / PhpStorm
2. `Settings` → `Plugins` → gear icon ⚙️ → `Install Plugin from Disk…`
3. Select `build/distributions/i18n-3.0.0.zip`
4. Restart the IDE

---

## 3. Run the Tests

```bash
# All tests
./gradlew test

# A specific class
./gradlew test --tests com.ibrahimdans.i18n.plugin.ide.actions.ExtractI18nIntentionActionTest

# A specific method
./gradlew test --tests com.ibrahimdans.i18n.plugin.ide.actions.ExtractI18nIntentionActionTest.testName

# Full build + tests + coverage
./gradlew check
```

Coverage report generated in:
```
build/reports/jacoco/test/html/index.html
```

---

## 4. Build Without Tests (faster)

```bash
./gradlew build -x test
```

---

## 5. Validate the Plugin Structure

```bash
./gradlew verifyPlugin
```

Checks that `plugin.xml` is valid before publication.

---

## Typical Development Workflow

```bash
# 1. Edit the code
# 2. Verify that tests pass
./gradlew test

# 3a. Test in a sandbox IDE (recommended)
./gradlew runIde

# 3b. OR generate the ZIP and install manually
./gradlew buildPlugin
# → build/distributions/i18n-3.0.0.zip
```

---

## 6. Manual Testing with Screenshots

Open the example project included in the repo:

```
examples/react-multi-namespace/
```

It contains pre-configured translation files (EN/FR, namespaces `common`, `auth`, `dashboard`) — ideal for testing all IDE features visually.

Pending screenshots to take (see `docs/SCREENSHOTS_CHECKLIST.md` for full instructions):

| # | Feature | File | How |
|---|---------|------|-----|
| 2 | Annotation — resolved key | `annotation-resolved.png` | Open a JS/TS file with `t('common:existingKey')` — key should show no error |
| 4 | Annotation — missing file | `annotation-missing-file.png` | Write `t('unknownNamespace:key')` — namespace should be underlined |

All other screenshots are already in `docs/img/` (16/17 done).

---

## Version Number

Defined in `gradle.properties`:
```properties
pluginVersion = 3.0.0
```

Update this value before generating a release build.

---

## Target IDE Compatibility

| Property         | Current Value   |
|------------------|-----------------|
| `platformType`   | `IU` (IntelliJ IDEA Ultimate) |
| `platformVersion`| `2022.1.4`      |
| `sinceBuild`     | `221`           |
| `untilBuild`     | `252.*`         |

The plugin is compatible from IntelliJ 2022.1 through 2025.2.x.
