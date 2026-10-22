# Spike: reading `t('…')` without the PSI (TASK-CORE-12)

2026-10-22 — prototypes in [`spike/tree-sitter/`](../../spike/tree-sitter/README.md).

## Question

The portable core can read and write translation files without IntelliJ, but every key of the
source code comes from 42 extractor files written on the JavaScript/PHP PSI. Can tree-sitter replace
the PSI outside the IDE, and through which binding: jtreesitter (official, Java 22+ FFM), bonede's
java-tree-sitter (JNI), or a TypeScript language server on web-tree-sitter (WASM)?

## What was built

- **Node, web-tree-sitter 0.25 + `tree-sitter-tsx.wasm` 0.23** (`node/extract.mjs`, ~150 lines):
  `t('key')`, `i18n.t('key')`, template literals split into literal / `${…}` elements as
  `TemplateKeyExtractor` does, the `{ ns }` option, `<Trans i18nKey>`, and the namespaces and
  `keyPrefix` of the `useTranslation(…)` that binds `t` in the enclosing function — the job of
  `ReactUseTranslationHookExtractor` and `TranslationHookInScope`.
- **JVM, Java 21, `io.github.bonede:tree-sitter` 0.26.6 + `tree-sitter-tsx` 0.23.2**
  (`jvm/ExtractKeys.java`, source launcher, no build): the call sites only, to prove the native
  library loads and the grammar parses on the project's Java.
- **jtreesitter**: not run. It needs Java 22+ (FFM) and the JDKs here stop at 21; it ships no native
  library either. Judged from its documentation and Maven metadata (0.26.1, 2026-06).

## Keys found

Three files of `examples/react-multi-namespace/src/components/`, 34 call sites, no parse error.
The plugin side was not run (no IDE in a spike): the expected keys are read off the extractors —
the hook extractor returns the literal as written plus the hook's namespaces, `i18n.t` is a
configured i18next function with no hook namespace, `XmlAttributeKeyExtractor` returns the
`i18nKey` value with no namespace.

| File | Call sites | Node (WASM) | JVM (JNI) | Plugin (read off the extractors) |
| --- | --- | --- | --- | --- |
| `Dashboard.tsx` — `useTranslation(['dashboard', 'common'])` | 15 | 15, ns `[dashboard, common]` | 15 | 15, same keys and namespaces |
| `DepositBox.tsx` — `useTranslation()`, template, `<Trans>`, `i18n.t` | 11 | 11, `deposit-box:status.${status}` as literal + template | 11 | 11, same |
| `LoginForm.tsx` — `useTranslation(['auth'])` | 8 | 8, ns `[auth]` | 8 | 8, same |

Same RawKeys on all 34. Parsing plus extraction takes 5–25 ms a file in Node (first file
includes WASM warm-up) and 3–15 ms on the JVM.

This proves the easy part. What the 34 sites do not exercise, and what the 1 513 lines of
`extensions/lang/js` handle: `const { t: tr }` aliases, `props.t` / `this.props.t`, a `t` passed down
or returned, `t(cond ? 'a' : 'b')`, keys in constants, react-intl descriptors, key assistance rules
and module presets, Vue/Svelte/Angular templates and Blade, where the JavaScript lives inside
another language. With the PSI the IDE parses those injections for us; with tree-sitter each host
grammar's expression nodes must be re-parsed with the JavaScript grammar and their offsets mapped
back.

## Options

| | jtreesitter (FFM) | bonede java-tree-sitter (JNI) | TypeScript server, web-tree-sitter |
| --- | --- | --- | --- |
| Runs on Java 21 | **no** (22+) — and IDEs 252/253 run JBR 21 | yes, verified | n/a (Node) |
| Native libraries | to build and ship per platform ourselves | bundled in the jars: Linux/macOS x64+arm64, Windows x64 (no Windows arm64) | none: one portable `.wasm` per grammar |
| Grammars | any, compiled by us | Maven: js, ts, tsx, vue, svelte, php, html; **no** angular, blade, astro | npm: same plus angular, blade |
| Reuses `core` (Kotlin) | yes | yes | **no**: `core` is JVM-only (java.io, Gson, Jackson, java.net.http); everything of priorities 1–5 would be rewritten in TypeScript |
| Maintenance | tree-sitter org | one maintainer, but steady (0.24 → 0.26.6, 2026-03) | tree-sitter org |
| Quirks seen | — | query predicates (`#eq?`, `#match?`) not applied by the cursor: filter in code | node objects re-created per access: compare `id`, not references |
| Distribution of a server | JVM process (JRE needed or jlink'd) | same | npm package, the natural fit for a VS Code extension |

## Recommendation

**bonede's JNI binding, on the JVM, behind `CodeNode` (TASK-CORE-18).**

It is the only option that both runs on Java 21 and reuses the `core` the backlog is building: a
TypeScript server would throw away priorities 1–5 and duplicate every rule in a second language,
and jtreesitter cannot load on the JBR 21 of the IDEs we support nor on the project's toolchain.
Behind `CodeNode` the binding stays an implementation detail: moving to jtreesitter later, once the
toolchain is past 22, is a change of adapter, not of extractors.

Risks, in order:
1. **Injected languages** (Vue, Svelte, Angular templates, Blade) are where the cost is, not the
   binding: expect each family to need an expression re-parse and offset mapping. Angular and Blade
   have no JNI grammar — they would need building or would stay IDE-only.
2. **Single-maintainer binding**: mitigated by `CodeNode`; worst case, jtreesitter on Java 22+ for an
   out-of-IDE process.
3. **No Windows arm64 native**: a server would refuse to start there.
4. **A JVM server needs a JRE** on the user's machine (or a jlink'd one, ~40 MB) — acceptable for an
   LSP, heavier than an npm package.
5. **Byte vs char offsets**: tree-sitter reports UTF-8 bytes; `TextSpan` is in chars. The adapter
   must convert, as `YamlTranslationTree` already does for code points.

## Impact on the backlog

- **TASK-CORE-18 (`CodeNode`)**: keep. Add to it: byte → char offsets, node identity by id, and a
  `TreeSitterCodeNode` adapter next to `PsiCodeNode`.
- **TASK-CORE-20 (standalone extractors)**: keep, but start with i18next / react-i18next only (the
  family this spike covers, and the largest), and make each other family a separate task gated on
  its grammar: Vue and Svelte (grammars exist, injections to handle), Angular and Blade (no JNI
  grammar: out until one exists).
- **TASK-CORE-21 (standalone index)**: keep as indicative.
- **Before priorities 6–8**, decide the consumer. A CLI that checks translation files needs none of
  this; one that reports unused or missing keys from code needs TASK-CORE-18 and the i18next part of
  TASK-CORE-20 only; a full LSP needs all three. The extraction work is only worth starting once that
  consumer is chosen.
