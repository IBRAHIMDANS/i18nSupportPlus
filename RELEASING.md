# Releasing

## Prérequis

- Secret `IJ_HUB_TOKEN` configuré dans les paramètres GitHub du repo (JetBrains Marketplace token)
- Branche `main` à jour et stable (CI verte)

---

## Flux de release

### 1. Alimenter `[Unreleased]` au fil des PRs

À chaque PR de feature ou de fix, mettre à jour la section `[Unreleased]` dans `CHANGELOG.md` :

```markdown
## [Unreleased]

### Features
- [Scope] Description courte (#PR)

### Bug Fixes
- [Scope] Description courte (#PR)
```

C'est le seul travail éditorial manuel — il doit être fait dans la PR elle-même, pas au moment de la release.

---

### 2. Lancer le workflow "Prepare Release"

Depuis GitHub : **Actions → Prepare Release → Run workflow**

| Champ | Exemple |
|---|---|
| Version à releaser | `1.0.7` |

Le workflow crée automatiquement une PR `chore/release-1.0.7` qui contient :
- `gradle.properties` : `pluginVersion = 1.0.7`
- `CHANGELOG.md` : `[Unreleased]` promu en `[1.0.7] - YYYY-MM-DD`, nouveau `[Unreleased]` vide ajouté en tête

---

### 3. Merger la PR

Relire le diff (version + changelog), puis merger la PR.

---

### 4. Créer et pousser le tag

```bash
git checkout main && git pull
git tag v1.0.7
git push origin v1.0.7
```

Le tag déclenche automatiquement le workflow **Release** qui :
1. Build le plugin (`./gradlew buildPlugin`)
2. Publie sur JetBrains Marketplace (`./gradlew publishPlugin`)
3. Extrait les notes de `CHANGELOG.md` pour la version taguée
4. Crée la GitHub Release avec le ZIP en pièce jointe

---

## Versioning

Format : `MAJOR.MINOR.PATCH` — pas de préfixe `v` dans `gradle.properties`, le `v` n'apparaît que dans le tag git.

| Incrément | Quand |
|---|---|
| `PATCH` | Bug fix, refactoring interne |
| `MINOR` | Nouvelle fonctionnalité rétrocompatible |
| `MAJOR` | Breaking change ou refonte majeure |

### Pré-releases

Un tag contenant `-beta` ou `-alpha` (ex: `v1.1.0-beta.1`) crée automatiquement une GitHub Release marquée **pre-release** et publie sur le canal correspondant du Marketplace.

---

## En cas de problème

**Le workflow "Prepare Release" a échoué avant le push :** aucune PR créée, rien à nettoyer. Corriger et relancer.

**La PR a été mergée mais le tag pointe sur le mauvais commit :**
```bash
git tag -d v1.0.7
git push origin :refs/tags/v1.0.7
# recréer le tag sur le bon commit
git tag v1.0.7 <sha>
git push origin v1.0.7
```

**La publication Marketplace a échoué mais la GitHub Release est créée :** relancer manuellement `./gradlew publishPlugin` en local avec `IJ_HUB_TOKEN` exporté, ou relancer le job depuis l'interface GitHub Actions.
