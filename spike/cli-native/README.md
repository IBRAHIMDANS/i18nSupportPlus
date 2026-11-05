# Spike: `i18n-check` as a native binary

Throwaway material for TASK-CLI-NATIVE-SPIKE, outside the Gradle build. The decision it supports is in
[docs/spikes/2026-11-05-cli-native-image.md](../../docs/spikes/2026-11-05-cli-native-image.md).

| File | Does |
| --- | --- |
| `named-reader.patch` | Replaces the `TSReader` lambda of `TreeSitterCodeNode.parseTsx` by a named class, without which `--code` crashes the binary |
| `config/` | The reflection, JNI and resource configuration, traced on the examples by `native-image-agent` |
| `build.sh` | Builds the binary from a checkout with the patch applied |
