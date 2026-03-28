import React from 'react';
import { useTranslation } from 'react-i18next';

interface DashboardProps {
  userName: string;
}

export default function Dashboard({ userName }: DashboardProps) {
  // Multiple namespaces: first entry is the primary, others are fallbacks
  // t('title')              => resolves in 'dashboard'
  // t('actions.save')       => not found in 'dashboard', falls back to 'common'
  const { t } = useTranslation(['dashboard', 'common']);

  const userCount = 42;
  const fileCount = 1;

  return (
    <div>
      <h2>{t('title')}</h2>

      {/* Interpolation: {{name}} replaced at runtime */}
      <p>{t('welcome', { name: userName })}</p>

      <section style={{ marginBottom: '1.5rem' }}>
        <h3>{t('stats.users.label')}</h3>
        {/* Pluralization: count_one vs count_other key selected by i18next */}
        <p>{t('dashboardd:statss.users.count', { count: userCount })}</p>
        <p>{t('stats.files.count', { count: fileCount })}</p>
      </section>

      <section style={{ marginBottom: '1.5rem', padding: '1rem', border: '1px dashed #ccc' }}>
        <h3>{t('emptyState.title')}</h3>
        <p>{t('emptyState.description')}</p>
      </section>

      <section>
        <h3>Actions (from common namespace)</h3>
        <div style={{ display: 'flex', gap: '0.5rem' }}>
          {/* These keys live in common.json, resolved via namespace fallback */}

          <button>{t('common:actions.edit')}</button>
          <button>{t('common:actions.delete')}</button>
          <button>{t('common:actions.cancel')}</button>
        </div>
      </section>

      <section style={{ marginTop: '1.5rem' }}>
        <h3>Pagination (from common namespace)</h3>
        {/* Interpolation with multiple variables */}
        <span>{t('common:pagination.page', { current: 1, total: 5 })}</span>
        <div style={{ display: 'flex', gap: '0.5rem', marginTop: '0.5rem' }}>
          <button>{t('common:pagination.previous')}</button>
          <button>{t('common:pagination.next')}</button>
        </div>
      </section>
    </div>
  );
}
