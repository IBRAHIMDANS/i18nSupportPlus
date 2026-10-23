<script setup lang="ts">
import { useI18n } from "vue-i18n";
import OrderSummary from "./components/OrderSummary.vue";
import WelcomeBanner from "./components/WelcomeBanner.vue";
import HelpPanel from "./components/HelpPanel.vue";

const { locale } = useI18n();

function switchLocale() {
  locale.value = locale.value === "en" ? "fr" : "en";
}
</script>

<template>
  <main>
    <h1>{{ $t('home.title') }}</h1>
    <p>{{ $t('home.greeting', { name: 'Ada' }) }}</p>
    <p>{{ $t('cart.items', 3) }}</p>
    <p>{{ $t('actions.saveHint') }}</p>
    <p>{{ $t('home.onlyInEnglish') }}</p>
    <p>{{ $t('home.doesNotExist') }}</p>

    <!-- v-t, deprecated by vue-i18n 11 but still shipped: a string, then an object's path. -->
    <p v-t="'common.cancel'"></p>
    <p v-t="{ path: 'home.greeting', args: { name: 'Grace' } }"></p>

    <i18n-t keypath="terms.accept" tag="p">
      <template #link>
        <a href="#">{{ $t('terms.link') }}</a>
      </template>
    </i18n-t>

    <button v-if="$te('common.switchLocale')" @click="switchLocale">{{ $t('common.switchLocale') }}</button>

    <OrderSummary status="paid" />
    <WelcomeBanner />
    <HelpPanel />
  </main>
</template>
