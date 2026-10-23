<script lang="ts">
  import { _, isLoading, locale } from "svelte-i18n";
  import Nav from "./lib/Nav.svelte";
  import { settingsLabel } from "./lib/labels";
  import { shouted } from "./lib/notSvelteI18n";

  let count = $state(0);
  const name = "Samuel";

  // A key read in the `<script>` block of a component.
  const profileTitle = $derived($_("user.profile.title"));

  function toggleLocale() {
    locale.set($locale?.startsWith("fr") ? "en" : "fr");
  }
</script>

{#if $isLoading}
  <p>Loading…</p>
{:else}
  <h1>{$_("app.title")}</h1>
  <Nav />
  <p>{$_("greeting", { values: { name } })}</p>
  <p>{$_("cart.items", { values: { count } })}</p>
  <button onclick={() => count++}>+1</button>
  <h2>{profileTitle}</h2>
  <p>{$_("only_in_english")}</p>
  <p>{$_("does.not.exist")}</p>
  {#key $locale}
    <p>{settingsLabel()}</p>
  {/key}
  <p>{shouted}</p>
  <button onclick={toggleLocale}>{$_("locale.switch")}</button>
{/if}
