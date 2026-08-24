import 'i18next';
import type authEn from '../public/locales/en/auth.json';
import type commonEn from '../public/locales/en/common.json';
import type navigationEn from '../public/locales/en/navigation.json';
import type dashboardEn from '../public/locales/en/dashboard.json';
import type depositBoxEn from '../public/locales/en/deposit-box.json';

declare module 'i18next' {
  interface CustomTypeOptions {
    defaultNS: 'common';
    resources: {
      auth: typeof authEn;
      common: typeof commonEn;
      navigation: typeof navigationEn;
      dashboard: typeof dashboardEn;
      'deposit-box': typeof depositBoxEn;
    };
  }
}
