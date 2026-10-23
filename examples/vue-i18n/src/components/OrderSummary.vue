<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";

const props = defineProps<{ status: "paid" | "shipped" | "pending" }>();

const { t } = useI18n();

const title = t("order.title");
// Built at runtime: no single key, every key under order.status counts as used.
const statusLabel = computed(() => t(`order.status.${props.status}`));
</script>

<template>
  <section>
    <h2>{{ title }}</h2>
    <p>{{ statusLabel }}</p>
    <button>{{ $t('common.save') }}</button>
  </section>
</template>
