# Spike: `i18n-check` as a native binary

Material of TASK-CLI-NATIVE-SPIKE. The decision it supports is in
[docs/spikes/2026-11-05-cli-native-image.md](../../docs/spikes/2026-11-05-cli-native-image.md).

| File | Does |
| --- | --- |
| `named-reader.patch` | Replaced the `TSReader` lambda of `TreeSitterCodeNode.parseTsx` by a named class, without which `--code` crashed the binary (applied in #861) |

The configuration the spike traced, and the build script, now live with the CLI: the configuration in
`cli/src/main/resources/META-INF/native-image/`, the build and the comparison with the JVM in `cli/native/`,
run on each platform by `.github/workflows/cli-native.yml`.
