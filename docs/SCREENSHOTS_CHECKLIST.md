# Checklist des captures pour le README

## Setup

1. Lancer `./gradlew runIde`
2. Ouvrir un projet de test avec des fichiers de traduction (ex: `examples/react-multi-namespace/`)
3. Outil recommande : [ScreenToGif](https://www.screentogif.com/) (screenshots + GIFs)

---

## Screenshots (`docs/img/`)

### 2. Annotation — cle resolue
- **Fichier** : `annotation-resolved.png`
- **Comment** : Ouvrir un fichier JS/TS avec `t('common:existingKey')`
- **Contenu** : La cle sans erreur, eventuellement avec l'inlay hint a cote
- **Statut** : DONE (source : `Screenshot from 2026-03-29 12-44-21.png`)


### 4. Annotation — fichier manquant
- **Fichier** : `annotation-missing-file.png`
- **Comment** : Ecrire `t('unknownNamespace:key')`
- **Contenu** : Le namespace souligne, indiquant que le fichier n'existe pas
- **Statut** : DONE (source : `Screenshot from 2026-03-29 12-43-08.png`)


## Recapitulatif

| # | Feature | Statut | Fichier(s) |
|---|---------|--------|------------|
| 1 | Setup Wizard | DONE | `Setup-wizard-step-{1,2,3}.png` |
| 2 | Annotation — cle resolue | DONE | `annotation-resolved.png` |
| 3 | Annotation — segment non resolu | DONE | `annotation-unresolved.png` |
| 4 | Annotation — fichier manquant | DONE | `annotation-missing-file.png` |
| 5 | Navigation | DONE | `navigation.png` |
| 6 | Completion | DONE | `completion.png` |
| 7 | Extraction | DONE | `extraction.png`, `extraction-intent.png`, `extraction-add-namespace.png`, `extraction-translation-value.png` |
| 8 | Gutter Icons | DONE | `gutter-icons.png` |
| 9 | Inlay Hints | DONE | `inlay-hints.png` |
| 10 | Code Folding | DONE | `folding-before.png`, `folding-after.png` |
| 11 | Tool Window — Tree | DONE | `toolwindow-tree.png` |
| 12 | Tool Window — Table | DONE | `toolwindow-table*.png` (4 fichiers) |
| 13 | Tool Window — Stats | DONE | `toolwindow-stats.png` |
| 14 | Settings | DONE | `settings.png` |
| 15 | Sync Missing Keys | DONE | `sync-missing-keys.png` |
| 16 | Hover Hint | DONE | `hover-hint.png` |
| 17 | Orphan Scan | DONE | `toolwindow-table-orphans.png` |

**Progression : 17/17 (100%) ✓**

### Captures supplementaires disponibles (non listees)
- `gutter-icons-tooltip.png` — Tooltip gutter "All locales resolved (4/4)"
- `annotation-partial.png` — Code JSX avec icones gutter orange (partial translation)
- `annotation-quickfix.png` — Quick fix popup "Create i18n key" sur cle non resolue
