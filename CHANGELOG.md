# Changelog

## Unreleased

### New Features

- [Display] Render **ICU MessageFormat** values instead of showing their source. react-intl / FormatJS pluralizes inside the value rather than through sub-keys, so folding replaced `t('cart.items')` with `{count, plural, one {# arti…` — strictly less readable than the key it hid, which emptied folding, hints and inlay hints of their purpose on every react-intl project. A representative branch is now displayed (`other`, falling back to the first one declared) with `#` replaced by its argument, so the same key reads `{count} articles`. `plural`, `select` and `selectordinal` are covered; this is a display layer, not an ICU engine — no argument value exists at that point, resolution and annotation are untouched, and a malformed message falls back to its raw value rather than throwing. Rendering happens before truncation, which used to cut in the middle of a brace, and a message without a single `{` — every i18next project — is returned unchanged without any scanning
- [Setup Wizard] Detect **react-intl / FormatJS** in `package.json` (`react-intl`, `@formatjs/intl`) and offer it alongside i18next, vue-i18n and lingui. The framework has been supported by the plugin for several releases but was invisible in the wizard, so a react-intl project looked unsupported on first launch. Matching is textual on the quoted dependency name, so `react-intl-universal` — a different library — is not mistaken for it
- [React-Intl] Extract the keys declared in a `defineMessages({ … })` / `defineMessage({ … })` catalogue. A react-intl project usually declares its messages apart from where they are used, then references them (`intl.formatMessage(messages.greeting)`); `ReactIntlExtractor` only ever saw the descriptor passed **inline** to `formatMessage`, so a project following the idiomatic layout had none of its ids annotated, completed or navigable. Both catalogue shapes are recognised — the descriptor as the call argument itself, and the usual one level below it (`{ greeting: { id } }`) — and only `id` is claimed: `defaultMessage` and `description` hold source text and are vetoed against the generic string-literal extractors, catalogue included. The indirect use site is deliberately left alone: resolving `messages.greeting` back to its declaration is a PSI reference resolution and a separate concern
- [i18n-js] Recognise **i18n-js**, the dominant i18n library of the React Native / Expo ecosystem, as a technology of its own. No technology declared it, and that such projects worked at all was an accident: `t` happens to be published by i18next, so an i18n-js project silently depended on a framework it does not use and would have broken had i18next been narrowed or disabled. `I18nJsTechnology` declares `t` and `i18n.t` — the qualified form has to be listed explicitly, since a qualified call whose full text is not a declared name is rejected, which is what keeps `toast.t('…')` out. Source discovery is left to `TsCatalogTechnology`, which already reads the locale-keyed TypeScript catalogues these projects use
- [Settings] Add **Treat keys as flat** (`flatKeys`, off by default) — resolves a key as a single property instead of splitting it on the key separator. react-intl / FormatJS projects store flat ids (`"app.header.title"` is one JSON property, not three nested levels), which the nested resolution could never match, making every key in such a project unresolvable. The setting reuses the parsing path gettext already relied on (`KeyParserBuilder.withoutTokenizer()` plus `emptyNamespace`), now shared through `Config.usesFlatKeys()`: namespace parsing is off too, so a `:` is just part of the key and translation files are located through the default namespace. Applied consistently to annotations, key extraction (single and batch) and the JS/JSX/PHP reference assistants. Existing projects are untouched — nested resolution stays the default
- [Frameworks] Recognise the idiomatic syntaxes of react-intl, ngx-translate and svelte-i18n. Their key extractors have existed since #52 but were never referenced by `JsLang` or `JsxLang`, so only the plain `fn('key')` form resolved through the generic extraction — `formatMessage({ id })`, `<FormattedMessage id>`, `translate.instant('key')` and the `| translate` pipe silently resolved to nothing. Extractors that own their syntax are now consulted before the `translationFunctionNames` filtering, which is what a descriptor object or a qualified call needs: the generic path matches only bare first arguments and rejects qualified calls by design. The ngx-translate pipe is reachable inside JSX/TSX only — the plugin registers no annotator for standalone Angular templates

### Refactoring

- [Setup Wizard] Move framework detection out of `SetupWizardDialog` into `FrameworkDetector`, a UI-free object holding the dependency table, the checkbox labels and the matching itself. `SetupWizardDialogTest` could not instantiate the dialog headlessly and so carried its **own copy** of the dependency table: the tests passed on the copy and would have kept passing had the real table been broken. They now exercise the shipped code, and the wizard builds its checkboxes from the same table instead of a hand-maintained second list
- [Setup Wizard] Move the translation file scan out of `SetupWizardDialog` into `TranslationFileScanner`, and the root deduction into `TranslationRootDetector`, both UI-free. `SetupWizardDialogTest` carried its **own copy** of the folder and extension tables — the same trap #155 removed from framework detection: the tests exercised the copy and would have kept passing had the shipped lists been broken. They now run the shipped scan

### Bug Fixes

- [Display] Handle ICU **apostrophe quoting** and single-value formats in the message renderer. `'{'` and `'}'` are literal braces in ICU, but the renderer counted them as real ones, so a message mixing a quoted brace with a plural fell back to its raw ICU source — the exact case the renderer exists to avoid. Quoting now follows ICU's default rules: `''` is one apostrophe, a lone `'` quotes only before `{`, `}` or `#`, and stays a plain apostrophe everywhere else, which leaves French values such as `L'article` untouched. A quoted `'#'` inside a plural branch also stays literal instead of being substituted. **Behaviour change**: `{amount, number, currency}`, `{day, date, short}` and `{hour, time, short}` now display as `{amount}`, `{day}` and `{hour}` rather than showing the whole format declaration — no value exists at display time, so the format was noise
- [Hints] Escape translation values before they reach the Ctrl+hover popup. The popup is assembled as raw HTML and interpolated the value straight into a `<td>`, so any translation carrying markup (`Voir <b>les CGU</b>`), an entity (`&nbsp;`) or a comparison (`a < b`) was parsed as markup instead of being shown: text swallowed, styles leaking into the following rows. The locale label and the file path behind the navigation arrow went through the same path, and the path sits in a single-quoted attribute where one apostrophe was enough to close it. All three are now escaped with the platform's `StringUtil.escapeXmlEntities`. Escaping is applied last, after ICU rendering and truncation, so the 60-character budget still counts source characters rather than entities — and a translation without a special character comes out byte-for-byte as before
- [ngx-translate] Fix `NgxTranslateExtractor` reading the literal's direct parent as the call expression: an argument's parent is the argument list, so the check never passed and `translate.instant('key')` was never extracted. The bug had gone unnoticed because the extractor was not wired to anything
- [React-Intl] Fix the very same argument-list bug in `ReactIntlExtractor`, which #151 corrected for ngx-translate but not here: it walked `objectLiteral.parent` expecting the call expression, so `canExtract` never returned true and wiring the extractor changed nothing — `formatMessage({ id })` still resolved to nothing. The call is now located with `PsiTreeUtil`, and matching on the `formatMessage` suffix covers every calling style: bare `formatMessage(…)` from `useIntl()`, `intl.formatMessage(…)`, and `this.props.intl.formatMessage(…)` from `injectIntl`
- [React-Intl] Never treat `defaultMessage` as a translation key. Inside a message descriptor only `id` is one; the other properties hold source text. Claiming `id` through the syntax-owned extractors is not enough — the descriptor's remaining literals still reach the generic path, which matches any string of a first-argument object — so they are now explicitly vetoed
- [React-Intl] Drop `t` from `ReactIntlTechnology.translationFunctionNames()` — react-intl has no such function. It only duplicated the name already published by `I18NextTechnology` (all technologies are active at once), widening the false-positive surface for every project. No behaviour change for i18next users
- [TS catalogs] Discover a catalogue declared as a plain TypeScript/JavaScript object keyed by locale (`export const translations = { fr: {…}, en: {…} }`), the norm in React Native / Expo projects. No code path read that shape: `TsLocalization` declares no file type, so the generic scan never opens a `.ts` file, and the only reader of a TS catalogue is hardwired to the i18next shape (a `resources` property holding `translation`). Nothing was found, so **every** key of such a project was flagged as having no translation file at all — annotator, completion, folding, hints, inlay hints, references and the tool window were all dead. `TsCatalogTechnology` now contributes one source per locale, rooted on that locale's object so `t('dashboard.title')` resolves against `translations.fr.dashboard.title`. An object counts as a catalogue only when **all** its top-level keys are locale codes, validated by the shared ISO-backed rule rather than by shape — two-letter codes collide with plenty of English words (`is`, `no`, `to`, `it`) — and the i18next shape is left to its owner so nothing is discovered twice. Candidates are pre-filtered on the conventional folders and file names, since opening every `.ts` file of a project on each resolution is not an option; a catalogue kept elsewhere is reachable through "Translations root directory". The technology contributes sources only — translation function names stay the business of the framework technologies
- [Resolution] Resolve keys that carry no namespace against a "one file per locale" project (`locales/fr.json` + `locales/en.json`). Such a key requests no namespace, so `findSources` fell back to the default one — `translation` — which the localizations match against the *file name*: a project owning no `translation.json` resolved nothing at all and every one of its keys was reported as having no translation file, across the annotator, completion, folding, hints, inlay hints, references and quickfixes. `findAllSources` had always read that layout correctly through its locale heuristic; the fallback simply was not wired into `findSources`, and `I18nGutterIconProvider` was the single consumer that had worked around it locally — gutter icons worked where everything else failed. The fallback now lives in `findSources` itself, and stays confined to keys that requested no namespace: an explicit namespace matching no file (`t('common:user.name')` without `common.json`) keeps being reported as an unresolved namespace instead of quietly resolving against unrelated files. A project owning the default namespace is unaffected — the file named after it still wins. Note that creating a key in such a project now offers the locale files that exist rather than proposing to create a `translation.json` next to them
- [Setup Wizard] Write the translations root on a **flat layout**. The wizard derived it by reading the grand-parent of every file it found, which assumes a fixed `locales/<locale>/<ns>.json` depth: on `locales/fr.json` that grand-parent is null, every candidate was dropped and **nothing at all was written** — right after the summary announced "2 file(s) found" and offered an *Apply* button, so the user believed the plugin was configured. The root is now derived by stripping the locale levels below it and taking the longest common prefix of what remains, so `locales/fr.json`, `locales/en/common.json` and the GetText `locales/fr/LC_MESSAGES/messages.po` all land on `locales`, and `src/locales/en/ns.json` on `src/locales`. Locale segments are recognised through the shared ISO-backed rule, so a folder named `lang` or `intl` is never mistaken for one. When no common root can be derived, the summary now says so instead of promising a configuration it will not write, and the root it will store is displayed before *Apply*
- [Setup Wizard] Offer the frameworks shipped since #151 — **ngx-translate** (`@ngx-translate/core`), **svelte-i18n** (`svelte-i18n`) and **i18n-js** (`i18n-js`), the React Native / Expo default. They were supported by the plugin but had no checkbox, so those projects looked unsupported on first launch. `locale`, `lang` and `langs` join the scanned folder names as well: `locale/` is the GNU GetText and Django convention, `lang/` the Laravel and Nuxt one, and all of them were already recognised as catalogue folders elsewhere in the plugin
- [Plurals] Stop reporting a nested plural object as "Reference to object". i18n-js, ruby-i18n and vue-i18n pluralize into `{ one: '1 boîte', other: '%{count} boîtes' }`, where i18next uses the flat `key_one` suffixes the resolver expands — so `t('common.box')` resolved onto the object and every plural key of those frameworks was flagged. A node whose children are *all* CLDR plural categories (`zero`, `one`, `two`, `few`, `many`, `other`) is now treated as resolved. Requiring **every** child to be a category is what keeps ordinary objects out: a namespace holding a key named `other` stays a namespace as long as it holds anything else
- [Gutter] Align the gutter icons with the resolution rule. This provider was the only consumer that had worked around `findSources` finding nothing for a key without a namespace: it substituted the default namespaces, then fell back to the whole project scan — which is why gutter icons worked on a "one file per locale" project while everything else reported the key unresolved. #159 moved that fallback into `findSources`, confined to keys that request no namespace, and the local one then became *wider* than the shared rule rather than redundant: it fired for an explicit namespace matching no file too. On `t('common:user.name')` with no `common.json`, the gutter showed per-locale statuses computed against unrelated files while the annotator reported an unresolved namespace — two verdicts on one key. The provider now passes the key's namespaces through untouched and leaves the fallback to the service
- [Display] Show the value of a **nested plural key** instead of nothing. Since #162 a key resolving onto `{ one: '1 boîte', other: '%{count} boîtes' }` — how i18n-js, ruby-i18n and vue-i18n pluralize — is annotated as resolved rather than reported as a "Reference to object". But folding, hints and inlay hints each required a leaf, so such a key was declared resolved while displaying nothing at all: the user saw the plugin recognise the key and never show its value. The three now go through a single entry point that returns the branch standing for the group — `other`, falling back to the first category declared, the same choice ICU rendering makes, so both pluralization styles read alike. A value is shown, never a sub-tree: a branch that is itself an object displays nothing, as does an ordinary object. Leaf keys are untouched, and ICU rendering and truncation still apply after the branch is picked
- [Setup Wizard] Apply the settings the wizard deduced, not just the translations root. *Apply* wrote `translationsRoot` and nothing else, while the scan already implied several other settings — and the framework checkboxes fed **nothing but a sentence** in the summary: ticking "react-intl", reading that it was detected and clicking *Apply* left the project exactly as unresolvable as before, until the user found `flatKeys` on their own. `WizardSettingsDeducer` now infers the default namespace from the file names (a "one file per locale" project names them after locales, so nothing is inferred there), turns GetText mode on when `.po` / `.pot` files are found, sets **Treat keys as flat** when react-intl is ticked — the setting #154 added for exactly those projects — and picks the preferred format from the majority extension. Ties are broken on convention then alphabetically, never on the order the file system returned. Nothing is ever overwritten: each field is written only while it still holds its default, because the wizard opens on first launch but is not guaranteed to write first. The summary lists the settings that will actually be stored, so the checkboxes finally mean something
- [Tool Window] Stop reading a source folder as a locale. The ISO-backed locale rule #122 introduced lived in `LocalizationSourceService`, but three consumers — the gutter icon tooltip, the Ctrl+hover popup and the tool window's data loader — each kept a **private copy** of the shape-only regex it replaced, and so never received the correction. The first two only mislabel a tooltip; the loader does not: it read the parent directory as the locale whenever it merely looked like one, so with a translations root covering a source tree, `src/api/common.json` was loaded under the locale `api` — the exact symptom #122 was reported for, still alive in the tool window because the loader had its own rule. The three copies are gone and all three call the shared one. The order they try the file name and its parent in was also inconsistent (the gutter and the hint tried the stem first, the loader the parent first); it is now stem, then parent, then the stem again as the fallback, so a file under a folder that names no language is labelled by its own name rather than by that folder. Projects using real codes (`en/`, `fr/`, `de.json`) are unaffected — this narrows the rule, it does not widen it
- [Tool Window] Stop indexing every key twice on a "one file per locale" project. The namespace was taken to be the file stem, unconditionally: on `locales/en.json` + `locales/fr.json` that made `en` and `fr` two namespaces, neither of them the default one, so the same key was prefixed once per locale — `en:menu.home` holding the English value, `fr:menu.home` the French one, and each missing from the other locale. Every consumer of that data was wrong in the same way: keys listed in double in the Tree and Table views with half-empty rows, roughly 50% of translations reported missing by the stats on a fully translated project, the doubling carried into the CSV export and back in through the import, and *Sync Keys* offering to create the `en:*` keys inside `fr.json` (the mandatory preview kept it from writing silently, but the proposal was wrong). A file named after its locale now contributes no namespace at all, so its keys carry no prefix, and the decision is delegated to the same rule that reads the locale off that file — the two cannot drift apart. The multi-namespace layout (`en/common.json`, `en/auth.json`), which is what most projects use, is strictly unchanged

### Tests

- [Tool Window] Cover the two layouts the data loader has to tell apart: `TranslationDataLoaderLayoutTest` loads a real fixture project through `loadAllTranslations` and pins one entry per key with both locales on the flat layout, a genuinely missing translation still reported as missing, and the namespaced layout keeping its prefixes. `LocaleNamingTest` pins the shared locale rule itself — stem before parent, and the fallback to the stem that keeps a source folder from being read as a language. The unit test that used to freeze the doubling as intended behaviour now states what is expected instead
- [Inlay Hints] Cover `I18nInlayHintsProvider`, which had no test at all while folding and hints — sharing its resolution path and its `unQuote()` → `renderIcu()` → `ellipsis()` display pipeline — had eleven suites between them. Every change to that pipeline was verified on two of the three display points and read over on the third. The declarative inlay API offers no fixture helper, so the collector is driven directly through a capturing `InlayTreeSink`: resolved key produces one hint carrying its value, unresolved key and missing translation file produce none, truncation honours `foldingMaxLength`, an ICU message is rendered rather than shown raw, and only the preferred folding language is displayed. The provider itself is unchanged
- [React-Intl] Cover react-intl end to end, where nothing existed: `ReactIntlExtractorTest` pins `canExtract` (qualified call, bare call, `defaultMessage` rejection), `ReactIntlLangWiringTest` walks the whole `Lang` pipeline through `RawKeyParser`, and `ReactIntlHighlightingTest` checks the annotations for both the descriptor and `<FormattedMessage>` forms. The absence of any such test is why the argument-list bug shipped twice
- [React-Intl] Pin the catalogue extraction with `DefineMessagesExtractorTest` (multi-entry `defineMessages`, singular `defineMessage`, `defaultMessage` / `description` never claimed, plain object and unrelated call ignored, plus the `Lang` pipeline and its veto) and four highlighting cases on the annotator itself. The plain-object case is the one that matters: it proves the extractor keys on the call, not on the shape of the object

### Performance

- [Scanning] Cache the project-wide translation scan (`LocalizationSourceService.findAllSources`) on the project, instead of re-querying the file index and rebuilding one element tree per translation file on every call. The annotator, completion, folding, inlay hints, gutter icons and the tool window all go through it, so a project with many locale files paid a full rescan on every highlighting pass. The cached scan is invalidated as soon as the PSI, the project roots or the plugin configuration change (settings are mutated through several paths, so the effective `Config` is hashed rather than instrumented), is held through a `SoftReference` so it never keeps translation files in memory, and is dropped when it holds invalid PSI — a stale scan is never handed out

### Documentation

- [README] Replace the "Translation functions" column of **Supported Frameworks** with the actual syntaxes each framework is recognised by (`formatMessage({ id })`, `<FormattedMessage id>`, `translate.instant()`, the `| translate` pipe, `<Trans i18nKey>`, options-based namespaces), and footnote the two known gaps: the ngx-translate pipe is only recognised inside JSX/TSX, and svelte-i18n only in `.js` / `.ts` — standalone Angular templates and `.svelte` components are not analysed
- [README] Fix the stated minimum IDE version: the Requirements section still promised 2024.3+ (build 243) after 1.2.1 raised `pluginSinceBuild` to 251 — exactly the incompatibility that release fixed. It now states 2025.1+ (build 251–263.*) and the versions the plugin is verified against. The `buildPlugin` output path was wrong too (`i18nSupportPlus-*.zip`): the archive is named after `rootProject.name`, so it is `i18n Support Plus-<version>.zip`

### Build

- [Repository] Delete `org/jetbrains/intellij/platform/gradle/Constants.kt`, a copy of an IntelliJ Platform Gradle Plugin source file committed at the repository root in #20. It sits outside every source set, is imported by nothing and is not referenced by the build — it only made the repository look like it shipped a package it does not own
- [deps] Bump Kotlin from 2.4.0 to 2.4.10 — the `org.jetbrains.kotlin.jvm` plugin and the `kotlin-stdlib` pinned through `resolutionStrategy.force` on the plugin and test runtime classpaths are bumped together, so the compiler and the stdlib shipped in the plugin's `lib/` stay on the same version
- [deps] Bump JUnit from 6.1.2 to 6.1.3 (`junit-bom`, `junit-jupiter`, `junit-jupiter-api`, `junit-jupiter-params`, `junit-jupiter-engine`)
- [deps] Bump the Gradle wrapper from 9.6.1 to 9.7.0, and realign `gradleVersion` in `gradle.properties`, which had stayed behind on 9.4.1. That property is what the `wrapper` task writes into the wrapper, so running `./gradlew wrapper` would have silently downgraded the distribution by three minor versions — undoing the bump that had just been merged. Dependabot only ever edits `gradle-wrapper.properties`, so the two had drifted apart across several bumps and have to be moved together from now on
- [ci] Bump `gradle/actions/setup-gradle` from the floating `v6` tag to the pinned `v6.3.0`, across the five workflows that use it (`ci`, `compatibility`, `coverage`, `prepare-release`, `release`)
- [ci] Bump `actions/labeler` from v6 to v7 and `actions/stale` from v10 to v11

## 1.2.1 - 2026-07-15

### Bug Fixes

- [Startup] Stop opening the Setup Wizard as a modal dialog on project startup: a `postStartupActivity` cannot block the EDT with a modal window. On a fresh IDE the wizard was shown automatically (`needsSetup` is true with no config), which timed out the Marketplace install verification and made it report the plugin as removing the IDE Trial widget. The wizard is now proposed through a non-blocking notification (`Run Setup Wizard` / `Don't show again`); the manual **Tools > i18n Support Plus > Run Setup Wizard** action is unchanged
- [Compatibility] Raise the minimum supported IDE to 2025.1 (`sinceBuild` 243 to 251): the plugin used a platform method absent from 2024.3, so 1.2.0 was declared compatible with 2024.3 but binary-incompatible with it (`Method not found`). 2024.3 is no longer supported

## 1.2.0 - 2026-07-14

### New Features

- [Action] Add **Export Translations to CSV** and **Import Translations from CSV** (Tools > i18n Support Plus) — export writes a `key` column plus one column per locale (RFC 4180 escaping, keys sorted) for handing to a translator; import diffs the CSV against the project and shows a mandatory preview (create/update per key and locale) before writing anything. Guardrails: an empty CSV cell never erases an existing translation, unknown keys are reported and never created (a translator's typo must not mint keys), unknown locale columns are ignored and reported, and all writes run in a single `WriteCommandAction` (one undo step). Values are routed to the right file by namespace and locale, the same way as the Keys Synchronizer
- [Action] Add **Cleanup Unused Keys** (Tools > i18n Support Plus) — scans every translation key for code usages and lists the dead ones in a mandatory preview (checkbox per key, all checked by default) before deleting them from every locale in a single `WriteCommandAction` (one undo for the whole cleanup). Deletion is doubly guarded: a key is only proposed when the text scan finds zero occurrences (full and bare form, same scan as Scan Orphans) **and** no PSI reference points at its property or any ancestor property — the ancestor check protects children of dynamically-accessed parents (`t(`role.${x}`)` resolves to the parent `role` object), so a dynamic key is never proposed. Better to keep a dead key than delete a live one
- [Tool Window] Make the Table View's locale cells **editable in place** — double-clicking a locale cell opens the standard cell editor; committing the edit writes straight to the matching translation file (routed by namespace and locale, one undo step per cell) and creates the entry when the locale doesn't have it yet, via the same `saveTranslation` path as the dialogs. If no translation file matches or the write fails, the previous value is restored and an error dialog is shown — never a silent failure. The key column stays read-only (renaming is `RenameI18nKeyHandler`'s job) and keeps opening the edit dialog on double-click

### UI

- [Tool Window] Improve Table/Stats readability. **Table**: translation values are rendered on a single line (whitespace runs collapsed, truncated at 200 chars with an ellipsis) instead of verbatim with their original newlines and indentation; the full raw value moves to the cell tooltip. The unscanned Usage cells ("—") and the Scan Orphans button now carry tooltips explaining that the scan fills the column. **Stats**: the % cell replaces the full-width color band with a compact proportional progress bar (same green/orange/red thresholds), and rows with missing keys show a hand cursor plus a "Click to list the N missing keys" tooltip, making the drill-down popup discoverable. **Toolbar**: the action toolbar's `targetComponent` now points at the tool window content instead of itself, fixing data-context lookups and tooltip anchoring
- [Settings] Rebuild the Settings panel with the Kotlin UI DSL — settings are now grouped in named sections (Namespaces and separators, Where translations are searched, Folding and preview, Key extraction, PHP / gettext, Inspections, Appearance) with labels and fields on an aligned grid, fixing the floating "Default namespace" field that overlapped the "Enable folding" row. Parenthetical explanations move from labels to comments under the fields, and the three scope labels are shortened accordingly (`Translations root directory`, `Excluded directories`, `Excluded file extensions`). Persistence is untouched: every control still writes to `Settings` immediately and keeps its component name, so `Configurable`'s snapshot diffing and the UI tests keep working. The module/rule tables stay plain Swing (`JTable` + buttons) on purpose: `JBTable`/`ToolbarDecorator` require a running IntelliJ Application, and the panel must remain buildable in plain Swing tests

### Bug Fixes

- [Action] Stop corrupting the source file when moving a key — **Move i18n Key to Namespace** deleted the source entry with a bare `delete()`, leaving the separating comma behind: moving `user.name` out of `{"user":{"first":"John","name":"Doe","last":"Smith"}}` produced `{"user":{"first":"John",,"last":"Smith"}}`. The bug shipped in 1.1.0 and hit essentially every real move, since a translation key almost always has a sibling. It went unnoticed because every fixture in `MoveI18nKeyHandlerTest` held a single key in the source object, so no separator was ever left to dangle
- [i18next] Stop breaking the plugin on configs without an inline `resources` object — `findInitObject` looked the property up with `.first()`, so an i18next setup that loads translations at runtime (i18next-http-backend and friends) threw `NoSuchElementException`. As it runs inside `findSourcesByConfiguration`, which every key resolution goes through (annotator, completion, folding, hints, gutter icons, tool window), the plugin was unusable on those projects. It now returns null and the project simply resolves no config-based sources
- [Tool Window] Scope orphan-key deletion to the current module — the Table View's **Delete orphan key** resolved its targets by namespace across the whole project, so deleting a key from one module's table also removed it from another module's file sharing that namespace
- [Performance] Load the translation sources once per Cleanup run — `CleanupUnusedKeysAction` called `findAllSources` for every key, and that call rebuilds the PSI tree of every translation file. The scan was O(keys x files): a few hundred dead keys over a few dozen locale files meant thousands of full PSI rebuilds and froze the IDE
- [Tool Window] Scope in-place table edits to their module — `TableViewModel.saveValue` resolved its target file with a project-wide source lookup while the rows themselves were loaded per module, so in a monorepo where two modules each own an `en/common.json` (the normal case), editing a cell in one module's table could write into the other module's file, silently and reporting success. The write now resolves through the same module-filtered source list as the read, and refuses (restoring the cell) when the key does not exist in that module instead of falling back to another one
- [CSV] Scope CSV export/import to a single module — a CSV row is keyed by `ns:key` and carries no module column, so a file spanning several modules cannot round-trip: two modules sharing a namespace and locale collapsed onto one row, the export silently keeping a single value and the import writing it back into whichever file resolved first. When two or more modules are configured, both actions now require an explicit module choice up front; projects without module configuration are unaffected
- [Delete] Stop corrupting JSON translation files when deleting a key — a bare `JsonProperty.delete()` leaves the separating comma behind, so removing `a` and `c` from `{"a":"1","b":"2","c":"3"}` produced `{,"b":"2",}`, a broken file where `b` no longer resolves. The comma-aware deletion written for Cleanup Unused Keys is extracted to `PsiUtils.deletePropertyAndSeparator` and now used by the two other deletion paths that carried the same latent bug: the **Delete unused key** quick fix (`UnusedTranslationKeyInspection`) and the Table View's **Delete orphan key** context menu (`OrphanKeyDeleter`). `OrphanKeyDeleter` had a second defect: it deleted the *value* element instead of the property, leaving a dangling `"key":` behind. It now deletes the whole property, and does so for every locale inside a single `WriteCommandAction` (one undo) instead of one command per locale
- [Sources] Stop treating any 2-3 letter directory or file name as a locale when no translations root is configured — the shape-only regex accepted `web`, `ios`, `api`, `src`…, so on monorepos the plugin ingested `package.json` / `.prettierrc` from `web/` or `ios/` as translation files, filling the tool window with bogus keys and reporting directories as locales in the stats. `looksLikeLocale` now validates the language subtag against the ISO codes known to the JDK (639-1, their 639-2 equivalents, and CLDR 3-letter languages such as `fil`) and the region subtag against ISO country codes; 4-letter script subtags (`sr-Latn`) remain accepted. Configured-root behaviour is unchanged
- [Action] Harden `MoveI18nKeyHandler` against an empty namespace map — the single-namespace branch called `leavesByNamespace.keys.first()`, whose non-emptiness is only guaranteed by a guard in `resolveKey`, ninety lines away. The call is now `firstOrNull() ?: return` (fail closed, same pattern as the `KeyCreator` fix in 1.0.8), so a future regression in `resolveKey` degrades to a no-op instead of a `NoSuchElementException` in the middle of the action

### Documentation

- [README] Document the three registered technologies missing from the "Supported Frameworks" table: react-intl (`formatMessage()`, `t()`), ngx-translate (`instant()`, `get()`, `stream()`) and svelte-i18n (`_()`, `$_()`). All three were already implemented, registered in `plugin.xml` and shipped, but invisible on the Marketplace page. Function lists taken from the `Technology` implementations, not invented

### Tests

- [Action] Add three sibling-key tests to `MoveI18nKeyHandlerTest` — moving the first, middle and last key of an object must leave no dangling separator. They assert on the raw file text on purpose: the JSON PSI is error-tolerant and still resolves the siblings of a `{"a":"1",,"c":"3"}` object, so asserting on resolved values alone passed on a corrupted file (verified: all three fail against the old `delete()`)
- [i18next] Add `I18NextTechnologyTest` — a backend-loading config with no inline `resources` returns null instead of throwing; a config with `resources` still resolves its init object
- [Tool Window] Add two module-scoping tests to `TableViewModelSaveValueTest` — with two modules owning the same namespace and locale, a scoped write lands in the right module and leaves the other file untouched; a key absent from the scoped module is refused rather than falling back to another module's file
- [Tool Window] Add `OrphanKeyDeleterTest` — the whole property is deleted (no dangling `"key":`), first/last property deletion leaves valid JSON, and the key is removed from every locale while siblings survive
- [Inspection] Add three quick-fix tests to `UnusedTranslationKeyInspectionTest` — deleting the first, middle and last property of a JSON object leaves no orphan comma (whitespace-insensitive comparison: the intention infrastructure reformats the modified range)
- [Action] Add `CleanupUnusedKeysActionTest` — leaf resolution across locales (one property per locale, missing locales skipped), multi-locale and multi-key deletion in one pass with sibling keys untouched, and the no-reference detection on a key never used in code
- [Tool Window] Add `TableViewModelSaveValueTest` — in-place edit write path against real PSI: update of an existing value, creation of a missing entry in a locale without touching the others, and refusal (no write) when no file matches the locale or the namespace
- [Utils] Add `CsvTranslationCodecTest` — export/parse round-trip without loss, comma/quote/newline escaping, Unix line endings, malformed-input rejection, and the import-plan rules (updates vs creations, unknown keys and locale columns ignored and reported, empty cells never erase, identical values are no-ops, header validation)
- [Settings] Repair `SettingsPanelTest` in display environments (CI always skips it as headless, so the breakage was invisible there): pre-fill `Settings.preferredLocalization` so building the panel no longer resolves the LOCALIZATION extension point outside a platform fixture, and `pack()` the test frame so Marathon clicks land on real component bounds
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
