#!/bin/bash
# Builds the native i18n-check of the machine it runs on, from the CLI's distribution. The reflection,
# JNI and resource configuration ships in the CLI's jar (`META-INF/native-image/`), where native-image
# finds it; only tree-sitter's libraries for this platform are added here, not the other four's 8 MB.
#
# usage, from the repository root, after `./gradlew :cli:installDist`, with GraalVM's bin on the PATH:
#   cli/native/build.sh <output binary> [--musl]
# --musl links musl statically, for Alpine. A static binary cannot load a library: --code fails there.
set -euo pipefail

out=$1
lib=cli/build/install/i18n-check/lib
native_image=native-image
case "$(uname -s)" in
  Linux) os=linux-gnu ;;
  Darwin) os=macos ;;
  MINGW* | MSYS* | CYGWIN*) os=windows; native_image=native-image.cmd; lib=$(cygpath -m "$lib") ;;
  *) echo "unsupported OS: $(uname -s)" >&2; exit 2 ;;
esac
case "$(uname -m)" in
  x86_64 | amd64) arch=x86_64 ;;
  aarch64 | arm64) arch=aarch64 ;;
  *) echo "unsupported architecture: $(uname -m)" >&2; exit 2 ;;
esac

options=()
[ "${2:-}" = --musl ] && options+=(--static --libc=musl)

# native-image writes into an existing folder only.
mkdir -p "$(dirname "$out")"

# The `+` form: macOS's bash 3.2 calls an empty array unbound under `set -u`.
"$native_image" ${options[@]+"${options[@]}"} \
  -cp "$lib/*" \
  "-H:IncludeResources=lib/$arch-$os-tree-sitter.*" \
  -o "$out" com.ibrahimdans.i18n.cli.MainKt
