<i18n lang="yaml">
en:
  help:
    title: Need help?
    body: Write to us, we answer within a day.
fr:
  help:
    title: Besoin d'aide ?
    body: Écrivez-nous, nous répondons sous un jour.
</i18n>

<script setup lang="ts">
import { useI18n } from "vue-i18n";

const { t } = useI18n();
</script>

<template>
  <aside>
    <h2>{{ t('help.title') }}</h2>
    <p>{{ t('help.body') }}</p>
    <button>{{ t('common.cancel') }}</button>
  </aside>
</template>
