# Releasing

## Prerequisites

- Secret `IJ_HUB_TOKEN` configured in the repository settings (JetBrains Marketplace token)
- `main` branch up to date and stable (CI green)

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

---

### 2. Trigger the "Prepare Release" workflow

Go to **GitHub → Actions → Prepare Release → Run workflow**, enter the target version and click **Run**.

| Field | Example |
|---|---|
| Version | `1.0.7` |

The workflow automatically:
- Sets `pluginVersion = 1.0.7` in `gradle.properties`
- Runs `./gradlew patchChangelog` — promotes `[Unreleased]` to `[1.0.7] - YYYY-MM-DD` and creates a fresh empty `[Unreleased]` at the top
- Commits both files to `main`
- Creates and pushes tag `v1.0.7`

The tag push automatically triggers the **Release** workflow, which:
1. Builds the plugin (`./gradlew buildPlugin`)
2. Publishes to JetBrains Marketplace (`./gradlew publishPlugin`)
3. Extracts release notes from `CHANGELOG.md` for the tagged version
4. Creates a GitHub Release with the plugin ZIP attached

**That's it — one click, no manual steps.**

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
