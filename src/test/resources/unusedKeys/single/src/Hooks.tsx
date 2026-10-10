import { useTranslation } from 'react-i18next';

export const Header = () => {
  const { t } = useTranslation('common');
  return t('hook.title');
};

export const Dashboard = () => {
  const { t } = useTranslation(['admin', 'common']);
  return t('dashboard.title');
};

export const Profile = () => {
  const { t } = useTranslation('common', { keyPrefix: 'profile' });
  return t('name');
};

export const Other = () => {
  const { t } = useTranslation('common');
  return t('label', { ns: 'other' });
};

// i18next falls back to `common` for a key `admin` does not hold.
export const Fallback = () => {
  const { t } = useTranslation('admin');
  return t('shared.ok');
};
