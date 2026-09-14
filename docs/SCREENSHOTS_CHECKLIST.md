# Checklist des captures pour le README

## Setup

1. Lancer `./gradlew runIde`
2. Ouvrir `examples/react-multi-namespace/` — c'est le projet de référence : cinq namespaces
   (`auth`, `common`, `dashboard`, `deposit-box`, `navigation`), deux locales (`en`, `fr`),
   des clés manquantes et vides prêtes à l'emploi
3. Thème sombre par défaut de l'IDE, fenêtre d'outil ancrée en bas, largeur ≥ 1400 px
4. Outil recommandé : [ScreenToGif](https://www.screentogif.com/) ou la capture système ;
   PNG, sans mise à l'échelle, rognée à la zone utile

---

## Captures de la fenêtre d'outil et des réglages — refaites le 2026-09-14

Sources : `~/Pictures/Screenshots/Screenshot from 2026-09-14 14-4*.png`, rognées avec ImageMagick
(`convert -crop`), sans mise à l'échelle.

| Fichier | Contenu | Statut |
|---|---|---|
| `toolwindow-tree.png` | groupes de namespace dépliés (`auth`, `common`, `dashboard`), badges `EN✓ FR✓`, légende | DONE |
| `toolwindow-table.png` | colonne Namespace, clés sans préfixe, deux cellules *Missing* | DONE |
| `toolwindow-table-namespace-filter.png` | combo *All namespaces* déroulée | DONE |
| `toolwindow-stats.png` | matrice Namespace × locale, ligne Total, barres vertes/orange | DONE |
| `toolwindow-stats-popup.png` | popup `common — 'fr': 2 missing, 0 empty — reference: en` sous la cellule | DONE |
| `gutter-icons.png` | `DepositBox.tsx` avec badges vert / orange / rouge | DONE (refaite) |
| `gutter-icons-tooltip.png` | tooltip *Partial translation (1/2 locales)* | DONE (refaite) |
| `hover-hint.png` | table de survol `en` / `fr` avec liens ↗ et crayon | DONE (refaite) |
| `settings.png` | *Namespaces and separators*, *Where translations are searched* | DONE |
| `settings-folding.png` | *Folding and preview*, *Key extraction*, *PHP / gettext*, *Inspections* | DONE (nouvelle) |
| `settings-modules.png` | *Appearance*, *Translation file formats*, *Modules configuration* | DONE (nouvelle) |
| `settings-rules.png` | *Key assistance rules* | DONE (nouvelle) |
| `toolwindow-create-translation.png` | dialogue *Create Translation* rempli (clé vérifiée, chemins, *Copy to empty locales*) — frame de `Screencast from 2026-09-14 14-45-40.mp4` à 26 s | DONE (nouvelle) |
| `toolwindow-create-namespace.png` | sous-dialogue *Add Namespace* ouvert par le `+` — même vidéo à 12 s | DONE (nouvelle) |

### Reste à refaire

#### Tool Window — Orphans
- **Fichier** : `toolwindow-table-orphans.png`
- **Comment** : onglet **Table**, après **Scan Orphans** (toolbar), clic droit sur une ligne *Unused*
- **Contenu** : la colonne Usage remplie, le menu contextuel ouvert avec *Delete unused key*
- **Périmé car** : capture d'avril, sans colonne Namespace ni états *Dynamic*

### À vérifier
- `navigation.png` : ne doit plus montrer de popup *Choose Declaration* (depuis #268 le Ctrl+clic
  ouvre directement la locale de prévisualisation)
- `sync-missing-keys.png` : refaire seulement si le dialogue a changé d'aspect

---

## Inchangées — à garder

| # | Feature | Fichier(s) |
|---|---------|------------|
| 1 | Setup Wizard | `Setup-wizard-step-{1,2,3}.png` |
| 2 | Annotation — clé résolue | `annotation-resolved.png` |
| 3 | Annotation — segment non résolu | `annotation-unresolved.png` |
| 4 | Annotation — fichier manquant | `annotation-missing-file.png` |
| 6 | Completion | `completion.png` |
| 7 | Extraction | `extraction.png`, `extraction-intent.png`, `extraction-add-namespace.png`, `extraction-translation-value.png` |
| 10 | Code Folding | `folding-before.png`, `folding-after.png` |
| 9 | Inlay Hints | `inlay-hints.png` |

### Captures supplémentaires disponibles (non référencées par le README)
- `annotation-partial.png` — Code JSX avec icônes gutter orange (partial translation)
- `annotation-quickfix.png` — Quick fix popup "Create i18n key" sur clé non résolue

## Récapitulatif

| Statut | Nombre |
|--------|--------|
| Refaites le 2026-09-14 | 14 |
| À refaire | 1 (`toolwindow-table-orphans`) |
| À vérifier | 2 (`sync-missing-keys`, `navigation`) |
| Inchangées | 15 |
