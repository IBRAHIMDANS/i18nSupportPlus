---
name: False positive / False negative
about: The plugin incorrectly flags a valid key as missing, or misses an actual error
title: '[Annotation] '
labels: ['bug', 'annotation']
assignees: ''

---

## Type

- [ ] **False positive** — the plugin highlights a key as an error, but it exists and is valid
- [ ] **False negative** — the plugin does not highlight a missing or broken key

## Description

<!-- Describe what the plugin shows vs. what you expect. -->

## Key Pattern

```ts
// How the key is used in source code
t('namespace:section.key')
```

## Translation File

```json
// Relevant excerpt from the translation file (e.g. en/namespace.json)
{
  "section": {
    "key": "value"
  }
}
```

## Plugin Settings (relevant to this case)

<!-- File > Settings > i18n Support Plus — copy the values that seem relevant:
     default namespace, namespace separator, key separator, translation file paths, etc. -->

## Environment

| Item | Value |
|------|-------|
| Plugin version | |
| IDE & version | |
| Project language | <!-- JS / TS / JSX / TSX / PHP --> |
| Translation format | <!-- JSON / YAML / PHP array --> |

## Additional Context

<!-- Dynamic keys? Namespace aliases? Nested namespaces? Custom i18next config? -->
