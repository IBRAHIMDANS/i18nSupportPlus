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

## Captures de l'éditeur et de l'assistant — refaites le 2026-10-25 (#670)

`Setup-wizard-step-{1,2,3}.png`, `annotation-{resolved,unresolved,missing-file}.png`, `completion.png`,
`extraction.png`, `extraction-intent.png`, `extraction-add-namespace.png`, `gutter-icons.png`,
`gutter-icons-tooltip.png`, `hover-hint.png`, `inlay-hints.png`, `navigation.png`.

## À refaire

### Fenêtre d'outil — périmées depuis #669 (vues *Tree / Table / Stats* dans la barre d'outils, plus d'onglets)

Capturées le 2026-09-14 avec la rangée d'onglets. Refaire avec la barre d'outils actuelle, fenêtre ≥ 1400 px
pour que les filtres *Namespace* et *Status* de la table soient à côté de la recherche.

| Fichier | Contenu attendu |
|---|---|
| `toolwindow-tree-namespaces.png` | les cinq groupes repliés avec leur complétude, dont `common (default)` |
| `toolwindow-tree.png` | groupes de namespace dépliés (`auth`, `common`, `dashboard`), badges `EN✓ FR✓`, légende |
| `toolwindow-table.png` | colonne Namespace avec `common (default)`, clés sans préfixe, une cellule *Missing*, en-tête *Usage (not counted)* |
| `toolwindow-table-namespace-filter.png` | combo *All namespaces* déroulée, `common (default)` en tête |
| `toolwindow-table-orphans.png` | vue **Table**, après **Count Key Usages** (barre d'outils), clic droit sur une ligne *No reference* : colonne Usage remplie, états *Dynamic*, menu ouvert sur *Delete Key with No Reference* (capture de mars) |
| `toolwindow-stats.png` | matrice Namespace × locale, ligne Total, `common (default)`, barres vertes/orange |
| `toolwindow-stats-popup.png` | popup `common — 'fr': 2 missing, 0 empty — reference: en` sous la cellule |
| `toolwindow-create-translation.png` | dialogue *Create Translation* rempli (clé vérifiée, chemins, *Copy Reference to Empty Locales*) |
| `toolwindow-create-namespace.png` | sous-dialogue *Add Namespace* ouvert par le `+` |

### Réglages — périmées depuis #666 et #667 (libellés renommés, sous-pages *Modules* et *Key Assistance Rules*, groupe *Checks*)

| Fichier | Contenu attendu |
|---|---|
| `settings.png` | page principale : *Namespaces and separators*, *Where translations are searched*, *Checks* |
| `settings-folding.png` | *Folding and preview* (*Project reference locale*), *Key extraction*, *PHP / gettext*, *Appearance* |
| `settings-modules.png` | sous-page *Modules* : liste à gauche, case *Active*, panneau *Resolution* |
| `settings-rules.png` | sous-page *Key Assistance Rules*, bouton *Import from i18n Ally…* |

## À vérifier
- `sync-missing-keys.png` (mars) : refaire si le dialogue a changé d'aspect
- `folding-before.png`, `folding-after.png`, `extraction-translation-value.png` (mars) : comparer avec l'IDE actuel

### Captures supplémentaires disponibles (non référencées par le README)
- `annotation-partial.png` — Code JSX avec icônes gutter orange (partial translation)
- `annotation-quickfix.png` — Quick fix popup "Create i18n key" sur clé non résolue

## Récapitulatif

| Statut | Nombre |
|--------|--------|
| Refaites le 2026-10-25 | 15 |
| À refaire | 13 (fenêtre d'outil 9, réglages 4) |
| À vérifier | 4 |
