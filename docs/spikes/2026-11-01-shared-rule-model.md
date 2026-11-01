# Spike: one description of each framework for the plugin and `i18n-check` (TASK-SHARED-RULE-MODEL)

2026-11-01 — code in `core/src/main/kotlin/com/ibrahimdans/i18n/plugin/rules/`.

## Question

Every framework was written twice: 24 extractor files on the JavaScript PSI in
`extensions/lang/js/extractors/`, 14 on tree-sitter in `core-treesitter/`. `i18n-check --code` ignored
react-intl, Lingui, Svelte and PHP because each one had to be rewritten on tree-sitter
(TASK-CLI-CODE-FRAMEWORKS, -TRANSLOCO showed the cost). What can a declarative rule say, so that a
framework is written once and both pipelines read it, and what must stay code?

## What a rule says

`FrameworkRules` (one per framework, in `FrameworkPresets`) holds four kinds of rule:

| Rule | Says | Examples |
| --- | --- | --- |
| `CallRule` | a function taking a key: its callee (`EXACT` or `SUFFIX` match), where the key sits (`Argument(i)`, `Descriptor(property, i)`, `Catalogue(property)`), the options object's `ns` and `context` properties, the packages the file must import | `t('k', { ns, context })`, `intl.formatMessage({ id })`, `defineMessages({ a: { id } })`, `msg('k')` once `@lingui/` is imported |
| `HookRule` | what binds a component's `t` and what its argument means (`NAMESPACES` or `KEY_PREFIX`), its `keyPrefix` option, the property holding the namespace, the methods of the bound `t` | `useTranslation(ns, { keyPrefix })`, `useTranslations('Home')`, `getTranslations({ namespace })`, `t.rich` |
| `ComponentRule` | a JSX tag whose attribute holds a key, with the attribute naming the context | `<FormattedMessage id>`, `<Trans i18nKey context>` on any tag |
| `PipeRule` | a template pipe taking the key | `'k' \| translate`, `'k' \| transloco` |

`RuleKeyExtractor` reads the `CallRule`s and `ComponentRule`s of a `FrameworkRules` through a
`CodeGrammar`: the twelve questions an extractor asks of a tree (is this a string literal, which
call takes it as which argument, which object property holds it, what the file imports, which JSX
attribute it is…). Two grammars answer them: `TsxGrammar` (core-treesitter, by node type and
position) and `PsiJsGrammar` (plugin, off the JavaScript PSI). One extractor, two parsers.

The `HookRule`s and `PipeRule`s are read by the hook and pipe extractors each side already had:
they take their names from the presets (`useTranslation`, the next-intl hooks and methods, `$t`,
`$tc`, `$te`, the i18next functions) and keep their logic — resolving the `t` of a scope is not a
rule, it is a walk.

## What stays code, and why

| Syntax | Why no rule says it | Where |
| --- | --- | --- |
| Lingui `<Trans>Hello {name}!</Trans>` | the key is the macro's msgid, built from the whole content: identifiers keep their name, other expressions are numbered, nested tags indexed, JSX whitespace folded | `LinguiTransKeyExtractor` (PSI), `LinguiTransMsgidExtractor` (tree-sitter) |
| Paraglide `m.hello()`, typesafe-i18n `LL.home.title()` | the key is a member path, no string at all | `ParaglideMessageExtractor`, `TypesafeI18nExtractor` (PSI only) |
| Transloco `*transloco="let t; read: 'admin'"` | the prefix comes from an enclosing template block | `TranslocoReadExtractor`, `TranslocoReadKeyExtractor` |
| ngx-translate `translate.instant('k')`, Transloco `translocoService.translate('k')` | read only when the qualifier names the service: `translate` alone is too common a method | `NgxTranslateExtractor`, `ServiceCallKeyExtractor` |
| Vue `v-t`, `<i18n-t keypath>`, Angular directives, Svelte and Blade markup | markup of a host language, rewritten or injected before any JavaScript grammar sees it | `VueI18nMarkupExtractors`, `AngularTemplate`, `VueSfc` |
| Angular `` $localize`:@@id:text` `` | a tagged template whose key is a metadata block | `LocalizeTaggedTemplateExtractor` |
| Keys from constants, `t(KEYS.save)` | a resolution, not a syntax (TASK-KEY-FROM-CONSTANT) | — |

## What was done

1. `rules/` in `core`: `FrameworkRules`, `KeyLocation`, `CalleeMatch`, `CallRule`, `HookRule`,
   `ComponentRule`, `PipeRule`; `FrameworkPresets` for i18next, next-intl, react-intl, Lingui,
   svelte-i18n, vue-i18n, Transloco and ngx-translate; `CodeGrammar`; `RuleKeyExtractor`.
2. Both pipelines consume the presets. tree-sitter: `TsxGrammar`, `reactIntlExtractors()` and
   `linguiExtractors()` are `RuleKeyExtractor` on the presets, the i18next and vue-i18n extractors
   take their function names from them. PSI: `PsiJsGrammar`; `ReactIntlExtractor`,
   `DefineMessagesExtractor` and `FormattedMessageExtractor` are `RuleKeyExtractor` on subsets of
   the react-intl preset (the `MessageDescriptors` walk is gone); `SvelteI18nExtractor`,
   `ReactUseTranslationHookExtractor`, `NextIntlMethodExtractor` and `JsLang`'s import gates take
   their names from the presets, as do the technologies' `translationFunctionNames()`.
3. `i18n-check --code` reads react-intl and Lingui by the presets alone, plus the ported `<Trans>`
   msgid. Parity on `examples/react-intl` and `examples/lingui`: `ReactIntlLinguiParityTest`
   (keys per line against the READMEs and the plugin's extractors), `I18nCheckTest` (the report),
   and `ExtractorParityTest` in the plugin's suite, which runs both pipelines on the same example
   files and compares the keys they find.

## What is left

- Svelte in `i18n-check`: the preset exists, the Svelte grammar does not (`tree-sitter-svelte` has
  no JNI jar and the markup needs a host rewrite like `VueSfc`). TASK-CLI-CODE-MORE-FRAMEWORKS.
- PHP (Laravel): no PHP preset yet; `tree-sitter-php` exists on Maven. Same task.
- `TemplateKeyExtractor` (PSI) still names `t`, `$t`, `i18n.t` itself: widening it to the presets
  would change which template literals are read (`$tc`, `props.t`…), a behaviour change for another
  task.
- `XmlAttributeKeyExtractor` keeps `i18nKey` in its own code: TASK-TRANS-NS-PROP rewrites it (the
  `ns` and `t` props); it can read the i18next component rule once merged.
