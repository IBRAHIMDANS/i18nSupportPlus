# Spike: i18n keys from TSX without the PSI

Throwaway prototypes for TASK-CORE-12, outside the Gradle build. The decision they support is in
[docs/spikes/2026-10-22-tree-sitter.md](../../docs/spikes/2026-10-22-tree-sitter.md).

| Folder | Runtime | Binding | Does |
| --- | --- | --- | --- |
| `node/` | Node 24 | `web-tree-sitter` 0.25 (WASM) + `tree-sitter-typescript` 0.23 | keys, namespaces from `useTranslation`, `keyPrefix`, `{ ns }` option, template literals, `<Trans i18nKey>` |
| `jvm/` | Java 21 | `io.github.bonede:tree-sitter` 0.26.6 (JNI) + `tree-sitter-tsx` 0.23.2 | the call sites only, to prove the native binding loads and parses on Java 21 |

```bash
# Node
cd spike/tree-sitter/node && npm install --ignore-scripts
node extract.mjs ../../../examples/react-multi-namespace/src/components/{Dashboard,DepositBox,LoginForm}.tsx

# JVM (no build: Java 21 source launcher; download the two jars from Maven Central first)
cd spike/tree-sitter/jvm
java -cp 'tree-sitter-0.26.6.jar:tree-sitter-tsx-0.23.2.jar' ExtractKeys.java ../../../examples/react-multi-namespace/src/components/Dashboard.tsx
```
