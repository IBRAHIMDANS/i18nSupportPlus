<i18n>
{
  "en": {
    "welcome": "Welcome back!",
    "tip": "Tip of the day: press Save often."
  },
  "fr": {
    "welcome": "Bon retour !",
    "tip": "Astuce du jour : enregistrez souvent."
  }
}
</i18n>

<script setup lang="ts">
import { useI18n } from "vue-i18n";

// A component holding an <i18n> block gets its own scope: t reads the block first, then the global messages.
const { t } = useI18n();

const tip = t("tip");
</script>

<template>
  <aside>
    <h2>{{ t('welcome') }}</h2>
    <p>{{ tip }}</p>
    <p>{{ t('farewell') }}</p>
    <button>{{ t('common.save') }}</button>
  </aside>
</template>
