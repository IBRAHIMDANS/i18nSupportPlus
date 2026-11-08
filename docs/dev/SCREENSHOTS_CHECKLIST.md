# Checklist des captures pour le README et `docs/`

## Setup

1. Automatisé : `generateUiScreenshots` et `verifyUiScreenshots` (recette dans `CONTRIBUTING.md`) prennent les
   images du README et de `docs/` dans WebStorm 2025.3.4 avec Starter + Driver, deux sessions fraîches, et ne remplacent une
   image qu'après deux captures identiques au pixel près. Un scénario par image dans `src/uiTest/` ;
   `-PuiScreenshots=a.png,b.png` n'en prend que certaines.
2. Une image sans scénario garde la recette manuelle ci-dessous, avec `runWebStorm` (propriété `webStormPath`
   requise) : le sandbox `runIde` d'IntelliJ IDEA Ultimate démarre sans licence et ne charge pas le support JavaScript.
3. Ouvrir `examples/react-multi-namespace/` — c'est le projet de référence : cinq namespaces
   (`auth`, `common`, `dashboard`, `deposit-box`, `navigation`), deux locales (`en`, `fr`),
   des clés manquantes et vides prêtes à l'emploi
4. Thème sombre par défaut de l'IDE, fenêtre d'outil ancrée en bas, largeur ≥ 1400 px
5. Outil recommandé : [ScreenToGif](https://www.screentogif.com/) ou la capture système ;
   PNG, sans mise à l'échelle, rognée à la zone utile
6. Sous Xvfb, sans gestionnaire de fenêtres : `WAYLAND_DISPLAY` bidon et `XDG_SESSION_TYPE=x11` pour que l'IDE
   s'ouvre sur l'écran virtuel, écran de 1920×1440 pour que la page de réglages tienne de *Namespaces* à *Checks*.
   Ne jamais capturer un chemin absolu local (panneau *Resolution* des modules) ni la modale de licence

---

## Captures de l'éditeur et de l'assistant — refaites le 2026-11-02

`Setup-wizard-step-{1,2,3}.png`, `annotation-{resolved,unresolved,missing-file}.png`, `completion.png`,
`extraction.png`, `extraction-intent.png`, `extraction-add-namespace.png`, `gutter-icons.png`,
`gutter-icons-tooltip.png`, `hover-hint.png`, `inlay-hints.png`, `navigation.png`.

**Refaites le 2026-11-03** (icônes New UI de #826, visibles dans la gouttière) : `annotation-{resolved,unresolved}.png`,
`completion.png`, `extraction-intent.png`, `folding-{folded,unfolded}.png`, `gutter-icons.png`, `hover-hint.png`.
`gutter-icons-tooltip.png` montre encore l'ancienne icône : à refaire à la main.

`folding-{folded,unfolded}.png` (section *Code Folding*) et `extraction-translation-value.png` (dialogue de
*Create missing key*, section *Quick Fixes*) sont citées par `docs/editor.md` depuis le 2026-11-08 ; elles ont
leur scénario dans `EditorScreenshots.kt`. Les deux captures de folding s'appelaient `folding-{before,after}.png`
jusqu'au 2026-11-08 : la première montre les clés repliées, la seconde la clé dépliée sous le curseur.

`extraction-intent.png`, `extraction.png` et `extraction-add-namespace.png` (section *Key Extraction*, dans cet ordre),
`gutter-icons.png` (section *Key status*) et `navigation.png` (section *Navigation*, sous `navigation.gif`) sont citées
par `docs/editor.md` depuis le 2026-11-08. Un scénario ne compte plus comme une citation : `scripts/check-docs.sh` refuse
une capture produite par un scénario et montrée par aucune page (« produced by a scenario, shown nowhere »).

**Sans scénario : `gutter-icons-tooltip.png`** (recette manuelle). Sous Xvfb, aucun survol de l'icône de
gouttière, ni `GutterUiComponent.hoverOverIcon` (ligne comptée à partir de 0), ni un mouvement `Robot` par
petits pas, n'a ouvert l'infobulle (2026-11-01). Attendre « `i18n:` » ne prouve rien : la barre d'état
affiche `i18n: en · 100 %`.

Piste clavier essayée le 2026-11-08 (WebStorm 2025.3.4, Starter), abandonnée. Ce qu'on sait, pour ne pas le refaire :
- `EditorFocusGutter` (*Focus Gutter*, `Alt+Shift+6 F`) est désactivé tant que `ScreenReader.isActive()` est faux :
  « action is disabled (early check) ». `ScreenReader.setActive(true)` par un `@Remote` sur la classe l'active.
- Sous Xvfb sans gestionnaire de fenêtres, la fenêtre n'a aucun propriétaire de focus
  (`KeyboardFocusManager.getFocusOwner()` = null) : *Focus Gutter* ne focalise rien. Un `editor.click()` avant le
  rétablit. Le premier élément de la ligne est le numéro de ligne ; une flèche droite passe à l'icône du plugin.
- `EditorShowGutterIconTooltip` (`Alt+Shift+6 T`) ouvre alors la bonne infobulle, au bon endroit, avec la nouvelle icône.
  Mais l'infobulle prend le focus et affiche un curseur de texte après « fr ». `waitForText` ne la lit pas :
  c'est un `LimitedWidthJBHtmlPane`, et son `getText()` coupe « 1/<wbr>2 ».
- Échec final : une fois l'infobulle ouverte en mode lecteur d'écran, le Driver échoue sur
  `javax.swing.text.html.AccessibleHTML$RootHTMLAccessibleContext`. L'erreur sort après la détection de l'infobulle et
  avant la capture. Aucune capture n'a été produite, donc pas de paire identique.
- Le mode lecteur d'écran élargit aussi la gouttière (les numéros de ligne se décalent) : le cadrage ne serait pas
  celui de `gutter-icons.png`.

## Captures de la fenêtre d'outil et des réglages — refaites le 2026-11-02

Vues *Tree / Table / Stats* dans la barre d'outils (#669), libellés et sous-pages de #666 / #667, WebStorm 2025.3.4 (Starter).

**Refaites le 2026-11-03** (icônes New UI de #826) : `toolwindow-tree.png`, `toolwindow-tree-namespaces.png`,
`toolwindow-stats-popup.png`. Les vues *Table* n'ont pas changé.

| Fichier | Contenu |
|---|---|
| `toolwindow-tree-namespaces.png` | les cinq groupes repliés avec leur complétude, dont `common (default)` |
| `toolwindow-tree.png` | groupes de namespace dépliés (`common`, `auth`, `dashboard`), badges `EN✓ FR✓`, légende |
| `toolwindow-table.png` | colonne Namespace avec `common (default)`, deux cellules *Missing*, en-tête *Usage (not counted)* |
| `toolwindow-table-namespace-filter.png` | combo *All namespaces* déroulée, `common (default)` en tête |
| `toolwindow-table-orphans.png` | filtre `auth`, après **Count Key Usages** : ligne *No reference* (`errors.accountLocked`, lue par aucun code) avec le menu ouvert sur *Delete Key with No Reference*. `deposit-box` n'a plus de clé orpheline depuis que le comptage lit `<Trans i18nKey>` |
| `toolwindow-stats.png` | matrice Namespace × locale, ligne Total, `common (default)`, barres vertes/orange |
| `toolwindow-stats-popup.png` | popup `common (default) — 'fr': 2 missing, 0 empty` sous la cellule (titre tronqué par la popup) |
| `toolwindow-create-translation.png` | dialogue *Create Translation* rempli (clé vérifiée, chemins, *Copy Reference to Empty Locales*) |
| `toolwindow-create-namespace.png` | sous-dialogue *Add Namespace* ouvert par le `+` |
| `settings.png` | page principale : *Namespaces and separators*, *Where translations are searched*, *Checks* |
| `settings-folding.png` | *Folding and preview* (*Project reference locale*), *Key extraction*, *PHP / gettext*, *Appearance*, *All projects* |
| `settings-modules.png` | sous-page *Modules* : module `web`, case *Active*, panneau *Resolution* (chemin absolu hors cadre) |
| `settings-rules.png` | sous-page *Key Assistance Rules* : règle `auth-forms`, bouton *Import from i18n Ally…* |

Le module et la règle des deux dernières captures sont saisis pour l'occasion puis abandonnés par *Cancel*.

## GIF de la page Marketplace — enregistrés le 2026-10-26

`docs/img/marketplace/`, 960 px de large, WebStorm 2026.1, `examples/react-multi-namespace/` (`DepositBox.tsx`).
Recette (ffmpeg `x11grab` + xdotool sous Xvfb, sandbox sans indices ni erreurs TypeScript) : mémoire du projet, 2026-10-26.

| Fichier | Contenu |
|---|---|
| `extract-i18n-key.gif` | Alt+Entrée sur `<h3>Status</h3>`, *Extract i18n key*, clé `sections.status` et valeur `fr`, valeur affichée en ligne |
| `key-completion.gif` | `t('auth:` puis `login.button.` : complétion des clés avec leur valeur |
| `create-key-quickfix.gif` | clé inconnue `deposit-box:status.help`, *Create i18n key*, valeurs `en` / `fr`, clé résolue |
| `navigation.gif` (2026-11-02, WebStorm 2026.2, `Dashboard.tsx`) | Ctrl+clic sur `stats.users.label`, `dashboard.json` s'ouvre sur la valeur, retour au code |
| `folding.gif` (2026-11-02, WebStorm 2026.2, `Dashboard.tsx`) | *Preview Locale* passée de `en` à `fr`, puis *Show Translations Inline* : les clés repliées sur leurs valeurs françaises |

Pour `navigation.gif` et `folding.gif`, l'appel volontairement faux de `Dashboard.tsx` (`dashboardd:statss.users.count`)
est remplacé par `stats.users.count` le temps de l'enregistrement, et *Partially translated key* est coupé (les clés
`_one`/`_other` de `fr` le déclenchent).

## À vérifier
- `gutter-icons-tooltip.png` (2026-10-25) : sans scénario, à refaire à la main (recette plus haut)

`annotation-partial.png` et `annotation-quickfix.png` sont supprimées le 2026-11-08 : citées nulle part, sans
scénario, prises le 2026-03-28 avec des icônes de gouttière qui ne sont plus celles du plugin. Le statut partiel est montré par
`gutter-icons-tooltip.png`, le quick fix par `marketplace/create-key-quickfix.gif` et `extraction-translation-value.png`.

## Récapitulatif

Compté sur `docs/img/` le 2026-11-08 (37 fichiers).

| Statut | Nombre |
|--------|--------|
| Avec scénario (`generateUiScreenshots`) | 31 (éditeur et assistant 17, fenêtre d'outil 10, réglages 4) |
| Sans scénario, recette manuelle | 1 (`gutter-icons-tooltip.png`) |
| GIF de la page Marketplace (`marketplace/`) | 5 |
| Montrées par une page (`.md` hors cette checklist, `.html`) | 37 sur 37, vérifié par `scripts/check-docs.sh` |
