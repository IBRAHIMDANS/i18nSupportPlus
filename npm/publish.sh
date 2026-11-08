#!/bin/bash
# Publishes the packages `npm pack` wrote in <dir>: the platform packages first, so that
# `i18n-support-plus` never points to a version that does not exist yet. A version already on the
# registry is skipped, so a release whose publication failed half-way can be run again.
#
# usage: npm/publish.sh <dir> [npm publish options...]
#   In release.yml with --provenance (trusted publishing); by hand, the first time, without it.
# A prerelease version (1.20.0-beta.1) goes under the `next` tag: `latest` stays on the last release.
set -euo pipefail

dir=$1
shift

main=$(ls "$dir"/i18n-support-plus-[0-9]*.tgz)
publish() {
  local tarball=$1 name version
  shift
  name=$(tar -xOzf "$tarball" package/package.json | node -p 'JSON.parse(require("fs").readFileSync(0)).name')
  version=$(tar -xOzf "$tarball" package/package.json | node -p 'JSON.parse(require("fs").readFileSync(0)).version')
  if npm view "$name@$version" version > /dev/null 2>&1; then
    echo "$name@$version is already published"
    return
  fi
  local tag=()
  [[ "$version" == *-* ]] && tag=(--tag next)
  npm publish "$tarball" --access public ${tag[@]+"${tag[@]}"} "$@"
}

for tarball in "$dir"/i18n-support-plus-*.tgz; do
  [ "$tarball" = "$main" ] || publish "$tarball" "$@"
done
publish "$main" "$@"
