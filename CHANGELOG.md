## [Unreleased]

---

## [1.0.6] - 2026-04-17

### Refactoring
- [TASK-F] Rename `ExpressionParserTest` → `ExpressionNormalizerTest` to reflect the tested class (`ExpressionNormalizer`)
- [TASK-F] Replace `"TODO-${fullKey.source}"` placeholder with `""` as neutral default in `ContentGenerator.generateContent()`

---

## [1.0.5] - 2026-04-15

### Bug Fixes
- [API] Replace deprecated `ReadAction.compute<T, Throwable>` with `runReadAction {}` in `DialogViewModel`, `LocalizationSourceService`, `TranslationDataLoader`, `TranslationStatsPanel`, and `HintProvider` — fixes deprecation warnings in IU-261+
- [API] Replace deprecated `DaemonCodeAnalyzer.restart()` (no-arg) with targeted `restart(PsiFile)` over all open editors in `ToggleFoldingAction`
- [Inlay] Migrate `I18nInlayHintsProvider` from experimental `InlayHintsProvider<NoSettings>` to stable declarative `InlayHintsProvider` + `SharedBypassCollector` + `HintFormat` API — reduces experimental API usages from ~90 to &lt;10 across all verified IDEs (IU-243..261)
- [Plugin.xml] Switch inlay hints registration from `codeInsight.inlayProvider` to `codeInsight.declarativeInlayHintsProvider`

---

## [1.0.4] - 2026-04-15

### Bug Fixes
- [JS] Replace `ES6Property` (scheduled for removal) with `JSComputedPropertyNameOwner` in `JsReferenceAssistant` — eliminates the "scheduled for removal API" warning reported by the Plugin Verifier

### CI / Infrastructure
- Remove plugin signing step from release workflow — signing secrets are no longer required

---

## [1.0.3] - 2026-04-14

### Features
- [Lingui] Support `<Trans>` JSX component from `@lingui/react/macro` — source text is used directly as the msgid key (JSON/YAML translation files; PO requires the GNU GetText plugin)

### Bug Fixes
- [Lingui] Fix `<Trans>` annotator not firing — JSX/TSX annotators were wired to `JsLang` instead of `JsxLang`; add dedicated `JsxCompositeKeyAnnotator`
- [Lingui] Fix invalid TextRange crash — `LinguiTransKeyExtractor` now targets the `XmlTag` element and extracts msgid via raw text slice, avoiding JSX lexer word-token splitting that caused `indexOf` to return -1
- [Wizard] Detect `.po` and `.pot` files in `locales/`, `i18n/`, `translations/` folders (step 2)
- [Wizard] Auto-detect Lingui projects using `@lingui/macro` or `@lingui/react/macro` imports (step 1)
- [Wizard] Show warning in step 3 when PO/POT files are found — informs user that the **GNU GetText** plugin is required for full PO support
- [Localization] Wire PO/POT file support for JS/Lingui projects via optional dependency on `org.jetbrains.plugins.localization`; `.po`/`.pot` files are now recognized anywhere in the project tree, not only inside `LC_MESSAGES/`

### CI / Infrastructure
- Bump Gradle wrapper 9.0.0 → 9.4.1
- Bump Kotlin 2.1.20 → 2.3.20 (language + API version updated to 2.3)
- Fix `pluginVerification` DSL: replace removed `ide(type, version)` calls with `recommended()` (IGPP 2.14.0)
- Extend `pluginVerification` to cover IntelliJ 2025.1 and 2025.2 in addition to the latest stable

---

## [1.0.2] - 2026-04-04

### Features
- [Wizard] Add Skip button directly in the wizard navigation bar (alongside Back / Next)
- [Wizard] Persist user dismissal — wizard no longer reappears after Skip or Apply (`wizardDismissed` flag saved in project settings)
- [Wizard] Add `setupWizardEnabled` toggle in Settings panel to disable the wizard globally (mirrors the gutter icons toggle)
- [Plugin] Add `META-INF/pluginIcon.svg` and `META-INF/pluginIcon_dark.svg` — custom icon now visible in JetBrains Marketplace and IDE plugin list

---

## [1.0.1] - 2026-03-30

### Features
- [Settings] `gutterIconsEnabled` toggle — disable gutter badges from the Settings panel
- [Settings] `excludedDirectories` field — comma-separated list of custom directories to exclude from translation scanning
- [Settings] Updated `translationsRoot` label to mention monorepo usage
- [Scan] Respect `.gitignore` and IDE exclusions via `ProjectFileIndex` when scanning translation files

### Bug Fixes
- [Scan] Exclude generated/vendored directories (`node_modules`, `build`, `dist`, etc.) from translation file scanning — fixes 3000+ false positives in tool window and Sync Missing Keys
- [Folding] Sort `FoldingDescriptors` by `startOffset` for deterministic output in CI
- [Threading] `DialogViewModel.loadTranslations` now runs inside `ReadAction.compute {}` — fixes "Read access is allowed from inside read-action only" crash when clicking a key in the Tool Window tree
- [YAML] `YamlContentGenerator.generateContent` no longer produces trailing spaces after intermediate mapping keys (e.g. `component: \n` → `component:\n`) — fixes CI test failures and malformed YAML generation
- [API] Use 3-param `isCheapEnoughToSearch(String, GlobalSearchScope, PsiFile?)` instead of deprecated 4-param variant in `I18NextTechnology` and `TranslationToCodeReferenceProvider`
- [Gutter] Guard empty/malformed key (`t("")`, `t(":")`) before resolving — prevents phantom results
- [Gutter] Relax `isLeaf()` to `element != null` — fixes false "missing" on intermediate JSON nodes
- [Gutter] Use `config.defaultNamespaces()` as fallback before `findAllSources()` when namespace is absent
- [Gutter] Synchronized cache read/write on document to prevent race condition with parallel language providers
- [Gutter] Deduplicate sources by `displayPath` — fixes `fr, fr, en, en` tooltip
- [Annotator] `findNamespaceFiles()` check — "Create translation file" quick fix now appears correctly for missing namespace files
- [Plugin] Rename plugin display names, fix warn dialog modal

### CI / Infrastructure
- Standardize test engine to JUnit 5 and fix EDT dispatch in `PlatformBaseTest`
- Add YAML plugin to test sandbox and patch `php-frontback.jar` for CI compatibility
- Make test execution deterministic and reproducible in CI environment

---

## [1.0.0] - 2026-03-29

### Fork & Modernization
- Forked from [nyavro/i18nPlugin](https://github.com/nyavro/i18nPlugin)
- Migrated to IntelliJ Platform 2024.3+ (Gradle IntelliJ Plugin v2, Kotlin 2.1, Java 21)
- Renamed plugin ID from `com.eny.i18n` to `com.ibrahimdans.i18n`
- Added vue-i18n framework support (`$t`, `$tc`, `$te`) with Vue SFC folding
- Added lingui framework support (`msg`, `i18n._`)
- Namespace extraction from `t(key, {ns: '...'})` options
- Dynamic template literal support with wildcard resolution (fix #205)
- Multi-language translation hints on hover with locale table, missing translations shown as "—", and navigation link (↗) to translation file
- JSX/TSX full support (folding, annotations, completion)
- Fixed StackOverflow on non-i18n template literals
- Fixed AssertionError on startup (#207)

### i18next / react-i18next
- `useTranslation` array form: `const [t] = useTranslation(['ns1', 'ns2'])` — multi-namespace support in hints, folding, and annotations
- `useTranslation` string form: `const [t] = useTranslation('ns')` — namespace resolved via scope walk fallback
- `useTranslation` namespace included in code folding values

### Tool Window
- Stats tab with translation coverage per locale (total keys, translated count, missing count, percentage)
- Real-time search field to filter translation keys
- Keys synchronization action — propagates missing keys across all locales with batch placeholder fill
- **Orphan key detection** — `Usage` column shows usage count per key; right-click to delete unused keys
- **Per-module tabs** — one tab per configured module when 2+ modules are present, each with its own Tree/Table/Stats panels

### Editor Features
- Gutter badges showing i18n key resolution status (resolved / partial / missing)
- "Show Translations Inline" toggle (`Ctrl+Alt+Shift+T`) to display i18n values inline
- Rename refactoring for i18n keys via `Shift+F6` (`RenameI18nKeyHandler`)
- Inlay hints showing resolved translation value inline after each key expression
- Setup wizard guiding through configuration on first launch
- **Wildcard traversal** — intermediate `*` wildcards in composite key resolution (e.g. `a.*.b`)
- **psi_element:// navigation** — hover hint popup links (↗) navigate directly to the PSI element in the translation file

### Quick Fixes & Dialogs
- Create missing key quick fix from unresolved key annotation
- Create translation file quick fix from missing namespace annotation
- **Namespace creation on the fly** — `+` button in Create Translation dialog
- **Namespace filtering** — Edit/Create Translation dialogs filter files by selected namespace

### Bug Fixes & Improvements
- Added i18next v4+ CLDR plural key support (`key_one`, `key_other`, `key_zero`, ...)
- Fixed CLDR plural key resolution in hover hints and Ctrl+Click navigation
- Excluded ternary condition strings from i18n key detection (false positive reduction)
- Replaced deprecated IntelliJ APIs with current equivalents
- Added `translationsRoot` setting for custom translation file root
- Fixed duplicate folding values caused by IntelliJ invoking the folding builder once per language registration — resolved via cross-builder deduplication using host document user data
- Guard `project.isDisposed` before resolving annotations — prevents IDE errors on project close
- Skip unresolved annotations for template-literal-only keys (dynamic keys can't be statically resolved)
- Fix repeated folding for spread arguments in `t()` calls
- Fix `useTranslation('ns')` string form — scope walk fallback correctly resolves namespace when IntelliJ resolves `t` to the TypeScript type declaration
- Fix duplicate hints with `useTranslation(['ns1', 'ns2'])` — deduplication via early return on unresolved keys
- Removed non-functional PlainObject localization from settings UI
