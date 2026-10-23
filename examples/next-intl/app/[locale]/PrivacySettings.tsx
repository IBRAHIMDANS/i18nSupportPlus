'use client';

import { useTranslations } from 'next-intl';

export default function PrivacySettings() {
  // A namespace with nested segments: `t('title')` reads `Settings.Privacy.title`.
  const t = useTranslations('Settings.Privacy');

  return (
    <section>
      <h2>{t('title')}</h2>
      <p>{t('updatedBy', { user: 'Grace' })}</p>
      <p>{t('trackers', { count: 12 })}</p>
      <label>
        <input type="checkbox" /> {t('cookies.label')}
      </label>
      {/* Missing in fr.json on purpose. */}
      <small>{t('cookies.hint')}</small>
      {/* In no locale on purpose. */}
      <small>{t('cookies.expiry')}</small>
    </section>
  );
}
