import { getLocaleFromNavigator, init, register } from "svelte-i18n";

// One file per locale, loaded when the locale is first used.
register("en", () => import("../locales/en.json"));
register("fr", () => import("../locales/fr.json"));

init({
  fallbackLocale: "en",
  initialLocale: getLocaleFromNavigator(),
});
