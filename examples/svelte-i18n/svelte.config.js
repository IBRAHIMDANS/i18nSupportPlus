import { vitePreprocess } from "@sveltejs/vite-plugin-svelte";

export default {
  // `<script lang="ts">` in App.svelte.
  preprocess: vitePreprocess(),
};
