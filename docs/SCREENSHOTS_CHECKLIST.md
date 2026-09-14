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

## À refaire — périmées depuis la refonte de la fenêtre d'outil (2026-09-14, PR #269–#271)

### Tool Window — Tree
- **Fichier** : `toolwindow-tree.png`
- **Comment** : onglet **Tree**, sans filtre
- **Contenu** : les groupes de namespace (icône paquet, gras, badge `15/17 (88%)`), au moins un
  groupe déplié montrant dossiers et clés avec badges `EN✓ FR✗`, la légende en bas
- **Périmé car** : l'ancienne capture montre `common:actions`, `common:appName`… à plat sous la racine

### Tool Window — Table
- **Fichier** : `toolwindow-table.png`
- **Comment** : onglet **Table**, combo sur *All namespaces*
- **Contenu** : la colonne **Namespace** en tête, la clé sans préfixe, les colonnes `en`/`fr`
  avec au moins une cellule *Missing* (icône rouge) et une *Empty* (icône orange), la colonne
  Usage à « not scanned »
- **Périmé car** : pas de colonne Namespace, clés préfixées

### Tool Window — Orphans
- **Fichier** : `toolwindow-table-orphans.png`
- **Comment** : onglet **Table**, après **Scan Orphans** (toolbar)
- **Contenu** : la colonne Usage remplie, une ligne *Unused* et une ligne *Dynamic* si possible,
  le menu contextuel ouvert sur une ligne *Unused* montrant **Delete unused key**
- **Périmé car** : même raison que la table

### Tool Window — Stats
- **Fichier** : `toolwindow-stats.png`
- **Comment** : onglet **Stats**
- **Contenu** : la matrice Namespace × locale : ligne **Total** en gras, une ligne par namespace,
  cellules `11/13 [=====  ] 84.6%` avec au moins une barre orange (< 90 %) ; en-têtes `EN` / `FR`
- **Périmé car** : l'ancienne capture montre le tableau Locale | Total | Translated | Missing | %

### Tool Window — Stats popup (nouvelle)
- **Fichier** : `toolwindow-stats-popup.png`
- **Comment** : onglet **Stats**, cliquer une cellule avec des clés non traduites (ex. `common` / `FR`)
- **Contenu** : la popup ancrée sous la cellule : titre `common — 'fr': 2 missing, 1 empty — reference: en`,
  les lignes avec icône ✗ / !, la clé, la valeur `en` en gris, la ligne d'aide
  « Enter or click: translate · F4: open in 'en' » en bas

### Sync Missing Keys
- **Fichier** : `sync-missing-keys.png`
- **Comment** : **Sync Keys** depuis la toolbar de la fenêtre d'outil
- **Contenu** : le dialogue de saisie groupée avec plusieurs clés à remplir
- **À vérifier** : la capture actuelle date d'avril ; refaire seulement si le dialogue a changé

### Settings
- **Fichier** : `settings.png`
- **Comment** : **Settings > Tools > i18n Support Plus Configuration**
- **Contenu** : la page complète avec ses groupes (*Namespaces and separators*, *Where translations
  are searched*, *Modules configuration*, *Key assistance rules*, …)
- **Périmé car** : la capture d'avril ne montre ni les modules, ni les règles, ni la locale de prévisualisation

### Navigation
- **Fichier** : `navigation.png`
- **Comment** : Ctrl+survol d'une clé
- **Contenu** : le lien souligné sur la clé ; ne plus montrer de popup *Choose Declaration* — depuis
  #268 le Ctrl+clic ouvre directement la locale de prévisualisation
- **À vérifier** : refaire seulement si l'ancienne capture montre la popup de choix

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
| 8 | Gutter Icons | `gutter-icons.png` |
| 9 | Inlay Hints | `inlay-hints.png` |
| 10 | Code Folding | `folding-before.png`, `folding-after.png` |
| 16 | Hover Hint | `hover-hint.png` |

### Captures supplémentaires disponibles (non référencées par le README)
- `gutter-icons-tooltip.png` — Tooltip gutter "All locales resolved (4/4)"
- `annotation-partial.png` — Code JSX avec icônes gutter orange (partial translation)
- `annotation-quickfix.png` — Quick fix popup "Create i18n key" sur clé non résolue
- `toolwindow-table-edit.png`, `toolwindow-table-missing.png`, `toolwindow-table-namespace-filter.png` —
  anciennes vues de la table, **périmées** : à supprimer ou à refaire si on veut les référencer

## Récapitulatif

| Statut | Nombre |
|--------|--------|
| À refaire | 5 (`toolwindow-tree`, `toolwindow-table`, `toolwindow-table-orphans`, `toolwindow-stats`, `settings`) |
| Nouvelle | 1 (`toolwindow-stats-popup`) |
| À vérifier | 2 (`sync-missing-keys`, `navigation`) |
| Inchangées | 18 |
