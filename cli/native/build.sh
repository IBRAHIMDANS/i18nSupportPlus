#!/bin/bash
# Builds the native i18n-check of the machine it runs on, from the CLI's distribution. The reflection,
# JNI and resource configuration ships in the CLI's jar (`META-INF/native-image/`), where native-image
# finds it; only tree-sitter's libraries for this platform are added here, not the other four's 8 MB.
#
# usage, from the repository root, after `./gradlew :cli:installDist`, with GraalVM's bin on the PATH:
#   cli/native/build.sh <output binary>
set -euo pipefail

out=$1
lib=cli/build/install/i18n-check/lib
native_image=native-image
separator=:
case "$(uname -s)" in
  Linux) os=linux-gnu ;;
  Darwin) os=macos ;;
  MINGW* | MSYS* | CYGWIN*) os=windows; native_image=native-image.cmd; separator=';' ;;
  *) echo "unsupported OS: $(uname -s)" >&2; exit 2 ;;
esac
case "$(uname -m)" in
  x86_64 | amd64) arch=x86_64 ;;
  aarch64 | arm64) arch=aarch64 ;;
  *) echo "unsupported architecture: $(uname -m)" >&2; exit 2 ;;
esac

# native-image writes into an existing folder only.
mkdir -p "$(dirname "$out")"

# The class path names each jar, and the options go through an argument file: native-image.cmd, run from Git Bash
# on Windows, split `lib/*` into one argument per jar, and cmd.exe splits an unquoted `;` the same way.
classpath=
for jar in "$lib"/*.jar; do
  [ "$os" = windows ] && jar=$(cygpath -m "$jar")
  classpath=${classpath:+$classpath$separator}$jar
done
args=$(dirname "$out")/native-image.args
printf '%s\n' -cp "$classpath" \
  -H:+UnlockExperimentalVMOptions \
  "-H:IncludeResources=lib/$arch-$os-tree-sitter.*" \
  -H:-UnlockExperimentalVMOptions > "$args"

# native-image adds `.exe` on Windows itself: given one, it wrote `i18n-check.exe.exe`.
"$native_image" "@$args" -o "${out%.exe}" com.ibrahimdans.i18n.cli.MainKt
