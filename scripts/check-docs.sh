#!/usr/bin/env bash
# Checks the documentation, with nothing but bash, git, grep and awk:
#   1. every relative link of the guides points to a file of the repository;
#   2. every image of docs/img is shown by a .md (the screenshot checklist aside) or a .html; a UI
#      screenshot scenario (src/uiTest) that produces an image is not a use of it;
#   3. no install instruction pins a release of the plugin (zip name, Action tag, image tag, download URL).
# Only tracked files are read. Exits 1 on the first run that finds anything, naming each finding.
set -uo pipefail
cd "$(dirname "$0")/.."

# Images known to be orphans on main, each with the task that removes it from this list.
# A listed image that is cited again only raises a warning: drop it from the list then.
ORPHAN_IMAGE_EXCEPTIONS=()

problems=0

error() { # file line message
  problems=$((problems + 1))
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    echo "::error file=$1,line=$2::$3"
  fi
  echo "$1:$2: $3"
}

warning() { # file message
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    echo "::warning file=$1::$2"
  fi
  echo "warning: $1: $2"
}

# --- 1. Relative links -------------------------------------------------------------------------
# Prints "line<TAB>target" for each link of a Markdown file, outside fenced code blocks and inline
# code: [text](target), ![alt](target), reference definitions "[id]: target", and src="target".
extract_links() {
  awk '
    /^[ \t]*(```|~~~)/ { fenced = !fenced; next }
    fenced { next }
    {
      line = $0
      gsub(/`[^`]*`/, "", line)
      if (match(line, /^[ \t]*\[[^]]+\]:[ \t]*[^ \t]+/)) {
        def = substr(line, RSTART, RLENGTH)
        sub(/^[ \t]*\[[^]]+\]:[ \t]*/, "", def)
        print NR "\t" def
      }
      rest = line
      while (match(rest, /\]\([^)]*\)/)) {
        print NR "\t" substr(rest, RSTART + 2, RLENGTH - 3)
        rest = substr(rest, RSTART + RLENGTH)
      }
      rest = line
      while (match(rest, /src="[^"]*"/)) {
        print NR "\t" substr(rest, RSTART + 5, RLENGTH - 6)
        rest = substr(rest, RSTART + RLENGTH)
      }
    }
  ' "$1"
}

while IFS= read -r doc; do
  dir=$(dirname "$doc")
  while IFS=$'\t' read -r line target; do
    target=${target#<}; target=${target%%>*}   # <path with spaces>
    target=${target%% *}                        # drop a "title"
    case "$target" in
      ''|'#'*|*://*|mailto:*|data:*|tel:*) continue ;;
    esac
    path=${target%%#*}; path=${path%%\?*}
    [ -z "$path" ] && continue
    path=${path//%20/ }
    if [ "${path#/}" != "$path" ]; then resolved=".${path}"; else resolved="$dir/$path"; fi
    [ -e "$resolved" ] || error "$doc" "$line" "broken relative link: $target"
  done < <(extract_links "$doc")
done < <(git ls-files -- 'README.md' 'CONTRIBUTING.md' 'RELEASING.md' 'docs/*.md' 'examples/*/README.md')

# --- 2. Orphan images --------------------------------------------------------------------------
# The screenshot checklist is the inventory of every capture: being listed there is not a use. A UI
# screenshot scenario only produces the image: a capture nobody sees is still an orphan.
mapfile -t citing < <(git ls-files -- '*.md' '*.html' ':!docs/dev/SCREENSHOTS_CHECKLIST.md')
mapfile -t scenarios < <(git ls-files -- 'src/uiTest/*')
while IFS= read -r image; do
  name=$(basename "$image")
  excepted=false
  for e in "${ORPHAN_IMAGE_EXCEPTIONS[@]}"; do [ "$e" = "$name" ] && excepted=true; done
  if grep -qF -- "$name" "${citing[@]}"; then
    $excepted && warning "$image" "cited again: remove it from ORPHAN_IMAGE_EXCEPTIONS in scripts/check-docs.sh"
  elif $excepted; then
    :
  elif [ "${#scenarios[@]}" -gt 0 ] && grep -qF -- "$name" "${scenarios[@]}"; then
    error "$image" 1 "image produced by a scenario, shown nowhere: show it in a .md or drop the image and its scenario"
  else
    error "$image" 1 "image cited by no .md or .html file"
  fi
done < <(git ls-files -- 'docs/img/*')
for e in "${ORPHAN_IMAGE_EXCEPTIONS[@]}"; do
  [ -e "docs/img/$e" ] || warning "docs/img/$e" "no longer exists: remove it from ORPHAN_IMAGE_EXCEPTIONS in scripts/check-docs.sh"
done

# --- 3. Pinned plugin releases -----------------------------------------------------------------
# Only install instructions are checked: a release written in prose ("from 1.16.0", "before 1.13.0")
# is history and stays true. docs/COMPARISON.md dates the plugin on purpose, and the spikes record
# the version they measured.
while IFS= read -r doc; do
  while IFS=: read -r line match; do
    error "$doc" "$line" "pinned plugin release in an install instruction: $match (write <version> and link the latest release)"
  done < <(grep -noE '(i18n-check-|i18n-check:|@v|releases/download/v|releases/tag/v)[0-9]+\.[0-9]+\.[0-9]+' "$doc")
done < <(git ls-files -- 'README.md' 'CONTRIBUTING.md' 'docs/*.md' ':!docs/COMPARISON.md' ':!docs/spikes/*')

if [ "$problems" -gt 0 ]; then
  echo "check-docs: $problems problem(s)"
  exit 1
fi
echo "check-docs: OK"
