# Checklist des captures pour le README

## Setup

1. Lancer `./gradlew runWebStorm` (propriété `webStormPath` requise) : le sandbox `runIde` d'IntelliJ IDEA Ultimate
   démarre sans licence et ne charge pas le support JavaScript
2. Ouvrir `examples/react-multi-namespace/` — c'est le projet de référence : cinq namespaces
   (`auth`, `common`, `dashboard`, `deposit-box`, `navigation`), deux locales (`en`, `fr`),
   des clés manquantes et vides prêtes à l'emploi
3. Thème sombre par défaut de l'IDE, fenêtre d'outil ancrée en bas, largeur ≥ 1400 px
4. Outil recommandé : [ScreenToGif](https://www.screentogif.com/) ou la capture système ;
   PNG, sans mise à l'échelle, rognée à la zone utile
5. Sous Xvfb, sans gestionnaire de fenêtres : `WAYLAND_DISPLAY` bidon et `XDG_SESSION_TYPE=x11` pour que l'IDE
   s'ouvre sur l'écran virtuel, écran de 1920×1440 pour que la page de réglages tienne de *Namespaces* à *Checks*.
   Ne jamais capturer un chemin absolu local (panneau *Resolution* des modules) ni la modale de licence

---

## Captures de l'éditeur et de l'assistant — refaites le 2026-10-25 (#670)

`Setup-wizard-step-{1,2,3}.png`, `annotation-{resolved,unresolved,missing-file}.png`, `completion.png`,
`extraction.png`, `extraction-intent.png`, `extraction-add-namespace.png`, `gutter-icons.png`,
`gutter-icons-tooltip.png`, `hover-hint.png`, `inlay-hints.png`, `navigation.png`.

## Captures de la fenêtre d'outil et des réglages — refaites le 2026-10-26

Vues *Tree / Table / Stats* dans la barre d'outils (#669), libellés et sous-pages de #666 / #667, WebStorm 2026.1.

| Fichier | Contenu |
|---|---|
| `toolwindow-tree-namespaces.png` | les cinq groupes repliés avec leur complétude, dont `common (default)` |
| `toolwindow-tree.png` | groupes de namespace dépliés (`common`, `auth`, `dashboard`), badges `EN✓ FR✓`, légende |
| `toolwindow-table.png` | colonne Namespace avec `common (default)`, deux cellules *Missing*, en-tête *Usage (not counted)* |
| `toolwindow-table-namespace-filter.png` | combo *All namespaces* déroulée, `common (default)` en tête |
| `toolwindow-table-orphans.png` | filtre `deposit-box`, après **Count Key Usages** : ligne *No reference* (`activity.entry.fileAdded`) avec le menu ouvert sur *Delete Key with No Reference*, lignes *Dynamic* `status.*` (appel `` t(`deposit-box:status.${status}`) ``) |
| `toolwindow-stats.png` | matrice Namespace × locale, ligne Total, `common (default)`, barres vertes/orange |
| `toolwindow-stats-popup.png` | popup `common (default) — 'fr': 2 missing, 0 empty` sous la cellule (titre tronqué par la popup) |
| `toolwindow-create-translation.png` | dialogue *Create Translation* rempli (clé vérifiée, chemins, *Copy Reference to Empty Locales*) |
| `toolwindow-create-namespace.png` | sous-dialogue *Add Namespace* ouvert par le `+` |
| `settings.png` | page principale : *Namespaces and separators*, *Where translations are searched*, *Checks* |
| `settings-folding.png` | *Folding and preview* (*Project reference locale*), *Key extraction*, *PHP / gettext*, *Appearance*, *All projects* |
| `settings-modules.png` | sous-page *Modules* : module `web`, case *Active*, panneau *Resolution* (chemin absolu hors cadre) |
| `settings-rules.png` | sous-page *Key Assistance Rules* : règle `auth-forms`, bouton *Import from i18n Ally…* |

Le module et la règle des deux dernières captures sont saisis pour l'occasion puis abandonnés par *Cancel*.

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
| Refaites le 2026-10-26 | 13 (fenêtre d'outil 9, réglages 4) |
| À vérifier | 4 |
