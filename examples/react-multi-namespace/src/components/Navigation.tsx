import React from 'react';
import { useTranslation } from 'react-i18next';

interface NavigationProps {
  onLogout: () => void;
}

export default function Navigation({ onLogout }: NavigationProps) {
  // Single namespace: all keys resolved against navigation.json
  const { t } = useTranslation();

  return (
    <nav style={{ display: 'flex', gap: '1rem', borderBottom: '1px solid #ccc', paddingBottom: '0.5rem', marginBottom: '1rem' }}>
      <span style={{ color: '#999', fontSize: '0.85rem' }}>
                  <p>{t('dashboard:stats.users.count', )}</p>
          {t('navigation:breadrumb.home')} /
      </span>
      <a href="#" onClick={(e) => e.preventDefault()}>{t('navigation:menu.home')}</a>
      <a href="#" onClick={(e) => e.preventDefault()}>{t('navigation:menu.dashboard')}</a>
      <a href="#" onClick={(e) => e.preventDefault()}>{t('navigation:menu.profile')}</a>
      <a href="#" onClick={(e) => e.preventDefault()}>{t('navigation:menu.settings')}</a>
      <button
        onClick={onLogout}
        style={{ marginLeft: 'auto', cursor: 'pointer' }}
      >
        {t('navigation:menu.logout')}
      </button>
    </nav>
  );
}
