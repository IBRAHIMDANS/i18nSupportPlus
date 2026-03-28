import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import Navigation from './components/Navigation';
import LoginForm from './components/LoginForm';
import Dashboard from './components/Dashboard';

type View = 'login' | 'dashboard';

export default function App() {
  const { t, i18n } = useTranslation('common');
  const [view, setView] = useState<View>('login');
  const [userName, setUserName] = useState('');

  const handleLogin = (name: string) => {
    setUserName(name);
    setView('dashboard');
  };

  const handleLogout = () => {
    setUserName('');
    setView('login');
  };

  const toggleLanguage = () => {
    i18n.changeLanguage(i18n.language === 'en' ? 'fr' : 'en');
  };

  return (
      <div style={{ fontFamily: 'sans-serif', maxWidth: 800, margin: '0 auto', padding: '1rem' }}>
        <header style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
          <h1 style={{ margin: 0 }}>{t('appName')}</h1>
          <button onClick={toggleLanguage}>
            {i18n.language === 'en' ? 'FR' : 'EN'}
          </button>
        </header>

        {view === 'dashboard' && (
            <Navigation onLogout={handleLogout} />
        )}

        <main>
          {view === 'login' ? (
              <LoginForm onLogin={handleLogin} />
          ) : (
              <Dashboard userName={userName} />
          )}
        </main>
      </div>
  );
}
