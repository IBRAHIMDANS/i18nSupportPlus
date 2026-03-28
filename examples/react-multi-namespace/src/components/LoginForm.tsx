import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';

interface LoginFormProps {
  onLogin: (name: string) => void;
}

export default function LoginForm({ onLogin }: LoginFormProps) {
  // Single namespace: typed keys from auth.json
  const { t } = useTranslation(['auth']);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();

    if (!email || !password) {
      // t() with a typed key path — plugin detects 'auth:errors.invalidCredentials'
      setError(t('errors.invalidCredentials'));
      return;
    }

    // Simulate login: pass first part of email as user name
    onLogin(email.split('@')[0]);
  };

  return (
    <div style={{ maxWidth: 360, margin: '2rem auto' }}>
      <h2>{t('login.title')}</h2>

      <form onSubmit={handleSubmit}>
        <div style={{ marginBottom: '1rem' }}>
          <label htmlFor="email" style={{ display: 'block', marginBottom: 4 }}>
            {t('login.form.email.label')}
          </label>
          <input
            id="email"
            type="email"
            placeholder={t('login.form.email.placeholder')}
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            style={{ width: '100%', padding: '0.5rem', boxSizing: 'border-box' }}
          />
        </div>

        <div style={{ marginBottom: '1rem' }}>
          <label htmlFor="password" style={{ display: 'block', marginBottom: 4 }}>
            {t('login.form.password.label')}
          </label>
          <input
            id="password"
            type="password"
            placeholder={t('login.form.password.placeholder')}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            style={{ width: '100%', padding: '0.5rem', boxSizing: 'border-box' }}
          />
        </div>

        {error && (
          <p style={{ color: 'red', marginBottom: '1rem' }}>{error}</p>
        )}

        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <button type="submit">{t('login.button.submit')}</button>
          <a href="#" onClick={(e) => e.preventDefault()}>
            {t('login.button.forgotPassword')}
          </a>
        </div>
      </form>
    </div>
  );
}
