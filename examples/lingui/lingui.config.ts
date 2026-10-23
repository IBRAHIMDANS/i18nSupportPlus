import { defineConfig } from "@lingui/cli";
import { formatter } from "@lingui/format-po";

export default defineConfig({
  sourceLocale: "en",
  locales: ["en", "fr"],
  catalogs: [
    {
      path: "<rootDir>/src/locales/{locale}/messages",
      include: ["src"],
    },
  ],
  // No `#: src/App.tsx:12` origin lines: the plugin finds an entry only when its msgid opens it.
  format: formatter({ origins: false }),
});
