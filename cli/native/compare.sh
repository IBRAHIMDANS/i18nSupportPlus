#!/bin/bash
# Runs the JVM i18n-check and a native one on the same cases, and fails on the first difference in
# stdout, stderr or exit code. A JNI or reflection lookup the native configuration misses crashes the
# binary instead of failing a check: this is what catches it, on each platform the binary is built for.
#
# usage, from the repository root, after `./gradlew :cli:installDist`:
#   cli/native/compare.sh <native binary> [--no-code]
# The JVM is $JAVA_HOME/bin/java. --no-code leaves out the cases that read the code with tree-sitter.
set -uo pipefail

binary=$1
code=true
[ "${2:-}" = --no-code ] && code=false

lib=cli/build/install/i18n-check/lib
java=${JAVA_HOME:?JAVA_HOME is not set}/bin/java
# On Windows, Git Bash runs java from a POSIX path, and java reads its class path as a Windows one.
if command -v cygpath > /dev/null; then
  lib=$(cygpath -m "$lib")
  java=$(cygpath -u "$JAVA_HOME")/bin/java
fi

# --format github appends to this file when it is set: neither run writes it.
unset GITHUB_STEP_SUMMARY

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# A Latin-1 file and a malformed one, beside the fixtures the CLI's tests read.
mkdir -p "$work/latin1" "$work/malformed"
printf '{"title": "Title"}\n' > "$work/latin1/en.json"
printf '{"title": "Caf\xe9"}\n' > "$work/latin1/fr.json"
printf '{"title": "Title"}\n' > "$work/malformed/en.json"
printf '{"title": \n' > "$work/malformed/fr.json"

cases=()
for example in examples/*/; do
  example=${example%/}
  cases+=("$example" "$example --format json" "$example --format github" "$example --no-values" "$example --max-problems 100000")
  $code && cases+=("$example --code" "$example --code --format json" "$example --code --format github")
done
for fixture in cli/src/test/resources/*/ "$work/latin1" "$work/malformed"; do
  fixture=${fixture%/}
  cases+=("$fixture" "--locales $fixture" "--locales $fixture --format json")
done
cases+=("--help" "--format yaml" "--max-problems -1" "--max-problems" "--unknown" "examples/simple examples/lingui")
$code && cases+=("examples/simple --code --locales examples/simple")

failed=0
for args in "${cases[@]}"; do
  # Word splitting is wanted: no case holds a path with a space.
  # shellcheck disable=SC2086
  "$java" -cp "$lib/*" com.ibrahimdans.i18n.cli.MainKt $args > "$work/jvm.out" 2> "$work/jvm.err"
  jvm_exit=$?
  # shellcheck disable=SC2086
  "$binary" $args > "$work/native.out" 2> "$work/native.err"
  native_exit=$?
  if [ "$jvm_exit" != "$native_exit" ] || ! cmp -s "$work/jvm.out" "$work/native.out" || ! cmp -s "$work/jvm.err" "$work/native.err"; then
    failed=$((failed + 1))
    echo "::error::i18n-check $args: JVM exited $jvm_exit, native $native_exit"
    diff "$work/jvm.out" "$work/native.out" | head -20
    diff "$work/jvm.err" "$work/native.err" | head -20
  fi
done

echo "${#cases[@]} cases, $failed different"
[ "$failed" = 0 ]
