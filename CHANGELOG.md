# Changelog

## Unreleased

### Tests

- [Completion] Fix `NullPointerException` in `CodeCompletionDefNsTestBase.testRootKeyCompletion` (all 8 JS/TS/JSX/TSX x JSON/YAML variants). The fixture declared a single root key matching the typed `tst` prefix, so the platform auto-inserted the only variant and `completeBasic()` returned `null` instead of a lookup list. The fixture now declares two roots sharing the prefix (`tst1`, `tst2`) and the test asserts both are offered. This unblocks the `org.jetbrains.intellij.platform` 2.16.0 to 2.18.1 bump, whose stricter auto-insertion surfaced the latent bug

### Build

- [deps] Bump `org.jetbrains.intellij.platform` from 2.16.0 to 2.18.1. Verified with `runIde` against IDEA Ultimate 2025.3.4: the plugin loads with no classloader conflict against the forced `kotlin-stdlib` 2.4.0
- [deps] Bump Kotlin from 2.3.21 to 2.4.0 — the `org.jetbrains.kotlin.jvm` plugin and the `kotlin-stdlib` pinned through `resolutionStrategy.force` on the plugin and test runtime classpaths are bumped together, so the compiler and the stdlib shipped in the plugin's `lib/` stay on the same version
- [deps] Bump JUnit from 6.1.0 to 6.1.2 (`junit-bom`, `junit-jupiter`, `junit-jupiter-api`, `junit-jupiter-params`, `junit-jupiter-engine`)
- [deps] Bump the Gradle wrapper from 9.5.1 to 9.6.1

## 1.1.0 - 2026-06-03

### New Features

- [Action] Add **Move i18n Key to Namespace** action (`Ctrl+Alt+Shift+M` / editor popup) — moves an i18n key from its current namespace to another namespace atomically: copies the value to the sibling target file in every locale (creating the target file when missing, so no locale is silently dropped), deletes the source entry, and rewrites all code references with an explicit `targetNs:` prefix. The source namespace is derived from the *resolved* translation files, so keys whose namespace comes from a `useTranslation('ns')` or `useTranslation(['a','b'])` hook (no explicit prefix in the literal) are handled too. When the key resolves in several namespaces of a multi-namespace hook, the action first asks which namespace to move from. Code references are only rewritten when the literal actually carries the moved key (bare or `<ns>:`-prefixed), so an unexpectedly broad host element is never overwritten. If the key already exists in the target namespace, a confirmation dialog is shown before overwriting. The entire operation runs under a single `WriteCommandAction` (one undo step)
- [Inspection] Add **Empty translation value** inspection — flags leaf translation entries (JSON/YAML) whose value is empty or blank, closing the loop with `KeysSynchronizer`/CreateMissingKeys, which intentionally insert missing keys with an empty value. Object/mapping-valued keys are skipped. Disabled by default to avoid noise during work-in-progress translation
- [Inspection] Add **Duplicate translation value** inspection — flags leaf entries (JSON/YAML) that share the same non-blank value within a single locale file, helping translators spot copy-paste mistakes and keys that should be merged. File-scoped (same value across locale files is expected and never flagged); blank values are left to the empty-value inspection. Disabled by default
- [Action] Add **Sort i18n Keys Alphabetically** action (editor popup) — reorders the keys of the current JSON translation file alphabetically (case-insensitive), recursively into nested objects. Conservative: each entry keeps its own value text and the file is reformatted afterwards. YAML is out of scope for now (block indentation requires a separate safe-reorder strategy)
- [Settings] Wire up the previously inert **Sort keys alphabetically** setting — when enabled, every key creation in a JSON translation file (extract, quick fix, synchronizer) re-sorts the whole file so the new key lands in order even if the file was not already sorted. Sort logic is shared with the Sort i18n Keys action (`JsonKeySorter`). JSON only for now

### Tests

- [Action] Add `MoveI18nKeyHandlerTest` — exercises the real `execute` write path (single-locale move + source deletion, multi-locale move, missing-target-file creation, collision overwrite, code-reference rewrite for explicit + implicit namespaces) and `resolveKey` resolution (implicit hook namespace, same key grouped across multiple `useTranslation(['a','b'])` namespaces, and null when no key is under the caret) — including a safety-guard test proving an unrelated literal is left untouched
- [Inspection] Add `EmptyTranslationValueInspectionTest` — covers empty/blank JSON strings, empty YAML scalars, non-empty values, and skipped object/mapping containers
- [Inspection] Add `DuplicateTranslationValueInspectionTest` — covers JSON/YAML duplicates (pairs and triples), distinct values, ignored blank values, and cross-nesting duplicates
- [Action] Add `SortI18nKeysActionTest` — top-level and nested sorting, case-insensitive order, already-sorted/single-key no-ops, and value preservation
- [Settings] Add `SortKeysOnInsertTest` — inserting a key with `sortKeysAlphabetically` on re-sorts the whole JSON file; off keeps the append-only behaviour; insertion into an empty object

### Build

- [deps] Bump JUnit from 5.11.4 to 6.1.0 (`junit-bom`, `junit-jupiter`, `junit-jupiter-api`, `junit-jupiter-params`, `junit-jupiter-engine`) — migrate test code to the new APIs: `InvocationInterceptor.Invocation<Void>` becomes nullable `Invocation<Void?>` in `PlatformBaseTest`, and `ArgumentsProvider.provideArguments` now takes the `ParameterDeclarations` parameter introduced in JUnit 6 across all providers in `TestDataProviders`

## 1.0.11 - 2026-05-19

### Bug Fixes

- [Annotator] Stop flagging keys with a trailing dynamic template segment (e.g. ``t(`common:role.${role}`)``) as "Reference to object" — the dynamic part tokenizes to a terminal wildcard that legitimately resolves to its parent object at runtime, so no static error is reported
- [Annotator] Stop flagging keys with a dynamic template segment in the middle (e.g. ``t(`common:role.${role}.label`)``) as "Unresolved key" — once resolution passes through the wildcard, the trailing static segments depend on the runtime value and cannot be verified statically
- [i18next] Honor the explicit `t(key, { ns })` namespace when `t` comes from `useTranslation()` — `ReactUseTranslationHookExtractor` previously only read the (often empty) `useTranslation(...)` arguments and ignored the options namespace, causing keys to resolve against the default namespace and be falsely flagged
- [Setup] Add **Tools > i18n Support Plus > Run Setup Wizard** action — the wizard previously only opened automatically on first launch, leaving no way to reopen it after dismissal or with a partial configuration (matches the documented "Run Setup Wizard" option that did not exist)

### Tests

- [Annotator] Add `CodeHighlightingTestBase.testTrailingDynamicTemplateKeyNoObjectError` — `t(`common:role.${role}`)` with a typed union variable and an existing `role` object must produce no annotation
- [Annotator] Add `CodeHighlightingTestBase.testMidKeyDynamicTemplateNoUnresolvedError` — `t(`common:role.${role}.label`)` must produce no annotation
- [JS] Add `JsFalsePositiveTest.testTWithOptionsNamespace_overridesHookDefault_noError` — `t('user.name', { ns: 'profile' })` with `useTranslation()` resolves via the options namespace

## 1.0.10 - 2026-05-05

### Bug Fixes

- [PHP] Fix false positive annotations on PHP method calls — `$request->get('action')` was mistakenly treated as an i18n key because Angular's `get` function name leaked into PHP detection; `PhpLang` and `PhpReferenceAssistant` now filter out function names containing `$` or `.` (Vue's `$t`/`$tc`/`$te`, i18next's `i18n.t`) and `PhpReferenceAssistant` guards against non-i18n function names in non-gettext mode

### New Features

- [Settings] Add "Excluded file extensions" setting — comma-separated list (e.g. `php,vue`) to suppress all i18n annotations on specific file types

### Tests

- [PHP] Add `testNoFalsePositiveOnPhpMethodCall` — `$request->get('action')` must produce no annotation errors

## 1.0.8 - 2026-04-28

### Bug Fixes

- [JSON/YAML/TS] Fix `NoSuchElementException` in `generate()` when `unresolved` is empty — replace `unresolved.first()` with `unresolved.firstOrNull() ?: return` in `JsonContentGenerator`, `YamlContentGenerator`, and `TsContentGenerator` (TASK-BUG-B, TASK-BUG-I)
- [Sources] Fix NPE on `containingDirectory` in `LocalizationSourceService` — guard both `findAllSourcesByFileType` and `findSourcesByFileType` with `file.containingDirectory ?: return@let null` before accessing `.name` and `.virtualFile.path` (TASK-BUG-C)
- [Actions] Fix potential `NoSuchElementException` in `KeyCreator` when `defaultNamespaces()` is empty — replace `.first()` with `.firstOrNull() ?: "common"` (TASK-BUG-J)
- [JSX] Fix NPE and `NoSuchElementException` in `JsxTranslationExtractor.text()` and `textRange()` — replace `!!` with safe calls, add guard for empty `textElements` (TASK-BUG-K)
- [JS] Fix `StringLiteralKeyExtractor.canExtract` over-matching — replace `.contains("String")` with strict `== "JS:STRING_LITERAL"` check to avoid false positives on non-literal PSI nodes (TASK-BUG-G)
- [JS] Fix false positives on qualified method calls (`toast.t()`, `router.get()`) — add qualifier guard in `JsLang`, `JsFoldingProvider`, `JsReferenceAssistant`, `JsTranslationExtractor`, `JsxTranslationExtractor`; calls with unknown qualifier are rejected unless the full expression matches a configured name (TASK-BUG-H)
- [i18next] Add `"i18n.t"` to `I18NextTechnology.translationFunctionNames()` — aligns with `LinguiTechnology` pattern (`"i18n._"`) and ensures `i18n.t(key)` is correctly recognized by the qualifier guard (TASK-BUG-H)
- [Parser] Fix `WaitingLiteral.fullKey()` injecting a spurious empty `Literal("", 0)` on trailing-separator keys (`"menu."`, `"common:menu."`) — return `null` instead so `KeyParser.parse()` correctly rejects malformed keys (TASK-BUG-A)
- [Synchronizer] Fix `buildFullKey` producing `[Literal("")]` for namespace-only keys (`"common:"`) — guard `keyPath.isBlank()` and filter empty segments from `split('.')` (TASK-BUG-E)
- [Utils] Refactor `foldWhileAccum` — replace `acc!!` + `if (acc == null) break` with a non-nullable `acc: A` and `?: return null` early exit; makes the invariant explicit and eliminates the unsafe assertion (TASK-BUG-L)
- [JS] Fix false positive on fully dynamic key `t(variable)` — add `JSLiteralExpression + isQuotedLiteral` guard in `ReactUseTranslationHookExtractor.canExtract` so variable references are rejected before extraction (TASK-BUG-F)
- [PHP] Fix regression: PHP string references no longer resolved after `StringLiteralKeyExtractor` was tightened to `== "JS:STRING_LITERAL"` — `PhpReferenceAssistant` now extracts the key text directly instead of delegating to a JS-typed extractor (TASK-BUG-PHP)
- [JS] Fix hint not shown when namespace comes from `useTranslation('ns')` or `useTranslation(['ns'])` — `ReactUseTranslationHookExtractor.canExtract` now accepts both the `JSLiteralExpression` node and its direct leaf token (returned by `findElementAt`), so the hook resolution works correctly in `HintProvider` (TASK-BUG-HINT)

### Tests

- [JSON] Add `JsonContentGeneratorTest` — `generate()` with `unresolved = emptyList()` must not throw (TASK-BUG-I)
- [YAML] Add `YamlContentGeneratorTest` — `generate()` with `unresolved = emptyList()` must not throw (TASK-BUG-I)
- [TS] Add `TsContentGeneratorTest` — `generate()` with `unresolved = emptyList()` must not throw (TASK-BUG-B)
- [JSX] Add `JsxTranslationExtractorTest` — `text()`/`textRange()` with no parent XmlTag and with empty nested tag must not throw (TASK-BUG-K)
- [Actions] Add `KeyCreatorTest` — `createKey()` with no ns in key must not throw (TASK-BUG-J)
- [JS] Add `JsFalsePositiveTest` — `toast.t()` / `router.get()` produce no annotations; `t()` and `i18n.t()` are correctly recognized (TASK-BUG-G, TASK-BUG-H)
- [Parser] Add `parseTrailingKeySeparator` / `parseTrailingKeySeparatorWithNamespace` in `InvalidExpressionTest`; update `ExpressionKeyParserTest` trailing-dot cases to expect `null` (TASK-BUG-A)
- [Synchronizer] Add `KeysSynchronizerTest` — `buildFullKey("common:")` produces empty compositeKey, `buildFullKey("")` produces empty compositeKey (TASK-BUG-E)
- [JS] Add `testTWithDynamicVariable_noAnnotation` in `JsFalsePositiveTest` — `t(k)` where `k` is a `JSReferenceExpression` produces no annotation (TASK-BUG-F)
- [PHP] Covered by existing `ReferenceTestPhp` suite — all 32 PHP reference test cases now pass after `PhpReferenceAssistant` fix (TASK-BUG-PHP)
- [JS/Hint] Covered by existing `HintTest` — `testHintWithUseTranslationNamespace`, `testHintWithUseTranslationArrayNamespace`, `testMultiNamespaceHintNoEmptyRows` now pass after leaf-token fix (TASK-BUG-HINT)

## 1.0.7 - 2026-04-23

### Features

- [Stats] Missing keys popup in Translation Stats panel — clicking a locale row opens a resizable list of missing keys; each key navigates to its exact position in the reference locale file (TASK-P)
- [GNU GetText] Re-enable `PlainObjectLocalization` — `org.jetbrains.plugins.localization:253.28294.218` now available on Marketplace; extension uncommented in `plainObjectConfig.xml` (BLOCKED/GNU)
- [GNU GetText] Re-enable PHP GetText tests (`PhpGettextHighlightingTest`, `ReferenceTestPhpGettext`) — `@Ignore` and `ignoredTest*` naming removed now that the plugin is loadable in test sandbox (BLOCKED/GNU)

### Bug Fixes

- [YAML] Fix NPE in `YamlElementTree.findChildren()` — replace double `!!` on `YAMLKeyValue.key` with a safe `mapNotNull` + `takeIf` chain (#56)
- [Build] Fix test sandbox for IntelliJ 2025.3.3 — flatten Vue plugin `lib/modules/*.jar` into `lib/` so `getPluginDistDirByClass()` works with the new modular structure; prevents `IllegalStateException` that was failing all tests (#57)
- [Tests] Extend `PlatformBaseTest` error suppressor coverage to test body execution (via `EdtTestInterceptor`) and add Vue LSP conditions as belt-and-suspenders (#57)

### Tests

- [PO] Add `PlainObjectContentGeneratorTest` and `PoTranslationGeneratorTest` — unit tests for PO content generation (TASK-K, TASK-B)
- [Inspections] Add `IcuFormatInspectionTest` — covers valid plural/select, missing `other` form, missing `one`/`zero` form, unbalanced braces, JSON and YAML (#56)
- [Inspections] Add `PlaceholderConsistencyInspectionTest` — covers same placeholders (no warn), missing placeholder, unbalanced brace, absent reference file, JSON and YAML (#56)
- [Inspections] Add `UnusedTranslationKeyInspectionTest` — covers leaf string flagging, object/mapping properties skipped, JSON and YAML (#56)

### Refactoring

- [Tests] Replace 6 `TODO` stubs in `testLocalization()` with neutral returns (`emptyList()`, `null`, `false`, minimal anonymous objects) — prevents runtime `NotImplementedError` if future tests invoke those paths (TASK-V)
- [Build] Upgrade target platform to IntelliJ IDEA 2025.3.3 (build 253); update PHP plugin to `253.31033.19`; add build 2025.3 to verification matrix
- [JS] Replace `JavascriptLanguage.INSTANCE` with `Language.findLanguageByID("JavaScript")` in `JsTranslationExtractor` and `JsxTranslationExtractor` — avoids compile-time binding to the JS plugin class (BLOCKED/GNU)

## 1.0.6 - 2026-04-17

### Features

- [React-Intl] Add `ReactIntlTechnology` + `ReactIntlExtractor` — key extraction from `intl.formatMessage({ id })` and `<FormattedMessage id="..." />` (TASK-M)
- [NgxTranslate] Add `NgxTranslateTechnology` + `NgxTranslateExtractor` + `NgxTranslatePipeExtractor` — key extraction from `translate.instant()`, `translate.get()`, and `| translate` pipe (TASK-N)
- [Svelte i18n] Add `SvelteI18nTechnology` + `SvelteI18nExtractor` — key extraction from `$_('key')` and `_('key')` (TASK-O)
- [Inspections] Add `PlaceholderConsistencyInspection` — warns when `{placeholder}` present in reference locale is missing in another locale (TASK-R)
- [Inspections] Add `IcuFormatInspection` — validates ICU plural/select blocks: balanced braces, required `other` form, at least `one` or `zero` for plurals (TASK-R)
- [Inspections] Add `UnusedTranslationKeyInspection` — marks translation keys with no code references; quick fix deletes the key (disabled by default) (TASK-S)
- [Actions] Add `BatchExtractI18nAction` (Ctrl+Alt+Shift+B) — scans current JS/TS file for unextracted strings, shows checkbox dialog, creates all selected keys in batch (TASK-T)

### Bug Fixes

- [PO] Guard null `nextSibling` in `PlainObjectTextTree.value()` — prevents NPE when navigating invalid PO nodes (#37)
- [YAML] Fix key creation in empty YAML files — `YamlElementTree.create()` now accepts `YAMLDocument` as valid tree root when no `YAMLMapping` exists (#38, #39)
- [YAML] Guard nullable `YAMLKeyValue.key` in `YamlLocalization` — replace `!!` with safe calls in `parents()` and `textRange()` to avoid crash on malformed YAML (#44)
- [Resolver] Prevent `NoSuchElementException` in `CompositeKeyResolver.resolveCompositeKey()` — use `firstOrNull()` with safe fallback instead of `first()` on empty list (#40, #43)
- [Annotator] Guard empty references list in `CompositeKeyAnnotatorBase` — replace `maxByOrNull()!!` crash with safe guard when all keys are unresolved (#41)
- [PO] Implement `PoTranslationGenerator.generate()` and `generateNamedBlock()` — flat PO format generation now complete (#42)
- [PO] Implement `PlainObjectContentGenerator` — `generateContent()`, `generateTranslationEntry()`, and `isSuitable()` now produce valid `msgid`/`msgstr` entries via `PsiDocumentManager`; replaces no-op stubs (TASK-K, #46, #50)

### Tests

- Activate `AsteriskKeyParserTest`, `InvalidExpressionTest`, `DefaultNsParserTest` — all three passed without code fixes needed (#45)
- [YAML] Add `YamlEdgeCasesTest` — covers null key value, block scalars (`|`/`>`), special-char keys (`@`, `$`, `.`, `#`), and inline comments (TASK-L, #49)

### Refactoring

- [TASK-F] Rename `ExpressionParserTest` → `ExpressionNormalizerTest` to reflect the tested class (`ExpressionNormalizer`)
- [TASK-F] Replace `"TODO-${fullKey.source}"` placeholder with `fullKey.source` as default in `ContentGenerator.generateContent()`

## 1.0.5 - 2026-04-15

### Bug Fixes

- [API] Replace deprecated `ReadAction.compute<T, Throwable>` with `runReadAction {}` in `DialogViewModel`, `LocalizationSourceService`, `TranslationDataLoader`, `TranslationStatsPanel`, and `HintProvider` — fixes deprecation warnings in IU-261+
- [API] Replace deprecated `DaemonCodeAnalyzer.restart()` (no-arg) with targeted `restart(PsiFile)` over all open editors in `ToggleFoldingAction`
- [Inlay] Migrate `I18nInlayHintsProvider` from experimental `InlayHintsProvider<NoSettings>` to stable declarative `InlayHintsProvider` + `SharedBypassCollector` + `HintFormat` API — reduces experimental API usages from ~90 to &lt;10 across all verified IDEs (IU-243..261)
- [Plugin.xml] Switch inlay hints registration from `codeInsight.inlayProvider` to `codeInsight.declarativeInlayHintsProvider`

## 1.0.4 - 2026-04-15

### Bug Fixes

- [JS] Replace `ES6Property` (scheduled for removal) with `JSComputedPropertyNameOwner` in `JsReferenceAssistant` — eliminates the "scheduled for removal API" warning reported by the Plugin Verifier

### CI / Infrastructure

- Remove plugin signing step from release workflow — signing secrets are no longer required

## 1.0.3 - 2026-04-14

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

## 1.0.2 - 2026-04-04

### Features

- [Wizard] Add Skip button directly in the wizard navigation bar (alongside Back / Next)
- [Wizard] Persist user dismissal — wizard no longer reappears after Skip or Apply (`wizardDismissed` flag saved in project settings)
- [Wizard] Add `setupWizardEnabled` toggle in Settings panel to disable the wizard globally (mirrors the gutter icons toggle)
- [Plugin] Add `META-INF/pluginIcon.svg` and `META-INF/pluginIcon_dark.svg` — custom icon now visible in JetBrains Marketplace and IDE plugin list

## 1.0.1 - 2026-03-30

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

## 1.0.0 - 2026-03-29

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
