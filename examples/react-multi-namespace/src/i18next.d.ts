import 'i18next';
import type authEn from '../public/locales/en/auth.json';
import type commonEn from '../public/locales/en/common.json';
import type navigationEn from '../public/locales/en/navigation.json';
import type dashboardEn from '../public/locales/en/dashboard.json';
import type depositBoxEn from '../public/locales/en/deposit-box.json';

declare module 'i18next' {
  interface CustomTypeOptions {
    // A list, not just 'common': `useTranslation()` with no argument types `t` from this value,
    // and i18next only accepts `ns:key` for the namespaces of a list. 'common' comes first, so a
    // key without prefix still resolves there, as `defaultNS: 'common'` does at runtime (i18n.ts).
    defaultNS: ['common', 'auth', 'navigation', 'dashboard', 'deposit-box'];
    resources: {
      auth: typeof authEn;
      common: typeof commonEn;
      navigation: typeof navigationEn;
      dashboard: typeof dashboardEn;
      'deposit-box': typeof depositBoxEn;
    };
  }
}
