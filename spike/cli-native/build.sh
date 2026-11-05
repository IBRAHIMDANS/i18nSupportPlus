#!/bin/bash
# Builds a native i18n-check from a repository with named-reader.patch applied.
# usage: GRAALVM_HOME=<GraalVM CE for JDK 21> build.sh <output binary>
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
out=$(realpath -m "$1")
(cd "$root" && JAVA_HOME=$GRAALVM_HOME ./gradlew :cli:installDist --no-daemon -q)
mkdir -p "$(dirname "$out")"
"$GRAALVM_HOME/bin/native-image" --no-fallback \
  "-H:ConfigurationFileDirectories=$here/config" \
  -cp "$root/cli/build/install/i18n-check/lib/*" \
  -o "$out" com.ibrahimdans.i18n.cli.MainKt
