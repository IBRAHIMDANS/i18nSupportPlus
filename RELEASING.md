# Releasing

## Prerequisites

- Secret `IJ_HUB_TOKEN` configured in the repository settings (JetBrains Marketplace token)
- `main` branch up to date and stable (CI green)

---

## Cadence

One minor release a week, on a fixed day (Tuesday), carrying everything merged since the previous one.
Between two trains, nothing is released because it is ready: a fix waits for the train, six days at most.

Before the tag, a day of soak: the `main` of the day before runs in a real IDE (`runWebStorm` on two of
the `examples/`, or the plugin's own use for a working day). A release has gone out before being used
at all three times in three weeks (1.17.1, 1.18.1, 1.19.1 each followed its minor within 48 hours).

A patch leaves the train on two triggers only:

- a crash reported by the Marketplace (Exceptions or Freezes tab) in the current release
- an issue that blocks someone from working

The count of `(regression in x.y.z)` entries, written in each release PR, sets the pace: a month at
zero allows two trains a week; a rising count means the soak gets longer, not the week shorter.
The plugin collects no usage data, and this count is what stands in for it.

*Prepare Release* warns, without blocking, when a minor is tagged on another day than Tuesday or when
the last commit of `main` is less than 20 hours old. It is a reminder: the person running it decides.

Why not slower: the Marketplace exceptions and the few issues are the only feedback there is, and
they arrive within days of a release. A monthly release would leave a crash with every user for a
month and make the What's New too long to read.

---

## Release flow

### 1. Keep `[Unreleased]` up to date as you work

In every feature or fix PR, update the `[Unreleased]` section in `CHANGELOG.md`:

```markdown
## [Unreleased]

### Features
- [Scope] Short description (#PR)

### Bug Fixes
- [Scope] Short description (#PR)
```

This is the only manual editorial step — it belongs in the PR itself, not at release time.

When a fix undoes something a recent release introduced, say so in the entry: end it with
`(regression in 1.18.0)`. At release time, count those entries in the section being promoted and
write the count in the release PR. The plugin collects no usage data, so this count is the one
measure that says whether releases are going out faster than they are being used: when half of a
version's fixes are regressions of the versions before, slow down.

Before triggering the release, check the docs that name a version:

- `docs/COMPARISON.md`: set the I18n Support Plus row to the new version and date, and recheck the version and claims of each other tool.
- `docs/checks.md` and `docs/getting-started.md`: they write `<version>` instead of a release number, so they need no bump. `git grep -nE 'i18nSupportPlus@v[0-9]|i18n-check-[0-9]' docs/` must stay empty.

---

### 2. Trigger the "Prepare Release" workflow

Go to **GitHub → Actions → Prepare Release → Run workflow** and click **Run**. The version field may
stay empty: the next minor after the latest tag is taken (`v1.19.1` → `1.20.0`). Type one for a patch
or a pre-release.

| Field | Example |
|---|---|
| Version | empty, or `1.19.2`, or `1.20.0-beta.1` |

Every Tuesday morning the same workflow runs on its own and only reminds: it opens an issue labelled
`release-train` with the next version, the entries waiting under `Unreleased`, how many of them are
marked as regressions, and whether `main` has had its day of soak. It publishes nothing, because the
checks below are made by hand. Close the issue when the release PR is merged.

Before running it:

- `docs/COMPARISON.md`: the plugin's version and date, no stale `next`
- `./gradlew verifyPlugin`: read the internal and deprecated API report, the Marketplace refuses an
  upload on an internal API and the CI does not block it

The workflow automatically:
- Sets `pluginVersion = 1.0.7` in `gradle.properties`
- Runs `./gradlew patchChangelog` — promotes `[Unreleased]` to `[1.0.7] - YYYY-MM-DD` and creates a fresh empty `[Unreleased]` at the top
- Commits both files to a new branch `release/v1.0.7`
- Creates and pushes tag `v1.0.7`
- Opens a pull request `release/v1.0.7 → main`

The tag push automatically triggers the **Release** workflow, which:
1. Builds the plugin (`./gradlew buildPlugin`)
2. Publishes to JetBrains Marketplace (`./gradlew publishPlugin`)
3. Extracts release notes from `CHANGELOG.md` for the tagged version
4. Creates a GitHub Release with the plugin ZIP attached

Once the Release workflow completes, merge the pull request to keep `main` up to date.

**Two steps: one click to release, one merge to close the loop.**

---

## Versioning

Format: `MAJOR.MINOR.PATCH` — no `v` prefix in `gradle.properties`, the `v` only appears in the git tag.

| Increment | When |
|---|---|
| `PATCH` | Bug fix, internal refactoring |
| `MINOR` | New backward-compatible feature |
| `MAJOR` | Breaking change or major redesign |

### Pre-releases

A tag containing `-beta` or `-alpha` (e.g. `v1.1.0-beta.1`) automatically creates a GitHub Release marked as **pre-release** and publishes to the corresponding Marketplace channel.

---

## Troubleshooting

**"Prepare Release" workflow failed before pushing:**
Nothing was committed or tagged. Fix the issue and re-run the workflow.

**Tag points to the wrong commit:**
```bash
git tag -d v1.0.7
git push origin :refs/tags/v1.0.7
# Re-run "Prepare Release" to recreate everything correctly
```

**Marketplace publish failed but GitHub Release was created:**
Re-run `publishPlugin` manually with `IJ_HUB_TOKEN` exported, or re-run the failed job from the GitHub Actions UI.

**"Plugin already contains version X":**
That version was already published to the Marketplace. Bump to the next version — you cannot overwrite a published release.
