# Spike: `i18n-check` as a native binary (TASK-CLI-NATIVE-SPIKE)

2026-11-05 — material in [`spike/cli-native/`](../../spike/cli-native/README.md).

## Question

`i18n-check` needs Java 21, and most of its users are on JS/TS projects. Publishing it on npm is only worth
it if `npx` works without Java. Can GraalVM `native-image` compile the CLI, `--code` and its JNI tree-sitter
included, so that it prints what the JVM version prints?

## What was built

The 1.19.0 CLI, compiled with GraalVM CE for JDK 21.0.2 on Linux x86_64, from a configuration traced by
`native-image-agent` on the 18 examples. The binary ran with no Java on the `PATH`.

## Results

**Yes, with one code change.** 183 runs out of 183 gave the same stdout, stderr and exit code as the JVM:

- 54 runs on the 18 examples: `--code`, `--code --format json`, and no option.
- 129 further cases: the fixtures of `cli/src/test/resources`, the examples under `--format github`,
  `--max-problems` and `--no-values`, bad arguments, a Latin-1 file and a malformed file. The JVM exited 0 on
  23 of them, 1 on 81 and 2 on 25.

| Measure | JVM | Native |
| --- | --- | --- |
| One run on an example | 389 ms | 45 ms |
| 5 600 source files, 9 MB (two runs) | 19-20 s | 24-29 s |
| Peak memory on these 5 600 files | 517 MB | 126 MB |
| Download | 5.6 MB zip + Java 21 | 32 MB binary, 10 MB gzipped |

The native version starts about 8 times faster, and loses to the JIT on a large project. It suits what
`npx` and pre-commit hooks do; a very large monorepo in CI takes 20 to 50% longer.

## The change needed

Without it, every `--code` run on JS or TS crashes the binary with a segfault (exit 99). The native library
looks `read` up on the reader's own class, and `TSReader { … }` is a Kotlin lambda, compiled to a hidden class:
the agent does not trace it, and declaring `TSReader.read` on the interface does not help. Replacing the
lambda by an `object : TSReader` ([`named-reader.patch`](../../spike/cli-native/named-reader.patch)) gives a
class that can be registered. The JVM's output is unchanged by the patch on the 54 example runs.

## Limits of this result

- **Only Linux x86_64 was built.** macOS (x86_64, arm64), Linux arm64 and Windows x86_64 are untested, though
  the binding ships libraries for each of them. It has none for Windows arm64. A native-image build needs a
  machine of each platform, so one CI runner per target.
- **glibc only.** The binary links `libc` and `libz` dynamically, so it will not run on Alpine as built. Musl
  needs its own build (`--static --libc=musl`), not tried here.
- **Each regression is a crash, not an error.** A JNI lookup the configuration misses aborts the process. Any
  new callback or lambda passed to tree-sitter needs the same care, so CI must run the binary on the examples.
- **The native libraries are unpacked into `/tmp` on each run**, as on the JVM: a `noexec` `/tmp` fails.
- The large-project figures come from two runs on a duplicated example.

## Decision

Native binaries, one optional npm package per platform as esbuild does, under the name `i18n-support-plus`.
`jlink` stays the fallback if a platform fails. It is not needed on what was measured.

A publication task comes next, and starts with: the named reader, the same comparison run on macOS and
Windows runners, and a smoke test of the binary in CI.
