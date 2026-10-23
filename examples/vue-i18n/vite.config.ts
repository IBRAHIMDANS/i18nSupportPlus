import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import VueI18nPlugin from "@intlify/unplugin-vue-i18n/vite";

export default defineConfig({
  plugins: [
    vue(),
    // Compiles the <i18n> blocks (JSON and YAML) and the files of src/locales at build time.
    VueI18nPlugin({ include: fileURLToPath(new URL("./src/locales/**", import.meta.url)) }),
  ],
});
