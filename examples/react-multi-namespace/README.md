# React + TypeScript + Vite

Manual testing target for the **i18n Support Plus** plugin: a react-i18next app whose
translations deliberately gather the key shapes a real codebase mixes.

## What the translations cover

| Case | Where |
|---|---|
| Namespace carrying a dash — also the plugin's default plural separator | `deposit-box.json` |
| Six-level key path | `deposit-box:myBoxes.myTrustees.modal.confirmPassword.addTrustee.description` |
| CLDR plurals (`_one` / `_other`), the suffix never written at the call site | `deposit-box:…addTrustee.description`, `dashboard:notifications.unread` |
| A plural form living next to a plain key of the same name | `dashboard:stats.users.count` |
| Interpolation (`{{name}}`, `{{count}}`) | `common:pagination.page`, `dashboard:welcome` |
| Markup in the value, rendered through `<Trans>` | `deposit-box:activity.entry.fileAdded` |
| Multiline value | `deposit-box:activity.tooltipInfo` |
| Key built at runtime as a template literal — nothing resolves it statically | `deposit-box:status.${status}` |
| A key passed as an option of another key | `deposit-box:activity.entry.boxShared` |
| Missing key, on purpose | `dashboardd:statss.users.count`, `common:actions.saxve` |

## What the call sites cover

`useTranslation()` with no namespace and fully prefixed keys, `useTranslation('ns')`,
`useTranslation(['ns', 'common'])` with its fallback, `i18n.t(…)` outside any hook, and the
`<Trans>` component.

---

This template provides a minimal setup to get React working in Vite with HMR and some ESLint rules.

Currently, two official plugins are available:

- [@vitejs/plugin-react](https://github.com/vitejs/vite-plugin-react/blob/main/packages/plugin-react) uses [Oxc](https://oxc.rs)
- [@vitejs/plugin-react-swc](https://github.com/vitejs/vite-plugin-react/blob/main/packages/plugin-react-swc) uses [SWC](https://swc.rs/)

## React Compiler

The React Compiler is enabled on this template. See [this documentation](https://react.dev/learn/react-compiler) for more information.

Note: This will impact Vite dev & build performances.

## Expanding the ESLint configuration

If you are developing a production application, we recommend updating the configuration to enable type-aware lint rules:

```js
export default defineConfig([
  globalIgnores(['dist']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      // Other configs...

      // Remove tseslint.configs.recommended and replace with this
      tseslint.configs.recommendedTypeChecked,
      // Alternatively, use this for stricter rules
      tseslint.configs.strictTypeChecked,
      // Optionally, add this for stylistic rules
      tseslint.configs.stylisticTypeChecked,

      // Other configs...
    ],
    languageOptions: {
      parserOptions: {
        project: ['./tsconfig.node.json', './tsconfig.app.json'],
        tsconfigRootDir: import.meta.dirname,
      },
      // other options...
    },
  },
])
```

You can also install [eslint-plugin-react-x](https://github.com/Rel1cx/eslint-react/tree/main/packages/plugins/eslint-plugin-react-x) and [eslint-plugin-react-dom](https://github.com/Rel1cx/eslint-react/tree/main/packages/plugins/eslint-plugin-react-dom) for React-specific lint rules:

```js
// eslint.config.js
import reactX from 'eslint-plugin-react-x'
import reactDom from 'eslint-plugin-react-dom'

export default defineConfig([
  globalIgnores(['dist']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      // Other configs...
      // Enable lint rules for React
      reactX.configs['recommended-typescript'],
      // Enable lint rules for React DOM
      reactDom.configs.recommended,
    ],
    languageOptions: {
      parserOptions: {
        project: ['./tsconfig.node.json', './tsconfig.app.json'],
        tsconfigRootDir: import.meta.dirname,
      },
      // other options...
    },
  },
])
```
