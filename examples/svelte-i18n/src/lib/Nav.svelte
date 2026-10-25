<script>
  import { _, t, json } from "svelte-i18n";
</script>

<nav>
  <a href="/">{$_("nav.home")}</a>
  <a href="/settings">{$t("nav.settings")}</a>
</nav>
<!-- `$json` returns the messages under a key: here every label of `nav`. -->
<ul>
  {#each Object.values($json("nav") ?? {}) as label}
    <li>{label}</li>
  {/each}
</ul>
